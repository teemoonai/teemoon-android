// ports: teemoon/Providers/Adapters/XAIAdapter.swift @ v1.0.4
package ai.teemoon.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlinx.serialization.DeserializationStrategy

/**
 * xAI (Grok) model catalogue. `/v1/models` is richer than the OpenAI list
 * (prices, context, aliases) and `/v1/language-models` lists ONLY the text
 * models, with modalities.
 *
 * The generic probe offered `grok-imagine-*` as chat models — picking one
 * breaks the chat.
 */
object XAIAdapter {

    private val json = Json { ignoreUnknownKeys = true }
    private val defaultClient by lazy { TeemoonHttp.client() }   // lazy: see EndpointDirectory.liveClient

    fun handles(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "x.ai" || h.endsWith(".x.ai")
    }

    /**
     * xAI quotes prices in hundredths of a cent per 100M tokens: 12500 → $1.25
     * per 1M.
     */
    fun perMillion(raw: Int?): Double? = raw?.let { it / 10_000.0 }

    /**
     * "grok-4.6" → "Grok 4.6"; "grok-4.20-0309-non-reasoning" → "Grok 4.20 Non Reasoning". A bare four-digit token
     * is a build stamp, not part of the product's name, so it is dropped (iOS c3fb76d: the hand-kept grokDisplayNames
     * are gone — a list that drifted within days, and named only the models it knew).
     */
    fun displayName(forID: String): String =
        forID.split('-').filterNot { it.length == 4 && it.all(Char::isDigit) }.joinToString(" ") { part ->
            if (part.lowercase() == "grok") "Grok"
            else part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    /**
     * The same two-endpoint fetch over the injected HTTP seam every catalogue
     * in this module shares (`(url, headers, timeoutMs) → (status, body)`), so
     * `ModelCatalog.liveCatalog` can route here without a live socket in tests.
     */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): EndpointModelCatalog.ProbeResult = coroutineScope {
        val language = async { fetch(LanguageModelsResponse.serializer(), "$baseUrl/language-models", apiKey, fetch) }
        val models = async { fetch(ModelsResponse.serializer(), "$baseUrl/models", apiKey, fetch) }
        assemble(language.await(), models.await())
    }

    /**
     * Combine the two endpoint results. Pure given the decoded bodies, so tests
     * do not need a live or stubbed HTTP stack.
     */
    fun assemble(
        language: Result<LanguageModelsResponse>,
        models: Result<ModelsResponse>,
        nowMs: Long = System.currentTimeMillis(),
    ): EndpointModelCatalog.ProbeResult {
        val contextByID = models.getOrNull()?.data.orEmpty()
            .mapNotNull { m -> m.context_length?.let { m.id.lowercase() to it } }
            .toMap()
        val entries: List<Entry> = when {
            language.isSuccess ->
                language.getOrThrow().models.filter { it.isTextModel }.map { Entry.fromLanguage(it) }
            models.isSuccess ->
                models.getOrThrow().data.filter { it.isTextModel }.map { Entry.fromModel(it) }
            else ->
                return EndpointModelCatalog.ProbeResult.Failed(
                    (language.exceptionOrNull() as? ProbeFailure)?.kind
                        ?: EndpointModelCatalog.FailureKind.OFFLINE,
                )
        }
        val known = buildModels(entries, contextByID, nowMs)
        if (known.isEmpty()) {
            return EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        }
        return EndpointModelCatalog.ProbeResult.Connected(known)
    }

    fun buildModels(
        entries: List<Entry>,
        contexts: Map<String, Int>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<KnownModel> =
        entries.sortedByDescending { it.created }.map { entry ->
            KnownModel(
                id = entry.id,
                displayName = displayName(entry.id),
                vendor = ModelCatalog.vendorLabel(entry.id),
                price = ModelCatalog.priceLabel(entry.inputPerMillion, entry.outputPerMillion),
                contextWindow = ModelCatalog.contextLabel(contexts[entry.id.lowercase()]),
                capabilities = entry.capabilities.toCapabilitySet(),
                modelPageURL = "https://docs.x.ai/developers/models/" + entry.id,
                isNew = ModelCatalog.isNew(
                    createdEpochMs = if (entry.created > 0) entry.created * 1000L else null,
                    nowMs = nowMs,
                ),
                // Carried, not only badged (iOS c3fb76d): "newest" sorts on it, and a list read back from disk
                // recomputes its New badge from it.
                created = if (entry.created > 0) Instant.ofEpochSecond(entry.created.toLong()) else null,
                openWeights = false,   // grok's weights are xAI's
            )
        }

    data class Entry(
        val id: String,
        val created: Int,
        val inputPerMillion: Double?,
        val outputPerMillion: Double?,
        val capabilities: ModelCapabilities,
    ) {
        companion object {
            fun fromLanguage(m: LanguageModelsResponse.Model): Entry {
                var caps = ModelCapabilities.TOOLS
                if (m.input_modalities?.contains("image") == true) caps += ModelCapabilities.VISION
                return Entry(
                    id = m.id,
                    created = m.created ?: 0,
                    inputPerMillion = perMillion(m.prompt_text_token_price),
                    outputPerMillion = perMillion(m.completion_text_token_price),
                    capabilities = caps,
                )
            }

            fun fromModel(m: ModelsResponse.Model): Entry = Entry(
                id = m.id,
                created = m.created ?: 0,
                inputPerMillion = perMillion(m.prompt_text_token_price),
                outputPerMillion = perMillion(m.completion_text_token_price),
                capabilities = ModelCapabilities.TOOLS,
            )
        }
    }

    private const val CALL_TIMEOUT_MS = 12_000L

    private suspend fun <T> fetch(
        serializer: DeserializationStrategy<T>,
        url: String,
        apiKey: String,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): Result<T> =
        // The one fetch every catalogue shares (iOS dfc528b: "one list fetch"). xAI's own copy read a keyless 403 as
        // "unauthorized" (fetchList: "forbidden" — the request carried no key to refuse), filed every transport error
        // as "offline" (fetchList separates nothing-listening and blocked cleartext), and swallowed cancellation.
        when (val r = EndpointModelCatalog.fetchList(url, apiKey, fetch = { u, h, _ -> fetch(u, h, CALL_TIMEOUT_MS) }) { json.decodeFromString(serializer, it) }) {
            is EndpointModelCatalog.Fetched.Success -> Result.success(r.value)
            is EndpointModelCatalog.Fetched.Failure -> Result.failure(ProbeFailure(r.kind))
        }

    class ProbeFailure(val kind: EndpointModelCatalog.FailureKind) : Exception()

    @Serializable
    data class ModelsResponse(val data: List<Model> = emptyList()) {
        @Serializable
        data class Model(
            val id: String,
            val created: Int? = null,
            val context_length: Int? = null,
            val prompt_text_token_price: Int? = null,
            val completion_text_token_price: Int? = null,
        ) {
            /**
             * Chat models only. The `grok-imagine-*` image/video family carries
             * no text-token pricing.
             */
            val isTextModel: Boolean
                get() = !id.lowercase().startsWith("grok-imagine") &&
                    prompt_text_token_price != null &&
                    completion_text_token_price != null
        }
    }

    @Serializable
    data class LanguageModelsResponse(val models: List<Model> = emptyList()) {
        @Serializable
        data class Model(
            val id: String,
            val created: Int? = null,
            val input_modalities: List<String>? = null,
            val aliases: List<String>? = null,
            val prompt_text_token_price: Int? = null,
            val completion_text_token_price: Int? = null,
        ) {
            val isTextModel: Boolean
                get() = !id.lowercase().startsWith("grok-imagine")
        }
    }
}
