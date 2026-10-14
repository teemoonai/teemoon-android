// ports: teemoon/Providers/Adapters/OpenRouterAdapter.swift @ v1.0.4 (the catalogue: handles,
//        listModels, buildModels, row, priceLabel, perMillion, features, extraCosts, productName
//        and the wire contract), and the model-card `offers` half (offersURL, listOffers, buildOffers, zdrKey,
//        zeroDataRetentionOffers, OffersResponse) @ 835d7c8.
package ai.teemoon.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * OpenRouter's model catalogue, contained in one file.
 *
 * Inference is OpenAI-compatible under `https://openrouter.ai/api/v1`, and the
 * `/api/v1/models` list is RICHER than the OpenAI shape: per-token prices, the
 * context window, modalities, supported parameters, a description and the
 * Hugging Face id. It is PUBLIC — no key needed to read it — which is why a
 * keyless OpenRouter row can show a live model count the way near.ai's does.
 *
 * Why native rather than the generic probe: the probe keeps ids only, so every
 * row would render with no price and no context. OpenRouter is the one catalogue
 * that prices EVERY model it lists, including the frontier vendors, which is
 * also why it is the source of ListPriceSnapshot for custom first-party endpoints.
 *
 * Wire contract — https://openrouter.ai/docs/api-reference/list-available-models
 *   GET /api/v1/models — prices are USD per TOKEN as decimal strings
 *   ("0.0000025" = $2.50 per 1M); "0" on both means a free model; a NEGATIVE
 *   value means dynamic (the auto router) and is not a price.
 */
object OpenRouterAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    /** Hosts this adapter serves. */
    fun handles(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "openrouter.ai" || h.endsWith(".openrouter.ai")
    }

    /**
     * Lists the chat models, doubling as the connection test. The list is
     * public, so the key is sent only when there is one — a keyless probe still
     * lists, and a bad key is only discovered at the first request.
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        // NOT defaulted: requiring the seam means no call site reaches the
        // network by accident. (The shared live implementation is
        // EndpointModelCatalog.liveFetch; ModelCatalog.liveCatalog passes it.)
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
        now: Instant = Instant.now(),
    ): EndpointModelCatalog.ProbeResult {
        val url = baseUrl.trimEnd('/') + "/models"
        val list = when (val r = EndpointModelCatalog.fetchList(url, apiKey, fetch = fetch) { decode(it) }) {
            is EndpointModelCatalog.Fetched.Failure -> return EndpointModelCatalog.ProbeResult.Failed(r.kind)
            is EndpointModelCatalog.Fetched.Success -> r.value
        }
        val models = buildModels(list.data, now)
        if (models.isEmpty()) return EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        return EndpointModelCatalog.ProbeResult.Connected(models)
    }

    fun decode(body: String): ModelsResponse = json.decodeFromString(ModelsResponse.serializer(), body)

    /** Pure (no network) so it is unit-testable against a captured payload. Newest first. */
    fun buildModels(records: List<ModelsResponse.Model>, now: Instant = Instant.now()): List<KnownModel> {
        val seen = HashSet<String>()
        return records
            .filter { it.isChatModel }
            .sortedByDescending { it.created ?: 0 }
            .mapNotNull { if (seen.add(it.id.lowercase())) row(it, now) else null }
    }

    fun row(record: ModelsResponse.Model, now: Instant): KnownModel {
        val vendor = ModelCatalog.vendorLabel(record.id)
        val caps = HashSet<ModelCapability>()
        if (record.supportedParameters?.contains("tools") == true) caps += ModelCapability.TOOLS
        if (record.architecture?.inputModalities?.contains("image") == true) caps += ModelCapability.VISION
        val (price, isFree) = priceLabel(record.pricing)
        val huggingFace = record.huggingFaceId?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://huggingface.co/$it" }
        return KnownModel(
            id = record.id,
            displayName = productName(record.name, record.id, vendor),
            vendor = vendor,
            price = price,
            contextWindow = ModelCatalog.contextLabel(record.contextLength),
            isNew = ModelCatalog.isNew(record.createdInstant, now),
            created = record.createdInstant,
            isFree = isFree,
            extraCosts = extraCosts(record.pricing),
            knowledgeCutoff = record.knowledgeCutoff?.trim()?.takeIf { it.isNotEmpty() },
            reasoningEfforts = record.reasoning?.supportedEfforts ?: emptyList(),
            // The catalogue naming a Hugging Face repo IS the published-weights claim.
            openWeights = huggingFace != null,
            aliasOf = record.aliasTarget?.slug?.trim()?.takeIf { it.isNotEmpty() },
            capabilities = caps,
            summary = record.description?.trim()?.takeIf { it.isNotEmpty() },
            features = features(record),
            samplingParameters = record.supportedParameters ?: emptyList(),
            maxOutputTokens = record.topProvider?.maxCompletionTokens,
            inputModalities = record.architecture?.inputModalities ?: emptyList(),
            huggingFaceURL = huggingFace,
            // The model page is the id verbatim under the site root.
            modelPageURL = "https://openrouter.ai/" + record.id,
            deprecationDate = ModelCatalog.parseISO8601(record.expirationDate),
        )
    }

    /**
     * "$2.50/$15" from per-token decimal strings, or ("", true) for a model
     * OpenRouter serves at zero. A NEGATIVE or missing rate is not a price — the
     * auto router quotes -1 — and renders blank rather than "$-1".
     */
    fun priceLabel(p: ModelsResponse.Model.Pricing?): Pair<String, Boolean> {
        val input = perMillion(p?.prompt) ?: return "" to false
        val output = perMillion(p?.completion) ?: return "" to false
        if (input < 0 || output < 0) return "" to false
        if (input == 0.0 && output == 0.0) return "" to true
        return ModelCatalog.priceLabel(input, output) to false
    }

    /**
     * "0.0000025" (USD per token) → 2.5 (USD per 1M tokens). Rounded to the cent
     * so binary float noise cannot print "$2.4999".
     */
    fun perMillion(perToken: String?): Double? {
        val v = perToken?.toDoubleOrNull() ?: return null
        return Math.round(v * 1_000_000 * 100) / 100.0
    }

    /**
     * Capability chips from the public card: what the model can be asked to DO.
     * Sampling knobs (temperature, seed) are not features and stay on
     * samplingParameters.
     *
     * Nothing is invented: `is_moderated: false` is silence, not a badge, and
     * mandatory reasoning says so because the payload does.
     */
    fun features(record: ModelsResponse.Model): List<String> {
        val out = ArrayList<String>()
        val params = (record.supportedParameters ?: emptyList()).toSet()
        if ("tools" in params) out += "tools"
        if ("reasoning" in params || record.reasoning != null) {
            out += if (record.reasoning?.mandatory == true) "reasoning (required)" else "reasoning"
        }
        if ("structured_outputs" in params) out += "structured outputs"
        if ("response_format" in params) out += "response format"
        if (record.topProvider?.isModerated == true) out += "moderated"
        return out
    }

    /**
     * Everything priced besides prompt and completion. Zero, negative and
     * missing rates are dropped — the same rule the row's price follows.
     *
     * A time-window override needs a clock to be true, so it is skipped; a
     * min_prompt_tokens override is a long-context rate that always applies past
     * that length, and is shown.
     */
    fun extraCosts(pricing: ModelsResponse.Model.Pricing?): List<KnownModel.ExtraCost> {
        if (pricing == null) return emptyList()
        val rows = ArrayList<KnownModel.ExtraCost>()
        fun perM(raw: String?, label: String) {
            val v = perMillion(raw) ?: return
            if (v > 0) rows += KnownModel.ExtraCost(label, perMillion = v)
        }
        fun perCall(raw: String?, label: String) {
            val v = raw?.toDoubleOrNull() ?: return
            if (v > 0) rows += KnownModel.ExtraCost(label, perCall = v)
        }
        perM(pricing.inputCacheRead, "cache read")
        perM(pricing.inputCacheWrite, "cache write")
        perM(pricing.internalReasoning, "reasoning tokens")
        perM(pricing.image, "image input")
        perM(pricing.imageOutput, "image output")
        perM(pricing.audio, "audio input")
        perCall(pricing.webSearch, "web search")
        perCall(pricing.request, "per request")
        for (o in pricing.overrides ?: emptyList()) {
            if (o.utcStart != null || o.utcEnd != null || o.utcDays != null) continue
            val from = o.minPromptTokens ?: continue
            val input = perMillion(o.prompt ?: pricing.prompt) ?: continue
            val output = perMillion(o.completion ?: pricing.completion) ?: continue
            if (input <= 0 && output <= 0) continue
            rows += KnownModel.ExtraCost(
                "past " + ModelCatalog.contextLabel(from).lowercase(),
                perMillion = input, perMillionOutput = output)
        }
        return rows
    }

    /**
     * OpenRouter names every model "Vendor: Product" ("OpenAI: GPT-5.4"). The
     * vendor half is the section header, so it goes; the product half is then
     * read like every other catalogue name.
     */
    fun productName(raw: String?, id: String, vendor: String): String {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return ModelCatalog.displayName(id)
        val product = text.substringAfter(": ", text)
        val named = ModelCatalog.productName(product, vendor)
        return named.takeIf { it.isNotEmpty() } ?: ModelCatalog.displayName(id)
    }

    // MARK: - Wire contract

    @Serializable
    data class ModelsResponse(val data: List<Model>) {
        @Serializable
        data class Model(
            val id: String,
            @SerialName("canonical_slug") val canonicalSlug: String? = null,
            @SerialName("hugging_face_id") val huggingFaceId: String? = null,
            val name: String? = null,
            val created: Long? = null,
            val description: String? = null,
            @SerialName("context_length") val contextLength: Int? = null,
            val architecture: Architecture? = null,
            val pricing: Pricing? = null,
            @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
            @SerialName("top_provider") val topProvider: TopProvider? = null,
            @SerialName("knowledge_cutoff") val knowledgeCutoff: String? = null,
            @SerialName("alias_target") val aliasTarget: AliasTarget? = null,
            @SerialName("expiration_date") val expirationDate: String? = null,
            val reasoning: Reasoning? = null,
        ) {
            @Serializable
            data class Architecture(
                val modality: String? = null,
                @SerialName("input_modalities") val inputModalities: List<String>? = null,
                @SerialName("output_modalities") val outputModalities: List<String>? = null,
            )

            /** An alias row points at the model that actually answers. */
            @Serializable
            data class AliasTarget(val name: String? = null, val slug: String? = null)

            @Serializable
            data class TopProvider(
                @SerialName("context_length") val contextLength: Int? = null,
                @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
                @SerialName("is_moderated") val isModerated: Boolean? = null,
            )

            @Serializable
            data class Reasoning(
                val mandatory: Boolean? = null,
                @SerialName("default_enabled") val defaultEnabled: Boolean? = null,
                @SerialName("supported_efforts") val supportedEfforts: List<String>? = null,
                @SerialName("default_effort") val defaultEffort: String? = null,
            )

            /**
             * USD per TOKEN as decimal strings, except [webSearch] and [request],
             * which are per call. The two text rates make the picker row; the rest
             * are the model card's cost lines.
             */
            @Serializable
            data class Pricing(
                val prompt: String? = null,
                val completion: String? = null,
                val request: String? = null,
                val image: String? = null,
                @SerialName("image_output") val imageOutput: String? = null,
                val audio: String? = null,
                @SerialName("web_search") val webSearch: String? = null,
                @SerialName("internal_reasoning") val internalReasoning: String? = null,
                @SerialName("input_cache_read") val inputCacheRead: String? = null,
                @SerialName("input_cache_write") val inputCacheWrite: String? = null,
                val overrides: List<PricingOverride>? = null,
            )

            /**
             * A rate that replaces the headline one past a prompt length, or inside
             * a time window — which teemoon does not show, because it would read as
             * always-on.
             */
            @Serializable
            data class PricingOverride(
                @SerialName("min_prompt_tokens") val minPromptTokens: Int? = null,
                @SerialName("utc_start") val utcStart: Int? = null,
                @SerialName("utc_end") val utcEnd: Int? = null,
                @SerialName("utc_days") val utcDays: List<String>? = null,
                val prompt: String? = null,
                val completion: String? = null,
            )

            val createdInstant: Instant?
                get() = created?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }

            /**
             * Chat models only: text out, not an embedding/media family, and not a
             * `:batch` variant — batch is asynchronous and cannot answer a chat turn.
             */
            val isChatModel: Boolean
                get() {
                    if (ModelCatalog.isNonChat(id) || id.lowercase().endsWith(":batch")) return false
                    val out = architecture?.outputModalities ?: return true
                    return "text" in out
                }
        }
    }

    // MARK: - Who can serve a model

    /**
     * `GET {base}/models/{author}/{slug}/endpoints`. The id's slash has to be a
     * path separator, not an escaped one — an escaped slash 404s.
     */
    fun offersURL(modelID: String, baseUrl: String): String? {
        val parts = modelID.split("/", limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return baseUrl.trimEnd('/') + "/models/" + parts[0] + "/" + parts[1] + "/endpoints"
    }

    /**
     * Who OpenRouter can route this model to. Empty on any failure: the card
     * already carries the catalogue's own facts, and a missing table is
     * absence, not an error worth a banner.
     *
     * The key is sent because latency and throughput are null without one; the
     * list itself is public either way.
     */
    suspend fun listOffers(
        modelID: String,
        baseUrl: String,
        apiKey: String,
        authHeaderName: String? = null,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): List<ModelOffer> {
        val url = offersURL(modelID, baseUrl) ?: return emptyList()
        val response = when (val r = EndpointModelCatalog.fetchList(url, apiKey, authHeaderName, fetch) { json.decodeFromString(OffersResponse.serializer(), it) }) {
            is EndpointModelCatalog.Fetched.Failure -> return emptyList()
            is EndpointModelCatalog.Fetched.Success -> r.value
        }
        val zdr = zeroDataRetentionOffers(baseUrl, apiKey, authHeaderName, fetch)
        return buildOffers(response.data.endpoints, zdr)
    }

    /** Pure, so a captured payload pins the table without a network. */
    fun buildOffers(records: List<OffersResponse.Endpoint>, zeroDataRetention: Set<String> = emptySet()): List<ModelOffer> =
        records.mapNotNull { record ->
            val provider = record.providerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tag = record.tag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val (price, isFree) = priceLabel(record.pricing)
            val quant = record.quantization?.takeIf { it.isNotBlank() }
            ModelOffer(
                providerName = provider, tag = tag, price = price, isFree = isFree,
                contextTokens = record.contextLength, maxOutputTokens = record.maxCompletionTokens,
                // OpenRouter writes "unknown" where it means nothing; a row saying
                // "quantization: unknown" is worse than no row.
                quantization = if (quant?.lowercase() == "unknown") null else quant,
                uptimePercent = record.uptimeLast1d,
                latencyMilliseconds = record.latencyLast30m?.p50,
                throughputTokensPerSecond = record.throughputLast30m?.p50,
                isZeroDataRetention = zdrKey(record.modelID, tag) in zeroDataRetention,
                supportsImplicitCaching = record.supportsImplicitCaching == true,
            )
        }

    /** A model can appear on one provider under several tags, so the pair is the identity. */
    fun zdrKey(modelID: String?, tag: String): String = (modelID ?: "").lowercase() + "\u0000" + tag.lowercase()

    /**
     * The public zero-data-retention list, cached for the hour. A FAILURE IS NOT
     * CACHED: one blip must not hide the badge for the rest of the process, which
     * is the difference between "no badge" and "we didn't ask".
     */
    suspend fun zeroDataRetentionOffers(
        baseUrl: String,
        apiKey: String,
        authHeaderName: String? = null,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): Set<String> {
        ZeroDataRetentionList.cached()?.let { return it }
        val url = baseUrl.trimEnd('/') + "/endpoints/zdr"
        return when (val r = EndpointModelCatalog.fetchList(url, apiKey, authHeaderName, fetch) { json.decodeFromString(ZeroDataRetentionResponse.serializer(), it) }) {
            is EndpointModelCatalog.Fetched.Failure -> emptySet()
            is EndpointModelCatalog.Fetched.Success -> r.value.data.mapNotNull { rec ->
                rec.tag?.takeIf { it.isNotBlank() }?.let { zdrKey(rec.modelID, it) }
            }.toSet().also { ZeroDataRetentionList.store(it) }
        }
    }

    fun resetZeroDataRetentionCache() = ZeroDataRetentionList.reset()

    /** `GET /models/{author}/{slug}/endpoints` — `{ data: { endpoints: [...] } }`. */
    @Serializable
    data class OffersResponse(val data: Body) {
        @Serializable
        data class Body(val endpoints: List<Endpoint> = emptyList())

        @Serializable
        data class Endpoint(
            @SerialName("model_id") val modelID: String? = null,
            @SerialName("provider_name") val providerName: String? = null,
            val tag: String? = null,
            val quantization: String? = null,
            @SerialName("context_length") val contextLength: Int? = null,
            @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
            val pricing: ModelsResponse.Model.Pricing? = null,
            /** Percent over the last day. The 5m and 30m windows are null on the public list, so this is the one teemoon reads. */
            @SerialName("uptime_last_1d") val uptimeLast1d: Double? = null,
            /** Milliseconds, and null without a key (verified live on iOS 2026-09-15). */
            @SerialName("latency_last_30m") val latencyLast30m: Percentiles? = null,
            /** Tokens per second, same condition. */
            @SerialName("throughput_last_30m") val throughputLast30m: Percentiles? = null,
            @SerialName("supports_implicit_caching") val supportsImplicitCaching: Boolean? = null,
        )

        @Serializable
        data class Percentiles(val p50: Double? = null)
    }

    /** `GET /endpoints/zdr` — the same endpoint rows, for every model. */
    @Serializable
    data class ZeroDataRetentionResponse(val data: List<OffersResponse.Endpoint> = emptyList())

    /** Session cache for the zero-data-retention list: one fetch an hour, and nothing remembered about a failure. */
    private object ZeroDataRetentionList {
        private var keys: Set<String>? = null
        private var storedAtMs = 0L
        private const val TTL_MS = 3_600_000L
        @Synchronized fun cached(): Set<String>? = keys?.takeIf { System.currentTimeMillis() - storedAtMs < TTL_MS }
        @Synchronized fun store(k: Set<String>) { keys = k; storedAtMs = System.currentTimeMillis() }
        @Synchronized fun reset() { keys = null; storedAtMs = 0L }
    }
}
