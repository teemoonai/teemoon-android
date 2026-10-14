// ports: teemoon/Presentation/ModelBrowserQuery.swift @ v1.0.4
package ai.teemoon.providers

/**
 * What the model browser shows: search, sort, filters, sections. The view
 * renders the answer; it does not compute it.
 *
 * The question a person opens this screen with is "what is the cheapest model
 * that can do the job", and the answer SPANS VENDORS — so any sort but the
 * recommended one dissolves the sections. Cheapest-within-one-vendor is the
 * same screen with that vendor typed into the search field.
 */
data class ModelBrowserQuery(
    val text: String = "",
    val sort: Sort = Sort.RECOMMENDED,
    val filters: Set<Filter> = emptySet(),
) {
    enum class Sort(val label: String, val summary: String) {
        RECOMMENDED("recommended", "recommended"),
        PRICE_LOW_TO_HIGH("price · low to high", "price ↑"),
        PRICE_HIGH_TO_LOW("price · high to low", "price ↓"),
        NEWEST("newest", "newest"),
        LARGEST_CONTEXT("largest context", "context"),
    }

    enum class Filter(val label: String, val summary: String) {
        TOOLS("uses tools", "tools"),
        VISION("reads images", "images"),
        FREE("free", "free"),
        OPEN_WEIGHTS("open weights", "open weights"),
        ENCRYPTED("end-to-end encrypted", "e2ee"),
    }
}

/**
 * The rendered answer: sections, what each row should say about itself, and
 * which controls are worth offering for this catalogue.
 */
data class ModelBrowserListing(
    val sections: List<Section>,
    val shown: Int,
    val total: Int,
    /**
     * Sorts and filters worth showing for THIS list: a price sort with no
     * prices, or a filter every row satisfies, is a control that does nothing.
     */
    val sorts: List<ModelBrowserQuery.Sort>,
    val filters: List<ModelBrowserQuery.Filter>,
    val effectiveSort: ModelBrowserQuery.Sort,
    val activeSummary: String?,
    /** The typed text, when it matches nothing and the caller allows an id as-is. */
    val customID: String?,
) {
    data class Row(
        val model: KnownModel,
        val tier: NearAIModelCatalog.Confidentiality?,
        /** Set when the list is flat: with no section header, the row names its own vendor. */
        val showsVendor: Boolean,
        val showsTier: Boolean,
        val isSelected: Boolean,
    ) { val id: String get() = model.id }

    data class Section(val id: String, val header: String?, val rows: List<Row>)

    val isEmpty: Boolean get() = sections.all { it.rows.isEmpty() }

    val countLabel: String
        get() = when {
            total == 0 -> ""
            shown == total -> if (total == 1) "1 model" else "$total models"
            else -> "$shown of $total"
        }

    companion object {
        /**
         * [tiered] marks a catalogue whose models carry a trust tier (near.ai).
         * [allowsCustomID] offers the typed text as a model id when nothing
         * matches — NEVER for near.ai, where an unknown id is treated as
         * own-fleet and would claim an encryption teemoon cannot check.
         */
        fun of(
            models: List<KnownModel>,
            query: ModelBrowserQuery,
            tiered: Boolean,
            selectedID: String = "",
            allowsCustomID: Boolean = false,
            tier: (String) -> NearAIModelCatalog.Confidentiality? = { NearAIModelCatalog.confidentiality(it) },
        ): ModelBrowserListing {
            val tierOf: (KnownModel) -> NearAIModelCatalog.Confidentiality? =
                { if (tiered) tier(it.id) else null }

            val offeredSorts = sorts(models)
            val offeredFilters = filters(models, tiered, tierOf)
            val sort = if (query.sort in offeredSorts) query.sort else ModelBrowserQuery.Sort.RECOMMENDED
            val filters = query.filters intersect offeredFilters.toSet()

            val matching = models.filter { matches(it, query.text) && satisfies(it, filters, tierOf) }
            val ordered = ordered(matching, sort)
            val flat = sort != ModelBrowserQuery.Sort.RECOMMENDED

            fun row(model: KnownModel, showsTier: Boolean) = Row(
                model = model, tier = tierOf(model), showsVendor = flat, showsTier = showsTier,
                isSelected = selectedID.isNotEmpty() && model.id == selectedID)

            val sections = when {
                flat -> listOf(Section("all", null, ordered.map { row(it, tiered) }))
                // TRUST IS THE RESTING STRUCTURE for near.ai: what teemoon can
                // seal, then what near.ai attests elsewhere, then plain proxies.
                // The tag is left off the rows because the header above says it.
                tiered -> listOf(
                    NearAIModelCatalog.Confidentiality.TEE_OWN,
                    NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY,
                    NearAIModelCatalog.Confidentiality.PROXIED,
                ).mapNotNull { want ->
                    val rows = ordered.filter { tierOf(it) == want }
                    if (rows.isEmpty()) null
                    else Section(want.name, want.label, rows.map { row(it, showsTier = false) })
                }
                else -> ordered.map { it.vendor }.distinct().map { vendor ->
                    Section(vendor, vendor.lowercase(),
                        ordered.filter { it.vendor == vendor }.map { row(it, showsTier = false) })
                }
            }

            val typed = query.text.trim()
            return ModelBrowserListing(
                sections = sections, shown = ordered.size, total = models.size,
                sorts = offeredSorts, filters = offeredFilters, effectiveSort = sort,
                activeSummary = if (filters.isEmpty()) null
                    else ModelBrowserQuery.Filter.entries.filter { it in filters }
                        .joinToString(" · ") { it.summary },
                customID = if (allowsCustomID && typed.isNotEmpty() && ordered.isEmpty()) typed else null)
        }

        // MARK: - Matching

        /** Every typed word must match something: "qwen 27" finds Qwen3.8-27B. */
        fun matches(model: KnownModel, text: String): Boolean {
            val tokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return true
            val haystack = listOf(model.displayName, model.id, model.vendor).joinToString(" ").lowercase()
            return tokens.all { haystack.contains(it) }
        }

        fun satisfies(
            model: KnownModel,
            filters: Set<ModelBrowserQuery.Filter>,
            tier: (KnownModel) -> NearAIModelCatalog.Confidentiality?,
        ): Boolean {
            // UNKNOWN CAPABILITIES DO NOT MATCH: a filter is a promise about what
            // the row can do, and null means the catalogue never said.
            if (ModelBrowserQuery.Filter.TOOLS in filters &&
                model.capabilities?.contains(ModelCapability.TOOLS) != true) return false
            if (ModelBrowserQuery.Filter.VISION in filters &&
                model.capabilities?.contains(ModelCapability.VISION) != true) return false
            if (ModelBrowserQuery.Filter.FREE in filters && !model.isFree) return false
            if (ModelBrowserQuery.Filter.OPEN_WEIGHTS in filters && model.openWeights != true) return false
            if (ModelBrowserQuery.Filter.ENCRYPTED in filters &&
                tier(model) != NearAIModelCatalog.Confidentiality.TEE_OWN) return false
            return true
        }

        // MARK: - Ordering

        /**
         * Price for sorting: input + output per 1M. null when the catalogue
         * quotes no price — which is NOT zero, and must not read as "cheapest".
         */
        fun priceScore(model: KnownModel): Double? {
            if (model.price.isEmpty()) return if (model.isFree) 0.0 else null
            return ModelDefaultRule.priceScore(model)
        }

        fun ordered(models: List<KnownModel>, sort: ModelBrowserQuery.Sort): List<KnownModel> {
            // Ties break by name then id, so the same list never shuffles
            // between two renders of the same screen.
            val byName = Comparator<KnownModel> { a, b ->
                val c = String.CASE_INSENSITIVE_ORDER.compare(a.displayName, b.displayName)
                if (c != 0) c else a.id.compareTo(b.id)
            }
            fun <T : Comparable<T>> nullsLast(get: (KnownModel) -> T?, descending: Boolean) =
                Comparator<KnownModel> { a, b ->
                    val x = get(a); val y = get(b)
                    when {
                        x != null && y != null ->
                            (if (descending) y.compareTo(x) else x.compareTo(y)).takeIf { it != 0 }
                                ?: byName.compare(a, b)
                        x != null -> -1          // unpriced/undated sorts LAST, both ways
                        y != null -> 1
                        else -> byName.compare(a, b)
                    }
                }
            return when (sort) {
                ModelBrowserQuery.Sort.RECOMMENDED -> models
                ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH ->
                    models.sortedWith(nullsLast(::priceScore, descending = false))
                ModelBrowserQuery.Sort.PRICE_HIGH_TO_LOW ->
                    models.sortedWith(nullsLast(::priceScore, descending = true))
                ModelBrowserQuery.Sort.NEWEST ->
                    models.sortedWith(nullsLast({ it.created }, descending = true))
                ModelBrowserQuery.Sort.LARGEST_CONTEXT -> models.sortedWith(
                    Comparator { a, b ->
                        val x = ModelDefaultRule.contextValue(a.contextWindow)
                        val y = ModelDefaultRule.contextValue(b.contextWindow)
                        if (x == y) byName.compare(a, b) else y.compareTo(x)
                    })
            }
        }

        // MARK: - What is worth offering

        /**
         * A sort is offered only when it would REORDER the list. NVIDIA gives
         * every model away, so "price" there is a control that does nothing.
         */
        fun sorts(models: List<KnownModel>): List<ModelBrowserQuery.Sort> {
            val out = mutableListOf(ModelBrowserQuery.Sort.RECOMMENDED)
            if (models.mapNotNull { priceScore(it) }.toSet().size > 1) {
                out += listOf(ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH, ModelBrowserQuery.Sort.PRICE_HIGH_TO_LOW)
            }
            if (models.mapNotNull { it.created }.toSet().size > 1) out += ModelBrowserQuery.Sort.NEWEST
            if (models.map { ModelDefaultRule.contextValue(it.contextWindow) }.filter { it > 0 }.toSet().size > 1) {
                out += ModelBrowserQuery.Sort.LARGEST_CONTEXT
            }
            return out
        }

        /**
         * A filter earns its place only when it DIVIDES the list: on NVIDIA every
         * model is free, so "free" would be a switch that does nothing.
         */
        fun filters(
            models: List<KnownModel>,
            tiered: Boolean,
            tier: (KnownModel) -> NearAIModelCatalog.Confidentiality?,
        ): List<ModelBrowserQuery.Filter> = ModelBrowserQuery.Filter.entries.filter { filter ->
            if (filter == ModelBrowserQuery.Filter.ENCRYPTED && !tiered) return@filter false
            val matching = models.count { satisfies(it, setOf(filter), tier) }
            matching > 0 && matching < models.size
        }
    }
}
