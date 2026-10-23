// ports: teemoon/Inference/ReasoningEffort.swift @ 5f015a9 (ReasoningControl.classifyIfNeeded, the launch's probed answers)
package ai.teemoon.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * The first send to an unclassified self-hosted server asks once whether it is Ollama (the maintainer, 1.0.6 build 58:
 * "Include"; iOS 5f015a9). A server added before 1.0.6, or never opened in Where, had no stored kind, so it got no
 * reasoning effort and no Think Harder (ReasoningControl reads the kind; found by the 3917.8 keep-reply gate). Before
 * the first send, a self-hosted server with no kind is asked GET /api/version (2 s). Yes is recorded like the Where
 * probe, and counted for the launch where the store keeps nothing; no is remembered for the launch. Cloud hosts are
 * never asked. Lives here, in front of the turn, so the on-device path and its gates stay untouched.
 */
class FirstSendKindProbe(
    /** The stored kind, or null when none was recorded. */
    private val storedKind: (Provider) -> LocalServerKind?,
    /** `OllamaAdapter.isOllama` with a [TIMEOUT_MS] call timeout; false on any failure. */
    private val isOllama: suspend (baseUrl: String) -> Boolean,
    /** Records the kind, as the Where sheet's probe does (`ConfigStore.recordProbe`). */
    private val recordOllama: (Provider) -> Unit,
) {
    /** This launch's answers, by endpoint: true = Ollama. A false is not asked again. Process memory only. */
    private val probed = ConcurrentHashMap<String, Boolean>()

    suspend fun beforeSend(provider: Provider) {
        if (provider.isLocal || !provider.isSelfHosted || storedKind(provider) != null || probed.containsKey(provider.endpoint)) return
        val base = provider.openAIBaseUrl ?: return
        // Never in the send's way: a probe that fails in any way (a malformed URL throws before the adapter's own guard)
        // is a "no" for the launch, and the turn goes out as it would have. Only a stop propagates.
        val answer = try { isOllama(base) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (_: Exception) { false }
        probed[provider.endpoint] = answer
        if (answer) recordOllama(provider)
    }

    /** What resolve reads: the stored kind, or this launch's yes (iOS `storedKind ?? probed == true`). */
    fun kind(provider: Provider): LocalServerKind? = storedKind(provider) ?: if (probed[provider.endpoint] == true) LocalServerKind.OLLAMA else null

    companion object {
        /** iOS `isOllama(baseURL:timeout: 2)`: a send waits on it once. The Where sheet's probe keeps 5 s. */
        const val TIMEOUT_MS = 2_000L
    }
}
