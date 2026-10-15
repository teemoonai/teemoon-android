// ports: teemoon/Providers/ListPriceSnapshot.swift @ 54617ce
//
// GENERATED, and NOT BY HAND. Regenerate by re-reading the Swift table:
//   git -C ../teemoon-ios show <sha>:teemoon/Providers/ListPriceSnapshot.swift
// and re-emitting the pairs below. The Swift is itself generated from
// OpenRouter's public /api/v1/models by the maintainers' release refresh.
package ai.teemoon.providers

/**
 * List prices for a CUSTOM endpoint on a first-party vendor host.
 *
 * A user's own OpenAI, Anthropic, Google, DeepSeek or Mistral key lists through
 * the generic `/models` probe, which carries no price. This table fills the row
 * when the model id matches EXACTLY. OpenRouter passes these vendors' list
 * prices through unchanged, so the number is the vendor's own. Anything not
 * matched stays blank — a blank row over a borrowed number.
 *
 * NUMBERS, NOT FORMATTED STRINGS, and that is the one deliberate difference
 * from the Swift. iOS stores "$10.00/$50.00"; storing a rendered price means the
 * table encodes a FORMAT, and Android's format differs by decision — decimals
 * only when there are cents (ports.json acceptedDivergences). A table of strings
 * would have imported 136 prices contradicting that decision, and a
 * regeneration upstream would have put them back. Holding per-1M doubles and
 * formatting through [ModelCatalog.priceLabel] makes the disagreement
 * structurally impossible rather than repeatedly corrected.
 */
object ListPriceSnapshot {

    const val SNAPSHOT_DATE = "2026-10-15"

    /** Vendor host → the namespace its ids live under below. */
    val hosts: Map<String, String> = mapOf(
        "api.anthropic.com" to "anthropic",
        "api.deepseek.com" to "deepseek",
        "api.mistral.ai" to "mistralai",
        "api.openai.com" to "openai",
        "generativelanguage.googleapis.com" to "google",
    )

    /** "namespace/model" → (input, output) USD per 1M tokens. */
    val prices: Map<String, Pair<Double, Double>> = mapOf(
        "anthropic/claude-fable-5" to (10.0 to 50.0),
        "anthropic/claude-fable-5.1" to (10.0 to 50.0),
        "anthropic/claude-haiku-4.5" to (1.0 to 5.0),
        "anthropic/claude-haiku-5.5" to (0.1 to 0.5),
        "anthropic/claude-opus-4.1" to (15.0 to 75.0),
        "anthropic/claude-opus-4.5" to (5.0 to 25.0),
        "anthropic/claude-opus-4.6" to (5.0 to 25.0),
        "anthropic/claude-opus-4.7" to (5.0 to 25.0),
        "anthropic/claude-opus-4.8" to (5.0 to 25.0),
        "anthropic/claude-opus-5" to (5.0 to 25.0),
        "anthropic/claude-opus-5.5" to (4.0 to 20.0),
        "anthropic/claude-sonnet-4.5" to (3.0 to 15.0),
        "anthropic/claude-sonnet-4.6" to (3.0 to 15.0),
        "anthropic/claude-sonnet-5" to (2.0 to 10.0),
        "anthropic/claude-sonnet-5.5" to (2.0 to 10.0),
        "deepseek/deepseek-chat" to (0.32 to 0.89),
        "deepseek/deepseek-chat-v3-0324" to (0.29 to 1.14),
        "deepseek/deepseek-chat-v3.1" to (0.25 to 0.95),
        "deepseek/deepseek-r1" to (0.7 to 2.5),
        "deepseek/deepseek-r1-0528" to (0.5 to 2.15),
        "deepseek/deepseek-v3.1-terminus" to (0.27 to 1.0),
        "deepseek/deepseek-v3.2" to (0.26 to 0.8),
        "deepseek/deepseek-v3.2-exp" to (0.27 to 0.41),
        "deepseek/deepseek-v4-flash" to (0.09 to 0.18),
        "deepseek/deepseek-v4-flash-0731" to (0.02 to 0.64),
        "deepseek/deepseek-v4-flash-vision-exp" to (0.22 to 0.65),
        "deepseek/deepseek-v4-pro" to (0.41 to 0.81),
        "deepseek/deepseek-v4-pro-0813" to (0.66 to 1.98),
        "deepseek/deepseek-v4.1-flash" to (0.02 to 0.72),
        "google/gemini-2.5-flash" to (0.3 to 2.5),
        "google/gemini-2.5-flash-image" to (0.3 to 2.5),
        "google/gemini-2.5-flash-lite" to (0.1 to 0.4),
        "google/gemini-2.5-pro" to (1.25 to 10.0),
        "google/gemini-2.5-pro-preview" to (1.25 to 10.0),
        "google/gemini-3-flash-preview" to (0.5 to 3.0),
        "google/gemini-3-pro-image" to (2.0 to 12.0),
        "google/gemini-3-pro-image-preview" to (2.0 to 12.0),
        "google/gemini-3.1-flash-image" to (0.5 to 3.0),
        "google/gemini-3.1-flash-image-preview" to (0.5 to 3.0),
        "google/gemini-3.1-flash-lite" to (0.25 to 1.5),
        "google/gemini-3.1-flash-lite-image" to (0.25 to 1.5),
        "google/gemini-3.1-flash-lite-preview" to (0.25 to 1.5),
        "google/gemini-3.1-pro-preview" to (2.0 to 12.0),
        "google/gemini-3.1-pro-preview-customtools" to (2.0 to 12.0),
        "google/gemini-3.5-flash" to (1.5 to 9.0),
        "google/gemini-3.5-flash-lite" to (0.3 to 2.5),
        "google/gemini-3.6-flash" to (0.75 to 3.75),
        "google/gemini-3.7-flash" to (0.75 to 3.75),
        "google/gemini-3.8-flash" to (0.75 to 3.75),
        "google/gemini-4-argon" to (2.0 to 10.0),
        "google/gemini-nano-banana-2.1" to (1.5 to 7.5),
        "google/gemma-2-27b-it" to (0.65 to 0.65),
        "google/gemma-3-12b-it" to (0.05 to 0.15),
        "google/gemma-3-27b-it" to (0.08 to 0.45),
        "google/gemma-3-4b-it" to (0.05 to 0.1),
        "google/gemma-4-26b-a4b-it" to (0.09 to 0.3),
        "google/gemma-4-31b-it" to (0.2 to 0.4),
        "mistralai/codestral-2508" to (0.3 to 0.9),
        "mistralai/devstral-2512" to (0.4 to 2.0),
        "mistralai/ministral-14b-2512" to (0.2 to 0.2),
        "mistralai/ministral-3b-2512" to (0.1 to 0.1),
        "mistralai/ministral-8b-2512" to (0.15 to 0.15),
        "mistralai/mistral-large" to (2.0 to 6.0),
        "mistralai/mistral-large-2407" to (2.0 to 6.0),
        "mistralai/mistral-large-2512" to (0.5 to 1.5),
        "mistralai/mistral-large-4-0" to (0.68 to 2.09),
        "mistralai/mistral-medium-3" to (0.4 to 2.0),
        "mistralai/mistral-medium-3-5" to (1.5 to 7.5),
        "mistralai/mistral-medium-3.1" to (0.4 to 2.0),
        "mistralai/mistral-nemo" to (0.03 to 0.03),
        "mistralai/mistral-saba" to (0.2 to 0.6),
        "mistralai/mistral-small-24b-instruct-2501" to (0.05 to 0.08),
        "mistralai/mistral-small-2603" to (0.15 to 0.6),
        "mistralai/mistral-small-3.1-24b-instruct" to (0.35 to 0.56),
        "mistralai/mistral-small-3.2-24b-instruct" to (0.09 to 0.25),
        "mistralai/mixtral-8x22b-instruct" to (2.0 to 6.0),
        "mistralai/voxtral-small-24b-2507" to (0.1 to 0.3),
        "openai/gpt-3.5-turbo" to (0.5 to 1.5),
        "openai/gpt-3.5-turbo-0613" to (1.0 to 2.0),
        "openai/gpt-3.5-turbo-16k" to (3.0 to 4.0),
        "openai/gpt-3.5-turbo-instruct" to (1.5 to 2.0),
        "openai/gpt-4" to (30.0 to 60.0),
        "openai/gpt-4-turbo" to (10.0 to 30.0),
        "openai/gpt-4.1" to (2.0 to 8.0),
        "openai/gpt-4.1-mini" to (0.4 to 1.6),
        "openai/gpt-4.1-nano" to (0.1 to 0.4),
        "openai/gpt-4o" to (2.5 to 10.0),
        "openai/gpt-4o-2024-05-13" to (5.0 to 15.0),
        "openai/gpt-4o-2024-08-06" to (2.5 to 10.0),
        "openai/gpt-4o-2024-11-20" to (2.5 to 10.0),
        "openai/gpt-4o-mini" to (0.15 to 0.6),
        "openai/gpt-4o-mini-2024-07-18" to (0.15 to 0.6),
        "openai/gpt-5" to (1.25 to 10.0),
        "openai/gpt-5-image" to (10.0 to 10.0),
        "openai/gpt-5-image-mini" to (2.5 to 2.0),
        "openai/gpt-5-mini" to (0.25 to 2.0),
        "openai/gpt-5-nano" to (0.05 to 0.4),
        "openai/gpt-5-pro" to (15.0 to 120.0),
        "openai/gpt-5.1" to (1.25 to 10.0),
        "openai/gpt-5.1-codex" to (1.25 to 10.0),
        "openai/gpt-5.1-codex-max" to (1.25 to 10.0),
        "openai/gpt-5.1-codex-mini" to (0.25 to 2.0),
        "openai/gpt-5.2" to (1.75 to 14.0),
        "openai/gpt-5.2-chat" to (1.75 to 14.0),
        "openai/gpt-5.2-codex" to (1.75 to 14.0),
        "openai/gpt-5.2-pro" to (21.0 to 168.0),
        "openai/gpt-5.3-codex" to (1.75 to 14.0),
        "openai/gpt-5.4" to (2.5 to 15.0),
        "openai/gpt-5.4-image-2" to (8.0 to 15.0),
        "openai/gpt-5.4-mini" to (0.75 to 4.5),
        "openai/gpt-5.4-nano" to (0.2 to 1.25),
        "openai/gpt-5.4-pro" to (30.0 to 180.0),
        "openai/gpt-5.5" to (5.0 to 30.0),
        "openai/gpt-5.5-pro" to (30.0 to 180.0),
        "openai/gpt-5.6-luna" to (0.2 to 1.2),
        "openai/gpt-5.6-luna-pro" to (0.2 to 1.2),
        "openai/gpt-5.6-sol" to (4.0 to 20.0),
        "openai/gpt-5.6-sol-pro" to (4.0 to 20.0),
        "openai/gpt-5.6-terra" to (2.0 to 12.0),
        "openai/gpt-5.6-terra-pro" to (2.0 to 12.0),
        "openai/gpt-6-astra" to (10.0 to 50.0),
        "openai/gpt-6-astra-pro" to (10.0 to 50.0),
        "openai/gpt-6-luna" to (0.1 to 0.5),
        "openai/gpt-6-luna-pro" to (0.1 to 0.5),
        "openai/gpt-6-sol" to (2.0 to 10.0),
        "openai/gpt-6-sol-pro" to (2.0 to 10.0),
        "openai/gpt-6.1-sol" to (2.0 to 10.0),
        "openai/gpt-6.1-sol-pro" to (2.0 to 10.0),
        "openai/gpt-audio" to (2.5 to 10.0),
        "openai/gpt-audio-mini" to (0.6 to 2.4),
        "openai/gpt-chat-latest" to (5.0 to 30.0),
        "openai/gpt-oss-120b" to (0.15 to 0.6),
        "openai/gpt-oss-20b" to (0.02 to 0.09),
        "openai/gpt-oss-safeguard-20b" to (0.08 to 0.3),
        "openai/o1" to (15.0 to 60.0),
        "openai/o1-pro" to (150.0 to 600.0),
        "openai/o3" to (2.0 to 8.0),
        "openai/o3-mini" to (1.1 to 4.4),
        "openai/o3-mini-high" to (1.1 to 4.4),
        "openai/o3-pro" to (20.0 to 80.0),
        "openai/o4-mini" to (1.1 to 4.4),
        "openai/o4-mini-high" to (1.1 to 4.4),
    )

    /**
     * The rendered price for a model on a known vendor host, or "" when the host
     * is not first-party or the id is not an exact match.
     *
     * EXACT MATCH ONLY. A near miss would put one model's price on another, and
     * a wrong number is worse than a blank — the blank at least says "unknown".
     */
    fun price(host: String?, modelID: String): String {
        val namespace = hosts[host?.lowercase() ?: return ""] ?: return ""
        val key = "$namespace/" + normalise(modelID)
        val (input, output) = prices[key] ?: return ""
        return ModelCatalog.priceLabel(input, output)
    }

    /**
     * Two vendor spellings normalised, because both name the same model:
     * Google prefixes its ids with "models/", and Anthropic writes a version
     * with dashes ("claude-sonnet-4-5") where the table holds dots.
     */
    private fun normalise(modelID: String): String {
        var id = modelID.lowercase().removePrefix("models/")
        id = Regex("-(\\d+)-(\\d+)$").replace(id) { m -> "-" + m.groupValues[1] + "." + m.groupValues[2] }
        return id
    }
}
