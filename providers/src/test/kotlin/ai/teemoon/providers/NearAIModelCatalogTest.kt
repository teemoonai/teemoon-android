// ports: teemoonTests/NearAIModelCatalogTests.swift @ v1.0.4 (NearAIModelCatalogTests +
//        NearAIModelCatalogOrderingTests + NearAIRecencyTests — see the recency note below)
package ai.teemoon.providers

import ai.teemoon.providers.NearAIModelCatalog.Confidentiality.PROXIED
import ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN
import ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * One `/v1/models` row, as near.ai sends it. The tests drive [NearAIModelCatalog.buildModels]
 * through this rather than the removed `merge`, because buildModels is what the app calls and
 * the live response is now the whole row.
 */
private fun wire(
    id: String, name: String? = null, inPrice: Double? = null, outPrice: Double? = null,
    context: Int? = null, created: Long? = null, ownedBy: String? = null,
) = NearAIModelCatalog.ModelsResponse.Model(
    id = id, name = name, created = created, ownedBy = ownedBy, contextLength = context,
    pricing = if (inPrice == null && outPrice == null) null
    else NearAIModelCatalog.ModelsResponse.Pricing(input = inPrice, output = outPrice),
)

/**
 * The pure merge that turns a live /v1/models id list into browser rows:
 * curated metadata preserved, non-chat models filtered, uncurated ids
 * synthesized with resolved direct hosts — and the one-way confidentiality veto.
 */
class NearAIModelCatalogTest {

    @Before fun clearCache() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })  // offline: never dial
    }
    @After fun clearCacheAfter() = NearAIModelCatalog.resetTierCache()

    /**
     * A CLOSED-WEIGHT MODEL CAN NEVER BE PROMOTED TO near.ai's OWN ENCLAVE,
     * whatever the catalogue says about it.
     *
     * Observed live 2026-08-02: near.ai returned `owned_by: "nearai"` for
     * `anthropic/claude-sonnet-5`, while `claude-sonnet-4-5` and `-4-6` came
     * back `"anthropic"`. The authoritative cache is consulted before the id
     * heuristic, so that single field made teemoon tell the user
     * "end-to-end encrypted" about a passthrough to Anthropic's API.
     *
     * Anthropic does not release these weights. No third party is running them
     * in its own TEE, and a wrong e2ee claim is the worst thing this app can
     * say — so the id vetoes the upgrade.
     */
    @Test fun ownedByCannotPromoteAClosedWeightModelToTheOwnFleet() {
        // Exactly what the live catalogue served.
        NearAIModelCatalog.recordTiers(mapOf("anthropic/claude-sonnet-5" to TEE_OWN))
        assertEquals(PROXIED, NearAIModelCatalog.confidentiality("anthropic/claude-sonnet-5"))
        assertFalse(NearAIModelCatalog.isAttestable("anthropic/claude-sonnet-5"))

        // The same refusal for the other closed frontier vendors.
        NearAIModelCatalog.recordTiers(mapOf(
            "openai/gpt-5.2" to TEE_OWN,
            "google/gemini-3-pro" to TEE_OWN,
        ))
        assertEquals(PROXIED, NearAIModelCatalog.confidentiality("openai/gpt-5.2"))
        assertEquals(PROXIED, NearAIModelCatalog.confidentiality("google/gemini-3-pro"))
    }

    /**
     * DOWNGRADES MUST STILL WORK. The veto is one-way — the catalogue remains
     * authoritative for moving an open-weight model teemoon guessed was
     * own-fleet down to third-party or proxied.
     */
    @Test fun theCatalogueCanStillDowngradeAnOpenWeightModel() {
        val id = "zai-org/GLM-5.1-FP8"
        assertEquals(TEE_OWN, NearAIModelCatalog.classify(id))
        NearAIModelCatalog.recordTiers(mapOf(id to TEE_THIRD_PARTY))
        assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.confidentiality(id))
    }

    // MARK: reused-node vendor mismatch (DeepSeek-host-shows-Qwen bug)

    @Test fun differentVendorFlagsCrossVendorComposeMismatch() {
        // The live bug: DeepSeek V4 Flash's node's latest compose_up is a Qwen
        // YAML, so the parsed artifact is Qwen while the requested model is
        // DeepSeek — must be flagged as a mismatch and dropped.
        assertTrue(NearAIModelCatalog.differentVendor("Qwen/Qwen3.5-122B", "deepseek-ai/DeepSeek-V4-Flash"))
        assertTrue(NearAIModelCatalog.differentVendor("deepseek-ai/DeepSeek-V4-Flash", "Qwen/Qwen3.6-27B-FP8"))
    }

    @Test fun differentVendorAcceptsNamespaceAliases() {
        // z-ai / zai-org are the same vendor (Z.ai) — NOT a mismatch.
        assertFalse(NearAIModelCatalog.differentVendor("z-ai/glm-5.2", "zai-org/GLM-5.2-FP8"))
        // deepseek / deepseek-ai both map to DeepSeek.
        assertFalse(NearAIModelCatalog.differentVendor("deepseek/deepseek-v3.2", "deepseek-ai/DeepSeek-V4-Flash"))
    }

    @Test fun differentVendorIsFalseWhenUnknown() {
        assertFalse(NearAIModelCatalog.differentVendor(null, "deepseek-ai/DeepSeek-V4-Flash"))
        assertFalse(NearAIModelCatalog.differentVendor("Qwen/Qwen3.6-27B-FP8", null))
    }

    @Test fun requantizedWeightsMustCompareServedNameNotModelPath() {
        // GLM-5.1 runs `QuantTrio/GLM-5.1-AWQ` weights but declares
        // `--served-model-name zai-org/GLM-5.1-FP8`. The reused-node guard must
        // compare the SERVED name (matches the requested id) — NOT the weights
        // repo, whose vendor differs by design. Comparing the served name is a
        // match (kept):
        assertFalse(NearAIModelCatalog.differentVendor("zai-org/GLM-5.1-FP8", "zai-org/GLM-5.1-FP8"))
        // …while comparing the model-path would have FALSELY flagged it (the
        // regression that dropped GLM-5.1's whole model layer):
        assertTrue(NearAIModelCatalog.differentVendor("QuantTrio/GLM-5.1-AWQ", "zai-org/GLM-5.1-FP8"))
    }

    /**
     * The LIVE response is the whole row. There is no curated snapshot to borrow
     * price or context from any more, so what near.ai answered is what shows —
     * and a field it omits stays blank rather than being filled from a frozen copy.
     */
    @Test fun liveMetadataIsTheRow() = runTest {
        val rows = NearAIModelCatalog.buildModels(
            listOf(wire("z-ai/glm-5.3-flash", name = "GLM 5.3 Flash",
                inPrice = 0.15, outPrice = 0.50, context = 1_000_000, created = 1_788_000_000)),
            directBase = { "https://glm-5-3-flash.completions.near.ai/v1" })
        val glm = rows.first { it.id == "z-ai/glm-5.3-flash" }
        assertEquals("GLM 5.3 Flash", glm.displayName)
        assertEquals("$0.15/$0.50", glm.price)
        assertEquals("1M", glm.contextWindow)
        assertNotNull(glm.created)
        assertEquals("https://glm-5-3-flash.completions.near.ai/v1", glm.directBaseURL)
    }

    /** An id near.ai names nothing for is still a readable row, with blanks where it said nothing. */
    @Test fun anUnnamedModelIsStillReadable() = runTest {
        val rows = NearAIModelCatalog.buildModels(
            listOf(wire("z-ai/glm-5.2-long")),
            directBase = { "https://glm-5-2-long.completions.near.ai/v1" })
        val glm = rows.first { it.id == "z-ai/glm-5.2-long" }
        assertEquals("Z.ai", glm.vendor)
        assertEquals("glm-5.2-long", glm.displayName)
        assertEquals("", glm.price)
        assertEquals("https://glm-5-2-long.completions.near.ai/v1", glm.directBaseURL)
    }

    @Test fun nonChatModelsAreFiltered() = runTest {
        val ids = listOf("Qwen/Qwen3-Embedding-0.6B", "Qwen/Qwen3-Reranker-0.6B",
            "openai/whisper-large-v3", "openai/privacy-filter",
            "black-forest-labs/FLUX.2-klein-4B", "zai-org/GLM-5.1-FP8")
        val rows = NearAIModelCatalog.buildModels(ids.map { wire(it) }, directBase = { null })
        assertEquals(listOf("zai-org/GLM-5.1-FP8"), rows.map { it.id })
    }

    @Test fun duplicatesCollapse() = runTest {
        val rows = NearAIModelCatalog.buildModels(
            listOf(wire("openai/gpt-oss-120b"), wire("openai/gpt-oss-120b")), directBase = { null })
        assertEquals(1, rows.count { it.id == "openai/gpt-oss-120b" })
    }

    @Test fun vendorLabelsMapNamespaces() {
        assertEquals("Z.ai", NearAIModelCatalog.vendorLabel("zai-org/GLM-5.1-FP8"))
        assertEquals("DeepSeek", NearAIModelCatalog.vendorLabel("deepseek-ai/DeepSeek-V4-Flash"))
        assertEquals("Moonshot", NearAIModelCatalog.vendorLabel("moonshotai/kimi-k2.6"))
        assertEquals("Mystery", NearAIModelCatalog.vendorLabel("mystery/model"))
    }

    /**
     * teemoon ships no near.ai model list at all: a retired model can only reach
     * a screen by being in what near.ai served. What does still ship is the one
     * fixed service's row.
     */
    @Test fun noModelListShips() {
        assertEquals(Provider.braveAnswers.model, KnownModel.braveAnswersModel.id)
    }

    // MARK: - Confidentiality tier / attestation gate

    @Test fun classify_openWeightModelsAreAttestable() {
        assertTrue(NearAIModelCatalog.isAttestable("zai-org/GLM-5.1-FP8"))
        assertTrue(NearAIModelCatalog.isAttestable("openai/gpt-oss-120b"))   // open-weight, not proxied
        assertTrue(NearAIModelCatalog.isAttestable("google/gemma-4-31b-it")) // gemma ≠ gemini
        assertTrue(NearAIModelCatalog.isAttestable("Qwen/Qwen3.5-122B-A10B"))
        assertTrue(NearAIModelCatalog.isAttestable("deepseek-ai/DeepSeek-V4-Flash"))
    }

    @Test fun classify_proxiedClosedModelsAreNotAttestable() {
        assertFalse(NearAIModelCatalog.isAttestable("anthropic/claude-opus-4-6"))
        assertFalse(NearAIModelCatalog.isAttestable("openai/gpt-5.2"))
        assertFalse(NearAIModelCatalog.isAttestable("openai/o3"))
        assertFalse(NearAIModelCatalog.isAttestable("google/gemini-2.5-pro"))
        assertFalse(NearAIModelCatalog.isAttestable("qwen/qwen3.7-max"))
    }

    @Test fun tierFromOwnedBy_mapsCatalogValues() {
        assertEquals(TEE_OWN, NearAIModelCatalog.tierFromOwnedBy("nearai"))
        assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.tierFromOwnedBy("attested 3p"))
        assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.tierFromOwnedBy("chutes"))
        assertEquals(PROXIED, NearAIModelCatalog.tierFromOwnedBy("anthropic"))
        assertNull(NearAIModelCatalog.tierFromOwnedBy(""))
        assertNull(NearAIModelCatalog.tierFromOwnedBy(null))
    }

    @Test fun ownedBy_cacheIsAuthoritativeAndCaseInsensitive() {
        // The live catalog's owned_by overrides the id heuristic: a vendor-owned
        // id the heuristic would treat as open-weight is marked proxied, and a
        // Chutes-attested model resolves to the third-party tier.
        NearAIModelCatalog.recordTiers(mapOf(
            "vendor/some-open-model" to PROXIED,
            "moonshotai/kimi-k2.5" to TEE_THIRD_PARTY,
        ))
        assertFalse(NearAIModelCatalog.isAttestable("vendor/some-open-model"))
        assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.confidentiality("MoonshotAI/Kimi-K2.5"))
        NearAIModelCatalog.resetTierCache()
        // Once cleared, the offline heuristic governs again — and it too knows
        // kimi is attested-3p (the exact-id set beside the catalog snapshot),
        // so an offline launch can't misfile a Chutes model as E2EE-capable.
        assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.confidentiality("moonshotai/kimi-k2.5"))
    }

    /** Android-only: the tier's label is ONE vocabulary, read off the type. */
    @Test fun labelsAreTheOneVocabulary() {
        assertEquals("end-to-end encrypted", TEE_OWN.label)
        assertEquals("attested on third-party hardware", TEE_THIRD_PARTY.label)
        assertEquals("proxied", PROXIED.label)
    }

    // MARK: - merge — ordering

    private fun row(id: String, created: Long?) = KnownModel(
        id = id, displayName = id, vendor = NearAIModelCatalog.vendorLabel(id), price = "",
        created = created?.let { Instant.ofEpochSecond(it) })

    /**
     * Ordering is by TIER first, then recency. It used to follow the curated
     * snapshot's vendor families; there is no curated snapshot now, and the
     * order a reader cares about is what can be sealed, then what is attested on
     * someone else's hardware, then the plain proxies.
     */
    @Test fun tiersLeadAndRecencyOrdersWithinThem() {
        val rows = NearAIModelCatalog.ordered(listOf(
            row("anthropic/claude-sonnet-5", 1_788_000_000),   // proxied, newest overall
            row("moonshotai/kimi-k3", 1_787_000_000),          // attested 3p
            row("z-ai/glm-5.3-flash", 1_780_000_000),          // own fleet, oldest
            row("Qwen/Qwen3.8-27B", 1_786_000_000),            // own fleet, newer
        ))
        assertEquals(
            listOf("Qwen/Qwen3.8-27B", "z-ai/glm-5.3-flash", "moonshotai/kimi-k3", "anthropic/claude-sonnet-5"),
            rows.map { it.id })
    }

    /** Nothing to sort by means the server's order stands, not an invented one. */
    @Test fun undatedRowsKeepTheServersOrder() {
        val rows = NearAIModelCatalog.ordered(listOf(
            row("Qwen/QwenC", null), row("Qwen/QwenA", null), row("Qwen/QwenB", null)))
        assertEquals(listOf("Qwen/QwenC", "Qwen/QwenA", "Qwen/QwenB"), rows.map { it.id })
    }

    /** update_providers.py, 2026-10-02: typesafe/jev-1.13 is proxied live; offline it must not read as own fleet. */
    @Test fun typesafeIsProxiedOffline() {
        assertEquals(NearAIModelCatalog.Confidentiality.PROXIED, NearAIModelCatalog.classify("typesafe/jev-1.13"))
    }

    /** `deepseek/…` is a proxy; `deepseek-ai/…` is a near.ai node. One character apart. */
    @Test fun proxiedVendorNamespacesAreNotOwnFleet() {
        assertEquals(NearAIModelCatalog.Confidentiality.PROXIED, NearAIModelCatalog.classify("x-ai/grok-4.6"))
        assertEquals(NearAIModelCatalog.Confidentiality.PROXIED, NearAIModelCatalog.classify("deepseek/deepseek-v4.1-flash"))
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.classify("deepseek-ai/DeepSeek-V4-Flash"))
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.classify("z-ai/glm-5.3-flash"))
    }

    /**
     * Only ids near.ai ANSWERED for are written down. A tier the guess merely
     * assumed, saved beside a list, reads back later as though near.ai had said
     * it — and it is what seeds the cache on a cold launch.
     */
    @Test fun knownTiersReportsOnlyWhatTheCatalogueSaid() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.recordTiers(mapOf("moonshotai/kimi-k9" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
        val tiers = NearAIModelCatalog.knownTiers(listOf("moonshotai/kimi-k9", "z-ai/glm-never-seen"))
        assertEquals(mapOf("moonshotai/kimi-k9" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY), tiers)
        NearAIModelCatalog.resetTierCache()
    }

    // MARK: - The recency badge, from near.ai's own `created`
    //
    // NOT PORTED: everyLiveModelCarriesACreatedDate, theBadgeExpiresOnItsOwn and
    // staleSnapshotBadgesAreNowCorrectlyDenied read the captured
    // `teemoonTests/Fixtures/nearai_models.json`, which may only cross through
    // `conformance/` and has no kit class. The RULE they assert is pinned below
    // against a literal `created`, with `now` built from a date, not an epoch
    // literal (the first iOS version hardcoded 1_784_073_600 for "2026-07-29",
    // which is actually 2026-07-15).

    /** glm-5.2 was published 2026-06-17. At 41 days it is inside the window; ten days later it is outside. */
    @Test fun theBadgeExpiresOnItsOwn() {
        val created = ZonedDateTime.of(2026, 6, 17, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val model = NearAIModelCatalog.ModelsResponse.Model(id = "z-ai/glm-5.2", created = created.epochSecond)
        assertEquals(created, model.createdDate)
        assertTrue(ModelCatalog.isNew(created, now = created.plusSeconds(41 * 86_400)))
        assertFalse(ModelCatalog.isNew(created, now = created.plusSeconds(51 * 86_400)))
        // 0/absent means no date was reported — no badge, no guessing.
        assertNull(NearAIModelCatalog.ModelsResponse.Model(id = "x", created = 0).createdDate)
        assertFalse(ModelCatalog.isNew(null, now = created))
    }

    /** A model published 2026-06-04, measured 2026-07-29: 55 days, ten past the window. */
    @Test fun staleSnapshotBadgesAreNowCorrectlyDenied() {
        val created = ZonedDateTime.of(2026, 6, 4, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val measured = ZonedDateTime.of(2026, 7, 29, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        assertTrue((measured.epochSecond - created.epochSecond) / 86_400 > 45)
        assertFalse("55 days old and must not be badged new", ModelCatalog.isNew(created, now = measured))
    }

    /** The rows carry near.ai's own `created`, so a saved list can re-age its badges instead of freezing them. */
    @Test fun rowsCarryTheirCreationDate() = runTest {
        val rows = NearAIModelCatalog.buildModels(listOf(wire("z-ai/glm-5.3-flash", ownedBy = "nearai", created = 1_788_000_000)),
            directBase = { null })
        assertEquals(Instant.ofEpochSecond(1_788_000_000), rows.first().created)
    }

    /** Android-only: `Instant` round-trips through the wire's unix seconds. */
    @Test fun createdDateIsUnixSeconds() {
        assertEquals(Instant.ofEpochSecond(1780561470),
            NearAIModelCatalog.ModelsResponse.Model(id = "x", created = 1780561470).createdDate)
    }
}
