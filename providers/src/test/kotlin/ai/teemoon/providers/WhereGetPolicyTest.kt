// ports: teemoonTests/WhereGetPolicyTests.swift @ v1.0.4
// ports: teemoonTests/WhereGetPolicyTests.swift @ f3773d9 (the memory-fit tests)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WhereGetPolicyTest {

    private fun grok(
        id: String = UUID.randomUUID().toString(),
        endpoint: String = "https://api.x.ai/v1/chat/completions",
    ): Provider = Provider.grok.copy(id = id, endpoint = endpoint)

    private fun ollama(id: String = UUID.randomUUID().toString()): Provider = Provider(
        id = id,
        name = "box",
        endpoint = "http://100.64.0.1:11434/v1",
        model = "llama",
        requiresAPIKey = false,
    )

    private fun policy(
        filter: WhereLocality? = null,
        providers: List<Provider>,
        network: Boolean = true,
        kinds: Map<String, LocalServerKind> = emptyMap(),
        byID: Map<String, String> = emptyMap(),
        byEndpoint: Map<String, String> = emptyMap(),
    ): WhereGetPolicy = WhereGetPolicy(
        filter = filter,
        providers = providers,
        networkSatisfied = network,
        home = kinds.mapValues { WhereGetPolicy.HomeInfo(kind = it.value) },
        credentialFor = { byID[it.id] ?: "" },
        credentialForEndpoint = { byEndpoint[it] ?: "" },
    )

    /** A Tensor phone that downloaded the general file before the device catalog: the place exists, the get row still offers the Tensor file. */
    @Test fun aPlaceRunningTheGeneralFileStillGetsTheDeviceFileOffered() {
        val device = LocalModelCatalog.forDevice("Tensor G5"); val tensor = device.first()
        val place = Provider.local(LocalModelCatalog.all.first())   // equipped from the general file, before
        val missing = WhereGetPolicy(null, listOf(place), true, emptyMap(), { "" }, { "" }, catalog = device, deviceFileMissing = { it.fileName != it.generalFileName })
        assertEquals(tensor, missing.recommendedDownload); assertTrue(tensor in missing.downloadableModels)
        val landed = WhereGetPolicy(null, listOf(place), true, emptyMap(), { "" }, { "" }, catalog = device, deviceFileMissing = { false })
        assertNull(landed.recommendedDownload); assertFalse(tensor in landed.downloadableModels)
        // no place at all: offered exactly as before, whatever the filesystem says
        assertEquals(tensor, WhereGetPolicy(null, emptyList(), true, emptyMap(), { "" }, { "" }, catalog = device).recommendedDownload)
    }

    @Test fun hasKeyFindsCredentialUnderADifferentInstanceID() {
        val preset = Provider.grok
        val saved = grok()
        val p = policy(providers = listOf(saved), byID = mapOf(preset.id to "sk-from-onboarding"))
        assertFalse(p.hasKey(saved))
        val viaEndpoint = policy(
            providers = listOf(saved),
            byEndpoint = mapOf(saved.endpoint to "sk-from-settings"),
        )
        assertTrue(viaEndpoint.hasKey(saved))
    }

    @Test fun keylessSelfHostedHasKeyWithoutAStoreEntry() {
        val box = ollama()
        val p = policy(providers = listOf(box))
        assertTrue(p.hasKey(box))
    }

    @Test fun pullAndDeleteAreOllamaOnly() {
        val ollamaID = UUID.randomUUID().toString()
        val lmID = UUID.randomUUID().toString()
        val box = ollama(id = ollamaID)
        val lm = Provider(
            id = lmID,
            name = "studio",
            endpoint = "http://100.64.0.2:1234/v1",
            model = "x",
            requiresAPIKey = false,
        )
        val p = policy(
            filter = WhereLocality.HOME,
            providers = listOf(box, lm),
            kinds = mapOf(ollamaID to LocalServerKind.OLLAMA, lmID to LocalServerKind.LM_STUDIO),
        )
        assertEquals(listOf(ollamaID), p.pullableProviders.map { it.id })
        assertTrue(p.canDeleteFromServer(box))
        assertFalse(p.canDeleteFromServer(lm))
    }

    @Test fun recommendedDownloadDoesNotFallThroughToE4B() {
        val e2b = LocalModelCatalog.all[0]
        val e4b = LocalModelCatalog.all[1]
        val installed = Provider.local(e2b)
        val p = policy(filter = null, providers = listOf(installed))
        assertNull(p.recommendedDownload)
        assertEquals(listOf(e4b.id), p.downloadableModels.map { it.id })
    }

    @Test fun getSectionEmptyOnPhoneWhenEverythingIsDownloaded() {
        val installed = LocalModelCatalog.all.map { Provider.local(it) }
        val p = policy(filter = WhereLocality.PHONE, providers = installed)
        assertTrue(p.getSectionIsEmpty)
        assertTrue(p.downloadableModels.isEmpty())
    }

    @Test fun browseRequiresAKey() {
        val g = grok()
        val p = policy(providers = listOf(g))
        assertFalse(p.canBrowse(g))
        val keyed = policy(providers = listOf(g), byEndpoint = mapOf(g.endpoint to "sk"))
        assertTrue(keyed.canBrowse(g))
    }

    @Test fun braveIsAFixedAnswerServiceAndNotBrowsable() {
        assertTrue(Provider.braveAnswers.isFixedAnswerService)
        assertFalse(Provider.grok.isFixedAnswerService)
        val brave = Provider.braveAnswers
        val keyed = policy(providers = listOf(brave), byEndpoint = mapOf(brave.endpoint to "token"))
        assertFalse(keyed.canBrowse(brave))
    }

    @Test fun addProviderGlyphFollowsFilter() {
        val empty = emptyList<Provider>()
        assertEquals(
            WhereLocality.HOME.systemImage,
            policy(filter = WhereLocality.HOME, providers = empty).addProviderGlyph,
        )
        assertEquals("key", policy(filter = WhereLocality.CLOUD, providers = empty).addProviderGlyph)
        assertEquals("plus", policy(filter = null, providers = empty).addProviderGlyph)
    }

    /** everyCloudCatalogueIsBrowsable (WhereGetPolicyTests @ v1.0.4): no search-first class; each cloud has its live source. */
    @Test fun everyCloudCatalogueIsBrowsable() {
        val router = Provider(id = UUID.randomUUID().toString(), name = "openrouter", endpoint = "https://openrouter.ai/api/v1/chat/completions", model = "x")
        assertEquals(EndpointModelCatalog.Source.OPEN_ROUTER, WhereProviderPresentation.liveCatalogSource(router))
        assertEquals(EndpointModelCatalog.Source.XAI, WhereProviderPresentation.liveCatalogSource(Provider.grok))
    }

    /** aCustomEndpointIsNotBrowsableJustBecauseItHasAKey (ac11ef2): its /models may not exist, and a 401 there reads as a bad key. */
    @Test fun aCustomEndpointIsNotBrowsableJustBecauseItHasAKey() {
        val custom = Provider(id = UUID.randomUUID().toString(), name = "mine", endpoint = "https://llm.example.com/v1/chat/completions", model = "m")
        assertFalse(policy(providers = listOf(custom), byEndpoint = mapOf(custom.endpoint to "sk")).canBrowse(custom))
        val openRouter = Provider.openRouter
        assertTrue(policy(providers = listOf(openRouter), byEndpoint = mapOf(openRouter.endpoint to "sk")).canBrowse(openRouter))
    }

    @Test fun browseNamePrefersServerKindOverNickname() {
        val id = UUID.randomUUID().toString()
        val box = ollama(id = id)
        val named = box.copy(name = "second mac")
        val p = policy(providers = listOf(named), kinds = mapOf(id to LocalServerKind.OLLAMA))
        assertEquals("ollama", p.browseName(named))
        assertEquals("equip another model on this setup", p.browseCaption(named))
        val counted = WhereGetPolicy(
            filter = WhereLocality.HOME,
            providers = listOf(named),
            networkSatisfied = true,
            home = mapOf(id to WhereGetPolicy.HomeInfo(kind = LocalServerKind.OLLAMA, modelCount = 3)),
            credentialFor = { "" },
            credentialForEndpoint = { "" },
        )
        assertEquals("3 models on this server", counted.browseCaption(named))
    }

    @Test fun addProviderCaptionDependsOnFilter() {
        val empty = emptyList<Provider>()
        assertEquals(
            "configures an api key for any provider",
            policy(filter = WhereLocality.CLOUD, providers = empty).addProviderCaption,
        )
        assertNull(policy(filter = null, providers = empty).addProviderCaption)
    }

    @Test fun customEndpointIsNotAPreset() {
        val custom = Provider(
            id = UUID.randomUUID().toString(),
            name = "mine",
            endpoint = "https://example.com/v1/chat/completions",
            model = "x",
        )
        val p = policy(providers = listOf(custom))
        assertTrue(p.isCustom(custom))
        assertFalse(p.isCustom(Provider.grok))
    }

    @Test fun phoneFooterIsSilentWithoutAMemoryFigure() {
        val p = policy(filter = WhereLocality.PHONE, providers = emptyList())
        assertEquals("", p.getFooter(openToGet = false, memoryFigure = null))
        assertTrue(
            p.getFooter(openToGet = false, memoryFigure = "4.1 gb")
                .contains("memory, not storage"),
        )
        assertTrue(p.getFooter(openToGet = true, memoryFigure = null).contains("start here"))
    }

    @Test fun destructionDependsOnPlace() {
        val phone = Provider.local(LocalModelCatalog.all[0])
        val ollamaID = UUID.randomUUID().toString()
        val box = ollama(id = ollamaID)
        val boxTwo = box.copy(equippedModels = listOf("a", "b"))
        val lm = Provider(
            id = UUID.randomUUID().toString(),
            name = "studio",
            endpoint = "http://100.64.0.2:1234/v1",
            model = "x",
            requiresAPIKey = false,
            equippedModels = listOf("a", "b"),
        )
        val p = policy(
            providers = listOf(phone, boxTwo, lm, Provider.grok),
            kinds = mapOf(ollamaID to LocalServerKind.OLLAMA, lm.id to LocalServerKind.LM_STUDIO),
        )
        assertEquals(WhereGetPolicy.RowDestruction.DELETE_LOCAL_WEIGHTS, p.destruction(phone))
        assertEquals(WhereGetPolicy.RowDestruction.DELETE_FROM_SERVER, p.destruction(boxTwo))
        assertEquals(WhereGetPolicy.RowDestruction.NONE, p.destruction(lm))
        assertEquals(WhereGetPolicy.RowDestruction.UNEQUIP_CLOUD, p.destruction(Provider.grok))
    }

    /**
     * The "may not fit" label and the start-here hero read total RAM against the model's floor, never free memory: an
     * 8 GB Pixel 8a that runs E2B was told it may not fit (Test Lab, 2026-10-30). Total memory as phones report it: an
     * "8 GB" phone about 7.5 GiB, a "6 GB" one about 5.6, a "4 GB" one (Galaxy A15) about 3.7. (iOS f3773d9)
     */
    @Test fun theFitLabelIsTheRAMFloorNotFreeMemory() {
        fun gib(g: Double) = (g * 1_073_741_824).toLong()
        val e2b = LocalModelCatalog.all.first { it.fileName == "gemma-4-E2B-it.litertlm" }
        val e4b = LocalModelCatalog.all.first { it.fileName == "gemma-4-E4B-it.litertlm" }
        assertEquals(8, LocalMemory.marketedRAMGB(gib(7.5))); assertEquals(6, LocalMemory.marketedRAMGB(gib(5.6))); assertEquals(4, LocalMemory.marketedRAMGB(gib(3.7)))
        assertFalse(LocalMemory.belowFloor(e2b, gib(7.5)))   // Pixel 8a, Pixel 10 Pro
        assertFalse(LocalMemory.belowFloor(e2b, gib(5.6)))   // 6 GB
        assertTrue(LocalMemory.belowFloor(e2b, gib(3.7)))    // 4 GB, Galaxy A15
        assertFalse(LocalMemory.belowFloor(e4b, gib(7.5)))
        assertTrue(LocalMemory.belowFloor(e4b, gib(5.6)))
        assertFalse("an unread total never warns", LocalMemory.belowFloor(e2b, 0))
        // The Tensor bundle is the same model as this device downloads it: the floor travels with it.
        assertEquals(e2b.minRAMGB, e2b.forDevice("Tensor G5").minRAMGB)
    }

    @Test fun recommendedPhoneModelDoesNotHeroADownloadThatWouldFail() {
        val model = LocalModelCatalog.all[0]
        assertEquals(model, WhereGetPolicy.recommendedPhoneModel(LocalModelCatalog.all, totalBytes = (7.5 * 1_073_741_824).toLong()))
        assertNull(WhereGetPolicy.recommendedPhoneModel(LocalModelCatalog.all, totalBytes = (3.7 * 1_073_741_824).toLong()))
        assertEquals(model, WhereGetPolicy.recommendedPhoneModel(LocalModelCatalog.all, totalBytes = 0))
    }

    /** The load gate asks for the measured peak plus headroom; until a model's peak is measured, its file size. */
    @Test fun theLoadGateUsesThePeakOnceMeasured() {
        assertEquals(2980, LocalMemory.loadNeedMB(sizeMB = 2468, peakFootprintMB = null))
        assertEquals(2012, LocalMemory.loadNeedMB(sizeMB = 2468, peakFootprintMB = 1500))
        assertTrue("a peak was set: update the floor check", LocalModelCatalog.all.all { it.peakFootprintMB == null })
    }

    /**
     * Android-only (pm 2026-10-30): the Pixel 8a's figures, read on Test Lab (matrix 9000657022189770859, akita API 35):
     * totalMem 7558 MB, availMem 2645 MB at launch and 3399 MB before the load, threshold 216 MB. The old label compared
     * the 2468 MB download with 2645 − 216 = 2429 MB and said "may not fit"; the floor rule reads 8 GB and says nothing.
     * The load gate passes on availMem before the load. The Pixel 6a (6 GB, bluejay API 32) is over E2B's floor too:
     * it ran the vision turns with 612 MB still available.
     */
    @Test fun thePixel8aPassesTheLabelAndTheLoadGate() {
        val e2b = LocalModelCatalog.all.first { it.fileName == "gemma-4-E2B-it.litertlm" }
        val mb = 1024L * 1024
        val totalMem = 7558 * mb; val availAtLaunchMB = 2645; val availBeforeLoadMB = 3399; val thresholdMB = 216
        assertFalse("8 GB is over E2B's floor", LocalMemory.belowFloor(e2b, totalMem))
        assertTrue("the old label tripped on the 8a at launch", e2b.sizeMB > availAtLaunchMB - thresholdMB)
        assertTrue("the gate lets the 8a load", availBeforeLoadMB >= LocalMemory.loadNeedMB(e2b.sizeMB, e2b.peakFootprintMB))
        assertFalse("6 GB (Pixel 6a, 5580 MB) is over E2B's floor", LocalMemory.belowFloor(e2b, 5580 * mb))
    }

    /**
     * Android-only (pm 2026-10-30): at or above the floor the gate asks the file size alone, below it file size + 512.
     * Test Lab's figures (2026-10-30): availMem at launch 2645 MB (Pixel 8a, 7558 MB) and 2539 MB (Pixel 6a, 5580 MB)
     * now clear E2B's 2468 MB; a 4 GB phone (3.7 GiB) still asks 2980.
     */
    @Test fun atOrAboveTheFloorTheGateAsksTheFileSizeAlone() {
        val e2b = LocalModelCatalog.all.first { it.fileName == "gemma-4-E2B-it.litertlm" }
        val mb = 1024L * 1024
        fun need(totalBytes: Long) = e2b.sizeMB + LocalMemory.androidHeadroomMB(e2b, totalBytes)
        assertEquals(2468, need(7558 * mb)); assertTrue("the 8a at launch", 2645 >= need(7558 * mb))
        assertEquals(2468, need(5580 * mb)); assertTrue("the 6a at launch", 2539 >= need(5580 * mb))
        assertEquals(2980, need((3.7 * 1_073_741_824).toLong()))
        assertEquals("an unread total keeps the headroom", 2980, need(0))
    }

    /**
     * Android-only: on a Tensor phone the start-here card heroes the Tensor bundle — the file this device downloads and
     * the one isInstalled looks for — never the general file (Pixel 10 Pro, 2026-09-24: "download · 2.4 gb" with the
     * Tensor weights already on disk).
     */
    @Test fun theStartHereCardHeroesTheFileThisDeviceDownloads() {
        val device = LocalModelCatalog.forDevice("Tensor G5")
        val hero = WhereGetPolicy.recommendedPhoneModel(device, totalBytes = 0)!!
        assertEquals(device[0].fileName, hero.fileName)
        assertTrue("a Tensor phone must hero the Tensor bundle, not ${hero.fileName}", hero.fileName.contains("Tensor_G5"))
        assertEquals(device[0].sizeMB, hero.sizeMB)
    }

    @Test fun firstRunIsEmptyAllWithAFitAndNothingMoreUrgent() {
        val leader = LocalModelCatalog.all[0]
        val empty = policy(filter = null, providers = emptyList())
        assertTrue(empty.showsFirstRun(loadFailed = false, recommended = leader))
        assertFalse(empty.showsFirstRun(loadFailed = true, recommended = leader))
        assertFalse(empty.showsFirstRun(loadFailed = false, recommended = null))
        assertFalse(
            policy(filter = WhereLocality.PHONE, providers = emptyList())
                .showsFirstRun(loadFailed = false, recommended = leader),
        )
        assertFalse(
            policy(filter = null, providers = emptyList(), network = false)
                .showsFirstRun(loadFailed = false, recommended = leader),
        )
        assertFalse(
            policy(providers = listOf(Provider.grok))
                .showsFirstRun(loadFailed = false, recommended = leader),
        )
    }

    @Test fun emptyCopyNamesTheTierNotAllThreePlaces() {
        val all = policy(filter = null, providers = emptyList())
        assertEquals("no setups yet", all.emptyTitle)
        assertTrue(all.emptyDescription(openToGet = true).contains("get below"))
        assertFalse(all.emptyDescription(openToGet = false).contains("get below"))
        assertEquals(
            "nothing downloaded",
            policy(filter = WhereLocality.PHONE, providers = emptyList()).emptyTitle,
        )
        assertEquals(
            "no computers connected",
            policy(filter = WhereLocality.HOME, providers = emptyList()).emptyTitle,
        )
        assertEquals(
            "no cloud keys",
            policy(filter = WhereLocality.CLOUD, providers = emptyList()).emptyTitle,
        )
        assertEquals(
            WhereLocality.PHONE.systemImage,
            policy(filter = WhereLocality.PHONE, providers = emptyList()).emptyGlyph,
        )
    }

    @Test fun readyCaptionMarksUnreachableHome() {
        val id = UUID.randomUUID().toString()
        val box = ollama(id = id)
        val unreachable = WhereGetPolicy(
            filter = null,
            providers = listOf(box),
            networkSatisfied = true,
            home = mapOf(id to WhereGetPolicy.HomeInfo(kind = LocalServerKind.UNKNOWN, modelCount = null)),
            credentialFor = { "" },
            credentialForEndpoint = { "" },
        )
        assertTrue(unreachable.readyCaption(box)?.contains("couldn't reach") == true)
    }

    @Test fun readyCaptionHidesTheServerNameOnASingleHomeFilter() {
        val id = UUID.randomUUID().toString()
        val box = ollama(id = id)
        val one = policy(filter = WhereLocality.HOME, providers = listOf(box), kinds = mapOf(id to LocalServerKind.OLLAMA))
        assertNull(one.readyCaption(box))
        val other = ollama(id = UUID.randomUUID().toString())
        val two = policy(
            filter = WhereLocality.HOME,
            providers = listOf(box, other),
            kinds = mapOf(id to LocalServerKind.OLLAMA, other.id to LocalServerKind.OLLAMA),
        )
        assertEquals("ollama", two.readyCaption(box))
    }

    @Test fun readyCaptionFlagsCloudWithoutE2EE() {
        val grok = Provider.grok
        assertTrue(
            policy(providers = listOf(grok)).readyCaption(grok)
                ?.contains("not end-to-end encrypted") == true,
        )
        NearAIModelCatalog.resetTierCache()
        // near.ai's tier list seen, as on a phone that has loaded it: unseen, the flagship's id reads unknown and claims
        // nothing (iOS ea19232). It passed only when an earlier test in the JVM had recorded a tier list.
        NearAIModelCatalog.tierListSeenOverride = true
        try {
        val confidential = Provider.nearAI
        assertTrue(ProviderCapability.END_TO_END_ENCRYPTION in confidential.capabilities)
        assertTrue(
            policy(providers = listOf(confidential)).readyCaption(confidential)
                ?.contains("end-to-end encrypted") == true,
        )
        assertFalse(
            policy(providers = listOf(confidential)).readyCaption(confidential)
                ?.contains("not end-to-end encrypted") == true,
        )
        } finally { NearAIModelCatalog.tierListSeenOverride = null }
    }

    @Test fun isWarmIsNilWhenUnmeasuredAndFalseWhenCold() {
        val id = UUID.randomUUID().toString()
        val box = ollama(id = id)
        val unmeasured = policy(providers = listOf(box))
        assertNull(unmeasured.isWarm(provider = box, modelID = "llama"))
        val unknown = WhereGetPolicy(
            filter = null,
            providers = listOf(box),
            networkSatisfied = true,
            home = mapOf(id to WhereGetPolicy.HomeInfo(kind = LocalServerKind.UNKNOWN, warm = setOf("llama"))),
            credentialFor = { "" },
            credentialForEndpoint = { "" },
        )
        assertNull(unknown.isWarm(provider = box, modelID = "llama"))
        val identified = WhereGetPolicy(
            filter = null,
            providers = listOf(box),
            networkSatisfied = true,
            home = mapOf(id to WhereGetPolicy.HomeInfo(kind = LocalServerKind.OLLAMA, warm = setOf("llama"))),
            credentialFor = { "" },
            credentialForEndpoint = { "" },
        )
        assertEquals(true, identified.isWarm(provider = box, modelID = "llama"))
        assertEquals(false, identified.isWarm(provider = box, modelID = "other"))
        assertNull(identified.isWarm(provider = Provider.grok, modelID = "x"))
    }

    /**
     * Review item where-near-unencrypted-muted (the maintainer 2026-11-07): a near.ai model that isn't end-to-end
     * encrypted (the proxied or third-party tier) is an ordinary choice, so its caption is the muted secondary, not
     * orange. Orange stays for a missing download and a failed pull. The trust chip's colours are unchanged.
     */
    @Test fun aCaptionWarnsOnlyForAMissingDownloadOrAFailedPull() {
        NearAIModelCatalog.resetTierCache()
        val proxied = Provider(
            id = UUID.randomUUID().toString(),
            name = "near.ai",
            endpoint = "https://cloud-api.near.ai/v1/chat/completions",
            model = "anthropic/claude-opus-4-6",
        )
        val p = policy(providers = listOf(proxied, Provider.nearAI, Provider.grok))
        assertFalse("a proxied near.ai model is an ordinary choice", p.captionWarns(proxied, missingDownload = false, pullFailed = false))
        assertFalse(p.captionWarns(Provider.nearAI, missingDownload = false, pullFailed = false))
        assertFalse(p.captionWarns(Provider.grok, missingDownload = false, pullFailed = false))
        assertTrue(p.captionWarns(Provider.grok, missingDownload = true, pullFailed = false))
        assertTrue(p.captionWarns(Provider.grok, missingDownload = false, pullFailed = true))
    }
}
