// ports: teemoon/Providers/ProviderStore+Healing.swift @ v1.0.4 (healAutoLabels, generatedLabelPlace,
//        isGeneratedLabel, looksLikeAModelRef, fuzzyModelKey, healSelfHostedKeyFlags, mergeDuplicateEndpoints,
//        mergeDuplicateMachines, noteMerge) and teemoon/Providers/Provider.swift @ v1.0.4 (machineIdentity,
//        unequipping). Pure functions over the provider list; the app's store applies the results and owns the log.
// ports: teemoon/Providers/ProviderStore+Healing.swift @ v1.0.4 (isGeneratedLabel's catalogue words: the saved live list)
// ports: teemoon/Providers/ProviderStore+Healing.swift @ v1.0.4 (healRetiredNearAIModels(served:), 4b9d154's step in main's shape)
// ports: teemoon/Providers/ProviderStore+Healing.swift @ v1.0.4 (nearAIProvider: the active near.ai setup first)
// ports: teemoon/Providers/ProviderStore.swift @ v1.0.4 (addProvider: the fold, the on-device exemption, the union)
package ai.teemoon.providers

import java.net.URI

/**
 * One physical machine, however its address was spelled: host, port and path
 * of a keyless self-hosted endpoint. null for cloud (a key is what
 * distinguishes accounts there) and for a self-hosted box that needs a key.
 */
val Provider.machineIdentity: String?
    get() {
        if (!isSelfHosted || requiresAPIKey) return null
        val url = openAIBaseUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val host = url.host ?: return null
        val port = if (url.port >= 0) url.port.toString() else ""
        var path = (url.path ?: "").lowercase()
        while (path.endsWith("/")) path = path.dropLast(1)
        return "${host.lowercase()}:$port$path"
    }

/**
 * Removes a model from the equipped set. EMPTY IS A VALID STATE: a server
 * exists independently of what is equipped on it — the endpoint, the key and
 * the machine are all still there. Returning "nothing" here once made the
 * caller delete the whole provider, Keychain entry included.
 */
fun Provider.unequipping(modelID: String): Provider {
    val remaining = equipped.filter { it != modelID }
    return copy(equippedModels = remaining.ifEmpty { null }).withModel(if (model == modelID) (remaining.firstOrNull() ?: "") else model)
}

object ProviderHealing {
    /**
     * ProviderStore.nearAIProvider: the near.ai setup the trust sheet's "add the key" / "switch model" act on — the
     * ACTIVE one when it is near.ai, else the first. With two near.ai setups, the first-in-list pick edited the one
     * the user was not talking to.
     */
    fun nearAIProvider(providers: List<Provider>, activeId: String?): Provider? {
        fun isNearAI(p: Provider) = ProviderPresets.matching(p.endpoint)?.id == Provider.nearAI.id
        providers.firstOrNull { it.id == activeId }?.takeIf(::isNearAI)?.let { return it }
        return providers.firstOrNull(::isNearAI)
    }

    data class Healed(val providers: List<Provider>, val renamed: List<String>)

    /** Records still wearing teemoon's own "place model" auto-label are renamed to the place. */
    fun healAutoLabels(providers: List<Provider>, catalogModels: (String) -> List<KnownModel> = { emptyList() }): Healed {
        val renamed = ArrayList<String>()
        val out = providers.map { p ->
            if (p.isLocal) return@map p
            val place = generatedLabelPlace(p) ?: return@map p
            if (!isGeneratedLabel(p.name, place, p, catalogModels) || p.name.equals(place, ignoreCase = true)) return@map p
            renamed.add(p.name + " → " + place); p.copy(name = place)
        }
        return Healed(out, renamed)
    }

    fun generatedLabelPlace(provider: Provider): String? {
        if (provider.isSelfHosted) provider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.let { return HostLabel.friendly(it).lowercase() }
        return Provider.presets.firstOrNull { ProviderAccounts.endpointKey(it.endpoint) == ProviderAccounts.endpointKey(provider.endpoint) }?.name?.lowercase()
    }

    /** "<place> <model>" where the suffix names a model this record has, had, or could have — teemoon's own label shape, not the user's. */
    fun isGeneratedLabel(name: String, place: String, provider: Provider, catalogModels: (String) -> List<KnownModel> = { emptyList() }): Boolean {
        val trimmed = name.trim()
        if (!trimmed.lowercase().startsWith(place.lowercase() + " ")) return false
        val suffix = trimmed.drop(place.length + 1).trim()
        if (suffix.isEmpty()) return false
        val candidates = HashSet<String>()
        for (id in provider.equipped + listOfNotNull(provider.model.ifEmpty { null })) {
            candidates.add(id.lowercase()); candidates.add(ModelCatalog.displayName(id).lowercase()); candidates.add(ModelCatalog.compactName(id).lowercase())
        }
        // A cloud label names the CATALOG's pretty name ("Qwen3.7 Plus"), which the
        // id alone does not produce — so the words come from what this server
        // actually answered with, whichever key fetched it ([LiveCatalogStore.allModels]).
        for (m in catalogModels(provider.endpoint)) {
            candidates.add(m.displayName.lowercase()); candidates.add(m.id.lowercase()); candidates.add(ModelCatalog.displayName(m.id).lowercase())
        }
        if (suffix.lowercase() in candidates) return true
        if (provider.isSelfHosted && looksLikeAModelRef(suffix)) return true
        return candidates.map(::fuzzyModelKey).contains(fuzzyModelKey(suffix))
    }

    fun looksLikeAModelRef(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains(' ') || t.length < 3) return false
        return t.contains(':') || t.contains('/')
    }

    /** Letters and digits only, with a "p" between digits dropped: k2p6 → k26, matching "k2.6". */
    fun fuzzyModelKey(s: String): String {
        val lower = s.lowercase(); val out = StringBuilder()
        for ((i, ch) in lower.withIndex()) {
            if (ch == 'p' && i > 0 && i + 1 < lower.length && lower[i - 1].isDigit() && lower[i + 1].isDigit()) continue
            if (ch.isLetterOrDigit()) out.append(ch)
        }
        return out.toString()
    }

    /** A self-hosted record that claims to need a key but has none stored is keyless — the flag was a hardcoded default, not a fact. */
    fun healSelfHostedKeyFlags(providers: List<Provider>, storedCredential: (Provider) -> String): Healed {
        val healed = ArrayList<String>()
        val out = providers.map { p ->
            if (!p.requiresAPIKey || p.isLocal || !p.isSelfHosted || storedCredential(p).isNotEmpty()) return@map p
            healed.add(p.name); p.copy(requiresAPIKey = false)
        }
        return Healed(out, healed)
    }

    /** What [addProvider] leaves: the list, the id the caller must key and select, and what moved current (a merge). */
    data class Added(val providers: List<Provider>, val id: String, val newCurrentProviderID: String? = null)

    /**
     * ProviderStore.addProvider. A second record for an already-configured endpoint is folded into the existing one:
     * the incoming fields win, the EXISTING id is kept — that is what makes key rotation work, the new key overwriting
     * the old one in the same slot rather than orphaning it — and the equipped sets are unioned, so no model the user
     * could previously run disappears.
     *
     * On-device providers are exempt: every one of them shares the literal endpoint "on-device", so matching on it
     * would collapse the whole local catalogue into a single record. Android's store folded on presetMatchKey with no
     * exemption and replaced rather than unioned: downloading a second phone model overwrote the first one's place and
     * selected an id no list held — the chip read "choose where" (Pixel 10 Pro, 2026-10-05).
     */
    fun addProvider(providers: List<Provider>, provider: Provider, currentProviderID: String?): Added {
        if (!provider.isLocal) {
            val existing = providers.firstOrNull { !it.isLocal && ProviderAccounts.endpointKey(it.endpoint) == ProviderAccounts.endpointKey(provider.endpoint) }
            if (existing != null && existing.id != provider.id) {
                val models = existing.equipped + provider.equipped.filter { it !in existing.equipped }
                val merged = provider.copy(id = existing.id, equippedModels = models)
                return Added(providers.map { if (it.id == existing.id) merged else it }, existing.id)
            }
        }
        val appended = if (providers.any { it.id == provider.id }) providers else providers + provider
        val merge = mergeDuplicateMachines(appended, currentProviderID)
        // The merge may have dropped this record into an existing machine, in which case the survivor is what the
        // caller must key.
        val id = merge.providers.firstOrNull { !it.isLocal && ProviderAccounts.endpointKey(it.endpoint) == ProviderAccounts.endpointKey(provider.endpoint) }?.id ?: provider.id
        return Added(merge.providers, id, merge.newCurrentProviderID)
    }

    data class Merge(val providers: List<Provider>, val dropped: List<Provider>, val newCurrentProviderID: String?, val notice: String?,
        /** Dropped records whose key may be deleted: the survivor has its own. The rest keep an orphaned key — the last copy is not recoverable. */
        val keysSafeToDelete: List<Provider>)

    /**
     * One connection saved more than once: the record that is current wins,
     * else the one with a key; the loser's equipped models fold in, and the
     * active model follows the record that was running.
     */
    fun mergeDuplicateEndpoints(providers: List<Provider>, currentProviderID: String?, hasKey: (Provider) -> Boolean): Merge {
        val keptIndex = HashMap<String, Int>(); val kept = ArrayList<Provider>(); val dropped = ArrayList<Provider>(); var newCurrent: String? = null
        for (p in providers) {
            if (p.isLocal) { kept.add(p); continue }
            val key = ProviderAccounts.endpointKey(p.endpoint)
            val index = keptIndex[key]
            if (index == null) { keptIndex[key] = kept.size; kept.add(p); continue }
            val incumbent = kept[index]
            val challengerWins = p.id == currentProviderID || (incumbent.id != currentProviderID && hasKey(p) && !hasKey(incumbent))
            var survivor = if (challengerWins) p else incumbent
            val loser = if (challengerWins) incumbent else p
            val models = survivor.equipped + loser.equipped.filter { it !in survivor.equipped }
            survivor = survivor.copy(equippedModels = models)
            if (loser.id == currentProviderID) { survivor = survivor.withModel(loser.model); newCurrent = survivor.id }
            kept[index] = survivor; dropped.add(loser)
        }
        if (dropped.isEmpty()) return Merge(providers, emptyList(), null, null, emptyList())
        val safe = dropped.filter { loser -> kept.firstOrNull { ProviderAccounts.endpointKey(it.endpoint) == ProviderAccounts.endpointKey(loser.endpoint) }?.let(hasKey) == true }
        return Merge(kept, dropped, newCurrent, noteMerge(dropped.size, "one connection was saved more than once"), safe)
    }

    /** The same computer saved more than once (machineIdentity): fold the later records into the first, keeping what was running. */
    fun mergeDuplicateMachines(providers: List<Provider>, currentProviderID: String?): Merge {
        val keptIndex = HashMap<String, Int>(); val kept = ArrayList<Provider>(); val removed = ArrayList<Provider>(); var newCurrent: String? = null
        for (p in providers) {
            val machine = p.machineIdentity ?: run { kept.add(p); return@run null } ?: continue
            val index = keptIndex[machine]
            if (index == null) { keptIndex[machine] = kept.size; kept.add(p); continue }
            var survivor = kept[index]
            val extras = p.equipped.filter { it !in survivor.equipped }
            if (extras.isNotEmpty()) survivor = survivor.copy(equippedModels = survivor.equipped + extras)
            if (p.id == currentProviderID) { survivor = survivor.withModel(p.model); newCurrent = survivor.id }
            kept[index] = survivor; removed.add(p)
        }
        if (removed.isEmpty()) return Merge(providers, emptyList(), null, null, emptyList())
        return Merge(kept, removed, newCurrent, noteMerge(removed.size, "the same computer was saved more than once"), removed)
    }

    /**
     * near.ai retires models; a saved setup keeps pointing at one and every
     * send through it is refused (GLM-5.2, 2026-09-11: every 1.0.3 iOS user's
     * setup, after a 15 s wait, and nothing moved it). Given the ids near.ai
     * serves right now, move such a setup — and prune its equipped list — to
     * the preset's default, or the first served model if the default is gone
     * too. Returns the whole list and the setups it moved; an empty [served]
     * list moves nothing.
     */
    fun healRetiredNearAIModels(providers: List<Provider>, served: List<String>): Pair<List<Provider>, List<Provider>> {
        val first = served.firstOrNull() ?: return providers to emptyList()
        val servedSet = served.map { it.lowercase() }.toSet()
        val fallback = if (Provider.nearAI.model.lowercase() in servedSet) Provider.nearAI.model else first
        val moved = ArrayList<Provider>()
        val healed = providers.map { p ->
            if (WhereProviderPresentation.liveCatalogSource(p) != EndpointModelCatalog.Source.NEAR_AI || p.model.isEmpty()) return@map p
            val model = if (p.model.lowercase() in servedSet) p.model else fallback
            val equipped = p.equippedModels?.let { list -> list.filter { it.lowercase() in servedSet }.ifEmpty { listOf(model) } }
            val h = p.copy(equippedModels = equipped).withModel(model)
            if (h != p) moved += h
            h
        }
        return healed to moved
    }

    fun noteMerge(count: Int, reason: String): String {
        val subject = if (count == 1) "one duplicate setup" else "$count duplicate setups"
        return "teemoon merged $subject — $reason. nothing you could run has been lost."
    }
}
