// ports: teemoonTests/NearAICatalogParseTests.swift @ v1.0.4
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * near.ai's /v1/models is decoded as a full typed struct and its metadata
 * (price, context, capabilities, tier) built straight into KnownModels. These
 * pin the wire contract and the parse helpers.
 */
class NearAICatalogParseTest {

    @Before fun clearCache() {
        NearAIModelCatalog.resetTierCache()
        // Offline suite: the directory seam must not dial. A fetch that answers
        // nothing means no host at all — there is no snapshot to fall through to.
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }
    @After fun clearCacheAfter() = NearAIModelCatalog.resetTierCache()

    /**
     * A trimmed but real-shaped /v1/models body: one proxied (Anthropic) model with
     * image input + tools, one near.ai-fleet GLM with reasoning.
     */
    private val sampleJSON = """
    {"object":"list","data":[
      {"id":"anthropic/claude-haiku-4-5","owned_by":"anthropic","name":"Claude Haiku 4.5",
       "pricing":{"input":1.0,"output":5.0,"prompt":"0.000001","completion":"0.000005"},
       "context_length":200000,"max_output_length":8192,
       "input_modalities":["text","image"],"output_modalities":["text"],
       "supported_features":["tools","structured_outputs"],"is_ready":false,
       "description":"Fastest Anthropic model.","top_provider":{"context_length":200000}},
      {"id":"z-ai/glm-5.2","owned_by":"nearai","name":"GLM 5.2",
       "pricing":{"input":1.4,"output":4.4},"context_length":1000000,
       "input_modalities":["text"],"supported_features":["tools","reasoning"]}
    ]}
    """

    private fun decoded() = NearAIModelCatalog.decode(sampleJSON)

    // MARK: full typed decode (the strong-typing rule)

    @Test fun decodesTheFullShape() {
        val r = decoded()
        assertEquals(2, r.data.size)
        val haiku = r.data[0]
        assertEquals("anthropic/claude-haiku-4-5", haiku.id)
        assertEquals("anthropic", haiku.ownedBy)
        assertEquals("Claude Haiku 4.5", haiku.name)
        assertEquals(1.0, haiku.pricing?.input!!, 0.0)
        assertEquals(5.0, haiku.pricing.output!!, 0.0)
        assertEquals(200000, haiku.contextLength)
        assertEquals(listOf("text", "image"), haiku.inputModalities)
        assertEquals(listOf("tools", "structured_outputs"), haiku.supportedFeatures)
        assertEquals(false, haiku.isReady)
        assertEquals("Fastest Anthropic model.", haiku.description)
        assertEquals(200000, haiku.topProvider?.contextLength)
    }

    // MARK: parse helpers

    @Test fun priceAndContextLabels() {
        val r = decoded()
        assertEquals("$1.00/$5.00", NearAIModelCatalog.priceLabel(r.data[0].pricing))
        assertEquals("$1.40/$4.40", NearAIModelCatalog.priceLabel(r.data[1].pricing))
        assertEquals("", NearAIModelCatalog.priceLabel(null))
        assertEquals("200k", NearAIModelCatalog.contextLabel(200000))
        assertEquals("1M", NearAIModelCatalog.contextLabel(1_000_000))
        assertEquals("", NearAIModelCatalog.contextLabel(null))
    }

    @Test fun capabilitiesFromFeaturesAndModalities() {
        val r = decoded()
        // Anthropic entry: tools + image input → TOOLS and VISION.
        val caps = NearAIModelCatalog.capabilities(r.data[0])
        assertTrue(ModelCapability.TOOLS in caps)
        assertTrue(ModelCapability.VISION in caps)
        // GLM: tools, text-only → TOOLS, no VISION; `reasoning` lands on THINKING.
        val glmCaps = NearAIModelCatalog.capabilities(r.data[1])
        assertTrue(ModelCapability.TOOLS in glmCaps)
        assertFalse(ModelCapability.VISION in glmCaps)
        assertTrue(ModelCapability.THINKING in glmCaps)
    }

    // MARK: slug (drift matching)

    @Test fun slugStripsQuantSuffixSoCuratedMatchesLive() {
        // The drift that caused the flicker: curated "…GLM-5.1-FP8" must slug-match a
        // live "glm-5.1".
        assertEquals("glm-5.1", NearAIModelCatalog.slug("zai-org/GLM-5.1-FP8"))
        assertEquals("glm-5.1", NearAIModelCatalog.slug("z-ai/glm-5.1"))
        assertEquals("glm-5.2", NearAIModelCatalog.slug("z-ai/glm-5.2"))
        assertEquals("qwen3.6-27b", NearAIModelCatalog.slug("Qwen/Qwen3.6-27B-FP8"))
    }

    // MARK: buildModels (live metadata → KnownModels)

    @Test fun buildsKnownModelsFromLiveMetadata() = runTest {
        val models = NearAIModelCatalog.buildModels(decoded().data)
        // GLM 5.2 (e2ee, Z.ai) sorts before the proxied Anthropic model.
        val glm = models.first { it.id == "z-ai/glm-5.2" }
        assertEquals("GLM 5.2", glm.displayName)
        assertEquals("$1.40/$4.40", glm.price)
        assertEquals("1M", glm.contextWindow)
        assertTrue(glm.capabilities?.contains(ModelCapability.TOOLS) == true)
        assertEquals("Z.ai", glm.vendor)
        assertEquals(0, models.indexOf(glm))

        val haiku = models.first { it.id == "anthropic/claude-haiku-4-5" }
        assertEquals("Claude Haiku 4.5", haiku.displayName)
        assertEquals("$1.00/$5.00", haiku.price)
        assertEquals("200k", haiku.contextWindow)
        assertTrue(haiku.capabilities?.contains(ModelCapability.VISION) == true)
    }

    // MARK: Android-only — the seams and the tier side effect

    /**
     * The directory is the ONLY host map. This used to assert a second half —
     * that a null directory fell back to the shipped snapshot's host — and
     * ac11ef2 deleted that fallback. The snapshot was a frozen copy of a fetch,
     * and a stale host is worse than no host now that a missing one degrades
     * instead of reading as an ordinary model
     * (ConfidentialSession.outcomeWhenNoDirectHost).
     */
    @Test fun buildModelsTakesTheDirectHostOnlyFromTheDirectory() = runTest {
        val noDirectory = NearAIModelCatalog.buildModels(decoded().data, directBase = { null })
        assertNull("no directory answer means no host, not a snapshot host",
            noDirectory.first { it.id == "z-ai/glm-5.2" }.directBaseURL)
        assertNull(noDirectory.first { it.id == "anthropic/claude-haiku-4-5" }.directBaseURL)
        assertEquals("end-to-end encrypted", noDirectory.first { it.id == "z-ai/glm-5.2" }.confidentialityNote)
        assertEquals("proxied", noDirectory.first { it.id == "anthropic/claude-haiku-4-5" }.confidentialityNote)

        val asked = ArrayList<String>()
        val fromDirectory = NearAIModelCatalog.buildModels(decoded().data, directBase = { id ->
            asked.add(id); "https://moved.completions.near.ai/v1"
        })
        assertEquals("https://moved.completions.near.ai/v1", fromDirectory.first { it.id == "z-ai/glm-5.2" }.directBaseURL)
        // Only TEE_OWN is asked: near.ai publishes no confidential endpoint for
        // attested third-party hardware, so that round trip can only answer null.
        assertEquals(listOf("z-ai/glm-5.2"), asked)

        // The DEFAULT seam is the installed EndpointDirectory, and it is the whole
        // answer: a moved host is simply the host, with nothing to out-rank.
        val moved = """{"endpoints":[{"domain":"glm-5-2-moved.completions.near.ai","models":["z-ai/glm-5.2"]}]}"""
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { 200 to moved.encodeToByteArray() })
        val viaDefault = NearAIModelCatalog.buildModels(decoded().data)
        assertEquals("https://glm-5-2-moved.completions.near.ai/v1", viaDefault.first { it.id == "z-ai/glm-5.2" }.directBaseURL)
    }

    /** fetchLive records `owned_by` tiers, fails open on a bad reply, and sends a bearer only when it has one. */
    @Test fun fetchLiveRecordsTiersAndFailsOpen() = runTest {
        val seen = ArrayList<Pair<String, String?>>()
        val models = NearAIModelCatalog.fetchLive(apiKey = "", fetch = { url, bearer ->
            seen.add(url to bearer); NearAIModelCatalog.HttpReply(200, sampleJSON)
        })
        assertEquals(listOf(ProviderKeyValidator.NEAR_AI_MODELS_URL to null), seen)
        assertEquals(2, models?.size)
        // The live `owned_by` is now authoritative for the open-weight row…
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality("z-ai/glm-5.2"))

        val warnings = ArrayList<String>()
        assertNull(NearAIModelCatalog.fetchLive(apiKey = "k", fetch = { _, bearer ->
            assertEquals("k", bearer); NearAIModelCatalog.HttpReply(503, "")
        }, warn = { warnings.add(it) }))
        assertNull(NearAIModelCatalog.fetchLive(fetch = { _, _ -> NearAIModelCatalog.HttpReply(200, "not json") }, warn = { warnings.add(it) }))
        assertNull(NearAIModelCatalog.fetchLive(fetch = { _, _ -> NearAIModelCatalog.HttpReply(200, """{"data":[]}""") }, warn = { warnings.add(it) }))
        assertNull(NearAIModelCatalog.fetchLive(fetch = { _, _ -> null }, warn = { warnings.add(it) }))
        assertEquals(4, warnings.size)
    }
}
