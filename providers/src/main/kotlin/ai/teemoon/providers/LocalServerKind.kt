// ports: teemoon/Providers/EndpointModelCatalog.swift @ v1.0.4 (LocalServerKind only)
package ai.teemoon.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * `owned_by` in `/v1/models` is not that signal: llama.cpp says "llamacpp", but
 * Ollama says "library" and LM Studio "organization_owner".
 *
 * Detection (`detect`) is EndpointProbe's job and is not this type.
 */
enum class LocalServerKind {
    OLLAMA,
    LM_STUDIO,
    /** llama.cpp, vLLM, or anything else OpenAI-compatible → the generic probe. */
    UNKNOWN,
    ;
    /** The persisted [ServerKind] this probe result establishes. [UNKNOWN] is null: a failed detection must not erase a stored kind. */
    val stored: ServerKind?
        get() = when (this) { OLLAMA -> ServerKind.OLLAMA; LM_STUDIO -> ServerKind.LM_STUDIO; UNKNOWN -> null }


    /**
     * What to call the server in the UI. [UNKNOWN] is null: llama.cpp, vLLM and
     * anything else OpenAI-compatible land here indistinguishably.
     */
    val displayName: String? get() = when (this) {
        OLLAMA -> "ollama"
        LM_STUDIO -> "lm studio"
        UNKNOWN -> null
    }

    companion object {
        /** Ollama and LM Studio each answer a probe of their own; anything else is the generic OpenAI-compatible server. */
        suspend fun detect(baseUrl: String): LocalServerKind = coroutineScope {
            val ollama = async { runCatching { OllamaAdapter.isOllama(baseUrl) }.getOrDefault(false) }
            val lmStudio = async { runCatching { LMStudioAdapter.isLMStudio(baseUrl) }.getOrDefault(false) }
            when {
                ollama.await() -> OLLAMA
                lmStudio.await() -> LM_STUDIO
                else -> UNKNOWN
            }
        }
    }
}
