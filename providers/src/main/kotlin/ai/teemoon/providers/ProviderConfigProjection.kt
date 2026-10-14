// ports: teemoon/Providers/ProviderConfigProjection.swift @ v1.0.1
//
// Translates between the persisted schema (`Server` + `EquippedModel`) and the
// `Provider` the rest of the app still reads.
//
// This seam is deliberate. The schema change is worth having on disk NOW —
// keys keyed by the server, capabilities keyed by the model, one row per model
// — but `Provider.model` is read in the whole inference and attestation chain,
// and rewriting those call sites in the same change as the storage would make
// a data migration and a refactor fail as one thing. Storage moves first.
package ai.teemoon.providers

object ProviderConfigProjection {

    /** Identity of one equipped row, for matching a save against what is stored. */
    fun rowKey(serverID: String, modelID: String): String = "$serverID|$modelID"

    /**
     * How many retired rows to keep. The recents list capped itself at the same
     * order of magnitude; the section shows a handful, and a config file's job
     * is to stay small.
     */
    const val RETIRED_ROW_LIMIT = 12

    data class Projection(val providers: List<Provider>, val currentProviderID: String?)

    /**
     * Normalizes the in-memory provider list into the stored schema.
     *
     * [previous] is what is already on disk, and it is not optional decoration:
     * it carries every fact `Provider` has nowhere to put — a probed
     * [ServerKind], the capabilities of models that are equipped but not
     * active, `addedAt`, `lastUsedAt`, and each row's stable id. Without it
     * every save would flatten the normalized data back down to what the flat
     * class can express, which is the bug this schema exists to fix.
     */
    fun snapshot(
        providers: List<Provider>,
        currentProviderID: String?,
        previous: ConfigSnapshot = ConfigSnapshot.empty,
        lastUsed: Map<String, Long> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ): ConfigSnapshot {
        val priorServers = previous.servers.associateBy { it.id }
        val priorRows = previous.equipped.associateBy { rowKey(it.serverID, it.modelID) }

        val servers = ArrayList<Server>()
        val equipped = ArrayList<EquippedModel>()
        var currentEquippedID: String? = null

        for (provider in providers) {
            val prior = priorServers[provider.id]
            servers.add(Server(
                // §4: the SAME id. The Keystore entry hangs off this string.
                id = provider.id,
                name = provider.name,
                endpoint = provider.endpoint,
                // A local provider is on-device by definition; anything else keeps
                // whatever a probe has already established, because `Provider` has
                // no field that could tell us otherwise.
                kind = if (provider.isLocal) ServerKind.ON_DEVICE else (prior?.kind ?: ServerKind.OPENAI_COMPATIBLE),
                requiresAPIKey = provider.requiresAPIKey,
                authHeaderName = provider.authHeaderName,
                extraParams = provider.extraParams,
                maxMessages = provider.maxMessages,
                hasBuiltInGrounding = provider.hasBuiltInGrounding,
                omitSystemPrompt = provider.omitSystemPrompt,
                supportsModelBrowsing = provider.supportsModelBrowsing,
                activeModelID = provider.model.ifEmpty { null },
                // Android's Provider carries no preset description or signup URL yet; keep what the row knew.
                presetDescription = prior?.presetDescription,
                signupURL = prior?.signupURL,
                servedModels = prior?.servedModels))

            for (modelID in provider.equipped) {
                val key = rowKey(provider.id, modelID)
                var row = priorRows[key] ?: EquippedModel(serverID = provider.id, modelID = modelID, addedAt = nowMs)

                // §2.2, the capabilities overwrite. A `Provider` carries exactly one
                // `modelCapabilities`, and it describes its ACTIVE model — so that is
                // the only row it may speak for. Every other row keeps what it already
                // knew, which is what stops equipping a second model from erasing the
                // first one's capabilities.
                val caps = provider.modelCapabilities
                if (modelID == provider.model && caps != null) row = row.copy(capabilities = caps)
                // Never clears knowledge: null means "unknown", and a provider re-saved
                // from a screen that does not fetch capabilities would otherwise wipe a real answer.

                val stamp = lastUsed[key]
                if (stamp != null && (row.lastUsedAt == null || row.lastUsedAt < stamp)) row = row.copy(lastUsedAt = stamp)

                if (provider.id == currentProviderID && modelID == provider.model) currentEquippedID = row.id
                // Re-equipping something retired clears the mark, so it leaves
                // `recently used` and goes back to `ready now` rather than appearing in both.
                row = row.copy(unequippedAt = null)
                equipped.add(row)
            }
        }

        // RETIRED ROWS SURVIVE. A row whose model is no longer equipped is kept and
        // marked, not dropped — its `lastUsedAt` is the only fact `recently used` is
        // built from, and anything still equipped is already in `ready now`, so
        // dropping these would empty the section it exists for.
        //
        // Kept only when the model was actually USED and its server still exists,
        // and capped, because this list would otherwise grow forever. Newest first.
        val liveServerIDs = servers.map { it.id }.toSet()
        val keptKeys = equipped.map { rowKey(it.serverID, it.modelID) }.toSet()
        val retired = previous.equipped
            .filter { rowKey(it.serverID, it.modelID) !in keptKeys }
            .filter { it.lastUsedAt != null && it.serverID in liveServerIDs }
            .sortedByDescending { it.lastUsedAt ?: Long.MIN_VALUE }
            .take(RETIRED_ROW_LIMIT)
            // Stamped once, on the save that retires it, so re-saving doesn't keep
            // pushing the date forward and make an old model look recent.
            .map { if (it.unequippedAt == null) it.copy(unequippedAt = nowMs) else it }
        equipped.addAll(retired)

        return ConfigSnapshot(servers = servers, equipped = equipped, currentEquippedID = currentEquippedID).validated()
    }

    /**
     * Rebuilds the flat provider list. One `Provider` per [Server], its
     * `equippedModels` the server's rows in stored order, and its
     * `modelCapabilities` the ACTIVE row's — which is the only model a
     * `Provider` can describe.
     */
    fun providers(snapshot: ConfigSnapshot): Projection {
        // EQUIPPED ONLY. Retired rows are kept in the snapshot so `recently used`
        // has something to read, and projecting them back into `equippedModels`
        // would put every model the user ever removed back in `ready now` —
        // undoing the unequip on the next launch.
        val rowsByServer = snapshot.equipped.filter { it.isEquipped }.groupBy { it.serverID }

        val providers = snapshot.servers.map { server ->
            val rows = rowsByServer[server.id] ?: emptyList()
            val modelIDs = rows.map { it.modelID }
            val active = server.activeModelID ?: modelIDs.firstOrNull() ?: ""
            val activeRow = rows.firstOrNull { it.modelID == active }
            Provider(
                id = server.id,
                name = server.name,
                endpoint = server.endpoint,
                model = active,
                authHeaderName = server.authHeaderName,
                requiresAPIKey = server.requiresAPIKey,
                supportsModelBrowsing = server.supportsModelBrowsing,
                extraParams = server.extraParams,
                maxMessages = server.maxMessages,
                hasBuiltInGrounding = server.hasBuiltInGrounding,
                omitSystemPrompt = server.omitSystemPrompt,
                modelCapabilities = activeRow?.capabilities,
                // ON_DEVICE is the stored fact; `localModelID` is how the rest of the
                // app still asks the question. `isLocal` is what callers branch on,
                // so this must never be null for an on-device server.
                localModelID = if (server.kind == ServerKind.ON_DEVICE) active.ifEmpty { null } else null,
                equippedModels = modelIDs.ifEmpty { null })
        }
        val current = snapshot.currentEquippedID?.let { id -> snapshot.equipped.firstOrNull { it.id == id } }
        return Projection(providers, current?.serverID)
    }
}
