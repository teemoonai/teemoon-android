// ports: teemoon/Providers/ModelCatalog.swift @ v1.0.4
package ai.teemoon.providers

import java.time.Instant
import java.util.Locale

/**
 * Catalog helpers that are NOT provider-specific: turning a model id into a
 * slug / vendor label and formatting price + context for a row. Every adapter
 * (near.ai, xAI, Fireworks, Ollama, generic OpenAI-compat) builds [KnownModel]s
 * through these, so one endpoint's rows can't drift in shape from another's.
 *
 * Ordering left with the curated snapshot it ordered by: there is no compiled
 * model list any more, so `sortByVendorFamily` and `curatedMatch` had nothing to
 * read. Each source orders its own rows now — near.ai by tier then recency,
 * OpenRouter by the catalogue's own `created`.
 *
 * [NearAIModelCatalog] keeps its own names for these (it was here first) and
 * forwards; new adapters call [ModelCatalog] directly.
 */
object ModelCatalog {

    // MARK: - Identity

    /**
     * Vendor-agnostic model key: the last path component, lowercased, minus a
     * trailing precision/quant suffix — so a curated `glm-5.1-fp8` and a live
     * `glm-5.1` share ONE slug (same model for metadata + ordering).
     */
    fun slug(id: String): String {
        var s = displayName(id).lowercase()
        for (suffix in listOf("-fp8", "-fp16", "-bf16", "-int8", "-int4", "-awq", "-gptq", "-nvfp4")) {
            if (s.endsWith(suffix)) { s = s.dropLast(suffix.length); break }
        }
        return s
    }

    /** The last path component of an id — the display name for models no curated list covers. */
    fun displayName(id: String): String =
        id.split('/').lastOrNull { it.isNotEmpty() } ?: id

    private val buildWords = setOf(
        "fp8", "fp16", "fp32", "bf16", "f16", "f32", "int8", "int4",
        "awq", "gptq", "nvfp4", "gguf", "ggml", "safetensors",
        "qat", "instruct", "chat", "it", "hf", "ud",
        "k_m", "k_s", "k_l", "k_xl",
    )

    /**
     * File extensions that appear when a server is pointed straight at a weights
     * file (llama.cpp serving a `.gguf`), so the "model id" is really a filename.
     */
    private val weightExtensions = listOf(".gguf", ".ggml", ".safetensors", ".bin", ".pt")

    private fun isBuildWord(word: String): Boolean {
        if (word in buildWords) return true
        val rest = when {
            word.startsWith("iq") -> word.drop(2)
            word.startsWith("q") -> word.drop(1)
            else -> return false
        }
        return rest.firstOrNull()?.isDigit() == true
    }

    /**
     * The shortest name that still identifies the model, for space-starved
     * surfaces like the chat title bar: last path segment, lowercased, stripped
     * of any weights-file extension, then stripped of every trailing build word.
     * A trailing `:tag` keeps whatever of it names a *size* — `gemma4:e2b` and
     * `gemma4:27b` are different models to the user, where `:UD-Q4_K_XL` is the
     * same model packed smaller. Build words are peeled out of the tag by the
     * same rule as the base, so a tag that mixes the two keeps only the part
     * that identifies the model.
     *
     *     zai-org/GLM-5.1-FP8                        → glm-5.1
     *     unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL → gemma-4-e4b
     *     ternary-bonsai-8b-q2_0.gguf                → ternary-bonsai-8b
     *     gemma4:e2b-it-qat                          → gemma4:e2b
     *     gemma4:e2b                                 → gemma4:e2b
     */
    fun compactName(id: String): String {
        var base = displayName(id)
        var tag: String? = null
        val colon = base.indexOf(':')
        if (colon >= 0) {
            tag = base.substring(colon + 1).lowercase()
            base = base.substring(0, colon)
        }
        base = base.lowercase()
        for (ext in weightExtensions) {
            if (base.endsWith(ext)) {
                base = base.dropLast(ext.length)
                break
            }
        }
        // The base must keep at least one word — peeling it to nothing would
        // leave no model at all. A tag may peel away entirely: a tag made only
        // of build words (`ud-q4_k_xl`) says nothing about which model this is.
        base = peelingBuildWords(base, keepingAtLeastOneWord = true)
        if (base.isEmpty()) return displayName(id).lowercase()
        if (tag != null) {
            val kept = peelingBuildWords(tag, keepingAtLeastOneWord = false)
            if (kept.isNotEmpty()) return "$base:$kept"
        }
        return base
    }

    /**
     * Drops build words from the end of a dash-separated name. Peeling stops at
     * the first word that isn't one, so size words ("8b", "27b", "e4b") — which
     * DO identify the model — survive anything trailing them.
     */
    private fun peelingBuildWords(s: String, keepingAtLeastOneWord: Boolean): String {
        val words = s.split("-").toMutableList()
        val floor = if (keepingAtLeastOneWord) 1 else 0
        while (words.size > floor && isBuildWord(words.last())) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString("-")
    }

    /**
     * What the chat title bar shows for a provider: the compacted model — unless
     * the model id is just an echo of the provider's own name, as with Brave
     * Answers, which has no `/models` endpoint and ships `model: "brave"`. There
     * the provider name says strictly more.
     *
     * Matches on **prefix**, never containment. A self-hosted provider's
     * auto-label is "<host> <model>", so it contains the model by construction —
     * substituting the whole label there is both longer and less informative,
     * and long enough to get dropped from the bar entirely.
     */
    fun titleLabel(model: String, providerName: String): String? {
        if (model.isEmpty()) return null
        val compact = compactName(model)
        val name = providerName.lowercase()
        return if (name.startsWith(compact)) name else compact
    }

    /**
     * Fireworks writes display names in three styles. The leading vendor word
     * is dropped ONLY when what remains still names the family.
     */
    fun productName(raw: String, vendor: String): String {
        val spaced = raw.replace('_', ' ').replace('-', ' ')
        var words = spaced.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) return raw
        if (words[0].equals(vendor, ignoreCase = true)) {
            val rest = words.drop(1)
            if (rest.isNotEmpty() && familyVendor(rest.joinToString("-")) != null) {
                words = rest.toMutableList()
            }
        }
        while (words.size > 1 && isBuildWord(words.last().lowercase())) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    /**
     * Model families that aren't conversational — excluded from every chat
     * model browser (embeddings, rerankers, speech, image, PII filter). Media
     * families an adapter can identify precisely (xAI's `grok-imagine-*`, which
     * carries no text-token price) are filtered there, not by keyword here.
     */
    private val nonChatKeywords = listOf(
        "embedding", "embed", "reranker", "rerank", "retriever", "whisper",
        "privacy-filter", "flux", "reward", "guard", "safety", "safeguard",
        "topic-control", "content-safety", "detector", "translate", "nvclip",
        "deplot", "kosmos", "neva", "vila", "fuyu", "calibration", "diffusion",
    )

    fun isNonChat(id: String): Boolean {
        val lower = id.lowercase()
        return nonChatKeywords.any { lower.contains(it) }
    }

    // MARK: - Vendor

    /** Namespace → display vendor, for ids shaped `vendor/name`. */
    private val namespaceVendors = mapOf(
        "zai-org" to "Z.ai", "z-ai" to "Z.ai",
        "deepseek-ai" to "DeepSeek", "deepseek" to "DeepSeek",
        "qwen" to "Qwen", "openai" to "OpenAI", "anthropic" to "Anthropic",
        "google" to "Google", "moonshotai" to "Moonshot", "minimax" to "MiniMax",
        "meta-llama" to "Meta", "meta" to "Meta", "mistralai" to "Mistral",
        "nv-mistralai" to "Mistral", "nvidia" to "NVIDIA",
        "black-forest-labs" to "Black Forest Labs", "xai" to "xAI", "x-ai" to "xAI",
    )

    /**
     * Family keyword → vendor, for ids whose namespace can't name a vendor:
     * `grok-4.3` (xAI) and `qwen3.5:4b` (Ollama) have none at all, and
     * `accounts/fireworks/models/kimi-k2p6` has the meaningless "accounts".
     * Without this each such id became its own vendor section, because the
     * fallback title-cased the id itself ("Grok-4.3", "Accounts").
     * Ordered — the first match wins, so `gpt-oss` beats `gpt`.
     */
    private val familyVendors: List<Pair<String, String>> = listOf(
        "grok" to "xAI",
        "gpt-oss" to "OpenAI",
        "claude" to "Anthropic",
        "gemma" to "Google", "gemini" to "Google",
        "deepseek" to "DeepSeek",
        "qwen" to "Qwen", "qwq" to "Qwen",
        "glm" to "Z.ai",
        "kimi" to "Moonshot",
        "minimax" to "MiniMax",
        "nemotron" to "NVIDIA",
        "llama" to "Meta",
        "mistral" to "Mistral", "mixtral" to "Mistral", "magistral" to "Mistral",
        "devstral" to "Mistral", "codestral" to "Mistral",
        "phi-" to "Microsoft", "phi3" to "Microsoft", "phi4" to "Microsoft",
        "command-" to "Cohere",
        "granite" to "IBM",
        "olmo" to "AI2",
        "smollm" to "Hugging Face",
        "flux" to "Black Forest Labs",
        "gpt-" to "OpenAI", "o3" to "OpenAI", "o4" to "OpenAI",
    )

    /**
     * Vendor inferred from the model NAME's family, ignoring any namespace.
     * null when no family is recognized. Use this only where the namespace is
     * known NOT to name a vendor (see [vendorLabel]).
     */
    fun familyVendor(id: String): String? {
        val lower = id.lowercase()
        return familyVendors.firstOrNull { lower.contains(it.first) }?.second
    }

    /**
     * Display vendor for a model id — the section header in the model browser.
     *
     * The namespace is AUTHORITATIVE when the id carries one, and the family
     * table is consulted only for ids without: `differentVendor` compares these
     * labels to catch a node serving something other than what was requested
     * (`QuantTrio/GLM-5.1-AWQ` vs `zai-org/GLM-5.1-FP8` must stay a mismatch),
     * so a family keyword must never collapse two distinct namespaces.
     */
    fun vendorLabel(id: String): String {
        if (!id.contains('/')) return familyVendor(id) ?: "Other"
        // OpenRouter marks an alias id with a leading "~" ("~openai/gpt-sol-latest").
        // That is a marker, not part of the vendor's name, and it sorted a second
        // "~openai" section under the real one.
        val namespace = (id.split('/').firstOrNull { it.isNotEmpty() } ?: "")
            .lowercase().trim('~')
        namespaceVendors[namespace]?.let { return it }
        if (namespace.isEmpty()) return "Other"
        return namespace.take(1).uppercase() + namespace.drop(1)
    }

    // MARK: - Row formatting

    /** "$1/$5" (whole) or "$1.40/$4.40" from per-1M-token input/output prices. */
    fun priceLabel(inputPerMillion: Double?, outputPerMillion: Double?): String {
        val i = inputPerMillion ?: return ""
        val o = outputPerMillion ?: return ""
        // Always two decimals (iOS fbedb79): the generated snapshots and the curated tables print "$2.00/$6.00", and
        // one model must read the same everywhere it appears. Android printed "$2/$6" by decision until conformance
        // kit 0.5.0 pinned iOS's form; the user chose to match iOS (2026-10-02).
        fun fmt(v: Double) = String.format(Locale.US, "$%.2f", v)
        return "${fmt(i)}/${fmt(o)}"
    }

    /**
     * "128k" / "1m" from a LOCAL model's context length. GGUF context sizes are
     * powers of two, so this is 1024-based: 131072 reads as "128k", which is what
     * the model card says, not the "131k" a decimal label would print. Cloud
     * catalogues quote round decimal numbers and use [contextLabel] instead.
     */
    fun localContextLabel(n: Int?): String {
        if (n == null || n <= 0) return ""
        if (n >= 1_048_576) return "${n / 1_048_576}m"
        if (n >= 1024) return "${n / 1024}k"
        return "$n"
    }

    /**
     * "$2.50", or more digits when the rate is sub-cent.
     *
     * A cache read of $0.0003 rounded to two places prints "$0.00", which reads
     * as free. These are the per-dimension rates on a model card, where the
     * sub-cent ones are exactly the interesting ones.
     */
    fun rateLabel(value: Double): String {
        val cents = Math.round(value * 100) / 100.0
        if (Math.abs(value - cents) < 0.0000001) return String.format(Locale.US, "$%.2f", cents)
        return String.format(Locale.US, "$%.4f", value).trimEnd('0')
    }

    /**
     * "262k" / "1M" / "1.1M" from a token count. Empty when unknown.
     * Rounds to one decimal FIRST: a real 1048576-token window is "1M", not the
     * "1.0M" a raw whole-number test produces (near.ai quotes a round 1000000,
     * so only Fireworks' power-of-two windows ever hit this).
     */
    fun contextLabel(n: Int?): String {
        if (n == null || n <= 0) return ""
        if (n >= 1_000_000) {
            val m = Math.rint(n.toDouble() / 100_000) / 10
            return if (m == Math.rint(m)) "${m.toInt()}M" else String.format(Locale.US, "%.1fM", m)
        }
        return "${n / 1000}k"
    }

    // MARK: - Recency

    /**
     * How recently a model must have been published to wear the "new" badge,
     * in seconds. Matches the window the catalog generator uses for the near.ai block.
     */
    const val NEW_BADGE_WINDOW_SECONDS: Long = 45L * 24 * 60 * 60

    /**
     * Whether a model published at [created] still counts as new. Derived from
     * the catalogue rather than hand-maintained, so a badge can't outlive its
     * model — `isNew: true` left in a snapshot is invisible rot.
     */
    fun isNew(created: Instant?, now: Instant = Instant.now()): Boolean {
        if (created == null) return false
        val age = now.epochSecond - created.epochSecond
        return age >= 0 && age <= NEW_BADGE_WINDOW_SECONDS
    }

    fun isNew(createdEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean =
        isNew(
            created = createdEpochMs?.let { Instant.ofEpochMilli(it) },
            now = Instant.ofEpochMilli(nowMs),
        )

    /**
     * ISO-8601 timestamp → Instant, with or without fractional seconds (Fireworks
     * sends "2026-04-24T04:09:07.488210Z").
     */
    fun parseISO8601(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
    }

    // MARK: - Live catalogue routing

    /**
     * Endpoint → its richest catalogue. **The only routing table**, because there
     * are two doors onto a provider's model list and each used to carry its own
     * copy: Settings' add/edit screen (`EndpointProbe`) and the Where sheet's
     * `browse <provider>` row.
     *
     * The copies drifted, twice, in the same direction — the sheet rendered a
     * curated KnownModel snapshot while Settings fetched live. near.ai was
     * fixed in place on 2026-07-29 (it was badging models published 55 days
     * earlier and quoting snapshot prices), and grok and fireworks were left
     * behind by that fix, so the sheet still showed Grok rows with `price: ""`
     * and no context window at all while the same key rendered a full row one
     * screen away. A second table is a table that disagrees; this is the first
     * one, and both doors now call it.
     *
     * [EndpointModelCatalog.Source.GENERIC] belongs here too. A custom cloud
     * endpoint has no curated snapshot, so routing it through the probe is the
     * difference between live bare ids and an empty browse sheet.
     *
     * Returns the same ProbeResult as every adapter — a failure is a failure,
     * so the caller can keep whatever it was already showing rather than
     * replacing a working list with an error. [fetch] is the module's shared
     * HTTP seam; production is OkHttp.
     */
    suspend fun liveCatalog(
        source: EndpointModelCatalog.Source,
        baseUrl: String,
        apiKey: String,
        authHeaderName: String? = null,
        /** Told what the near.ai fetch threw before it is folded into the fallback, so an offline verdict names its cause. */
        warn: (String) -> Unit = {},
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> =
            EndpointModelCatalog::liveFetch,
    ): EndpointModelCatalog.ProbeResult {
        suspend fun genericProbe() = EndpointModelCatalog.probe(
            baseUrl = baseUrl, authHeaderName = authHeaderName, apiKey = apiKey, fetch = fetch)
        return when (source) {
            EndpointModelCatalog.Source.NEAR_AI -> {
                // near.ai's fetch records confidentiality tiers as a side effect,
                // which is why it isn't an adapter. A miss is a FAILURE, never the
                // generic probe: bare OpenAI-shaped rows carry no hosts, prices or
                // tiers, and recording them would replace the last good list with a
                // worse one. The caller falls back to the saved answer.
                val live = NearAIModelCatalog.fetchLive(apiKey = apiKey, warn = warn, fetch = { url, bearer ->
                    val headers = bearer?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                    runCatching { fetch(url, headers, NEAR_AI_MODELS_TIMEOUT_MS) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; warn("[catalog] near.ai /v1/models: ${it::class.simpleName}: ${it.message}") }
                        .getOrNull()
                        ?.let { (status, body) -> NearAIModelCatalog.HttpReply(status, body.decodeToString()) }
                })
                if (live != null && live.isNotEmpty()) EndpointModelCatalog.ProbeResult.Connected(live)
                else EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.OFFLINE)
            }
            EndpointModelCatalog.Source.XAI -> XAIAdapter.listModels(baseUrl, apiKey, fetch)
            EndpointModelCatalog.Source.FIREWORKS -> FireworksAdapter.listModels(baseUrl, apiKey, fetch)
            EndpointModelCatalog.Source.OPEN_ROUTER -> publicList(apiKey) {
                OpenRouterAdapter.listModels(baseUrl, it, fetch)
            }
            EndpointModelCatalog.Source.NVIDIA -> publicList(apiKey) {
                NVIDIAAdapter.listModels(baseUrl, it, fetch)
            }
            EndpointModelCatalog.Source.GENERIC -> genericProbe()
        }
    }

    /**
     * OpenRouter lists without a credential. A STORED KEY THE HOST REJECTS MUST
     * NOT BLANK THE PICKER: retry once without it, so a wrong key costs the user
     * their key screen and not their catalogue.
     */
    private suspend fun publicList(
        apiKey: String,
        list: suspend (String) -> EndpointModelCatalog.ProbeResult,
    ): EndpointModelCatalog.ProbeResult {
        val result = list(apiKey)
        val unauthorized = result is EndpointModelCatalog.ProbeResult.Failed &&
            result.kind == EndpointModelCatalog.FailureKind.UNAUTHORIZED
        if (!unauthorized || apiKey.trim().isEmpty()) return result
        return list("")
    }

    /** iOS gives the near.ai `/v1/models` request 10 seconds. */
    private const val NEAR_AI_MODELS_TIMEOUT_MS = 10_000L
}
