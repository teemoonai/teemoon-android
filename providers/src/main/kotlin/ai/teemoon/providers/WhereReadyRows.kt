// ports: teemoon/Views/Where/WhereSheetView+Ready.swift @ v1.0.1 (Equipped, ordered, placeKey, recommendationRank),
//        teemoon/Views/Where/WhereRow.swift @ v1.0.1 (ModelDetailTarget)
package ai.teemoon.providers

/** One "ready now" row: a model equipped on a place. */
data class Equipped(val provider: Provider, val modelID: String) {
    val id: String get() = "${provider.id}#$modelID"
    /** The provider as it would be if this row were active. */
    val asActive: Provider get() = provider.withModel(modelID)
}

data class ModelDetailTarget(val provider: Provider, val model: KnownModel) {
    val id: String get() = provider.id + "|" + model.id
}

object WhereReadyRows {
    fun rows(providers: List<Provider>): List<Equipped> =
        providers.flatMap { p -> p.equipped.map { Equipped(p, it) } }

    /**
     * Tier first (phone, home, cloud), then within ONE place: the recommended
     * phone model on top, then warm above cold when warmth was measured, then
     * the configured order. Machines never interleave, and an unmeasured
     * warmth (null) leaves the configured order alone.
     */
    fun ordered(rows: List<Equipped>, isWarm: (Equipped) -> Boolean?): List<Equipped> =
        rows.withIndex().sortedWith { a, b ->
            val lhs = WhereLocality.of(a.value.provider).rank
            val rhs = WhereLocality.of(b.value.provider).rank
            if (lhs != rhs) return@sortedWith lhs.compareTo(rhs)
            if (placeKey(a.value) == placeKey(b.value)) {
                val lhsRec = recommendationRank(a.value); val rhsRec = recommendationRank(b.value)
                if (lhsRec != rhsRec) return@sortedWith lhsRec.compareTo(rhsRec)
                val lhsWarm = isWarm(a.value); val rhsWarm = isWarm(b.value)
                if (lhsWarm != null && rhsWarm != null && lhsWarm != rhsWarm) return@sortedWith if (lhsWarm) -1 else 1
            }
            a.index.compareTo(b.index)
        }.map { it.value }

    /** Every on-device model is one place; a machine is its own. */
    fun placeKey(row: Equipped): String =
        if (WhereLocality.of(row.provider) == WhereLocality.PHONE) "phone" else row.provider.id

    fun recommendationRank(row: Equipped, catalog: List<LocalModel> = LocalModelCatalog.all): Int {
        if (WhereLocality.of(row.provider) != WhereLocality.PHONE) return 1
        val recommended = catalog.firstOrNull()?.id ?: return 1
        return if (row.provider.localModelID == recommended) 0 else 1
    }
}
