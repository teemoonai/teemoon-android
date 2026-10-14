// ports: teemoonTests/WherePresentationTests.swift @ v1.0.4 (the "Ready rows ordering", "Home server probe", "Home model deletion"
//        and "Server outlives its models" suites; the presentation suites not already in WhereGetPolicyTest/PlaceLabelTest)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereReadyRowsTest {
    private fun machine(name: String, host: String, models: List<String>) =
        Provider(id = "m-$host", name = name, endpoint = "http://$host:11434/v1", model = models[0], requiresAPIKey = false, equippedModels = models)
    private fun rows(vararg providers: Provider) = WhereReadyRows.rows(providers.toList())
    private fun offCatalogLocal(id: String) = Provider(id = "local-$id", name = id, endpoint = "on-device", model = id, requiresAPIKey = false, localModelID = id, equippedModels = listOf(id))

    @Test fun warmRisesAboveCold() {
        val mac = machine("second mac", "100.100.0.12", listOf("qwen3:14b", "deepseek-r1:32b", "gemma4:e2b"))
        val warm = setOf("gemma4:e2b")   // configured LAST, so order must move it
        assertEquals(listOf("gemma4:e2b", "qwen3:14b", "deepseek-r1:32b"), WhereReadyRows.ordered(rows(mac)) { it.modelID in warm }.map { it.modelID })
    }
    @Test fun machinesDoNotInterleave() {
        val mac = machine("second mac", "100.100.0.12", listOf("a-cold", "a-warm")); val linux = machine("linux box", "100.100.0.13", listOf("b-cold", "b-warm"))
        val warm = setOf("a-warm", "b-warm")
        assertEquals(listOf("a-warm", "a-cold", "b-warm", "b-cold"), WhereReadyRows.ordered(rows(mac, linux)) { it.modelID in warm }.map { it.modelID })
    }
    @Test fun unmeasuredWarmthLeavesConfiguredOrderAlone() {
        val mac = machine("second mac", "100.100.0.12", listOf("qwen3:14b", "deepseek-r1:32b", "gemma4:e2b"))
        assertEquals(listOf("qwen3:14b", "deepseek-r1:32b", "gemma4:e2b"), WhereReadyRows.ordered(rows(mac)) { null }.map { it.modelID })
    }
    @Test fun theRecommendedPhoneModelStaysOnTopWhileCold() {
        assertTrue(LocalModelCatalog.all.size > 1)
        val recommended = Provider.local(LocalModelCatalog.all[0]); val warmer = Provider.local(LocalModelCatalog.all[1])
        assertEquals(listOf(recommended.id, warmer.id), WhereReadyRows.ordered(rows(recommended, warmer)) { it.provider.id == warmer.id }.map { it.provider.id })
    }
    @Test fun warmthOrdersPhoneRowsBeneathTheRecommendedOne() {
        val recommended = Provider.local(LocalModelCatalog.all[0]); val cold = offCatalogLocal("someone/cold-model"); val warm = offCatalogLocal("someone/warm-model")
        assertEquals(3, setOf(recommended.id, cold.id, warm.id).size)
        assertEquals(listOf(recommended.id, warm.id, cold.id), WhereReadyRows.ordered(rows(recommended, cold, warm)) { it.provider.id == warm.id }.map { it.provider.id })
    }
    @Test fun tierOutranksWarmth() {
        val phone = Provider.local(LocalModelCatalog.all[0]); val mac = machine("second mac", "100.100.0.12", listOf("warm-one"))
        assertEquals(WhereLocality.PHONE, WhereLocality.of(WhereReadyRows.ordered(rows(mac, phone)) { true }[0].provider))
    }
}

class HomeServerProbeTest {
    @Test fun modelListTriesV1WhenTheEndpointOmitsIt() {
        val candidates = HomeServerProbe.modelListCandidates("https://ringzero.tailnet-name.ts.net:11434")
        assertEquals(listOf("https://ringzero.tailnet-name.ts.net:11434", "https://ringzero.tailnet-name.ts.net:11434/v1"), candidates)
    }
    @Test fun modelListDoesNotDoubleUpV1() { assertEquals(1, HomeServerProbe.modelListCandidates("http://100.100.0.12:11434/v1").size) }
    @Test fun forgetDropsTheModelFromTheCachedList() {
        val provider = Provider(id = "box", name = "box", endpoint = "http://100.64.0.1:11434/v1", model = "a", requiresAPIKey = false)
        val probe = HomeServerProbe.previewing(mapOf("box" to HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("a", "b"), setOf("a", "b"))))
        probe.forget("b", provider)
        assertEquals(listOf("a"), probe.info(provider)?.models); assertEquals(setOf("a"), probe.info(provider)?.warm)
    }
    @Test fun forgetOnUnknownProviderIsANoOp() {
        val probe = HomeServerProbe.previewing(emptyMap())
        val provider = Provider(id = "box", name = "box", endpoint = "http://100.64.0.1:11434/v1", model = "a", requiresAPIKey = false)
        probe.forget("a", provider); assertNull(probe.info(provider))
    }
    /** Seeded facts paint first; every refresh then probes live (iOS sets isSeeded only for previews — see HomeServerProbeRefreshTest). */
    @Test fun seedUsesThePersistedProbeAndEveryRefreshProbes() = runTest {
        var probes = 0
        val probe = HomeServerProbe { _, _, _ -> probes++; HomeServerProbe.Info(LocalServerKind.OLLAMA, listOf("live"), setOf("live")) }
        val box = Provider(id = "box", name = "box", endpoint = "http://100.64.0.1:11434/v1", model = "a", requiresAPIKey = false)
        probe.seed(listOf(box), servedModels = { listOf("a") }, storedKind = { LocalServerKind.OLLAMA })
        assertEquals(listOf("a"), probe.info(box)?.models)
        probe.refresh(listOf(box)) { "" }; probe.refresh(listOf(box)) { "" }
        assertEquals("each refresh probes", 2, probes); assertEquals(listOf("live"), probe.info(box)?.models)
    }
}

class HomeModelDeletionTest {
    @Test fun onlyOllamaExposesADeleteEndpoint() { assertEquals("http://100.100.0.12:11434", OllamaAdapter.rootUrl("http://100.100.0.12:11434/v1")) }
    @Test fun theOtherKindsHaveNoDeletionPath() { assertNotEquals(LocalServerKind.OLLAMA, LocalServerKind.LM_STUDIO); assertNotEquals(LocalServerKind.OLLAMA, LocalServerKind.UNKNOWN) }
}

class ServerOutlivesItsModelsTest {
    @Test fun unequippingTheLastModelKeepsTheServer() {
        val p = Provider(id = "near", name = "near.ai", endpoint = "https://api.near.ai/v1", model = "glm-5.2", requiresAPIKey = true, equippedModels = listOf("glm-5.2", "glm-5.1"))
        val survivor = p.unequipping("glm-5.1").unequipping("glm-5.2")
        assertTrue(survivor.equipped.isEmpty()); assertEquals("https://api.near.ai/v1", survivor.endpoint); assertTrue(survivor.requiresAPIKey)
    }
    @Test fun unequippingReportsEmptinessRatherThanReturningNull() {
        val p = Provider(id = "box", name = "box", endpoint = "http://100.100.0.12:11434/v1", model = "only", requiresAPIKey = false, equippedModels = listOf("only"))
        val emptied = p.unequipping("only")
        assertTrue(emptied.equipped.isEmpty()); assertEquals("", emptied.model); assertEquals(p.endpoint, emptied.endpoint); assertEquals(p.id, emptied.id)   // SAME id — the Keystore hangs off it
    }
}

class WherePresentationMoreTest {
    /** WhereChip.chipPlace(for:): the chip names the cloud, never the guarantee — the title block already states it. */
    @Test fun chipPlaceNamesTheCloudWithoutTheTrustSuffix() {
        val near = Provider.presets.first { it.endpoint.contains("near.ai") }
        assertEquals("near.ai", WhereProviderPresentation.chipPlace(near))
        assertEquals(null, WhereProviderPresentation.chipPlace(Provider.local(LocalModelCatalog.all[0])))
        val box = Provider(id = "b", name = "box", endpoint = "http://10.0.2.2:8765/v1/chat/completions", model = "fake-model")
        assertEquals(WhereProviderPresentation.placeCaption(box), WhereProviderPresentation.chipPlace(box))
        // an unsealed cloud shows its place caption (brave answers: "single turn q&a, fast built-in search")
        assertEquals(WhereProviderPresentation.placeCaption(Provider.braveAnswers), WhereProviderPresentation.chipPlace(Provider.braveAnswers))
    }

    /**
     * c3fb76d: the count is the saved live list's length and nothing else. A server
     * that has never answered shows NO number (a made-up one is worse than none),
     * not the length of a table compiled into the app — which is why OpenRouter and
     * NVIDIA used to have no count at all while near.ai counted a stale snapshot.
     */
    @Test fun browseRowsAndCountsAreWhatTheServerLastAnswered() {
        val store = LiveCatalogStore(null)
        assertTrue("nothing ships: a cold app has no near.ai rows", WhereProviderPresentation.browseModels(Provider.nearAI, "", store).isEmpty())
        val policy = WhereGetPolicy(null, emptyList(), true, emptyMap(), { "k" }, { "" }, catalogCount = { store.count(it, "k") })
        assertNull(policy.presetTrailingText(Provider.grok, keyed = true))
        assertEquals("add key", policy.presetTrailingText(Provider.grok, keyed = false))
        store.record(listOf(KnownModel("grok-4.6", "Grok 4.6", "xAI", ""), KnownModel("grok-4.5", "Grok 4.5", "xAI", "")), Provider.grok, "k")
        assertEquals("2", policy.presetTrailingText(Provider.grok, keyed = true))
        assertEquals(listOf("grok-4.6", "grok-4.5"), WhereProviderPresentation.browseModels(Provider.grok, "k", store).map { it.id })
        // The one fixed service is a row, not a list.
        assertEquals(listOf(Provider.braveAnswers.model), WhereProviderPresentation.browseModels(Provider.braveAnswers, "", store).map { it.id })
    }

    /** There is a live catalogue to ask — so a keyed near.ai row can browse before it has ever answered. */
    @Test fun nearAIBrowsesFromItsLiveSource() {
        assertEquals(EndpointModelCatalog.Source.NEAR_AI, WhereProviderPresentation.liveCatalogSource(Provider.nearAI))
    }

    @Test fun onDeviceCaptionMatchesTheDownloadScreen() { assertEquals("on this device", WhereProviderPresentation.placeCaption(Provider.local(LocalModelCatalog.all[0]))) }
    @Test fun onDeviceLabelUsesTheCatalogName() {
        val model = LocalModelCatalog.all[0]; val label = WhereProviderPresentation.modelLabel(Provider.local(model))
        assertEquals(model.displayName.lowercase(), label); assertFalse(label.contains("litert"))
    }
    @Test fun onDeviceHasNothingToBrowse() {
        val p = Provider.local(LocalModelCatalog.all[0]); assertFalse(p.supportsModelBrowsing)
        assertNull(WhereProviderPresentation.liveCatalogSource(p))
        assertTrue(WhereProviderPresentation.browseModels(p, "", LiveCatalogStore(null)).isEmpty())
    }
    @Test fun weightsHeadroomLabelFormatting() {
        assertEquals("around 4.1gb free for loading weights", LocalMemory.weightsHeadroomLabel(4198))
        assertEquals("around 12gb free for loading weights", LocalMemory.weightsHeadroomLabel(12_288))
        assertNull(LocalMemory.weightsHeadroomLabel(0))
        assertEquals("4.1 gb", LocalMemory.weightsHeadroomFigure(4198)); assertEquals("12 gb", LocalMemory.weightsHeadroomFigure(12_288))
    }
    @Test fun nearAIIsCloud() { assertEquals(WhereLocality.CLOUD, WhereLocality.of(Provider.nearAI)); assertTrue(WhereProviderPresentation.showsConfidentialityTags(Provider.nearAI)) }
    @Test fun modelLabelPrefersCatalog() { val label = WhereProviderPresentation.modelLabel(Provider.nearAI); assertTrue(label.isNotEmpty()); assertFalse(label.contains("http")) }
    @Test fun theAnswersEndpointSaysSingleTurnAndClaimsNoPrivacy() {
        val caption = WhereProviderPresentation.placeCaption(Provider.braveAnswers)
        assertTrue(Provider.braveAnswers.answersSingleTurnOnly); assertFalse(ProviderCapability.END_TO_END_ENCRYPTION in Provider.braveAnswers.capabilities)
        assertTrue(caption.contains("single turn")); assertTrue(caption.contains("search"))
        for (claim in listOf("private", "encrypted", "confidential")) assertFalse("claims $claim on a plaintext endpoint", caption.contains(claim))
    }
    @Test fun theAnswersCaptionFitsOneLine() { assertTrue(WhereProviderPresentation.placeCaption(Provider.braveAnswers).length <= 50) }
    @Test fun theTitleDoesNotRepeatTheProviderName() {
        assertEquals("brave answers", WhereProviderPresentation.modelLabel(Provider.braveAnswers.copy(name = "brave answers brave")))   // what auto-labelling actually stores
    }
    @Test fun canonicalNamingStillLeavesCloudRowsNamedByModel() {
        val label = WhereProviderPresentation.modelLabel(Provider.nearAI.equipping("glm-5.2")); assertTrue(label.contains("glm")); assertNotEquals("near.ai", label)
    }
    @Test fun cloudByokCaptionUsesProviderName() { assertTrue(WhereProviderPresentation.placeCaption(Provider.grok).isNotEmpty()) }
    @Test fun warmthJoinsTheCaptionRun() { assertEquals("ollama · warm", WhereProviderPresentation.metadataRun("ollama", "warm")) }
    @Test fun aMissingSideNeverLeavesAnOrphanedSeparator() {
        assertEquals("ollama", WhereProviderPresentation.metadataRun("ollama", null)); assertEquals("warm", WhereProviderPresentation.metadataRun(null, "warm")); assertNull(WhereProviderPresentation.metadataRun(null, null))
    }
}

class HomePlaceNamingTest {
    @Test fun aMachineIsNamedByItsHostNotItsStaleLabel() {
        val machine = Provider(id = "m", name = "ringzero gemma4:e2b-it-qat", endpoint = "https://ringzero.tailnet-name.ts.net:11434/v1", model = "gemma4:e4b", requiresAPIKey = false, equippedModels = listOf("gemma4:e4b"))
        val place = machine.canonicalName
        assertEquals("ringzero", place); assertFalse("the place named a model, and the wrong one", place.contains("e2b"))
        assertEquals(place, WhereProviderPresentation.canonicalName(machine))
    }
    @Test fun anIPAddressIsKeptWhole() { assertEquals("192.168.1.50", Provider(id = "b", name = "box", endpoint = "http://192.168.1.50:11434/v1", model = "qwen3.5:4b", requiresAPIKey = false).canonicalName) }
    @Test fun cloudPresetsKeepTheirName() { assertEquals("near.ai", Provider.nearAI.canonicalName); assertEquals("fireworks", Provider.fireworks.canonicalName) }
    @Test fun managedPlacesExcludeThePhone() {
        val onDevice = Provider(id = "d", name = "Gemma 4 E2B", endpoint = "on-device", model = "gemma", requiresAPIKey = false, localModelID = "litert-community/gemma-4-E2B-it-litert-lm")
        val home = Provider.nearAI.copy(id = "h", endpoint = "http://192.168.1.50:11434/v1", requiresAPIKey = false)
        val managed = WhereLocality.managed(listOf(onDevice, Provider.nearAI, home))
        assertEquals(2, managed.size); assertFalse(managed.any { WhereLocality.of(it) == WhereLocality.PHONE })
        assertTrue(managed.any { WhereLocality.of(it) == WhereLocality.CLOUD }); assertTrue(managed.any { WhereLocality.of(it) == WhereLocality.HOME })
    }
}
