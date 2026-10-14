// ports: teemoonTests/OpenRouterModelCardTests.swift @ v1.0.4 (the five "who can serve it" cases). Payloads are
//        RECONSTRUCTED from the Swift assertions, not copied from Fixtures/openrouter_model_endpoints.json or
//        openrouter_zdr.json — no conformance kit class carries them. The first five cases of that suite (cost lines,
//        features, alias rows) are the catalogue half and live with OpenRouterAdapterTest.
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterOffersTest {
    private val endpoints = """
        {"data":{"endpoints":[
          {"model_id":"openai/gpt-5.4","provider_name":"OpenAI","tag":"openai/flex","quantization":"unknown",
           "context_length":1050000,"max_completion_tokens":128000,"pricing":{"prompt":"0.00000125","completion":"0.00001"},
           "uptime_last_1d":91.6,"latency_last_30m":{"p50":2085.5},"throughput_last_30m":{"p50":37.2},"supports_implicit_caching":true},
          {"model_id":"openai/gpt-5.4","provider_name":"Azure","tag":"azure","quantization":"unknown",
           "context_length":1050000,"pricing":{"prompt":"0.0000025","completion":"0.00002"},"uptime_last_1d":99.99},
          {"model_id":"openai/gpt-5.4","provider_name":"OpenAI","tag":"openai","quantization":"unknown",
           "context_length":1050000,"pricing":{"prompt":"0.0000025","completion":"0.00002"},"latency_last_30m":{"p50":null},"throughput_last_30m":null}
        ]}}
    """.trimIndent()
    private val zdr = """
        {"data":[{"model_id":"openai/gpt-5.4","tag":"azure/us","provider_name":"Azure"},
                 {"model_id":"openai/gpt-5.4","tag":"azure/eu","provider_name":"Azure"}]}
    """.trimIndent()
    private val json = Json { ignoreUnknownKeys = true }
    private fun offers() = json.decodeFromString(OpenRouterAdapter.OffersResponse.serializer(), endpoints).data.endpoints
    private fun zdrKeys() = json.decodeFromString(OpenRouterAdapter.ZeroDataRetentionResponse.serializer(), zdr).data
        .mapNotNull { r -> r.tag?.let { OpenRouterAdapter.zdrKey(r.modelID, it) } }.toSet()

    /** One provider can serve the same model twice under different tags, so the variant is part of the name and the identity. */
    @Test fun offersNameTheirVariant() {
        val built = OpenRouterAdapter.buildOffers(offers())
        assertEquals(listOf("OpenAI · flex", "Azure", "OpenAI"), built.map { it.title })
        assertEquals(built.size, built.map { it.id }.toSet().size)
    }

    /** Latency is milliseconds and throughput tokens per second — checked on iOS against a live keyed response. */
    @Test fun speedIsReadInTheUnitsOpenRouterSends() {
        val built = OpenRouterAdapter.buildOffers(offers())
        val flex = built.first()
        assertEquals(2085.5, flex.latencyMilliseconds!!, 0.0)
        assertEquals("2.1 s", flex.latencyLabel)
        assertEquals("37 tok/s", flex.throughputLabel)
        assertEquals("91.6%", flex.uptimeLabel)
        // The public list answers null for both, and a blank row says nothing.
        assertNull(built.last().latencyLabel)
        assertNull(built.last().throughputLabel)
    }

    /** "unknown" is how OpenRouter spells "no answer"; a row saying "quantization: unknown" is worse than no row. */
    @Test fun anUnknownQuantizationIsNotARow() {
        assertTrue(OpenRouterAdapter.buildOffers(offers()).all { it.quantization == null })
    }

    /** The badge follows the (model, tag) pair: Azure serves gpt-5.4, but only its azure/us and azure/eu offers are on the list. */
    @Test fun zeroDataRetentionMatchesTheModelAndTheTag() {
        val keys = zdrKeys()
        val azure = OpenRouterAdapter.buildOffers(offers(), keys).first { it.providerName == "Azure" }
        assertEquals("azure", azure.tag)
        assertFalse("gpt-5.4 is listed under azure/us and azure/eu, not under azure", azure.isZeroDataRetention)
        assertTrue(OpenRouterAdapter.zdrKey("openai/gpt-5.4", "azure/us") in keys)
        // And a listed pair does wear it.
        val listed = OpenRouterAdapter.OffersResponse.Endpoint(modelID = "openai/gpt-5.4", providerName = "Azure", tag = "azure/us")
        assertTrue(OpenRouterAdapter.buildOffers(listOf(listed), keys).single().isZeroDataRetention)
    }

    /**
     * A failed fetch must not be remembered: one blip would hide every badge for the rest of the process, which reads as
     * "no provider retains nothing" rather than "we could not ask".
     */
    @Test fun aFailedZeroDataRetentionFetchIsNotCached() = runTest {
        OpenRouterAdapter.resetZeroDataRetentionCache()
        var requests = 0
        var answer = 500 to "{}"
        val fetch: suspend (String, Map<String, String>, Long) -> Pair<Int, ByteArray> = { url, _, _ ->
            assertTrue(url.endsWith("/endpoints/zdr")); requests++; answer.first to answer.second.toByteArray()
        }
        val base = "https://openrouter.ai/api/v1"
        assertTrue(OpenRouterAdapter.zeroDataRetentionOffers(base, "", fetch = fetch).isEmpty())
        answer = 200 to """{"data":[{"model_id":"openai/gpt-5.4","tag":"azure/us","provider_name":"Azure"}]}"""
        assertTrue(OpenRouterAdapter.zdrKey("openai/gpt-5.4", "azure/us") in OpenRouterAdapter.zeroDataRetentionOffers(base, "", fetch = fetch))
        assertEquals("the failure was re-asked", 2, requests)
        OpenRouterAdapter.zeroDataRetentionOffers(base, "", fetch = fetch)
        assertEquals("the success was not", 2, requests)
        OpenRouterAdapter.resetZeroDataRetentionCache()
    }

    /** Android-only: the id's slash is a path separator. Escaped, it 404s. */
    @Test fun theOffersURLKeepsTheIdsSlash() {
        assertEquals("https://openrouter.ai/api/v1/models/openai/gpt-5.4/endpoints", OpenRouterAdapter.offersURL("openai/gpt-5.4", "https://openrouter.ai/api/v1/"))
        assertNull(OpenRouterAdapter.offersURL("no-slash", "https://openrouter.ai/api/v1"))
        assertNull(OpenRouterAdapter.offersURL("/half", "https://openrouter.ai/api/v1"))
    }

    /** Android-only: the card's loader exists for OpenRouter alone — an empty table elsewhere would imply a list. */
    @Test fun onlyOpenRouterOffersATable() {
        val fetch: suspend (String, Map<String, String>, Long) -> Pair<Int, ByteArray> = { _, _, _ -> 200 to ByteArray(0) }
        assertTrue(WhereProviderPresentation.offersLoader(Provider.openRouter, "", fetch) != null)
        assertNull(WhereProviderPresentation.offersLoader(Provider.nearAI, "", fetch))
        assertNull(WhereProviderPresentation.offersLoader(Provider.fireworks, "k", fetch))
    }
}
