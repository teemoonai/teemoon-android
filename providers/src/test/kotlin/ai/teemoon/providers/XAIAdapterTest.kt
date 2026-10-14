// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.1 (the XAIAdapter suite)
package ai.teemoon.providers

import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XAIAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** grokIDsReadAsProducts (ProviderCatalogAdapterTests @ v1.0.4, iOS c3fb76d). */
    @Test fun grokIDsReadAsProducts() {
        assertEquals("Grok 5", XAIAdapter.displayName("grok-5"))
        assertEquals("Grok 4.6", XAIAdapter.displayName("grok-4.6"))
        assertEquals("Grok 4.20 Non Reasoning", XAIAdapter.displayName("grok-4.20-0309-non-reasoning"))
        assertEquals("Grok 4.20 Multi Agent", XAIAdapter.displayName("grok-4.20-multi-agent-0309"))
        // Version digits are not a stamp, however many there are.
        assertEquals("Grok Build 0.1", XAIAdapter.displayName("grok-build-0.1"))
    }

    @Test fun priceUnitIsHundredthsOfACentPer100MTokens() {
        assertEquals(1.25, XAIAdapter.perMillion(12500))
        assertEquals(6.0, XAIAdapter.perMillion(60000))
        assertNull(XAIAdapter.perMillion(null))
    }

    /** One list fetch (iOS dfc528b): xAI's own copy read a keyless 403 as a bad key and every transport error as offline. */
    @Test fun aKeyless403IsForbiddenAndAnUnreachableHostIsNotOffline() = runTest {
        val forbidden = XAIAdapter.listModels("https://api.x.ai/v1", "", fetch = { _, _, _ -> 403 to ByteArray(0) })
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.FORBIDDEN), forbidden)
        val unreachable = XAIAdapter.listModels("https://api.x.ai/v1", "k", fetch = { _, _, _ -> throw UnknownHostException("api.x.ai") })
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.NOTHING_LISTENING), unreachable)
    }

    /** A cancelled probe stays cancelled — Kotlin's CancellationException is an Exception, and both fetches caught it. */
    @Test fun cancellationPropagates() = runTest {
        try {
            XAIAdapter.listModels("https://api.x.ai/v1", "k", fetch = { _, _, _ -> throw kotlinx.coroutines.CancellationException("left the screen") })
            org.junit.Assert.fail("a cancelled fetch must not become a ProbeResult")
        } catch (_: kotlinx.coroutines.CancellationException) {}
    }

    @Test fun mediaModelsAreNotChatModels() {
        val list = json.decodeFromString(XAIAdapter.ModelsResponse.serializer(), MODELS_JSON)
        assertEquals(10, list.data.size)
        val text = list.data.filter { it.isTextModel }.map { it.id }
        assertEquals(6, text.size)
        assertFalse(text.any { it.startsWith("grok-imagine") })
    }

    @Test fun languageModelsFixtureDecodesModalitiesAndAliases() {
        val list = json.decodeFromString(
            XAIAdapter.LanguageModelsResponse.serializer(),
            LANGUAGE_JSON,
        )
        assertEquals(6, list.models.size)
        val flagship = list.models.first { it.id == "grok-4.5" }
        assertEquals(listOf("text", "image"), flagship.input_modalities)
        assertEquals(20000, flagship.prompt_text_token_price)
        assertEquals(60000, flagship.completion_text_token_price)
        val g420 = list.models.first { it.id == "grok-4.20-0309-reasoning" }
        assertTrue(g420.aliases?.contains("grok-4.20") == true)
    }

    @Test fun listModelsBuildsCompletePickerRows() {
        val language = json.decodeFromString(
            XAIAdapter.LanguageModelsResponse.serializer(),
            LANGUAGE_JSON,
        )
        val models = json.decodeFromString(XAIAdapter.ModelsResponse.serializer(), MODELS_JSON)
        val result = XAIAdapter.assemble(Result.success(language), Result.success(models))
        val rows = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(6, rows.size)
        assertFalse(rows.any { it.id.startsWith("grok-imagine") })
        assertTrue(rows.all { it.vendor == "xAI" })
        assertTrue(rows.all { it.price.isNotEmpty() })
        assertTrue(rows.all { it.contextWindow.isNotEmpty() })
        assertTrue(rows.all { it.capabilities?.contains(ModelCapability.TOOLS) == true })
        assertTrue(rows.all { it.capabilities?.contains(ModelCapability.VISION) == true })
        assertEquals("grok-4.5", rows.first().id)
        assertEquals("Grok 4.5", rows.first().displayName)
        assertEquals("\$2.00/\$6.00", rows.first().price)
        assertEquals("500k", rows.first().contextWindow)
    }

    /** iOS c3fb76d: a row carries its `created` (the "newest" sort, and a saved list's New badge) and closed weights. */
    @Test fun rowsCarryCreatedAndClosedWeights() {
        val language = json.decodeFromString(XAIAdapter.LanguageModelsResponse.serializer(), LANGUAGE_JSON)
        val models = json.decodeFromString(XAIAdapter.ModelsResponse.serializer(), MODELS_JSON)
        val rows = (XAIAdapter.assemble(Result.success(language), Result.success(models)) as EndpointModelCatalog.ProbeResult.Connected).models
        assertTrue(rows.all { it.created != null })
        assertTrue(rows.all { it.openWeights == false })
    }

    @Test fun uncuratedIDsGetASynthesizedName() {
        assertEquals("Grok 5", XAIAdapter.displayName("grok-5"))
        assertEquals(
            "Grok 4.20 Non Reasoning",
            XAIAdapter.displayName("grok-4.20-0309-non-reasoning"),
        )
    }

    @Test fun rejectedKeyClassifiesAsUnauthorized() {
        val fail = Result.failure<XAIAdapter.LanguageModelsResponse>(
            XAIAdapter.ProbeFailure(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
        )
        val failM = Result.failure<XAIAdapter.ModelsResponse>(
            XAIAdapter.ProbeFailure(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
        )
        assertEquals(
            EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
            XAIAdapter.assemble(fail, failM),
        )
    }

    @Test fun fallsBackToModelsWhenLanguageModelsIsUnavailable() {
        val fail = Result.failure<XAIAdapter.LanguageModelsResponse>(
            XAIAdapter.ProbeFailure(EndpointModelCatalog.FailureKind.NO_MODELS_ENDPOINT),
        )
        val models = json.decodeFromString(XAIAdapter.ModelsResponse.serializer(), MODELS_JSON)
        val result = XAIAdapter.assemble(fail, Result.success(models))
        val rows = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(6, rows.size)
        assertFalse(rows.any { it.id.startsWith("grok-imagine") })
    }

    @Test fun handlesXaiHosts() {
        assertTrue(XAIAdapter.handles("api.x.ai"))
        assertTrue(XAIAdapter.handles("x.ai"))
        assertFalse(XAIAdapter.handles("phoenix.ai"))
    }

    companion object {
        // Reconstructed from the Swift test assertions, not teemoonTests/Fixtures.
        private const val LANGUAGE_JSON = """
        {"models":[
          {"id":"grok-4.5","created":200,"input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-0309-reasoning","created":190,"aliases":["grok-4.20"],
           "input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-0309-non-reasoning","created":180,
           "input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.3","created":170,
           "input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-multi-agent-0309","created":160,
           "input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-build-0.1","created":150,
           "input_modalities":["text","image"],
           "prompt_text_token_price":20000,"completion_text_token_price":60000}
        ]}
        """

        private const val MODELS_JSON = """
        {"data":[
          {"id":"grok-4.5","context_length":500000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-0309-reasoning","context_length":256000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-0309-non-reasoning","context_length":256000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.3","context_length":256000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-4.20-multi-agent-0309","context_length":256000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-build-0.1","context_length":128000,
           "prompt_text_token_price":20000,"completion_text_token_price":60000},
          {"id":"grok-imagine-image"},
          {"id":"grok-imagine-video"},
          {"id":"grok-imagine-image-pro"},
          {"id":"grok-imagine-video-pro"}
        ]}
        """
    }
}
