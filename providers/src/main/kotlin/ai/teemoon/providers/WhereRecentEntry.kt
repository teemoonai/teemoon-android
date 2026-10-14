// ports: teemoon/Providers/WhereRecentEntry.swift @ v1.0.1
//        teemoon/Providers/ProviderStore+Recents.swift @ v1.0.1 (recordUse, recentlyUsed, stillRunnable, forgetModel;
//        recordProbe / servedModels / storedKind are ConfigStore verbs here)
//
// One row of "recently used": the provider instance with `model` set to the
// id that answered, and when it last produced output.
//
// This used to come with a store. `WhereRecentsStore` kept its own blob of
// picks, and the argument for deleting it stands: the config file holds the
// same fact on the row that owns it (`EquippedModel.lastUsedAt`), written by
// the same event, and that row OUTLIVES the unequip which a recent is mostly
// made of. Two stores of one fact disagree eventually. So the store is gone
// and what remains is the pick. Labels are computed from the live provider —
// not stored here — because a cached caption is a description of how the app
// talked about a place in the past.
package ai.teemoon.providers

/** One past pick: a provider with `model` set to the id that answered then. */
data class WhereRecentEntry(
    /** Live provider, `model` already pointed at the used id. */
    val provider: Provider,
    val recordedAtMs: Long,
) {
    /** Stable identity for list diffs: provider + model. */
    val id: String get() = "${provider.id}|$modelID"
    val providerID: String get() = provider.id
    val modelID: String get() = provider.model
    val locality: WhereLocality get() = WhereLocality.of(provider)
}

/** What has answered lately. iOS reaches these through ProviderStore; here they read the ConfigStore directly. */
object ProviderRecents {

    /** Records that a model actually produced output. The only writer of `lastUsedAt` — "recently used" means used, not picked. */
    fun recordUse(store: ConfigStore, provider: Provider) {
        if (provider.model.isEmpty()) return
        store.stampUse(provider.id, provider.model)
    }

    /**
     * What has actually ANSWERED lately, newest first — read from
     * `EquippedModel.lastUsedAt`. Two things this has to get right, both of
     * which the old store learned the hard way:
     *
     * - EQUIPPED ROWS COUNT. Filtering them out empties the list, because the
     *   model you just used is equipped by definition. The overlap with
     *   `ready now` is deliberate — a short list in the order you ran things
     *   answers "what was I using yesterday".
     * - A ROW WHOSE SERVER IS GONE DOESN'T. It can't be activated, so it would
     *   be a slot spent on something untappable. Dropped before the limit.
     *
     * [installed] answers whether a phone model's weights are on disk
     * (LocalModelStorage.isInstalled on both platforms); the default is "no",
     * so a caller that has a storage must say so.
     */
    fun recentlyUsed(store: ConfigStore, providers: List<Provider>, limit: Int = 3, installed: (localModelID: String) -> Boolean = { false }): List<WhereRecentEntry> {
        val byID = providers.associateBy { it.id }
        return store.snapshot.equipped
            .mapNotNull { row ->
                val stamp = row.lastUsedAt ?: return@mapNotNull null
                val provider = byID[row.serverID] ?: return@mapNotNull null
                if (!stillRunnable(row.modelID, provider, store.servedModels(row.serverID), installed)) return@mapNotNull null
                WhereRecentEntry(provider.withModel(row.modelID), stamp)
            }
            .sortedByDescending { it.recordedAtMs }
            .take(limit)
    }

    /**
     * Whether tapping this recent could actually run it, without first fetching
     * gigabytes. The three tiers answer differently, and only cloud keeps a
     * model it no longer has equipped:
     *
     * - CLOUD: always. Unequipping is a curation choice; the model is still at
     *   the provider and re-equipping it costs one request.
     * - PHONE: only while the weights are on disk.
     * - HOME: only while the machine still serves it. A null [served] means the
     *   machine hasn't been probed this launch, and the answer is then the last
     *   persisted list rather than "nothing" — otherwise every home recent
     *   would disappear until a probe landed.
     */
    fun stillRunnable(modelID: String, provider: Provider, served: List<String>?, installed: (String) -> Boolean = { false }): Boolean =
        when (WhereLocality.of(provider)) {
            WhereLocality.CLOUD -> true
            WhereLocality.PHONE -> provider.localModelID?.let { LocalModelCatalog.model(it) != null && installed(it) } ?: false
            WhereLocality.HOME -> served?.contains(modelID) ?: true
        }

    /** Forgets a model entirely — equipped row, usage history and the server's record of serving it. For weights that are GONE, not merely unequipped. */
    fun forgetModel(store: ConfigStore, modelID: String, provider: Provider) = store.forgetModel(provider.id, modelID)
}
