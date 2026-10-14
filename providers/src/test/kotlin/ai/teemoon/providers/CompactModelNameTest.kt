// ports: teemoonTests/CompactModelNameTests.swift @ v1.0.1
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ModelCatalog.compactName` is what the chat title bar shows, and the title
 * bar has no room to be wrong: it must drop how the model was *built* while
 * keeping which model it *is*.
 */
class CompactModelNameTest {

    @Test fun dropsNamespaceAndPrecision() {
        assertEquals("glm-5.1", ModelCatalog.compactName("zai-org/GLM-5.1-FP8"))
        assertEquals("qwen3.6-27b", ModelCatalog.compactName("Qwen/Qwen3.6-27B-FP8"))
        assertEquals("glm-5.2", ModelCatalog.compactName("z-ai/glm-5.2"))
    }

    @Test fun dropsStackedBuildSuffixes() {
        assertEquals(
            "gemma-4-e4b",
            ModelCatalog.compactName("unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"),
        )
    }

    @Test fun peelsBuildWordsOutOfTheTag() {
        assertEquals("gemma4:e2b", ModelCatalog.compactName("gemma4:e2b-it-qat"))
        assertEquals("qwen3.5:4b", ModelCatalog.compactName("qwen3.5:4b-instruct-fp16"))
        assertEquals("mymodel", ModelCatalog.compactName("mymodel:it-qat-gguf"))
    }

    @Test fun keepsSizeTagDropsQuantTag() {
        assertEquals("gemma4:e2b", ModelCatalog.compactName("gemma4:e2b"))
        assertEquals("qwen3.5:4b", ModelCatalog.compactName("qwen3.5:4b"))
        assertEquals("llama4:latest", ModelCatalog.compactName("llama4:latest"))
        assertEquals("mymodel", ModelCatalog.compactName("mymodel:Q8_0"))
        assertEquals("mymodel", ModelCatalog.compactName("mymodel:IQ3_M"))
    }

    @Test fun stripsWeightsFilenameCruft() {
        assertEquals("ternary-bonsai-8b", ModelCatalog.compactName("ternary-bonsai-8b-q2_0.gguf"))
        assertEquals("meta-llama-4-8b", ModelCatalog.compactName("/models/Meta-Llama-4-8B-Q4_K_M.gguf"))
        assertEquals("mistral-7b", ModelCatalog.compactName("mistral-7b-iq3_m.gguf"))
    }

    @Test fun keepsSizeWords() {
        assertEquals("ternary-bonsai-8b", ModelCatalog.compactName("ternary-bonsai-8b"))
        assertEquals("gemma-4-e4b", ModelCatalog.compactName("gemma-4-e4b-gguf"))
    }

    @Test fun neverReturnsEmpty() {
        assertEquals("gguf", ModelCatalog.compactName("GGUF"))
        assertEquals("-fp8", ModelCatalog.compactName("vendor/-fp8"))
        assertFalse(ModelCatalog.compactName("org/model-it-qat-gguf").isEmpty())
    }

    @Test fun placeholderModelFallsBackToProviderName() {
        assertEquals("brave answers", ModelCatalog.titleLabel("brave", "Brave Answers"))
    }

    @Test fun selfHostedLabelDoesNotSwallowTheModel() {
        assertEquals(
            "ternary-bonsai-8b",
            ModelCatalog.titleLabel("ternary-bonsai-8b-q2_0.gguf", "ringzero ternary-bonsai-8b-q2_0.gguf"),
        )
        assertEquals("gemma4:e2b", ModelCatalog.titleLabel("gemma4:e2b", "ringzero gemma4:e2b"))
    }

    @Test fun ordinaryProviderKeepsTheModel() {
        assertEquals("glm-5.1", ModelCatalog.titleLabel("zai-org/GLM-5.1-FP8", "near.ai"))
        assertNull(ModelCatalog.titleLabel("", "near.ai"))
    }

    @Test fun leavesOrdinaryNamesAlone() {
        assertEquals("gpt-5", ModelCatalog.compactName("gpt-5"))
        assertEquals("claude-opus-5", ModelCatalog.compactName("claude-opus-5"))
    }
}
