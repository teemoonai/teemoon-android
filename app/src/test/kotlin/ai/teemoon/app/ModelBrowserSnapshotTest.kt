// Paparazzi baselines: the browser by tier with the selected check, flat by price, the failed load with its retry, and a model detail.
package ai.teemoon.app

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.ModelBrowserDoor
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.ModelCapability
import ai.teemoon.providers.ModelOffer
import ai.teemoon.providers.NearAIModelCatalog
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModelBrowserSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 900, density = com.android.resources.Density.MEDIUM))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    private val rows = listOf(
        KnownModel("zai-org/GLM-5.1-FP8", "GLM 5.1", "z-ai", "\$1.40/\$4.40", contextWindow = "1M", isNew = true),
        KnownModel("deepseek-ai/DeepSeek-V4-Flash", "DeepSeek V4 Flash", "DeepSeek", "\$0.17/\$0.35", contextWindow = "1M"),
        KnownModel("anthropic/claude-opus-4-6", "Claude Opus 4.6", "Anthropic", "\$15/\$75", contextWindow = "200K"),
    )
    /** near.ai at rest: trust is the structure — own fleet, third-party hardware, proxied — and the setup's model wears the check. */
    @Test fun browser_nearAI_byTier() {
        paparazzi.snapshot { TeemoonTheme { Surface { ModelBrowserScreen(ModelBrowserDoor(models = rows, showsConfidentialityTags = true), onSelect = {}, onCancel = {}, selectedModel = "zai-org/GLM-5.1-FP8") } } }
    }
    /** Any sort but recommended dissolves the sections: one list, cheapest first ACROSS vendors, each row naming its vendor and tier. */
    @Test fun browser_nearAI_flatByPrice() {
        paparazzi.snapshot { TeemoonTheme { Surface { ModelBrowserScreen(ModelBrowserDoor(models = rows, showsConfidentialityTags = true), onSelect = {}, onCancel = {},
            initialQuery = ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH)) } } }
    }
    /** Nothing saved and the fetch failed: say so, and offer the retry (b2aeee9) — not an empty list. */
    @Test fun browser_loadFailed_offersRetry() {
        paparazzi.snapshot { TeemoonTheme { Surface { ModelBrowserScreen(ModelBrowserDoor(liveLoader = { null }), onSelect = {}, onCancel = {}) } } }
    }
    /** OpenRouter's card: who can serve the model, with the zero-data-retention badge and only the fields the list reported. */
    @Test fun detail_openRouter_providers() {
        // ModelDetailView's preview (iOS 835d7c8): what else it costs, the reasoning efforts, the knowledge cutoff.
        val m = KnownModel("openai/gpt-5.4", "GPT-5.4", "OpenAI", "\$2.50/\$20.00", contextWindow = "1M",
            extraCosts = listOf(KnownModel.ExtraCost("cache read", perMillion = 0.25), KnownModel.ExtraCost("web search", perCall = 0.01),
                KnownModel.ExtraCost("past 272k", perMillion = 5.0, perMillionOutput = 22.5)),
            reasoningEfforts = listOf("xhigh", "high", "medium", "low", "none"), knowledgeCutoff = "2025-09-30")
        val offers = listOf(
            ModelOffer("OpenAI", "openai/flex", price = "\$1.25/\$10.00", contextTokens = 1_050_000, uptimePercent = 91.6,
                latencyMilliseconds = 2085.5, throughputTokensPerSecond = 37.2, supportsImplicitCaching = true),
            ModelOffer("Azure", "azure/us", price = "\$2.50/\$20.00", contextTokens = 1_050_000, uptimePercent = 99.99, isZeroDataRetention = true),
        )
        paparazzi.snapshot { TeemoonTheme { Surface { ModelDetailScreen(m, confidentiality = null, onCopyID = {}, onBack = {}, offersLoader = { offers }) } } }
    }
    /** NVIDIA's free tier (iOS b7edacb, 96d801e): no per-token price, and the card says "free", not "no published price". */
    @Test fun detail_nvidiaFreeTier() {
        val m = KnownModel("nvidia/nemotron-3-super-120b-a12b", "nemotron-3-super-120b-a12b", "NVIDIA", "",
            modelPageURL = "https://build.nvidia.com/nvidia/nemotron-3-super-120b-a12b", isFree = true)
        paparazzi.snapshot { TeemoonTheme { Surface { ModelDetailScreen(m, confidentiality = null, onCopyID = {}, onBack = {}) } } }
    }
    @Test fun detail_deepseek() {
        val m = KnownModel("deepseek-ai/DeepSeek-V4-Flash", "DeepSeek V4 Flash", "DeepSeek", "\$0.17/\$0.35", contextWindow = "1M", capabilities = setOf(ModelCapability.TOOLS),
            summary = "Large mixture-of-experts model, FP8-quantized, served in a TDX-confidential inference CVM.", features = listOf("tools", "reasoning", "json_mode"),
            samplingParameters = listOf("temperature", "top_p", "max_tokens", "seed"), maxOutputTokens = 32768, inputModalities = listOf("text"), huggingFaceURL = "https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash")
        paparazzi.snapshot { TeemoonTheme { Surface { ModelDetailScreen(m, confidentiality = "end-to-end encrypted", onCopyID = {}, onBack = {}) } } }
    }
}
