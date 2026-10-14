// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 ("Cloud browse routing": the two routing tests; the two stub-API
//        tests need the xai/fireworks catalog fixtures, which cross only through conformance/). The browser's sort, sections and
//        filters are ModelBrowserListing's and are pinned in ModelBrowserQueryTest.
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveModelsLoaderTest {
    private fun m(id: String, vendor: String, price: String, ctx: String = "") = KnownModel(id, id, vendor, price, contextWindow = ctx)

    @Test fun everyCloudProviderResolvesToItsOwnCatalogue() {
        assertEquals(EndpointModelCatalog.Source.NEAR_AI, WhereProviderPresentation.liveCatalogSource(Provider.nearAI))
        assertEquals(EndpointModelCatalog.Source.XAI, WhereProviderPresentation.liveCatalogSource(Provider.grok))
        assertEquals(EndpointModelCatalog.Source.FIREWORKS, WhereProviderPresentation.liveCatalogSource(Provider.fireworks))
        val custom = Provider(id = "c", name = "my proxy", endpoint = "https://llm.example.com/v1/chat/completions", model = "some-model", requiresAPIKey = true)
        assertEquals(EndpointModelCatalog.Source.GENERIC, WhereProviderPresentation.liveCatalogSource(custom))
    }
    @Test fun theOtherTwoTiersHaveNothingLiveToBrowse() {
        val machine = Provider(id = "m", name = "second mac", endpoint = "http://100.100.0.12:11434/v1", model = "qwen3:14b", requiresAPIKey = false)
        assertNull(WhereProviderPresentation.liveCatalogSource(machine))
        assertNull(WhereProviderPresentation.liveCatalogSource(Provider.local(LocalModelCatalog.all[0])))
    }
    @Test fun liveLoaderRoutesCloudToItsSourceAndOllamaToTheMachine() = runTest {
        val machine = Provider(id = "m", name = "second mac", endpoint = "http://100.100.0.12:11434/v1", model = "qwen3:14b", requiresAPIKey = false)
        var cloudCalls = 0; var ollamaCalls = 0
        val cloud: suspend (EndpointModelCatalog.Source, String, String, String?) -> EndpointModelCatalog.ProbeResult = { _, _, _, _ -> cloudCalls++; EndpointModelCatalog.ProbeResult.Connected(listOf(m("a", "v", ""))) }
        val ollama: suspend (String) -> EndpointModelCatalog.ProbeResult = { ollamaCalls++; EndpointModelCatalog.ProbeResult.Connected(listOf(m("b", "v", ""))) }
        val store = LiveCatalogStore(null, list = cloud)
        assertEquals("a", WhereProviderPresentation.liveModelsLoader(Provider.grok, "k", null, store, ollama)!!()!!.single().id)
        assertEquals("b", WhereProviderPresentation.liveModelsLoader(machine, "", LocalServerKind.OLLAMA, store, ollama)!!()!!.single().id)
        assertNull("an unknown server has no list to browse", WhereProviderPresentation.liveModelsLoader(machine, "", LocalServerKind.UNKNOWN, store, ollama))
        assertNull(WhereProviderPresentation.liveModelsLoader(Provider.local(LocalModelCatalog.all[0]), "", null, store, ollama))
        assertEquals(1, cloudCalls); assertEquals(1, ollamaCalls)
        // The browser's fetch IS the store's: what it loaded is what the get row now counts.
        assertEquals(1, store.count(Provider.grok, "k"))
    }
    @Test fun metaLabelJoinsPriceAndContextAndIsEmptyWithoutEither() {
        assertEquals("\$1.40/\$4.40 · 1m", m("x", "v", "\$1.40/\$4.40", "1M").metaLabel)
        assertEquals("256k", m("x", "v", "", "256K").metaLabel); assertEquals("", m("x", "v", "").metaLabel)
        assertEquals("\$2/\$6 / 1M tokens", m("x", "v", "\$2/\$6").priceLabel)
    }
}
