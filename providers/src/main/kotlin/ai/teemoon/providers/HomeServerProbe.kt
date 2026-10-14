// ports: teemoon/Views/Where/HomeServerProbe.swift @ v1.0.1
package ai.teemoon.providers

import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What each home machine is and serves, probed on every presentation of the where sheet. Seeded from
 * the persisted probe so the sheet paints before the network answers; a
 * stored kind is never erased by a failed detection.
 */
class HomeServerProbe(
    private val probe: suspend (base: String, authHeaderName: String?, apiKey: String) -> Info = ::liveProbe,
) {
    data class Info(
        val kind: LocalServerKind = LocalServerKind.UNKNOWN,
        /** null: the model list could not be read. Empty: the server is up and serves nothing. */
        val models: List<String>? = null,
        val warm: Set<String> = emptySet(),
    ) {
        val modelCount: Int? get() = models?.size
    }

    private val _infoByProvider = MutableStateFlow<Map<String, Info>>(emptyMap())
    val infoByProvider: StateFlow<Map<String, Info>> get() = _infoByProvider
    private val inFlight = HashSet<String>()
    private var isSeeded = false

    fun info(provider: Provider): Info? = _infoByProvider.value[provider.id]

    /** A model deleted from the machine leaves the cached list too, so the sheet does not re-offer it before the next probe. */
    fun forget(modelID: String, provider: Provider) {
        val info = _infoByProvider.value[provider.id] ?: return
        _infoByProvider.value = _infoByProvider.value + (provider.id to info.copy(models = info.models?.filter { it != modelID }, warm = info.warm - modelID))
    }

    fun seed(providers: List<Provider>, servedModels: (Provider) -> List<String>?, storedKind: (Provider) -> LocalServerKind?) {
        if (isSeeded) return
        val next = _infoByProvider.value.toMutableMap()
        for (provider in providers) {
            if (WhereLocality.of(provider) != WhereLocality.HOME || next.containsKey(provider.id)) continue
            val served = servedModels(provider)?.takeIf { it.isNotEmpty() } ?: continue
            next[provider.id] = Info(kind = storedKind(provider) ?: LocalServerKind.UNKNOWN, models = served, warm = emptySet())
        }
        _infoByProvider.value = next
    }

    /**
     * Probes every home machine, on EVERY call — WhereSheetView's .task calls it on each presentation, so a model the
     * server has since unloaded reads cold the next time the sheet opens. [isSeeded] is the PREVIEW's guard alone (iOS
     * sets it only in `previewing`); this port also set it here, so one probe per process ever ran and warm/cold froze at
     * the first open (the C6 journey, 2026-10-04). [inFlight] still keeps one probe per machine at a time.
     */
    suspend fun refresh(providers: List<Provider>, credential: (Provider) -> String) {
        if (isSeeded) return
        coroutineScope {
            for (provider in providers) {
                if (WhereLocality.of(provider) != WhereLocality.HOME) continue
                val base = provider.openAIBaseUrl ?: continue
                if (!inFlight.add(provider.id)) continue
                val key = credential(provider); val header = provider.authHeaderName; val id = provider.id
                async {
                    val info = probe(base, header, key)
                    _infoByProvider.value = _infoByProvider.value + (id to info)
                    inFlight.remove(id)
                }
            }
        }
    }

    companion object {
        /** A base without `/v1` is tried bare and then with it; one that already ends in `/v1` is not doubled up. */
        fun modelListCandidates(base: String): List<String> {
            val path = runCatching { URI(base).path ?: "" }.getOrDefault("")
            if (path.endsWith("/v1") || path.endsWith("/v1/")) return listOf(base)
            return listOf(base, base.trimEnd('/') + "/v1")
        }

        suspend fun liveProbe(base: String, authHeaderName: String?, apiKey: String): Info {
            val kind = LocalServerKind.detect(base)
            var ids: List<String>? = null
            for (candidate in modelListCandidates(base)) {
                val result = EndpointModelCatalog.probe(candidate, authHeaderName, apiKey)
                if (result is EndpointModelCatalog.ProbeResult.Connected && result.models.isNotEmpty()) { ids = result.models.map { it.id }; break }
            }
            val warm: Set<String> = when (kind) {
                LocalServerKind.OLLAMA -> runCatching { OllamaAdapter.loadedModels(base) }.getOrDefault(emptySet())
                LocalServerKind.LM_STUDIO -> runCatching { LMStudioAdapter.loadedModels(base) }.getOrDefault(emptySet())
                LocalServerKind.UNKNOWN -> emptySet()
            }
            return Info(kind, ids, warm)
        }

        fun previewing(entries: Map<String, Info>): HomeServerProbe = HomeServerProbe().apply { isSeeded = true; _infoByProvider.value = entries }
    }
}
