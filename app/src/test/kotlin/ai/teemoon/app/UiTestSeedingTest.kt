package ai.teemoon.app

// ports: teemoon/App/ContentView.swift (the `--uitesting` seeding) @ v1.0.1 — the plan and what it does to the store
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTestSeedingTest {
    private class Store : AddEditProviderModel.Store {
        val list = mutableListOf<Provider>(); val keys = mutableMapOf<String, String>()
        override val providers: List<Provider> get() = list
        override var currentProviderID: String? = null
        override fun credential(provider: Provider) = keys[provider.id].orEmpty()
        override fun credential(endpoint: String) = list.firstOrNull { it.endpoint == endpoint }?.let { keys[it.id] }
        override fun addProvider(provider: Provider): String { list.removeAll { it.id == provider.id }; list += provider; return provider.id }
        override fun updateProvider(provider: Provider) {}
        override fun removeProvider(provider: Provider) { list.removeAll { it.id == provider.id } }
        override fun setCredential(apiKey: String, providerID: String) { keys[providerID] = apiKey }
    }

    /** UITestHostSecrets @ v1.0.4: the two 1.0.2 presets seed like the others, from the same `~/.…_API_KEY` pair iOS reads. */
    @Test fun openRouterAndNvidiaSeedFromTheirHostKeyFiles() {
        assertEquals(listOf(".OPENROUTER_API_KEY", ".openrouter_api_key"), UiTestSeeding.keyFileNames("openrouter"))
        assertEquals(listOf(".NVIDIA_API_KEY", ".nvidia_api_key"), UiTestSeeding.keyFileNames("nvidia"))
        assertEquals(Provider.openRouter.id, UiTestSeeding.preset("OpenRouter")?.id)
        assertEquals(Provider.nvidia.id, UiTestSeeding.preset("nvidia")?.id)
    }

    @Test fun aLaunchWithoutTheFlagNeverSeeds() {
        assertNull(UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_PRESETS to "nearai")))
        assertNull(UiTestSeeding.plan(emptyMap()))
    }

    @Test fun seedingReplacesThePlacesAddsPresetsWithTheirKeysAndSelectsTheFirst() {
        val plan = UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true, UiTestSeeding.EXTRA_PRESETS to "nearai, grok,braveanswers",
            UiTestSeeding.EXTRA_LOCAL_ENDPOINT to "http://10.0.2.2:8765/v1", UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model", UiTestSeeding.EXTRA_GROUNDING to true))!!
        val store = Store().apply { list += Provider(id = "old", name = "old", endpoint = "http://old/v1/chat/completions", model = "m") }
        val files = mapOf(".NEAR_AI_API_KEY" to "near-key", ".XAI_API_KEY" to "xai-key", ".BRAVE_API_KEY" to "brave-key")
        var braveKey: String? = null; var grounding = false
        val log = UiTestSeeding.apply(plan, store, key = { names -> names.firstNotNullOfOrNull { files[it] } }, setBraveKey = { braveKey = it; null }, setGroundingEnabled = { grounding = it })
        assertTrue(store.list.none { it.id == "old" })                                      // a test never sees real places
        assertEquals("http://10.0.2.2:8765/v1/chat/completions", store.list[0].endpoint)   // the home server, from the base url
        assertEquals("fake-model", store.list[0].model)
        assertEquals(listOf("uitest-local", Provider.nearAI.id, Provider.grok.id, Provider.braveAnswers.id), store.list.map { it.id })
        assertEquals("near-key", store.keys[Provider.nearAI.id]); assertEquals("xai-key", store.keys[Provider.grok.id])
        assertNull(store.keys[Provider.braveAnswers.id])                                   // no file for it: added, unkeyed
        assertEquals("uitest-local", store.currentProviderID)
        assertEquals("brave-key", braveKey); assertTrue(grounding)
        assertTrue(log.any { it == "Brave Answers (no key file)" })
    }

    /**
     * `seed_ondevice` equips the staged bundle instead of re-downloading it. The `get` section offers a model until a
     * PROVIDER points at it, and only a finished download builds one, so weights pushed by a harness are invisible
     * without this. It goes in first: an on-device run wants the phone answering, not a server.
     */
    @Test fun seedingOnDeviceEquipsTheStagedModelAndSelectsIt() {
        val plan = UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true, UiTestSeeding.EXTRA_ON_DEVICE to true,
            UiTestSeeding.EXTRA_LOCAL_ENDPOINT to "http://10.0.2.2:8765/v1", UiTestSeeding.EXTRA_LOCAL_MODEL to "fake-model"))!!
        val gemma = LocalModelCatalog.all.first()
        val store = Store()
        val log = UiTestSeeding.apply(plan, store, key = { null }, setBraveKey = { null }, setGroundingEnabled = {}, installedLocal = { gemma })
        val local = store.list.first()
        assertEquals(gemma.id, local.localModelID)
        assertEquals("on-device", local.endpoint)
        assertEquals(local.id, store.currentProviderID)          // first, ahead of the home server
        assertEquals("uitest-local", store.list[1].id)           // the server is still seeded, just not current
        assertTrue(log.any { it == "on-device ${gemma.id} equipped" })
    }

    /** `seed_download` is a plan flag the activity acts on; the plan must carry it and nothing else must change. */
    @Test fun theDownloadFlagRidesThePlan() {
        assertTrue(UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true, UiTestSeeding.EXTRA_DOWNLOAD to true))!!.download)
        assertFalse(UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true))!!.download)
    }

    /** No weights on disk: say so and equip nothing, rather than a provider pointing at a file that is not there. */
    @Test fun seedingOnDeviceWithNoWeightsEquipsNothing() {
        val plan = UiTestSeeding.plan(mapOf(UiTestSeeding.EXTRA_FLAG to true, UiTestSeeding.EXTRA_ON_DEVICE to true))!!
        val store = Store()
        val log = UiTestSeeding.apply(plan, store, key = { null }, setBraveKey = { null }, setGroundingEnabled = {}, installedLocal = { null })
        assertTrue(store.list.isEmpty())
        assertNull(store.currentProviderID)
        assertTrue(log.any { it == "on-device (no weights on disk)" })
    }
}
