// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the LMStudioAdapter suite)
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape taken from the Swift tests' assertions, not from teemoonTests/Fixtures
 * (fixtures may only cross via conformance/).
 */
class LMStudioAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun records(): List<LMStudioAdapter.ModelsResponse.Model> =
        json.decodeFromString(
            LMStudioAdapter.ModelsResponse.serializer(),
            FIXTURE,
        ).data

    @Test fun fixtureDecodesTheNativeFields() {
        val all = records()
        assertEquals(4, all.size)
        val gemma = all.first { it.id == "gemma-4-e4b-it-qat@q4_k_xl" }
        assertEquals("vlm", gemma.type)
        assertEquals("unsloth", gemma.publisher)
        assertEquals("gemma4", gemma.arch)
        assertEquals("Q4_K_XL", gemma.quantization)
        assertEquals(131_072, gemma.max_context_length)
        assertEquals(listOf("tool_use"), gemma.capabilities)
        assertEquals("not-loaded", gemma.state)
        assertFalse(gemma.isLoaded)
    }

    /** The embedding model ships in the same list; selecting it 400s on send. */
    @Test fun embeddingsAreExcludedAndChatModelsKept() {
        val models = LMStudioAdapter.buildModels(records())
        assertEquals(3, models.size)
        assertFalse(models.any { it.id.contains("embedding") })
    }

    @Test fun rowsCarryNameVendorContextAndQuant() {
        val models = LMStudioAdapter.buildModels(records())
        val qwen = models.first { it.id == "qwen2.5-7b-instruct" }
        assertEquals("qwen2.5-7b-instruct", qwen.displayName)
        // "bartowski" is the GGUF uploader, not the vendor.
        assertEquals("Qwen", qwen.vendor)
        assertEquals("32k · Q4_K_M", qwen.contextWindow)
        assertEquals("", qwen.price)

        val gemma = models.first { it.id == "gemma-4-e4b-it-qat@q4_k_xl" }
        assertEquals("gemma-4-e4b-it-qat", gemma.displayName)
        assertTrue(gemma.id.endsWith("@q4_k_xl"))
        assertEquals("Google", gemma.vendor)
        assertEquals("128k · Q4_K_XL", gemma.contextWindow)
    }

    @Test fun toolAndVisionCapabilitiesComeFromTheAPI() {
        val models = LMStudioAdapter.buildModels(records())
        val gemma = models.first { it.id == "gemma-4-e4b-it-qat@q4_k_xl" }
        assertEquals(setOf(ModelCapability.TOOLS, ModelCapability.VISION), gemma.capabilities)
        val qwen = models.first { it.id == "qwen2.5-7b-instruct" }
        assertEquals(setOf(ModelCapability.TOOLS), qwen.capabilities)
    }

    @Test fun loadedStateIsRead() {
        val all = records()
        assertTrue(all.none { it.isLoaded })
        val decoded = json.decodeFromString(
            LMStudioAdapter.ModelsResponse.serializer(),
            """{"data":[{"id":"m","state":"loaded","type":"llm"}]}""",
        )
        assertEquals(true, decoded.data.first().isLoaded)
    }

    /** A future LM Studio `type` must not silently empty the picker. */
    @Test fun unknownTypesAreKeptAsChatModels() {
        val decoded = json.decodeFromString(
            LMStudioAdapter.ModelsResponse.serializer(),
            """{"data":[{"id":"some-new-thing","type":"omni"},{"id":"m2"}]}""",
        )
        assertEquals(2, LMStudioAdapter.buildModels(decoded.data).size)
    }

    companion object {
        // Minimal payload that satisfies the suite's assertions. Not the iOS
        // fixture file — that may only cross via conformance/.
        private const val FIXTURE = """
        {
          "object": "list",
          "data": [
            {
              "id": "gemma-4-e4b-it-qat@q4_k_xl",
              "type": "vlm",
              "publisher": "unsloth",
              "arch": "gemma4",
              "quantization": "Q4_K_XL",
              "state": "not-loaded",
              "max_context_length": 131072,
              "capabilities": ["tool_use"]
            },
            {
              "id": "qwen2.5-7b-instruct",
              "type": "llm",
              "publisher": "bartowski",
              "quantization": "Q4_K_M",
              "state": "not-loaded",
              "max_context_length": 32768,
              "capabilities": ["tool_use"]
            },
            {
              "id": "llama-3-8b",
              "type": "llm",
              "state": "not-loaded"
            },
            {
              "id": "text-embedding-nomic",
              "type": "embeddings"
            }
          ]
        }
        """
    }
}
