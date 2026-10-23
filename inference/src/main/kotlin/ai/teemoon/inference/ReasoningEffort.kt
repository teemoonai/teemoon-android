// ports: teemoon/Inference/ReasoningEffort.swift @ 09dcb6e (ReasoningEffort, ReasoningControl)
//
// How long the SAME model on the SAME provider thinks. Never a model or
// provider switch. Chat sends [FAST] (the maintainer, 2026-10-19, after pm's eval:
// low lost no objective task; an omitted level let GLM 5.3 overthink into
// the output cap). Never "none": grok-4.3 at none got the hard maths wrong.
package ai.teemoon.inference

import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

enum class ReasoningEffort {
    FAST,
    THINK_HARDER;

    /**
     * Request-body fields for this effort, or null where the provider takes no such parameter. Which field, if any, is
     * [ReasoningControl]'s call (iOS 7cd8c10). Never "none": GLM 5.3 on Fireworks and grok-4.7 reject it, so fast is
     * "low" everywhere a level is sent.
     */
    fun bodyFields(provider: Provider): Map<String, JsonElement>? {
        if (refusalKey(provider) in refused) return null
        val high = this == THINK_HARDER
        return when (val control = ReasoningControl.current(provider)) {
            is ReasoningControl.Effort -> mapOf("reasoning_effort" to JsonPrimitive(if (high) "high" else "low")) +
                (if (high && control.cap != null) mapOf("max_tokens" to JsonPrimitive(control.cap)) else emptyMap())
            ReasoningControl.OpenRouter -> mapOf("reasoning" to JsonObject(mapOf("effort" to JsonPrimitive(if (high) "high" else "low"))))
            ReasoningControl.ThinkingSwitch -> mapOf("chat_template_kwargs" to JsonObject(mapOf("enable_thinking" to JsonPrimitive(high))))
            ReasoningControl.OnDevice, ReasoningControl.None -> null
        }
    }

    /** An effort and the setup it is for: what the transport carries, so the control is read from the provider, as on iOS. */
    data class For(val effort: ReasoningEffort, val provider: Provider) {
        fun bodyFields(): Map<String, JsonElement>? = effort.bodyFields(provider)
    }

    companion object {
        /** What a chat turn sends unless the person asks for more. */
        val CHAT_DEFAULT: ReasoningEffort = FAST
        /**
         * Output budget on a near.ai Think Harder turn. Fireworks, xAI and OpenRouter get none: their defaults wrote
         * 11k–13k tokens (measured 2026-10-22), so 8192 gave Think Harder less room.
         */
        const val THINK_HARDER_OUTPUT_TOKENS = 8192

        /** Think Harder is offered only where a higher level is known to take effect. */
        fun canThinkHarder(control: ReasoningControl, provider: Provider): Boolean =
            control != ReasoningControl.None && refusalKey(provider) !in refused

        /** The card's label for a request body: "effort low/high" or "thinking off/on"; null when it sent none. */
        fun sentLevel(bodyJson: String?): String? = runCatching {
            val body = kotlinx.serialization.json.Json.parseToJsonElement(bodyJson ?: return null) as? JsonObject ?: return null
            (body["reasoning_effort"] as? JsonPrimitive)?.content?.let { return "effort $it" }
            ((body["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.content?.let { return "effort $it" }
            ((body["chat_template_kwargs"] as? JsonObject)?.get("enable_thinking") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?.let { return if (it) "thinking on" else "thinking off" }
            null
        }.getOrNull()

        // A model that rejects the parameter.

        /**
         * Models that answered 400 to the effort field, this launch only: the API decides support, not a list.
         * Process memory, keyed `host|model`; nothing from the response is kept.
         */
        private val refused: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        private fun refusalKey(provider: Provider): String {
            val host = provider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase().orEmpty()
            return "$host|${provider.model}"
        }

        /** True when a 400 names the field ("Invalid reasoning effort.", "unexpected field chat_template_kwargs"). */
        fun isRefusal(status: Int, body: String?): Boolean {
            if (status != 400) return false
            // xAI names fields in camel case ("does not support parameter reasoningEffort", grok-build-0.1, 2026-10-22),
            // so the match is on the text with separators removed (iOS 09dcb6e). Missed in 58 and 3917.8: every send to
            // such a model failed closed instead of retrying without the field; 1.0.5 never sent it.
            val text = body.orEmpty().lowercase().replace("_", "").replace(".", "").replace(" ", "")
            return text.contains("reasoningeffort") || text.contains("chattemplatekwargs") || text.contains("enablethinking")
        }

        fun noteRefused(provider: Provider) { refused += refusalKey(provider) }

        fun forgetRefusals() = refused.clear()
    }
}

/**
 * How a provider's model is told how hard to think, from what is known about it: a documented host, OpenRouter's
 * `supported_parameters`, or an Ollama server (iOS 7cd8c10, measured 2026-10-22). A server that might silently ignore
 * the field gets nothing, so Think Harder is never a plain re-ask dressed up as more thinking.
 */
sealed interface ReasoningControl {
    /** `reasoning_effort` low/high; [cap] is Think Harder's max_tokens, null for none. */
    data class Effort(val cap: Int?) : ReasoningControl
    /** OpenRouter's `reasoning.effort`. */
    data object OpenRouter : ReasoningControl
    /** `chat_template_kwargs.enable_thinking` off/on (NVIDIA: measured, effort is ignored). */
    data object ThinkingSwitch : ReasoningControl
    /** LiteRT's ThinkingConfig off/on. */
    data object OnDevice : ReasoningControl
    data object None : ReasoningControl

    companion object {
        fun resolve(provider: Provider, serverKind: LocalServerKind?, modelParameters: List<String>?): ReasoningControl {
            if (provider.isLocal) return OnDevice
            if (ProviderCapability.BUILT_IN_GROUNDING in provider.capabilities) return None
            val host = provider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase() ?: return None
            if (Provider.isNearAIHost(host)) return Effort(ReasoningEffort.THINK_HARDER_OUTPUT_TOKENS)
            if (host == "api.fireworks.ai" || host == "api.x.ai") return Effort(null)
            if (host == "openrouter.ai") return if (modelParameters?.contains("reasoning") == true) OpenRouter else None
            if (host == "integrate.api.nvidia.com") return ThinkingSwitch
            if (serverKind == LocalServerKind.OLLAMA) return Effort(null)
            return None
        }

        /** Resolved where the store is (the app), read by the transport per request. */
        private val known = java.util.concurrent.ConcurrentHashMap<String, ReasoningControl>()

        fun note(control: ReasoningControl, provider: Provider) { known[key(provider)] = control }

        fun current(provider: Provider): ReasoningControl = known[key(provider)] ?: resolve(provider, null, null)

        /** Tests only. */
        fun forget() = known.clear()

        private fun key(provider: Provider) = "${provider.endpoint}|${provider.model}"
    }
}
