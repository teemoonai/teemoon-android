// ports: teemoon/Providers/Provider.swift @ v1.0.1 (KnownModel value type, deprecationDate(fromProvider:),
//        deprecationLabel, isRetiring), @ v1.0.4 (metaLabel, priceLabel)
package ai.teemoon.providers

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A catalogue row. Identity is the id — the exact API identifier.
 *
 * Everything past [price] is optional and shown only when the provider
 * actually returned it. The rule is the price table's: a blank field, never an
 * invented one.
 */
data class KnownModel(
    val id: String,
    val displayName: String,
    /** Used for section grouping. */
    val vendor: String,
    /** Input and output price per 1M tokens, e.g. "$1.05/$3.10". */
    val price: String,
    /** Context window size, e.g. "2M", "256K", "132K"; empty if unknown. */
    val contextWindow: String = "",
    /** Show a "New" badge in the model browser. */
    val isNew: Boolean = false,
    /**
     * When the provider says this model was published. Carried on the row, not
     * only folded into [isNew], because two things need it after the fetch:
     * ordering (newest first inside a tier) and re-deciding the badge when a
     * saved list is read back — a row saved as "new" months ago must not still
     * wear it. [isNew] alone cannot answer either question.
     */
    val created: Instant? = null,
    /**
     * The provider serves this model at no per-token charge — a free tier
     * (NVIDIA's hosted catalogue) or a `:free` OpenRouter variant. [price] is
     * empty for these, and this flag is what lets a row say "free" instead of
     * showing the same blank a model with an UNPUBLISHED price shows. Set by
     * the source that knows; never inferred from an empty price.
     */
    val isFree: Boolean = false,
    /**
     * What the catalogue charges besides the two text rates: cache reads, image
     * or audio tokens, a per-search fee, a long-context tier. Amounts are
     * numbers, formatted where they are drawn.
     */
    val extraCosts: List<ExtraCost> = emptyList(),
    /** Where the model's training data ends, as the provider states it. */
    val knowledgeCutoff: String? = null,
    /**
     * Reasoning effort names the model accepts ("high", "low"). Empty when it
     * does not reason, or the catalogue does not say.
     */
    val reasoningEfforts: List<String> = emptyList(),
    /**
     * true when the weights are published (the catalog names a Hugging Face
     * repo, or the host serves only open models), false when the model is
     * closed, null when the catalog does not say.
     */
    val openWeights: Boolean? = null,
    /**
     * The model this id redirects to, when the catalogue says it is an alias
     * ("~openai/gpt-sol-latest" → "openai/gpt-5.6-sol"). Questions about how it
     * is served have to be asked of the target: an alias has no providers of
     * its own.
     */
    val aliasOf: String? = null,
    /**
     * Base URL for near.ai direct completions mode. When set, inference
     * connects straight to the model's TEE, bypassing the gateway.
     */
    val directBaseURL: String? = null,
    /**
     * What this model can do. **null = unknown** — the source exposed no
     * capability metadata. Non-null = known (adapter-supplied).
     * Persisted on [Provider] as a bitset ([ModelCapabilities]); here a set of
     * flags. Empty is known-none; null is unknown.
     */
    val capabilities: Set<ModelCapability>? = null,
    /** The vendor's own prose. */
    val summary: String? = null,
    /**
     * Feature names as the provider spells them — `tools`, `reasoning`,
     * `json_mode`, `structured_outputs`.
     */
    val features: List<String> = emptyList(),
    val samplingParameters: List<String> = emptyList(),
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val huggingFaceURL: String? = null,
    val githubURL: String? = null,
    val modelPageURL: String? = null,
    /**
     * Set when the provider has announced a retirement date. FIREWORKS ONLY,
     * as of today; an absent date means "not published", never "not retiring".
     */
    val deprecationDate: Instant? = null,
    val quantization: String? = null,
    val parameterSize: String? = null,
    /**
     * The confidentiality tier's ONE label, resolved where the catalogue is —
     * not in a view. See `NearAIModelCatalog.Confidentiality.label`.
     */
    val confidentialityNote: String? = null,
) {
    /** "retired" once the date has passed, else "retiring in …" — the model browser's badge. */
    val deprecationLabel: String? get() = deprecationLabel(Instant.now())
    fun deprecationLabel(now: Instant): String? {
        val date = deprecationDate ?: return null
        if (!date.isAfter(now)) return "retired"
        return "retiring " + relative(date, now)
    }
    val isRetiring: Boolean get() = deprecationDate != null

    companion object {
        /**
         * Fireworks sends the date two ways — a bare `2026-12-01` and a full
         * `2026-12-01T00:00:00Z`; both mean the same instant. Anything else
         * (a quarter, a blank) is "not published", never a guess.
         */
        fun deprecationDate(fromProvider: String?): Instant? {
            val raw = fromProvider?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { Instant.parse(raw) }.getOrNull()?.let { return it }
            return runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        }

        /** RelativeDateTimeFormatter's full style, coarsely: the largest unit that fits. */
        private fun relative(date: Instant, now: Instant): String {
            val seconds = Duration.between(now, date).seconds
            val (n, unit) = when {
                seconds < 3600 -> maxOf(1, seconds / 60) to "minute"
                seconds < 86_400 -> seconds / 3600 to "hour"
                seconds < 7 * 86_400 -> seconds / 86_400 to "day"
                seconds < 30 * 86_400 -> seconds / (7 * 86_400) to "week"
                seconds < 365 * 86_400 -> seconds / (30 * 86_400) to "month"
                else -> seconds / (365 * 86_400) to "year"
            }
            return "in $n $unit${if (n == 1L) "" else "s"}"
        }
    }

    /**
     * One priced dimension besides prompt and completion. Exactly one of the
     * two rates is set: per-million for tokens, per-call for a search or a
     * request.
     */
    @kotlinx.serialization.Serializable
    data class ExtraCost(
        val label: String,
        val perMillion: Double? = null,
        val perCall: Double? = null,
        /** The output half of a long-context tier, when it is priced apart from input. */
        val perMillionOutput: Double? = null,
    ) {
        /**
         * "$0.25 / 1M tokens" or "$0.01 / search". Sub-cent rates keep the
         * digits that make them different from zero — rounding a cache read of
         * $0.0003 to "$0.00" prints a charge as free.
         */
        val amount: String
            get() {
                if (perMillion != null) {
                    val out = perMillionOutput?.let { "/" + ModelCatalog.rateLabel(it) } ?: ""
                    return ModelCatalog.rateLabel(perMillion) + out + " / 1M tokens"
                }
                return perCall?.let { ModelCatalog.rateLabel(it) } ?: ""
            }
    }
}

/** "$1.40/$4.40 · 1m" — reference data for the right-hand column; empty when the catalogue published neither. */
val KnownModel.metaLabel: String get() = listOf(if (price.isEmpty() && isFree) "free" else price, contextWindow.lowercase()).filter { it.isNotEmpty() }.joinToString(" · ")
val KnownModel.priceLabel: String get() = "$price / 1M tokens"
