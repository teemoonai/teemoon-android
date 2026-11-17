// spec: teemoon-ios docs/IMAGES.md §4e @ c69b3492 (the where-sheet filter, 1.1.0; the maintainer 2026-11-11, image-flow
// mock Version 18) and conformance/wherefilter.json (kit 0.7.0), which is the contract
package ai.teemoon.providers

import ai.teemoon.providers.ModelBrowserQuery.Filter

/**
 * The where sheet's "show only" row: the tab's count on the left, the filter in words on the right. A filter combines
 * with the tab and narrows ready now, recently used and get. The words and short names are the model browser's own
 * ([Filter.label], [Filter.summary]), so the sheet and the browser it opens read as one control. Every string marked
 * DRAFT is the maintainer's to edit. See WhereFilterKitTest, which holds this to iOS's generated cells.
 */
object WhereFilter {
    /** The menu's items, in its order. No "uses tools" (nearly every model takes tools), no free / open weights. */
    val offered: List<Filter> = listOf(Filter.VISION, Filter.ENCRYPTED)
    const val MENU_HEADING = "show only"
    /** DRAFT. The phone tab with only "reads images" on, while no phone model can (imagesOnDevice false). */
    const val PHONE_CANT_YET = "no model on this phone reads images yet."
    /** DRAFT. */
    const val NOTHING_MATCHES = "nothing here matches."

    /**
     * What a row's model can do. `readsImages` is ImageModelChoice's answer, where unknown is false: a filter is a
     * promise about what the row can do. `e2ee` is the row's own caption claim (Provider.claimsEndToEndEncryption
     * with that model active), so phone and home rows never have it.
     */
    data class Ability(val readsImages: Boolean, val e2ee: Boolean, val imagesRefused: Boolean = false)

    /**
     * A phone model this phone can never give the memory images need ([Ability.imagesRefused], PhoneImageMemory): under
     * reads images it is listed in its place with the permanent caption, and not counted (IMAGES.md §4b, the maintainer
     * 2026-11-12). It passes the other filters as a model that read images would.
     */
    fun refused(ability: Ability, filters: Set<Filter>): Boolean =
        Filter.VISION in filters && ability.imagesRefused && passes(ability.copy(readsImages = true), filters)

    sealed interface GetRow {
        data class Download(val id: String, val readsImages: Boolean) : GetRow
        data class Pull(val machine: String) : GetRow
        data object Connect : GetRow
        /**
         * A preset, or a keyed custom server's browse row (`custom`). [listCount] is how many models the server's saved
         * list holds under the given filters, by the browser's own predicate (ModelBrowserListing.satisfies), so the
         * number on the row is the number the browser shows when the row opens it; null when there is no saved list.
         */
        data class Server(
            val name: String, val custom: Boolean, val keyed: Boolean, val claimsE2ee: Boolean,
            val fixedAnswer: Boolean = false, val configured: Boolean = false,
            val listCount: (Set<Filter>) -> Int? = { null },
        ) : GetRow
        data object AddCloudKey : GetRow
    }

    data class Shown(val row: GetRow, val trailing: String?)

    fun passes(ability: Ability, filters: Set<Filter>): Boolean =
        (Filter.VISION !in filters || ability.readsImages) && (Filter.ENCRYPTED !in filters || ability.e2ee)

    /** The browser's words: "7 models", "1 model", "4 of 7" when a filter hides some. Blank when the tab has none. */
    fun count(shown: Int, total: Int): String? = when {
        total == 0 -> null
        shown == total -> if (total == 1) "1 model" else "$total models"
        else -> "$shown of $total"
    }

    /** "show only" with no filter, the filter's words with one, the short names joined with both. */
    fun control(filters: Set<Filter>): String {
        val on = offered.filter { it in filters }
        return when (on.size) {
            0 -> MENU_HEADING
            1 -> on.single().label
            else -> on.joinToString(" · ") { it.summary }
        }
    }

    /**
     * The get rows a tab offers before any filter, in the sheet's order; null offline, where get is the offline
     * caption and the filter doesn't touch it. The all tab keeps its one recommended download.
     */
    fun candidates(
        tab: WhereLocality?, online: Boolean, recommended: GetRow.Download?, downloadable: List<GetRow.Download>,
        pulls: List<GetRow.Pull>, customs: List<GetRow.Server>, presets: List<GetRow.Server>,
    ): List<GetRow>? {
        if (!online) return null
        return when (tab) {
            null -> listOfNotNull(recommended) + pulls + GetRow.Connect + presets + GetRow.AddCloudKey
            WhereLocality.PHONE -> downloadable
            WhereLocality.HOME -> pulls + GetRow.Connect
            WhereLocality.CLOUD -> customs + presets + GetRow.AddCloudKey
        }
    }

    /** What survives the filter, with each row's trailing text. */
    fun get(rows: List<GetRow>, filters: Set<Filter>): List<Shown> = rows.mapNotNull { row ->
        when (row) {
            // A phone model passes as its row would; none is end-to-end encrypted.
            is GetRow.Download -> Shown(row, null).takeIf { passes(Ability(row.readsImages, e2ee = false), filters) }
            // No home model can pass "end-to-end encrypted", so the ways to one hide with it (Android's S44 review).
            is GetRow.Pull, GetRow.Connect -> Shown(row, null).takeIf { Filter.ENCRYPTED !in filters }
            GetRow.AddCloudKey -> Shown(row, null)
            is GetRow.Server -> server(row, filters)
        }
    }

    private fun server(row: GetRow.Server, filters: Set<Filter>): Shown? {
        if (row.fixedAnswer) {
            // brave answers: no row once it's set up (presetRow), and none under any filter — it answers searches only
            if (row.configured || filters.isNotEmpty()) return null
            return Shown(row, if (row.keyed) "set up" else "add key")
        }
        val total = if (row.keyed) row.listCount(emptySet()) else null
        if (total == null) {
            // no key, or no saved list: no number to narrow, so the row stays unless it can't be what the filter asks
            if (Filter.ENCRYPTED in filters && !row.claimsE2ee) return null
            return Shown(row, if (row.keyed) null else "add key")
        }
        if (filters.isEmpty()) return Shown(row, "$total")
        val shown = row.listCount(filters) ?: 0
        return if (shown == 0) null else Shown(row, "$shown of $total")
    }

    /**
     * The one empty line, under the row: when a filter is on and nothing shows in ready now or recently used, and get
     * offers no download or server row (pull and connect rows are not offers of a model).
     */
    fun line(tab: WhereLocality?, filters: Set<Filter>, ready: Int, recents: Int, get: List<Shown>?, imagesOnDevice: Boolean): String? {
        if (filters.isEmpty() || ready > 0 || recents > 0) return null
        if (get.orEmpty().any { it.row is GetRow.Download || it.row is GetRow.Server }) return null
        return if (tab == WhereLocality.PHONE && filters == setOf(Filter.VISION) && !imagesOnDevice) PHONE_CANT_YET else NOTHING_MATCHES
    }
}

/**
 * The sheet's filtered state, built from the person's setups the way the sheet draws them: [WhereFilter]'s rules over
 * the tab's ready rows, recents and get rows. The sheet renders this; it does not filter. See WhereFilterSheetTest.
 */
data class WhereFilterSheet(
    val count: String?,
    val control: String,
    val ready: List<Equipped>,
    val recents: List<WhereRecentEntry>,
    val get: List<WhereFilter.Shown>?,
    val line: String?,
    /** Phone models listed under reads images that this phone can never fit (WhereFilter.refused): not counted. */
    val refused: List<Equipped> = emptyList(),
) {
    companion object {
        /**
         * [rows] are the tab's ready rows in the sheet's order, [recents] what "recently used" would show (all tab,
         * online). [listCount] is how many of a server's saved models pass the filters, null with no saved list.
         * [recommendedStepsAside]: the all tab's download row steps aside while its file lands over an existing place.
         * Presets and custom servers are named for [WhereFilter.GetRow.Server.name] by the preset's lowercased name
         * and the custom setup's id, which the sheet looks back up.
         */
        fun of(
            policy: WhereGetPolicy, rows: List<Equipped>, recents: List<WhereRecentEntry>, showOnly: Set<ModelBrowserQuery.Filter>,
            readsImages: (Provider) -> Boolean, hasKey: (Provider) -> Boolean, listCount: (Provider, Set<ModelBrowserQuery.Filter>) -> Int?,
            claimsE2ee: (Provider) -> Boolean = { it.claimsEndToEndEncryption },
            imagesOnDevice: Boolean = LocalModelCatalog.imagesOnDevice,
            recommendedStepsAside: (LocalModel) -> Boolean = { false },
            /** A phone model this phone can never give the memory images need, or whose image turn the OS killed here. */
            imagesRefused: (Provider) -> Boolean = { false },
            /** Whether a phone model's images would fit this phone, for its download row under reads images. */
            phoneFits: (LocalModel) -> Boolean = { true },
        ): WhereFilterSheet {
            val ability = { p: Provider -> WhereFilter.Ability(readsImages(p), claimsE2ee(p), imagesRefused = imagesRefused(p)) }
            val ready = rows.filter { WhereFilter.passes(ability(it.asActive), showOnly) }
            val shownRecents = recents.filter { WhereFilter.passes(ability(it.provider), showOnly) }
            // a download whose images would never fit this phone is not offered under reads images (IMAGES.md §4b)
            val download = { m: LocalModel -> WhereFilter.GetRow.Download(m.id, readsImages = imagesOnDevice && m.readsImages && phoneFits(m)) }
            val refused = rows.filter { WhereFilter.refused(ability(it.asActive), showOnly) }
            fun server(name: String, setup: Provider, preset: Provider, custom: Boolean) = WhereFilter.GetRow.Server(
                name, custom, keyed = hasKey(setup), claimsE2ee = claimsE2ee(preset), fixedAnswer = preset.isFixedAnswerService,
                configured = custom || setup !== preset, listCount = { f -> listCount(setup, f) })
            val presets = Provider.presets.map { preset ->
                val configured = policy.providers.firstOrNull { it.sameEndpoint(preset) }
                server(preset.name.lowercase(), configured ?: preset, preset, custom = false)
            }
            val candidates = WhereFilter.candidates(
                policy.filter, policy.networkSatisfied,
                recommended = policy.recommendedDownload?.takeUnless(recommendedStepsAside)?.let(download),
                downloadable = if (policy.getSectionIsEmpty) emptyList() else policy.downloadableModels.map(download),
                pulls = policy.pullableProviders.map { WhereFilter.GetRow.Pull(it.id) },
                customs = policy.browseableProviders.filter(policy::isCustom).map { server(it.id, it, it, custom = true) },
                presets = presets)
            val get = candidates?.let { WhereFilter.get(it, showOnly) }
            return WhereFilterSheet(WhereFilter.count(ready.size, rows.size), WhereFilter.control(showOnly), ready, shownRecents, get,
                WhereFilter.line(policy.filter, showOnly, ready.size + refused.size, shownRecents.size, get, imagesOnDevice), refused)
        }
    }
}
