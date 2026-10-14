// ports: teemoon/Providers/Adapters/FireworksAdapter.swift @ v1.0.4 (buildModels, listModels,
//        control-plane paging, generic-probe fallback)
// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (fireworksPrices — iOS 1.0.4's table, kit 0.5.0; ember-1 and
//        deepseek-v4p1-flash's rate from the pricing page, 2026-10-02, ahead of iOS — tools/fireworks_prices.py)
package ai.teemoon.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.time.Instant

/**
 * Fireworks AI model catalogue. Inference is OpenAI-compat at
 * `https://api.fireworks.ai/inference/v1`, but the model list teemoon needs
 * lives on the control plane at
 * `https://api.fireworks.ai/v1/accounts/fireworks/models`.
 *
 * `/inference/v1/models` under-reports. The control plane carries displayName,
 * contextLength, supportsTools, supportsImageInput, state and deprecationDate.
 * Fireworks publishes no price in either API, so rates come from the shipped
 * table.
 */
object FireworksAdapter {

    const val catalogAccount = "fireworks"
    const val LIST_TIMEOUT_MS = 15_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun handles(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "fireworks.ai" || h.endsWith(".fireworks.ai")
    }

    /**
     * Control-plane base derived from the inference base URL: the same host,
     * with `/inference` dropped — `…/inference/v1` → `…/v1`.
     */
    fun controlPlaneBase(from: String): String? {
        val u = runCatching { URI(from) }.getOrNull() ?: return null
        return URI(
            u.scheme, u.userInfo, u.host, u.port,
            "/v1/accounts/$catalogAccount/models", null, null,
        ).toString()
    }

    /**
     * Lists the serverless chat models, doubling as the connection test. Falls
     * back to the generic `/models` probe when the control plane refuses (an
     * account-scoped key, or an API change) so the user still gets a list.
     *
     * [fetch] is injected. Production is OkHttp with an explicit 15 s timeout.
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> =
            EndpointModelCatalog::liveFetch,
    ): EndpointModelCatalog.ProbeResult {
        val base = controlPlaneBase(baseUrl)
            ?: return EndpointModelCatalog.probe(baseUrl, apiKey = apiKey, fetch = fetch)
        val records = ArrayList<ModelsResponse.Model>()
        var pageToken: String? = null
        var pagesFetched = 0
        do {
            when (val page = fetchPage(base, pageToken, apiKey, fetch)) {
                is PageResult.Failed -> {
                    // Control plane refused — an account-scoped key, or an API change.
                    return EndpointModelCatalog.probe(baseUrl, apiKey = apiKey, fetch = fetch)
                }
                is PageResult.Ok -> {
                    records.addAll(page.body.models)
                    pageToken = page.body.nextPageToken?.takeIf { it.isNotEmpty() }
                    pagesFetched += 1
                }
            }
            // The serverless set is small (one page today); the cap only stops a
            // malformed nextPageToken from looping.
        } while (pageToken != null && pagesFetched < 5)

        val models = buildModels(records)
        if (models.isEmpty()) {
            return EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        }
        return EndpointModelCatalog.ProbeResult.Connected(models)
    }

    private sealed class PageResult {
        data class Ok(val body: ModelsResponse) : PageResult()
        data class Failed(val kind: EndpointModelCatalog.FailureKind) : PageResult()
    }

    private suspend fun fetchPage(
        base: String,
        pageToken: String?,
        apiKey: String,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): PageResult {
        val url = try {
            val b = base.toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "200")
                // CEL filter over snake_case fields — camelCase is rejected with 400.
                .addQueryParameter("filter", "supports_serverless=true")
            if (pageToken != null) b.addQueryParameter("pageToken", pageToken)
            b.build().toString()
        } catch (_: Exception) {
            return PageResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        }
        val key = apiKey.trim()
        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val (status, data) = try {
            fetch(url, headers, LIST_TIMEOUT_MS)
        } catch (e: Exception) {
            return PageResult.Failed(EndpointModelCatalog.failureKindForTransport(e))
        }
        EndpointModelCatalog.failureKind(status, data)?.let { return PageResult.Failed(it) }
        val decoded = try {
            json.decodeFromString(ModelsResponse.serializer(), data.decodeToString())
        } catch (_: Exception) {
            return PageResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        }
        return PageResult.Ok(decoded)
    }

    /**
     * Pure (no network) so it is unit-testable against a captured payload.
     * Everything here is live except the price.
     */
    fun buildModels(from: List<ModelsResponse.Model>, nowMs: Long = System.currentTimeMillis()): List<KnownModel> {
        val seen = mutableSetOf<String>()
        val models = from.mapNotNull { r ->
            if (!r.isSelectableChatModel) return@mapNotNull null
            if (!seen.add(r.name.lowercase())) return@mapNotNull null
            var caps = ModelCapabilities.NONE
            if (r.supportsTools == true) caps += ModelCapabilities.TOOLS
            if (r.supportsImageInput == true) caps += ModelCapabilities.VISION
            val vendor = ModelCatalog.familyVendor(ModelCatalog.slug(r.name)) ?: "Fireworks"
            val display = r.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ModelCatalog.productName(it, vendor) }
                ?: ModelCatalog.displayName(r.name)
            KnownModel(
                id = r.name,
                displayName = display,
                vendor = vendor,
                price = price(r.name),
                contextWindow = ModelCatalog.contextLabel(r.contextLength),
                capabilities = caps.toCapabilitySet(),
                isNew = ModelCatalog.isNew(parseIso8601(r.createTime), nowMs),
                created = parseIso8601(r.createTime)?.let { Instant.ofEpochMilli(it) },   // iOS c3fb76d: "newest" sorts on it
                // Every Fireworks serverless row is a published open-weight model (iOS 2d76b10): without this the
                // preset's "strongest open-weight" default found no open rows and took the first, and the browser's
                // open-weights filter hid every Fireworks model.
                openWeights = true,
                // The rest of what the control plane publishes, as iOS carries it (kit 0.4.0 catalogs.json found summary,
                // github and the retirement date missing here): the detail screen shows them.
                summary = r.description?.trim()?.takeIf { it.isNotEmpty() },
                huggingFaceURL = r.huggingFaceUrl?.trim()?.takeIf { it.isNotEmpty() },
                githubURL = r.githubUrl?.trim()?.takeIf { it.isNotEmpty() },
                modelPageURL = "https://app.fireworks.ai/models/fireworks/" +
                    ModelCatalog.displayName(r.name),
                deprecationDate = KnownModel.deprecationDate(r.deprecationDate),
            )
        }
        return sortByVendorThenRecency(models, from)
    }

    fun price(forID: String): String {
        fireworksPrices[forID]?.let { return it }
        val key = ModelCatalog.slug(forID)
        fireworksPrices.entries.firstOrNull { ModelCatalog.slug(it.key) == key }?.let { return it.value }
        val undated = droppingDateSuffix(key)
        if (undated == key) return ""
        return fireworksPrices.entries.firstOrNull { ModelCatalog.slug(it.key) == undated }?.value ?: ""
    }

    /** Drops a trailing release-date suffix — `-0731`, `-202607`, `-20260731`. */
    internal fun droppingDateSuffix(slug: String): String {
        val dash = slug.lastIndexOf('-')
        if (dash < 0) return slug
        val tail = slug.substring(dash + 1)
        if (tail.length !in listOf(4, 6, 8) || !tail.all { it.isDigit() }) return slug
        return slug.substring(0, dash)
    }

    private fun sortByVendorThenRecency(
        models: List<KnownModel>,
        records: List<ModelsResponse.Model>,
    ): List<KnownModel> {
        val createdByID = records.associate {
            it.name.lowercase() to (parseIso8601(it.createTime) ?: Long.MIN_VALUE)
        }
        fun created(m: KnownModel) = createdByID[m.id.lowercase()] ?: Long.MIN_VALUE
        val newestByVendor = mutableMapOf<String, Long>()
        for (m in models) {
            newestByVendor[m.vendor] = maxOf(newestByVendor[m.vendor] ?: Long.MIN_VALUE, created(m))
        }
        return models.sortedWith { a, b ->
            val va = newestByVendor[a.vendor] ?: Long.MIN_VALUE
            val vb = newestByVendor[b.vendor] ?: Long.MIN_VALUE
            when {
                va != vb -> vb.compareTo(va)
                a.vendor != b.vendor -> a.vendor.compareTo(b.vendor)
                created(a) != created(b) -> created(b).compareTo(created(a))
                else -> a.id.compareTo(b.id)
            }
        }
    }

    private fun parseIso8601(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    }

    val fireworksPrices = mapOf(
        "accounts/fireworks/models/kimi-k3" to "$3.00/$15.00",
        "accounts/fireworks/models/ember-1" to "$3.00/$15.00",   // live and priced on the page since iOS's table was cut (2026-10-02)
        "accounts/fireworks/models/kimi-k2p7-code" to "$0.95/$4.00",
        "accounts/fireworks/models/kimi-k2p6" to "$0.95/$4.00",
        "accounts/fireworks/models/inkling" to "$1.00/$4.05",
        "accounts/fireworks/models/deepseek-v4p1-flash" to "$0.30/$1.20",   // docs.fireworks.ai/serverless/pricing, 2026-10-02 (was $0.22/$0.66)
        "accounts/fireworks/models/deepseek-v4-flash-0731" to "$0.22/$0.66",
        "accounts/fireworks/models/deepseek-v4-flash-vision-exp" to "$0.22/$0.66",
        "accounts/fireworks/models/deepseek-v4-pro-0813" to "$1.32/$3.96",
        "accounts/fireworks/models/glm-5p3" to "$1.40/$4.40",
        "accounts/fireworks/models/glm-5p3-flash" to "$0.15/$0.50",
        "accounts/fireworks/models/glm-5p2" to "$1.40/$4.40",
        "accounts/fireworks/models/qwen3p8-max" to "$2.00/$6.00",
        "accounts/fireworks/models/muse-glimmer-30b" to "$0.35/$1.50",
        "accounts/fireworks/models/minimax-m3" to "$0.30/$1.20",
        "accounts/fireworks/models/nemotron-lightning-3p5-30b-a3b" to "$0.05/$0.20",
        "accounts/fireworks/models/nemotron-3-ultra-nvfp4" to "$0.60/$2.40",
        "accounts/fireworks/models/gpt-oss-120b" to "$0.15/$0.60",
    )

    @Serializable
    data class ModelsResponse(
        val models: List<Model> = emptyList(),
        val nextPageToken: String? = null,
        val totalSize: Int? = null,
    ) {
        @Serializable
        data class Model(
            val name: String,
            val displayName: String? = null,
            val description: String? = null,
            val createTime: String? = null,
            val kind: String? = null,
            val state: String? = null,
            val deprecationDate: String? = null,
            val contextLength: Int? = null,
            val supportsServerless: Boolean? = null,
            val supportsTools: Boolean? = null,
            val supportsImageInput: Boolean? = null,
            val huggingFaceUrl: String? = null,
            val githubUrl: String? = null,
            val baseModelDetails: BaseModelDetails? = null,
        ) {
            @Serializable
            data class BaseModelDetails(val parameterCount: String? = null)

            private val nonChatKinds = setOf(
                "EMBEDDING_MODEL", "FLUMINA_BASE_MODEL", "FLUMINA_ADDON",
                "DRAFT_ADDON", "HF_PEFT_ADDON", "HF_TEFT_ADDON",
            )

            /** Serverless, ready, not deprecated, and actually a chat model. */
            val isSelectableChatModel: Boolean
                get() {
                    if (supportsServerless != true) return false
                    if (deprecationDate != null) return false
                    if (state != null && state != "READY") return false
                    if (kind != null && (kind in nonChatKinds || kind.endsWith("_ADDON"))) return false
                    return !ModelCatalog.isNonChat(name)
                }
        }
    }
}
