// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.1 (the live-catalogue routing suite:
//        everyCloudProviderResolvesToItsOwnCatalogue, theOtherTwoTiersHaveNothingLiveToBrowse,
//        aRejectedKeyIsNotAnEmptyCatalogue. NOT ported: the three fixture-backed Grok/Fireworks row
//        tests, which read teemoonTests/Fixtures/xai_*.json and fireworks_serverless_models.json)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveCatalogTest {

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })  // never dial from a unit test
    }

    private fun machine() = Provider(
        id = "m", name = "second mac", endpoint = "http://100.100.0.12:11434/v1", model = "qwen3:14b", requiresAPIKey = false)

    /** A fetch that answers by URL suffix; anything unrouted is a 404 with an empty body. */
    private fun routes(vararg table: Pair<String, Pair<Int, String>>): suspend (String, Map<String, String>, Long) -> Pair<Int, ByteArray> =
        { url, _, _ ->
            table.firstOrNull { url.endsWith(it.first) }?.second?.let { (s, b) -> s to b.encodeToByteArray() } ?: (404 to ByteArray(0))
        }

    /** The regression that matters: this returned near.ai or nothing, and nothing is what made a Grok row a name and a vendor. */
    @Test fun everyCloudProviderResolvesToItsOwnCatalogue() {
        assertEquals(EndpointModelCatalog.Source.NEAR_AI, WhereProviderPresentation.liveCatalogSource(Provider.nearAI))
        assertEquals(EndpointModelCatalog.Source.XAI, WhereProviderPresentation.liveCatalogSource(Provider.grok))
        assertEquals(EndpointModelCatalog.Source.FIREWORKS, WhereProviderPresentation.liveCatalogSource(Provider.fireworks))
        // A BYOK endpoint the presets don't cover has no curated snapshot at all,
        // so routing it to the generic probe is the difference between live bare
        // ids and an empty browse sheet.
        val custom = Provider(id = "c", name = "my proxy", endpoint = "https://llm.example.com/v1/chat/completions",
            model = "some-model", requiresAPIKey = true)
        assertEquals(EndpointModelCatalog.Source.GENERIC, WhereProviderPresentation.liveCatalogSource(custom))
    }

    /** null for the tiers that don't browse — a home server's models all arrive in `ready now`, and the phone has no browse row. */
    @Test fun theOtherTwoTiersHaveNothingLiveToBrowse() {
        assertNull(WhereProviderPresentation.liveCatalogSource(machine()))
        assertNull(WhereProviderPresentation.liveCatalogSource(Provider.local(LocalModelCatalog.all[0])))
    }

    /**
     * A failure must stay a failure. The browse sheet renders the curated list
     * while nothing live has arrived, so a router that turned 401 into
     * Connected([]) would replace a usable fallback with an empty list — the
     * one outcome worse than the inconsistency this fixes.
     */
    @Test fun aRejectedKeyIsNotAnEmptyCatalogue() = runTest {
        val result = ModelCatalog.liveCatalog(
            EndpointModelCatalog.Source.XAI, "https://api.x.ai/v1", apiKey = "nope",
            fetch = routes("/language-models" to (401 to """{"error":"bad key"}"""), "/models" to (401 to """{"error":"bad key"}""")))
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED), result)
    }

    // MARK: Android-only — each door of the one table, driven through the shared fetch seam

    @Test fun xaiRoutesThroughItsAdapterAndCarriesTheBearer() = runTest {
        val seen = ArrayList<Pair<String, String?>>()
        val language = """{"models":[{"id":"grok-4.3","created":1,"input_modalities":["text"],"prompt_text_token_price":20000,"completion_text_token_price":100000}]}"""
        val result = ModelCatalog.liveCatalog(EndpointModelCatalog.Source.XAI, "https://api.x.ai/v1", apiKey = "test-key") { url, headers, _ ->
            seen.add(url to headers["Authorization"])
            if (url.endsWith("/language-models")) 200 to language.encodeToByteArray() else 404 to ByteArray(0)
        }
        val rows = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(listOf("grok-4.3"), rows.map { it.id })
        assertEquals("Grok 4.3", rows.single().displayName)
        assertTrue(seen.all { it.second == "Bearer test-key" })
        assertEquals(setOf("https://api.x.ai/v1/language-models", "https://api.x.ai/v1/models"), seen.map { it.first }.toSet())
    }

    @Test fun nearAIRoutesThroughFetchLiveAndRecordsTiers() = runTest {
        val body = """{"object":"list","data":[
          {"id":"anthropic/claude-haiku-4-5","owned_by":"anthropic","name":"Claude Haiku 4.5","pricing":{"input":1.0,"output":5.0},"context_length":200000},
          {"id":"z-ai/glm-5.2","owned_by":"nearai","name":"GLM 5.2","pricing":{"input":1.4,"output":4.4},"context_length":1000000}]}"""
        val seen = ArrayList<String>()
        val result = ModelCatalog.liveCatalog(EndpointModelCatalog.Source.NEAR_AI, "https://cloud-api.near.ai/v1", apiKey = "") { url, headers, _ ->
            seen.add(url); assertTrue("no key → no bearer", "Authorization" !in headers)
            200 to body.encodeToByteArray()
        }
        val rows = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(listOf("z-ai/glm-5.2", "anthropic/claude-haiku-4-5"), rows.map { it.id })
        assertEquals("$1.40/$4.40", rows.first().price)
        assertEquals(listOf(ProviderKeyValidator.NEAR_AI_MODELS_URL), seen)
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality("z-ai/glm-5.2"))
    }

    /**
     * A NEAR.AI MISS IS A FAILURE, NEVER THE GENERIC PROBE (ac11ef2).
     *
     * It used to fall through to the probe on an empty list. The probe returns
     * bare OpenAI-shaped rows — no direct hosts, no prices, no confidentiality
     * tiers — and the caller RECORDS what it gets, so a blink would overwrite a
     * good saved list with a worse one and leave every row unattestable. One
     * request now, and the caller falls back to the last saved answer instead.
     */
    @Test fun aNearAIMissIsAFailureAndNeverTheGenericProbe() = runTest {
        var calls = 0
        val result = ModelCatalog.liveCatalog(EndpointModelCatalog.Source.NEAR_AI, "https://cloud-api.near.ai/v1", apiKey = "k") { url, _, _ ->
            assertEquals(ProviderKeyValidator.NEAR_AI_MODELS_URL, url)
            calls++; 200 to """{"data":[]}""".encodeToByteArray()
        }
        assertEquals("the probe must not be tried at all", 1, calls)
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.OFFLINE), result)
    }

    @Test fun genericRoutesToTheProbeWithTheCustomHeader() = runTest {
        val seen = ArrayList<Map<String, String>>()
        val result = ModelCatalog.liveCatalog(EndpointModelCatalog.Source.GENERIC, "https://llm.example.com/v1", apiKey = "tok",
            authHeaderName = "X-Api-Key") { url, headers, _ ->
            seen.add(headers); assertEquals("https://llm.example.com/v1/models", url)
            200 to """{"data":[{"id":"llama3"},{"id":"Qwen/Qwen3-Embedding-0.6B"}]}""".encodeToByteArray()
        }
        assertEquals(listOf("llama3"), (result as EndpointModelCatalog.ProbeResult.Connected).models.map { it.id })
        assertEquals(mapOf("X-Api-Key" to "tok"), seen.single())
    }

    @Test fun fireworksRoutesToItsAdapter() = runTest {
        // The control plane refuses → the adapter itself falls back to the generic probe, which succeeds.
        val result = ModelCatalog.liveCatalog(EndpointModelCatalog.Source.FIREWORKS, "https://api.fireworks.ai/inference/v1", apiKey = "fw",
            fetch = routes("inference/v1/models" to (200 to """{"data":[{"id":"accounts/fireworks/models/kimi-k2p6"}]}""")))
        assertEquals(listOf("accounts/fireworks/models/kimi-k2p6"), (result as EndpointModelCatalog.ProbeResult.Connected).models.map { it.id })
    }

    // MARK: - OpenRouter (96d801e)

    @Test fun openRouterResolvesToItsOwnCatalogue() {
        assertEquals(EndpointModelCatalog.Source.OPEN_ROUTER,
            EndpointModelCatalog.Source.resolve("openrouter.ai"))
    }

    /**
     * A STORED KEY THE HOST REJECTS MUST NOT BLANK THE PICKER. OpenRouter's list
     * is public, so a 401 means the key is wrong, not that the catalogue is
     * unavailable — the retry costs the user their key screen and not their
     * models.
     */
    @Test fun aRejectedKeyFallsBackToTheKeylessList() = runTest {
        val body = """{"data":[{"id":"openai/gpt-5.4","name":"OpenAI: GPT-5.4","created":1789800000,
            "context_length":1100000,"pricing":{"prompt":"0.0000025","completion":"0.000015"},
            "architecture":{"output_modalities":["text"]}}]}"""
        val sentKeys = ArrayList<String?>()
        val result = ModelCatalog.liveCatalog(
            EndpointModelCatalog.Source.OPEN_ROUTER, "https://openrouter.ai/api/v1", apiKey = "sk-or-stale",
        ) { _, headers, _ ->
            sentKeys.add(headers["Authorization"])
            if (headers.containsKey("Authorization")) 401 to ByteArray(0) else 200 to body.encodeToByteArray()
        }
        assertEquals(listOf("Bearer sk-or-stale", null), sentKeys)
        assertEquals(listOf("openai/gpt-5.4"),
            (result as EndpointModelCatalog.ProbeResult.Connected).models.map { it.id })
    }

    /** With no key there is nothing to retry, so a 401 stands. */
    @Test fun aKeylessRejectionIsNotRetried() = runTest {
        var calls = 0
        val result = ModelCatalog.liveCatalog(
            EndpointModelCatalog.Source.OPEN_ROUTER, "https://openrouter.ai/api/v1", apiKey = "",
        ) { _, _, _ -> calls++; 401 to ByteArray(0) }
        assertEquals(1, calls)
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED), result)
    }

    /**
     * The generic probe is where a user's own OpenAI key lists its models, and
     * it is where the snapshot has to land: a first-party row carries its list
     * price, an id the table lacks stays blank, and a self-hosted server gets
     * nothing — there is no list price for someone's own machine.
     */
    @Test fun theGenericProbeFillsFirstPartyPrices() = runTest {
        val body = """{"data":[{"id":"gpt-5.4"},{"id":"gpt-private-preview"}]}"""
        val firstParty = EndpointModelCatalog.probe("https://api.openai.com/v1", apiKey = "sk-x",
            fetch = { _, _, _ -> 200 to body.encodeToByteArray() })
        val rows = (firstParty as EndpointModelCatalog.ProbeResult.Connected).models.associateBy { it.id }
        assertEquals("$2.50/$15.00", rows["gpt-5.4"]!!.price)
        assertTrue("an id the table lacks stays blank", rows["gpt-private-preview"]!!.price.isEmpty())

        val selfHosted = EndpointModelCatalog.probe("http://192.168.1.5:11434/v1", apiKey = "",
            fetch = { _, _, _ -> 200 to """{"data":[{"id":"gpt-5.4"}]}""".encodeToByteArray() })
        assertTrue("someone's own machine has no list price",
            (selfHosted as EndpointModelCatalog.ProbeResult.Connected).models.single().price.isEmpty())
    }
}
