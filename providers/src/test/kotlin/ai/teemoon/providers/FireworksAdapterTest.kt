// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the FireworksAdapter suite)
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FireworksAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun records(): List<FireworksAdapter.ModelsResponse.Model> =
        json.decodeFromString(FireworksAdapter.ModelsResponse.serializer(), FIXTURE).models

    @Test fun controlPlaneBaseDropsTheInferencePrefix() {
        assertEquals(
            "https://api.fireworks.ai/v1/accounts/fireworks/models",
            FireworksAdapter.controlPlaneBase("https://api.fireworks.ai/inference/v1"),
        )
    }

    @Test fun fixtureDecodesTheFieldsAPickerRowNeeds() {
        val all = records()
        assertEquals(14, all.size)
        val kimi = all.first { it.name == "accounts/fireworks/models/kimi-k2p6" }
        assertEquals(262_144, kimi.contextLength)
        assertEquals(true, kimi.supportsTools)
        assertEquals(true, kimi.supportsImageInput)
        assertEquals("READY", kimi.state)
        assertNull(kimi.deprecationDate)
        assertEquals("1028542417904", kimi.baseModelDetails?.parameterCount)
    }

    /**
     * iOS 2d76b10 + c3fb76d: every row is open-weight and carries its created date. Without openWeights the preset's
     * StrongestOpenWeight default had no open rows to rank and the open-weights filter hid all of Fireworks.
     */
    @Test fun rowsAreOpenWeightAndDated() {
        val models = FireworksAdapter.buildModels(records())
        assertTrue(models.all { it.openWeights == true })
        val pick = ModelDefaultRule.StrongestOpenWeight.resolve(models)
        assertTrue("the rule ranks real open rows", pick != null && models.first { it.id == pick }.openWeights == true)
    }

    @Test fun nonChatKindsAreExcludedAndCustomModelsKept() {
        val models = FireworksAdapter.buildModels(records())
        val ids = models.map { it.id }
        assertFalse(ids.any { it.contains("embedding") || it.contains("reranker") })
        assertTrue(ids.contains("accounts/fireworks/models/qwen3p7-plus"))
        assertEquals(12, models.size)
    }

    @Test fun rowsAreCompleteNamedAndVendored() {
        val models = FireworksAdapter.buildModels(records())
        for (missing in listOf(
            "accounts/fireworks/models/deepseek-v4-flash",
            "accounts/fireworks/models/gpt-oss-20b",
            "accounts/fireworks/models/kimi-k2p7-code",
            "accounts/fireworks/models/minimax-m2p7",
            "accounts/fireworks/models/minimax-m3",
            "accounts/fireworks/models/nemotron-3-ultra-nvfp4",
        )) {
            assertTrue("$missing missing", models.any { it.id == missing })
        }
        assertFalse(models.any { it.vendor == "Accounts" })
        assertTrue(models.all { !it.displayName.contains("accounts/") })
        val kimi = models.first { it.id == "accounts/fireworks/models/kimi-k2p6" }
        assertEquals("Kimi K2.6", kimi.displayName)
        assertEquals("Moonshot", kimi.vendor)
        assertEquals("262k", kimi.contextWindow)
        assertEquals("\$0.95/\$4.00", kimi.price)
    }

    @Test fun toolAndVisionCapabilitiesComeFromTheAPI() {
        val models = FireworksAdapter.buildModels(records())
        val noTools = models.first { it.id == "accounts/fireworks/models/gpt-oss-20b" }
        assertEquals(emptySet<ModelCapability>(), noTools.capabilities)
        val p = Provider(
            id = "1", name = "fw",
            endpoint = "https://api.fireworks.ai/inference/v1",
            model = noTools.id,
            modelCapabilities = ModelCapabilities.fromCapabilitySet(noTools.capabilities),
        )
        assertFalse(p.modelSupportsTools)
        val vision = models.first { it.id == "accounts/fireworks/models/kimi-k2p7-code" }
        assertEquals(setOf(ModelCapability.TOOLS, ModelCapability.VISION), vision.capabilities)
    }

    /** The request the adapter actually issues: control-plane path, serverless filter, bearer key. */
    @Test fun listModelsQueriesTheControlPlaneWithTheServerlessFilter() = runTest {
        var url = ""
        var headers = emptyMap<String, String>()
        var timeout = 0L
        val result = FireworksAdapter.listModels(
            baseUrl = "https://api.fireworks.ai/inference/v1",
            apiKey = "fw-key",
            fetch = { u, h, t ->
                url = u
                headers = h
                timeout = t
                200 to FIXTURE.toByteArray()
            },
        )
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(12, models.size)
        assertTrue(url.contains("/v1/accounts/fireworks/models"))
        assertFalse(url.contains("/inference/"))
        assertTrue(url.contains("supports_serverless=true") || url.contains("supports_serverless%3Dtrue"))
        assertTrue(url.contains("pageSize=200"))
        assertEquals("Bearer fw-key", headers["Authorization"])
        assertEquals(FireworksAdapter.LIST_TIMEOUT_MS, timeout)
    }

    /** A key that can't read the control plane still gets a usable picker. */
    @Test fun fallsBackToTheGenericProbeWhenTheControlPlaneRefuses() = runTest {
        val result = FireworksAdapter.listModels(
            baseUrl = "https://api.fireworks.ai/inference/v1",
            apiKey = "fw-key",
            fetch = { url, _, _ ->
                when {
                    url.contains("/v1/accounts/fireworks/models") -> 403 to "{}".toByteArray()
                    url.contains("/inference/v1/models") ->
                        200 to """{"data":[{"id":"accounts/fireworks/models/kimi-k2p6","object":"model"}]}""".toByteArray()
                    else -> error("unexpected $url")
                }
            },
        )
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(listOf("accounts/fireworks/models/kimi-k2p6"), models.map { it.id })
    }

    /** Android-only: a nextPageToken is followed, then an empty token stops. */
    @Test fun listModelsFollowsNextPageTokenAndStops() = runTest {
        val urls = ArrayList<String>()
        val page1 = """{"models":[${chat("kimi-k2p6", "Kimi K2.6")}],"nextPageToken":"abc"}"""
        val page2 = """{"models":[${chat("glm-5p2", "GLM 5.2")}],"nextPageToken":""}"""
        val result = FireworksAdapter.listModels(
            baseUrl = "https://api.fireworks.ai/inference/v1",
            apiKey = "k",
            fetch = { url, _, _ ->
                urls.add(url)
                if ("pageToken=abc" in url) 200 to page2.toByteArray()
                else 200 to page1.toByteArray()
            },
        )
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(2, urls.size)
        assertTrue(urls[1].contains("pageToken=abc"))
        assertEquals(
            listOf("accounts/fireworks/models/kimi-k2p6", "accounts/fireworks/models/glm-5p2"),
            models.map { it.id },
        )
    }

    companion object {
        private fun chat(
            slug: String,
            display: String? = null,
            tools: Boolean = true,
            image: Boolean = false,
            context: Int = 8192,
            kind: String = "HF_BASE_MODEL",
            extra: String = "",
        ) = """
            {"name":"accounts/fireworks/models/$slug","displayName":${display?.let { "\"$it\"" } ?: "null"},
             "state":"READY","kind":"$kind","supportsServerless":true,
             "supportsTools":$tools,"supportsImageInput":$image,"contextLength":$context
             $extra}
        """.trimIndent()

        private val FIXTURE = """
        {"models":[
          ${chat("kimi-k2p6", "Kimi K2.6", tools = true, image = true, context = 262144,
            extra = """, "baseModelDetails":{"parameterCount":"1028542417904"}""")},
          ${chat("qwen3p7-plus", "Qwen3.7 Plus", kind = "CUSTOM_MODEL")},
          ${chat("deepseek-v4-flash", "DeepSeek V4 Flash")},
          ${chat("gpt-oss-20b", "OpenAI gpt-oss-20b", tools = false)},
          ${chat("kimi-k2p7-code", "Kimi K2.7 Code", tools = true, image = true)},
          ${chat("minimax-m2p7", "MiniMax M2.7")},
          ${chat("minimax-m3", "MiniMax M3")},
          ${chat("nemotron-3-ultra-nvfp4", "NVIDIA Nemotron 3 Ultra NVFP4")},
          ${chat("glm-5p1", "GLM 5.1")},
          ${chat("glm-5p2", "GLM 5.2")},
          ${chat("kimi-k3", "Kimi K3")},
          ${chat("gpt-oss-120b", "gpt oss 120b")},
          {"name":"accounts/fireworks/models/nomic-embed","kind":"EMBEDDING_MODEL",
           "state":"READY","supportsServerless":true},
          {"name":"accounts/fireworks/models/qwen-reranker","kind":"HF_BASE_MODEL",
           "state":"READY","supportsServerless":true}
        ]}
        """
    }
}
