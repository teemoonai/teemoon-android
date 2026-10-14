// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.1 (the preset facts and helpers that Provider.kt's
//        preset table lacks: signup URLs, descriptions, default-model rules, key-gated hosts, console recovery;
//        the KnownModel tables are KnownModelNearAI.kt). The four presets themselves are Provider.presets.
// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (open-weight default rules for fireworks/openrouter/nvidia,
//        defaultModelRule through matchingPreset, nvidia's 40-per-minute caption). keyValidationEndpoint's OpenRouter
//        case @ v1.0.4.
package ai.teemoon.providers

import java.net.URI

/** Preset facts keyed by the preset's fixed id — kept off the Provider data class so a saved record never carries them. */
object ProviderPresets {
    private val signupURLs = mapOf(
        Provider.nearAI.id to "https://cloud.near.ai/signin",
        Provider.braveAnswers.id to "https://api-dashboard.search.brave.com/app/keys",
        Provider.grok.id to "https://console.x.ai/",
        Provider.fireworks.id to "https://fireworks.ai/api-keys",
        Provider.openRouter.id to "https://openrouter.ai/settings/keys",
        Provider.nvidia.id to "https://build.nvidia.com/settings/api-keys",
    )
    private val descriptions = mapOf(
        Provider.nearAI.id to "open models running inside attested enclaves encrypted to the llm model, so the operator can't read your chats. teemoon checks the proof on the device.",
        Provider.braveAnswers.id to "single answers grounded in live web search, with citations. has a different key than brave llm grounding api.",
        Provider.grok.id to "xai's models, with a 2m token context window, reasoning modes, and live web and x knowledge.",
        Provider.fireworks.id to "fast, low-cost inference for open models. large catalog — kimi, deepseek, qwen, glm, and more.",
        Provider.openRouter.id to "one key for every vendor's models — claude, gpt, gemini, deepseek and hundreds more, each with its price and context window.",
        // "free" is a fact about the tier, not a promise: the hosted catalogue is
        // free for development under the NVIDIA Developer Program and rate-limited
        // per model. There is no per-token price to show.
        Provider.nvidia.id to "nvidia's hosted catalog — nemotron, kimi, deepseek, gemma and more. free, rate limited to 40 requests per minute.",
    )

    /** Hosts whose `/models` answers 401 without a key (measured), so the first probe is skipped rather than shown failing. */
    val modelListRequiresKeyHosts: Set<String> = setOf("api.x.ai", "api.fireworks.ai")

    val defaultModelRules: Map<String, ModelDefaultRule> = mapOf(
        Provider.nearAI.id to ModelDefaultRule.MostExpensive(e2eeOnly = true),
        Provider.grok.id to ModelDefaultRule.First,          // closed weights; the list is newest-first
        // Open-weight hosts: the strongest published model wins, by price tier, then parameter count, then recency —
        // no shortlist to keep.
        Provider.fireworks.id to ModelDefaultRule.StrongestOpenWeight,
        Provider.braveAnswers.id to ModelDefaultRule.Fixed("brave"),   // no /models endpoint
        // Catalogues too large to shortlist and too uniform to sort by price: the
        // rule picks by published weights, then size, then recency (2d76b10).
        Provider.openRouter.id to ModelDefaultRule.StrongestOpenWeight,
        Provider.nvidia.id to ModelDefaultRule.StrongestOpenWeight,
    )

    fun signupURL(presetID: String): String? = signupURLs[presetID]
    fun description(presetID: String): String? = descriptions[presetID]

    /** The preset whose endpoint matches, if any. */
    fun matching(endpoint: String): Provider? {
        val target = endpoint.trim().lowercase()
        return Provider.presets.firstOrNull { it.endpoint.lowercase() == target }
    }

    private fun vendorDomain(host: String): String {
        val labels = host.split('.')
        return if (labels.size > 2) labels.takeLast(2).joinToString(".") else host
    }

    /**
     * Where a failing request's user should go to fix it: the vendor console
     * for the host the error came from, else the active provider's, else a
     * preset named in the error's source label. (LLMError is :inference's, so
     * the caller passes its parts.)
     */
    fun consoleRecovery(isBraveGrounding: Boolean, errorHost: String?, sourceLabel: String?, activeProvider: Provider?): Pair<String, String>? {
        if (isBraveGrounding) return Provider.braveAnswers.consoleURL?.let { it to Provider.braveAnswers.name }
        errorHost?.lowercase()?.let { host ->
            for (preset in Provider.presets) {
                val presetHost = runCatching { URI(preset.endpoint).host }.getOrNull()?.lowercase() ?: continue
                if (vendorDomain(host) == vendorDomain(presetHost)) preset.consoleURL?.let { return it to preset.name }
            }
        }
        activeProvider?.consoleURL?.let { return it to activeProvider.consoleDisplayName }
        sourceLabel?.lowercase()?.let { lower ->
            Provider.presets.firstOrNull { lower.contains(it.name.lowercase()) }?.let { p -> p.consoleURL?.let { return it to p.name } }
        }
        return null
    }
}

val Provider.signupURL: String? get() = ProviderPresets.signupURL(id)
val Provider.presetDescription: String? get() = ProviderPresets.description(id)
/** Falls through matchingPreset: saving mints a fresh id, so a saved setup no longer carries the preset's. */
val Provider.defaultModelRule: ModelDefaultRule? get() = ProviderPresets.defaultModelRules[id] ?: matchingPreset?.let { ProviderPresets.defaultModelRules[it.id] }
/** Console / keys URL: the matching preset's, else this record's own. */
val Provider.consoleURL: String? get() = matchingPreset?.signupURL ?: signupURL
val Provider.consoleDisplayName: String get() = matchingPreset?.name ?: name
val Provider.effectiveOmitSystemPrompt: Boolean get() = omitSystemPrompt || (matchingPreset?.omitSystemPrompt ?: false)
/**
 * The cheap request that can say a key is wrong. Brave Answers asks web search; OpenRouter asks /auth/key, because its
 * /models is 200 for any key. Through matchingPreset: a saved setup no longer carries the preset's id.
 */
val Provider.keyValidationEndpoint: ProviderKeyValidator.Endpoint? get() = when (matchingPreset?.id ?: id) {
    Provider.braveAnswers.id -> ProviderKeyValidator.Endpoint.BRAVE_SEARCH
    Provider.openRouter.id -> ProviderKeyValidator.Endpoint.OPEN_ROUTER
    else -> null
}
