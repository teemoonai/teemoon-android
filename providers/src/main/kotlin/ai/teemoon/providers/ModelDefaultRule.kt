// ports: teemoon/Providers/ModelDefaultRule.swift @ v1.0.4
package ai.teemoon.providers

/**
 * How a provider chooses its default / highlighted model from the LIVE catalogue.
 * Per-provider, resolved against live data, so each provider expresses its own
 * notion of "the right default" and nothing is version-pinned.
 *
 * `e2eeOnly` restricts to near.ai's OWN fleet so a proxied Claude/GPT can never
 * win. That filter is [NearAIModelCatalog.confidentiality].
 */
sealed class ModelDefaultRule {
    /** No /models endpoint — pin this id and skip the fetch entirely (Brave). */
    data class Fixed(val id: String) : ModelDefaultRule()
    /** Most expensive by input+output rate (ties → larger context). */
    data class MostExpensive(val e2eeOnly: Boolean) : ModelDefaultRule()
    /** Newest model (the catalogue is recency-sorted, so the first in the pool). */
    data class Newest(val e2eeOnly: Boolean) : ModelDefaultRule()
    /** First match from an ordered curated id list. */
    data class Curated(val ids: List<String>) : ModelDefaultRule()
    /**
     * The strongest model whose weights are published, by what every catalog
     * reports: price tier first, then parameter count read from the id, then
     * recency. No leaderboard is consulted — this is a consistent, explainable
     * proxy, not a quality measurement. Falls back to the catalog's first model
     * when nothing is marked open.
     */
    data object StrongestOpenWeight : ModelDefaultRule()
    /** Whatever the catalogue returns first — generic fallback (Grok / unknown). */
    data object First : ModelDefaultRule()

    /** Whether this rule needs the live catalogue at all. [Fixed] doesn't. */
    val needsCatalogue: Boolean get() = this !is Fixed

    /**
     * Resolve to a model id against a catalogue that is already recency-sorted
     * (newest first within each family). Returns null only for an empty catalogue
     * (except [Fixed], which always returns its id).
     */
    fun resolve(from: List<KnownModel>): String? {
        fun pool(e2eeOnly: Boolean): List<KnownModel> {
            if (!e2eeOnly) return from
            // TEE_OWN only. Attested third-party (Chutes) hardware has no
            // confidential endpoint via near.ai, so recommending one under an
            // e2ee-only rule would name a model teemoon cannot seal.
            return from.filter {
                NearAIModelCatalog.confidentiality(it.id) == NearAIModelCatalog.Confidentiality.TEE_OWN
            }
        }
        return when (this) {
            is Fixed -> id
            is First -> from.firstOrNull()?.id
            is StrongestOpenWeight -> {
                val open = from.filter { it.openWeights == true }
                if (open.isEmpty()) from.firstOrNull()?.id
                // Price, then size, then recency, then catalogue order. The index
                // is negated so an earlier row wins a tie, which keeps the
                // catalogue's own ordering as the last word.
                else open.withIndex().maxWithOrNull(
                    compareBy({ priceScore(it.value) },
                              { parameterCount(it.value.id) ?: 0.0 },
                              { if (it.value.isNew) 1.0 else 0.0 },
                              { -it.index.toDouble() })
                )?.value?.id
            }
            is Newest -> (pool(e2eeOnly).firstOrNull() ?: from.firstOrNull())?.id
            is Curated -> {
                val have = from.map { it.id.lowercase() }.toSet()
                ids.firstOrNull { it.lowercase() in have } ?: from.firstOrNull()?.id
            }
            is MostExpensive -> {
                val p = pool(e2eeOnly)
                if (p.isEmpty()) return from.firstOrNull()?.id
                p.reduce { best, m ->
                    val sm = priceScore(m)
                    val sb = priceScore(best)
                    when {
                        sm != sb -> if (sm > sb) m else best
                        contextValue(m.contextWindow) > contextValue(best.contextWindow) -> m
                        else -> best
                    }
                }.id
            }
        }
    }

    companion object {
        /** input + output per-1M rates from "$1.40/$4.40". */
        fun priceScore(m: KnownModel): Double =
            m.price.split('/').sumOf { part ->
                val d = part.dropWhile { !it.isDigit() && it != '.' }
                d.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
            }

        /**
         * Total parameters in billions, read from the id: "27b" → 27,
         * "120b-a12b" → 120 (the TOTAL, not the active count), "2.4t-a95b" →
         * 2400. null when the id carries no size.
         */
        private val sizeInID = Regex("""(\d+(?:\.\d+)?)([tb])(?![a-z0-9])""")

        fun parameterCount(id: String): Double? {
            val m = sizeInID.find(id.lowercase()) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            return if (m.groupValues[2] == "t") n * 1000 else n
        }

        /**
         * "1M" → 1_000_000, "203k" → 203_000, "128000" → 128_000.
         *
         * Only the unit DIRECTLY after the number counts. A local row reads
         * "128k · Q4_K_M", and a `contains("m")` over the whole string found the
         * m in Q4_K_M and returned 128 million.
         */
        fun contextValue(s: String): Double {
            val t = s.lowercase()
            val digits = t.takeWhile { it.isDigit() || it == '.' }
            val num = digits.toDoubleOrNull() ?: 0.0
            return when (t.drop(digits.length).firstOrNull()) {
                'm' -> num * 1_000_000
                'k' -> num * 1_000
                else -> num
            }
        }
    }
}
