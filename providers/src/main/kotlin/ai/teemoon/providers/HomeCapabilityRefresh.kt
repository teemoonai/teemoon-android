// ports: teemoon/Providers/HomeCapabilityRefresh.swift @ a15f0cec; recheckBeforeRefusing @ 6218fbf5, unknown kind @ d9d9db05
package ai.teemoon.providers

/**
 * What each model on the user's own Ollama machine can do ("reads images"), kept on its equipped row. The where sheet
 * calls this right after it probes the machine; nothing else does. The catalogue store keys cloud servers only, so
 * without this a home model's capabilities were kept only when its place was saved from the edit form.
 */
class HomeCapabilityRefresh(
    private val list: suspend (base: String) -> List<KnownModel>? = { base ->
        (OllamaAdapter.listModels(base) as? EndpointModelCatalog.ProbeResult.Connected)?.models
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Keyed by setup AND address: keyed by the setup alone, a setup whose endpoint was edited kept the old machine's
     * hour, so the new machine's capabilities were not read until it ran out.
     */
    private val fetchedAt = HashMap<Pair<String, String>, Long>()

    /** Ollama servers only: the other home kinds report no capabilities over this path. */
    suspend fun refresh(providers: List<Provider>, kind: (Provider) -> LocalServerKind?, adopt: (List<KnownModel>, String) -> Unit) {
        for (provider in providers) {
            if (WhereLocality.of(provider) != WhereLocality.HOME || kind(provider) != LocalServerKind.OLLAMA) continue
            val base = provider.openAIBaseUrl ?: continue
            // one read per server per hour: each is a /api/show per model on the user's machine
            val last = fetchedAt[provider.id to base]
            if (last != null && nowMs() - last < MAX_AGE_MS) continue
            val models = list(base)?.takeIf { it.isNotEmpty() } ?: continue
            fetchedAt[provider.id to base] = nowMs()
            adopt(models, provider.id)
        }
    }

    /**
     * Before a photo to a home Ollama model is refused (iOS 6218fbf5; the maintainer 2026-11-12): unless the machine was
     * read this hour, ask it about that one model — one /api/show, a short timeout — and keep the answer. True when it
     * reads images. Unreachable: false, and the refusal stands. Never at launch, never a cloud server or a machine known
     * not to be Ollama (an unknown kind is asked: a non-Ollama server just fails the /api/show).
     * The gap it closes: a setup saved before a15f0cec, a model switched on the same server, a failed /api/show at save.
     */
    suspend fun recheckBeforeRefusing(
        provider: Provider, kind: LocalServerKind?,
        show: suspend (model: String, base: String) -> ModelCapabilities? = { model, base -> OllamaAdapter.capabilities(model, base) },
        adopt: (List<KnownModel>, String) -> Unit,
    ): Boolean {
        // unknown kind is asked (a server whose probe never ran: iOS d9d9db05); only one known not to be Ollama is skipped
        if (WhereLocality.of(provider) != WhereLocality.HOME || (kind != null && kind != LocalServerKind.OLLAMA)) return false
        val base = provider.openAIBaseUrl ?: return false
        val last = fetchedAt[provider.id to base]
        if (last != null && nowMs() - last < MAX_AGE_MS) return false
        val caps = show(provider.model, base) ?: return false
        adopt(listOf(KnownModel(provider.model, provider.model, "", "", capabilities = caps.toCapabilitySet())), provider.id)
        return caps.contains(ModelCapabilities.VISION)
    }

    companion object {
        const val MAX_AGE_MS = 3_600_000L
    }
}
