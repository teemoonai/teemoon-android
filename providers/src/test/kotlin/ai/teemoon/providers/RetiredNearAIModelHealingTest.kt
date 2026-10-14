// ports: teemoonTests/ProviderTests.swift @ v1.0.4 (the "ProviderStore heals retired near.ai models" suite, all 5 — over the
//        pure step; the store applies it in AppState.healRetiredNearAIModelsFromLive)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * near.ai retired GLM-5.2 on 2026-09-11 while it was the shipped default: every 1.0.3 user's near.ai setup pointed at
 * a dead host, and nothing moved it. Given what near.ai serves, a setup on a retired model moves to the preset default;
 * one on a served model is left alone.
 */
class RetiredNearAIModelHealingTest {
    private val served = listOf("z-ai/glm-5.3-flash", "Qwen/Qwen3.8-27B", "deepseek-ai/DeepSeek-V4-Flash")
    private fun near(model: String, equipped: List<String>? = null) = Provider.nearAI.copy(model = model, equippedModels = equipped)

    @Test fun aRetiredModelMovesToThePresetDefault() {
        val p = near("z-ai/glm-5.2", listOf("z-ai/glm-5.2", "Qwen/Qwen3.8-27B"))
        val (list, moved) = ProviderHealing.healRetiredNearAIModels(listOf(p), served)
        assertEquals(listOf(p.id), moved.map { it.id })
        assertEquals("z-ai/glm-5.3-flash", list.single().model)
        assertEquals(listOf("Qwen/Qwen3.8-27B"), list.single().equippedModels)
    }

    @Test fun aServedModelIsLeftAlone() {
        val p = near("Qwen/Qwen3.8-27B")
        val (list, moved) = ProviderHealing.healRetiredNearAIModels(listOf(p), served)
        assertTrue(moved.isEmpty()); assertEquals("Qwen/Qwen3.8-27B", list.single().model)
    }

    @Test fun nothingMovesWithoutALiveList() {
        val p = near("z-ai/glm-5.2")
        val (list, moved) = ProviderHealing.healRetiredNearAIModels(listOf(p), emptyList())
        assertTrue(moved.isEmpty()); assertEquals("z-ai/glm-5.2", list.single().model)
    }

    @Test fun otherProvidersAreNeverTouched() {
        val p = Provider.nvidia.copy(model = "z-ai/glm-5.2")   // not near.ai's problem
        assertTrue(ProviderHealing.healRetiredNearAIModels(listOf(p), served).second.isEmpty())
    }

    @Test fun whenTheDefaultIsGoneTooTheFirstServedModelWins() {
        val p = near("z-ai/glm-5.2")
        assertEquals("Qwen/Qwen3.8-27B", ProviderHealing.healRetiredNearAIModels(listOf(p), listOf("Qwen/Qwen3.8-27B")).first.single().model)
    }

    /** ProviderStore.nearAIProvider (a411f18): the active near.ai setup first, else the first near.ai in the list. */
    @Test fun nearAIProvider_prefersTheActiveSetup() {
        val a = near("z-ai/glm-5.3-flash").copy(id = "a"); val b = near("Qwen/Qwen3.8-27B").copy(id = "b")
        val other = Provider(id = "x", name = "home", endpoint = "http://192.168.1.5:11434/v1/chat/completions", model = "llama")
        assertEquals("b", ProviderHealing.nearAIProvider(listOf(other, a, b), activeId = "b")?.id)
        assertEquals("a", ProviderHealing.nearAIProvider(listOf(other, a, b), activeId = "x")?.id)
        assertEquals(null, ProviderHealing.nearAIProvider(listOf(other), activeId = "x"))
    }
}
