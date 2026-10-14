// ports: teemoonTests/ProviderTests.swift @ v1.0.4 (the suites not already in ProviderTest/ProviderHealingTest/
//        NearAIModelCatalogTest: sameEndpoint, openAIBaseURL, isValid, console recovery, codable, equatable, deprecation),
//        teemoonTests/teemoonTests.swift @ v1.0.4 (the two endpoint-scheme tests; splitEndpoint is EndpointScheme.split)
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.net.URI
import kotlinx.serialization.builtins.ListSerializer

class ProviderValidityTest {
    private fun p(name: String = "test", endpoint: String, model: String = "gpt-4", id: String = "id-1") = Provider(id = id, name = name, endpoint = endpoint, model = model)

    @Test fun anEndpointMatchesItsPresetHoweverItIsSpelled() {
        val preset = Provider.grok
        fun saved(endpoint: String) = preset.copy(id = "fresh", endpoint = endpoint)
        assertTrue(saved("https://api.x.ai/v1/chat/completions").sameEndpoint(preset))
        assertTrue(saved("https://api.x.ai/v1").sameEndpoint(preset))
        assertTrue(saved("https://api.x.ai/v1/").sameEndpoint(preset))
        assertTrue(saved("  https://API.X.AI/v1/chat/completions  ").sameEndpoint(preset))
        assertFalse(saved("https://api.x.ai/v2").sameEndpoint(preset))
        assertFalse(saved("https://api.openai.com/v1").sameEndpoint(preset))
        assertFalse(Provider.nearAI.sameEndpoint(preset))
    }
    @Test fun openAIBaseURL_stripsTrailingChatCompletions() { assertEquals("https://api.example.com/v1", p(endpoint = "https://api.example.com/v1/chat/completions").openAIBaseUrl) }
    @Test fun openAIBaseURL_preservesEndpointWithoutChatCompletions() { assertEquals("https://api.example.com/v1", p(endpoint = "https://api.example.com/v1").openAIBaseUrl) }
    @Test fun openAIBaseURL_nullForEmptyEndpoint() { assertNull(p(endpoint = "").openAIBaseUrl) }
    @Test fun openAIBaseURL_schemelessFails() {
        assertNull(p(endpoint = "192.168.1.1:8080/v1").openAIBaseUrl)
        assertNull(p(endpoint = "192.168.88.7:1234/v1").openAIBaseUrl)
    }
    @Test fun openAIBaseURL_httpScheme() {
        val base = p(endpoint = "http://192.168.88.7:1234/v1").openAIBaseUrl
        assertNotNull(base); assertTrue(base!!.startsWith("http://")); assertEquals("192.168.88.7", URI(base).host)
    }
    @Test fun isValid_trueForProperProvider() { assertTrue(p("OpenAI", "https://api.openai.com/v1").isValid) }
    @Test fun isValid_falseForEmptyName() { assertFalse(p("", "https://api.openai.com/v1").isValid) }
    @Test fun isValid_falseForWhitespaceOnlyName() { assertFalse(p("   ", "https://api.openai.com/v1").isValid) }
    @Test fun isValid_falseForEmptyEndpoint() { assertFalse(p(endpoint = "").isValid) }
    @Test fun isValid_falseForEmptyModel() { assertFalse(p(endpoint = "https://api.openai.com/v1", model = "").isValid) }

    @Test fun splitEndpoint_https() { assertEquals(EndpointScheme.HTTPS to "api.openai.com/v1", EndpointScheme.split("https://api.openai.com/v1")) }
    @Test fun splitEndpoint_http() { assertEquals(EndpointScheme.HTTP to "192.168.1.1:8080/v1", EndpointScheme.split("http://192.168.1.1:8080/v1")) }
    @Test fun splitEndpoint_noScheme_defaultsToHttps() { assertEquals(EndpointScheme.HTTPS to "192.168.1.1:8080/v1", EndpointScheme.split("192.168.1.1:8080/v1")) }

    @Test fun consoleURL_matchesPresetEndpoint() {
        val saved = Provider(id = "saved", name = "near.ai glm 5.2", endpoint = Provider.nearAI.endpoint, model = "z-ai/glm-5.2")
        assertEquals(Provider.nearAI.signupURL, saved.consoleURL); assertEquals("near.ai", saved.consoleDisplayName)
    }
    @Test fun consoleURL_nullForCustomHost() { assertNull(p("lab", "http://192.168.1.9:11434/v1", "llama").consoleURL) }

    private fun recovery(host: String, label: String = "renamed", brave: Boolean = false) =
        ProviderPresets.consoleRecovery(isBraveGrounding = brave, errorHost = host, sourceLabel = label, activeProvider = null)

    @Test fun consoleRecovery_byRequestHost() {
        val r = recovery("cloud-api.near.ai", "my near label")
        assertEquals("near.ai", r?.second); assertEquals(Provider.nearAI.signupURL, r?.first)
    }
    @Test fun consoleRecovery_directNearAICompletionHost() {
        val r = recovery("glm-5-2.completions.near.ai", "near.ai glm 5.2")
        assertEquals("near.ai", r?.second); assertNotNull(r?.first)
    }
    @Test fun consoleRecovery_braveGrounding() {
        val r = recovery("api.search.brave.com", brave = true)
        assertEquals("Brave Answers", r?.second); assertEquals(Provider.braveAnswers.signupURL, r?.first)
    }
    @Test fun consoleRecovery_nullForHostContainingPresetDomain() {
        for (host in listOf("llm.phoenix.ai", "matrix.ai", "notbrave.com", "myfireworks.ai")) assertNull("$host is not a preset vendor", recovery(host, "self-hosted"))
    }
    @Test fun consoleRecovery_matchesVendorSubdomains() {
        for ((host, expected) in listOf("glm-5-2.completions.near.ai" to "near.ai", "cloud-api.near.ai" to "near.ai", "app.fireworks.ai" to "Fireworks"))
            assertEquals("$host should resolve to $expected", expected, recovery(host)?.second)
    }
    @Test fun consoleRecovery_nullForUnknownHost() { assertNull(recovery("192.168.1.9", "lab")) }

    @Test fun capabilities_trueForNearAI() { assertTrue(ProviderCapability.ATTESTATION in p("near.ai", "https://cloud-api.near.ai/v1", "test").capabilities) }
    @Test fun capabilities_falseForOtherEndpoints() { assertFalse(ProviderCapability.ATTESTATION in p("OpenAI", "https://api.openai.com/v1").capabilities) }
    @Test fun capabilities_reflectStoredFlags() {
        var provider = p(endpoint = "https://api.example.com/v1", model = "m").copy(supportsModelBrowsing = true, hasBuiltInGrounding = true)
        assertTrue(provider.capabilities.containsAll(setOf(ProviderCapability.MODEL_BROWSING, ProviderCapability.BUILT_IN_GROUNDING)))
        provider = provider.copy(supportsModelBrowsing = false, hasBuiltInGrounding = false)
        assertTrue(provider.capabilities.none { it == ProviderCapability.MODEL_BROWSING || it == ProviderCapability.BUILT_IN_GROUNDING })
    }

    @Test fun codable_roundtrip() {
        val original = Provider(id = "id", name = "Test Provider", endpoint = "https://api.test.com/v1", model = "gpt-4", authHeaderName = "X-Custom-Auth",
            requiresAPIKey = true, extraParams = mapOf("temperature" to "0.7"), maxMessages = 10, hasBuiltInGrounding = true, omitSystemPrompt = false)
        val decoded = Json.decodeFromString(Provider.serializer(), Json.encodeToString(Provider.serializer(), original))
        assertEquals(original, decoded)
    }
    @Test fun codable_multipleProviders_roundtrip() {
        val providers = listOf(p("A", "https://a.com/v1", "m1", "1"), p("B", "https://b.com/v1", "m2", "2").copy(authHeaderName = "X-Key"))
        val ser = ListSerializer(Provider.serializer())
        val decoded = Json.decodeFromString(ser, Json.encodeToString(ser, providers))
        assertEquals(2, decoded.size); assertEquals("A", decoded[0].name); assertEquals("X-Key", decoded[1].authHeaderName)
    }
    @Test fun equatable_sameID_areEqual() { assertEquals(p("A", "https://a.com", "m1", "x"), p("A", "https://a.com", "m1", "x")) }
    @Test fun equatable_differentID_areNotEqual() { assertNotEquals(p("A", "https://a.com", "m1", "x"), p("A", "https://a.com", "m1", "y")) }

    @Test fun deprecationDate_parsesBothShapesFireworksSends() {
        val plain = KnownModel.deprecationDate("2026-12-01"); val iso = KnownModel.deprecationDate("2026-12-01T00:00:00Z")
        assertNotNull(plain); assertNotNull(iso); assertEquals(plain, iso)   // same instant, both spellings
    }
    @Test fun deprecationDate_isNullForNothingAndForGarbage() {
        for (raw in listOf(null, "", "   ", "Q4 2026")) assertNull(KnownModel.deprecationDate(raw))
    }
    @Test fun deprecationDate_isLocaleIndependent() {
        val parts = KnownModel.deprecationDate("2026-12-01")!!.atZone(ZoneOffset.UTC)
        assertEquals(2026, parts.year); assertEquals(12, parts.monthValue); assertEquals(1, parts.dayOfMonth)
    }
    @Test fun deprecationLabel_readsAsRetiredOnlyOncePast() {
        val now = Instant.parse("2026-09-03T00:00:00Z")
        val past = KnownModel("m", "M", "V", "", deprecationDate = now.minusSeconds(86_400))
        assertEquals("retired", past.deprecationLabel(now)); assertTrue(past.isRetiring)
        val future = KnownModel("m", "M", "V", "", deprecationDate = now.plusSeconds(86_400 * 120))
        assertEquals("retiring in 4 months", future.deprecationLabel(now))
        val silent = KnownModel("m", "M", "V", "")
        assertNull(silent.deprecationLabel(now)); assertFalse(silent.isRetiring)
    }
}
