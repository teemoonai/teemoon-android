// ports: teemoonTests/ModelDefaultRuleTests.swift @ v1.0.4
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ModelDefaultRuleTest {

    private fun km(id: String, price: String, ctx: String) =
        KnownModel(id = id, displayName = id, vendor = "v", price = price, contextWindow = ctx)

    private val catalogue = listOf(
        km("z-ai/glm-5.2", "$1.40/$4.40", "1M"),
        km("zai-org/GLM-5.1-FP8", "$1.40/$4.40", "203K"),
        km("Qwen/Qwen3.6-27B-FP8", "$0.33/$3.25", "262K"),
        km("anthropic/claude-opus-4-7", "$5.00/$25.00", "1M"),
        km("openai/gpt-5.5", "$5.00/$30.00", "1.1M"),
    )

    @Test fun priceScoreSumsInputAndOutput() {
        assertTrue(abs(ModelDefaultRule.priceScore(km("x", "$1.40/$4.40", "")) - 5.80) < 0.0001)
        assertEquals(30.0, ModelDefaultRule.priceScore(km("x", "$5.00/$25.00", "")), 0.0)
        assertEquals(0.0, ModelDefaultRule.priceScore(km("x", "", "")), 0.0)
    }

    @Test fun contextValueParsesUnits() {
        assertEquals(1_000_000.0, ModelDefaultRule.contextValue("1M"), 0.0)
        assertEquals(1_100_000.0, ModelDefaultRule.contextValue("1.1M"), 0.0)
        assertEquals(203_000.0, ModelDefaultRule.contextValue("203k"), 0.0)
        assertEquals(128_000.0, ModelDefaultRule.contextValue("128000"), 0.0)
        assertEquals(0.0, ModelDefaultRule.contextValue(""), 0.0)
    }

    @Test fun mostExpensiveWithoutE2EEFilterAllowsProxied() {
        assertEquals(
            "openai/gpt-5.5",
            ModelDefaultRule.MostExpensive(e2eeOnly = false).resolve(catalogue),
        )
    }

    @Test fun fixedReturnsItsIdRegardlessOfCatalogue() {
        assertEquals("brave", ModelDefaultRule.Fixed("brave").resolve(emptyList()))
        assertEquals("brave", ModelDefaultRule.Fixed("brave").resolve(catalogue))
        assertFalse(ModelDefaultRule.Fixed("brave").needsCatalogue)
    }

    @Test fun firstReturnsTheFirstModel() {
        assertEquals("z-ai/glm-5.2", ModelDefaultRule.First.resolve(catalogue))
        assertNull(ModelDefaultRule.First.resolve(emptyList()))
    }

    @Test fun curatedTakesFirstAvailableElseFallsBack() {
        assertEquals(
            "Qwen/Qwen3.6-27B-FP8",
            ModelDefaultRule.Curated(listOf("missing", "Qwen/Qwen3.6-27B-FP8")).resolve(catalogue),
        )
        assertEquals(
            "z-ai/glm-5.2",
            ModelDefaultRule.Curated(listOf("nope")).resolve(catalogue),
        )
    }

    // MARK: mostExpensive (near.ai's rule) — the e2eeOnly pool

    @Test fun mostExpensiveE2EEPicksFlagshipNotProxied() {
        NearAIModelCatalog.resetTierCache()  // exercise the id heuristic, not stale live tiers
        // Claude/GPT are pricier but proxied — must be excluded; GLM 5.2 is the top e2ee.
        assertEquals("z-ai/glm-5.2", ModelDefaultRule.MostExpensive(e2eeOnly = true).resolve(catalogue))
    }

    @Test fun mostExpensiveTieBreaksByContextIndependentOfOrder() {
        NearAIModelCatalog.resetTierCache()
        // GLM 5.1 first in the list, same price as 5.2 — the larger-context tie-break
        // must still choose 5.2 (this is the flicker bug).
        val reordered = listOf(
            km("zai-org/GLM-5.1-FP8", "$1.40/$4.40", "203K"),
            km("z-ai/glm-5.2", "$1.40/$4.40", "1M"),
        )
        assertEquals("z-ai/glm-5.2", ModelDefaultRule.MostExpensive(e2eeOnly = true).resolve(reordered))
    }

    @Test fun newestE2EEPicksFirstAttestableInList() {
        NearAIModelCatalog.resetTierCache()
        // The catalogue is recency-sorted, so "newest" = first in the e2ee pool.
        assertEquals("z-ai/glm-5.2", ModelDefaultRule.Newest(e2eeOnly = true).resolve(catalogue))
    }

    // MARK: - contextValue reads only the unit after the number (dfc528b)

    @Test fun contextValueIgnoresLettersThatAreNotTheUnit() {
        // A local row spells the quantisation after the size. The "m" in
        // "q4_k_m" is not a unit; reading it as one made 128k look like 128M
        // and a laptop model win every largest-context comparison.
        assertEquals(128_000.0, ModelDefaultRule.contextValue("128k · Q4_K_M"), 0.0)
        assertEquals(32_768.0, ModelDefaultRule.contextValue("32768 · Q8_0"), 0.0)
    }

    // MARK: - e2eeOnly is own-fleet only (daf3486)

    /**
     * Attested third-party (Chutes) hardware is attestable but has NO
     * confidential endpoint via near.ai, so an e2ee-only rule must never name
     * one — however dear it is. Live on 2026-09-14 that was kimi-k3 at $19.80.
     */
    @Test fun e2eeOnlyExcludesAttestedThirdParty() {
        val models = listOf(
            km("moonshotai/kimi-k3", "$3.30/$16.50", "1M"),      // attested 3p, 19.80
            km("z-ai/glm-5.3-flash", "$0.15/$0.50", "1M"),       // own fleet, 0.65
        )
        assertEquals("z-ai/glm-5.3-flash", ModelDefaultRule.MostExpensive(e2eeOnly = true).resolve(models))
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY,
            NearAIModelCatalog.confidentiality("moonshotai/kimi-k3"))
        // Without the filter the dearest model still wins.
        assertEquals("moonshotai/kimi-k3", ModelDefaultRule.MostExpensive(e2eeOnly = false).resolve(models))
    }

    // MARK: - strongestOpenWeight (2d76b10)

    private fun open(id: String, price: String, isNew: Boolean = false) =
        km(id, price, "").copy(openWeights = true, isNew = isNew)
    private fun closed(id: String, price: String) = km(id, price, "").copy(openWeights = false)

    @Test fun parameterCountReadsTotalsFromIds() {
        assertEquals(27.0, ModelDefaultRule.parameterCount("qwen/qwen3.8-27b"))
        // The TOTAL, not the active count: "120b-a12b" is 120.
        assertEquals(120.0, ModelDefaultRule.parameterCount("nvidia/nemotron-3-super-120b-a12b"))
        assertEquals(550.0, ModelDefaultRule.parameterCount("nvidia/nemotron-3-ultra-550b-a55b"))
        assertEquals(2400.0, ModelDefaultRule.parameterCount("qwen/qwen3.8-2.4t-a95b"))
        assertNull(ModelDefaultRule.parameterCount("z-ai/glm-5.3-flash"))
        assertNull(ModelDefaultRule.parameterCount("x-ai/grok-4.6"))
        // "FP8" is a precision, not a size — the b/t must not be followed by more.
        assertNull(ModelDefaultRule.parameterCount("zai-org/GLM-5.1-FP8"))
    }

    @Test fun strongestOpenWeightIgnoresClosedModelsHoweverExpensive() {
        val picked = ModelDefaultRule.StrongestOpenWeight.resolve(listOf(
            closed("anthropic/claude-opus-5", "$5.00/$25.00"),
            open("deepseek/deepseek-v4-pro", "$1.60/$3.20"),
            open("moonshotai/kimi-k3", "$2.65/$13.28"),
            km("qwen/qwen3.8-max", "$2.00/$6.00", ""),        // unknown, not open
        ))
        assertEquals("moonshotai/kimi-k3", picked)
    }

    @Test fun strongestOpenWeightOnAFreeHostFallsToParameterCount() {
        val picked = ModelDefaultRule.StrongestOpenWeight.resolve(listOf(
            open("nvidia/nemotron-3-super-120b-a12b", ""),
            open("moonshotai/kimi-k3", ""),
            open("nvidia/nemotron-3-ultra-550b-a55b", ""),
        ))
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", picked)
    }

    @Test fun strongestOpenWeightBreaksAFullTieByRecency() {
        val picked = ModelDefaultRule.StrongestOpenWeight.resolve(listOf(
            open("qwen/qwen3.6-27b", "$0.30/$2.00"),
            open("qwen/qwen3.7-27b", "$0.30/$2.00", isNew = true),
        ))
        assertEquals("qwen/qwen3.7-27b", picked)
    }

    @Test fun strongestOpenWeightWithNothingOpenTakesTheFirstModel() {
        assertEquals("a", ModelDefaultRule.StrongestOpenWeight.resolve(listOf(closed("a", "$1/$1"), closed("b", "$9/$9"))))
        assertNull(ModelDefaultRule.StrongestOpenWeight.resolve(emptyList()))
    }
}
