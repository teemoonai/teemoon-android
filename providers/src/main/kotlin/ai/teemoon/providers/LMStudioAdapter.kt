// ports: teemoon/Providers/Adapters/LMStudioAdapter.swift @ v1.0.1
package ai.teemoon.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * LM Studio-specific behavior, contained in one file.
 * LM Studio speaks OpenAI-compat at `{host}/v1` (default port 1234), but like
 * Ollama it carries the metadata teemoon needs on a native surface at the host
 * ROOT: `/api/v0/models`, a sibling of `/v1`.
 *
 * Why native, not the generic /v1/models probe — `/api/v0/models` is the richest
 * catalogue any local server exposes:
 *  • `type` — "llm" | "vlm" | "embeddings". The embedding model sits in the same
 *    `/v1/models` list as the chat models; picking one 400s on first message.
 *  • `capabilities` — e.g. ["tool_use"], so a non-tool model is gated before the
 *    request instead of discovering it as a 400 in the hot path.
 *  • `max_context_length` + `quantization` — the "128k · Q4_K_M" a local row
 *    otherwise renders blank.
 *  • `state` — "loaded" | "not-loaded", the same warm/cold signal Ollama's
 *    `/api/ps` gives.
 *
 * Not knowable from here: whether a listed model will actually LOAD.
 */
object LMStudioAdapter {

    private val json = Json { ignoreUnknownKeys = true }
    private val defaultClient by lazy { TeemoonHttp.client() }   // lazy: see EndpointDirectory.liveClient

    /**
     * Cheap probe: is this endpoint LM Studio? `GET {root}/api/v0/models` is its
     * own REST surface — Ollama and llama.cpp 404 it. Swallows all errors → false.
     */
    suspend fun isLMStudio(baseUrl: String, client: OkHttpClient = defaultClient): Boolean =
        fetchModels(baseUrl, timeoutSec = 5, client) != null

    /**
     * Native model list that doubles as the connection test. Falls back to
     * [EndpointModelCatalog.FailureKind.OFFLINE] if the native surface does not
     * answer — the generic `/v1/models` probe is EndpointModelCatalog.probe and
     * is not wired here yet.
     */
    suspend fun listModels(
        baseUrl: String,
        client: OkHttpClient = defaultClient,
    ): EndpointModelCatalog.ProbeResult {
        val list = fetchModels(baseUrl, timeoutSec = 12, client)
            ?: return EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.OFFLINE,
            )
        val models = buildModels(list.data)
        if (models.isEmpty()) {
            return EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.BAD_RESPONSE,
            )
        }
        return EndpointModelCatalog.ProbeResult.Connected(models)
    }

    /** Ids currently loaded in memory (warm). Same shape as OllamaAdapter.loadedModels. */
    suspend fun loadedModels(baseUrl: String, client: OkHttpClient = defaultClient): Set<String> {
        val list = fetchModels(baseUrl, timeoutSec = 6, client) ?: return emptySet()
        return list.data.filter { it.isLoaded }.map { it.id }.toSet()
    }

    /** Pure (no network) so it is unit-testable against a captured payload. */
    fun buildModels(from: List<ModelsResponse.Model>): List<KnownModel> =
        from.mapNotNull { m ->
            if (!m.isChatModel) return@mapNotNull null
            KnownModel(
                id = m.id,
                displayName = m.displayName,
                // `publisher` is whoever uploaded the GGUF, not the model's vendor,
                // so the family wins; publisher is the fallback.
                vendor = ModelCatalog.familyVendor(m.id)
                    ?: m.publisher?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    ?: "",
                price = "",
                contextWindow = m.contextLabel,
                capabilities = m.modelCapabilities.toCapabilitySet(),
            )
        }

    private suspend fun fetchModels(
        baseUrl: String,
        timeoutSec: Long,
        client: OkHttpClient,
    ): ModelsResponse? = withContext(Dispatchers.IO) {
        val url = "${EndpointModelCatalog.rootUrl(baseUrl)}/api/v0/models"
        val req = Request.Builder().url(url).get().build()
        val callClient = client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        val body = runCatching {
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body.string()
            }
        }.getOrNull() ?: return@withContext null
        runCatching { json.decodeFromString(ModelsResponse.serializer(), body) }.getOrNull()
    }

    /**
     * `GET /api/v0/models` — every model in the library, loaded or not.
     */
    @Serializable
    data class ModelsResponse(
        val `object`: String? = null,
        val data: List<Model> = emptyList(),
    ) {
        @Serializable
        data class Model(
            /** The id `/v1/chat/completions` expects, including "@quant". */
            val id: String,
            val `object`: String? = null,
            /** "llm" | "vlm" (vision) | "embeddings" */
            val type: String? = null,
            /** The GGUF's uploader ("unsloth", "bartowski"), NOT the model vendor. */
            val publisher: String? = null,
            val arch: String? = null,
            val quantization: String? = null,
            /** "loaded" | "not-loaded" */
            val state: String? = null,
            val max_context_length: Int? = null,
            val capabilities: List<String>? = null,
        ) {
            val isLoaded: Boolean get() = state == "loaded"

            /**
             * Chat models only. `embeddings` ships in the same list and would
             * 400 on the first message; an unknown/absent type is kept (a new
             * LM Studio type shouldn't silently empty the picker).
             */
            val isChatModel: Boolean
                get() {
                    val t = type?.lowercase()
                    if (t == null) return !ModelCatalog.isNonChat(id)
                    return t != "embeddings" && !ModelCatalog.isNonChat(id)
                }

            /**
             * "gemma-4-e4b-it-qat@q4_k_xl" → "gemma-4-e4b-it-qat". The quant is
             * shown beside the context window instead of inside the name.
             */
            val displayName: String
                get() = id.split('@').first()

            /** "128k · Q4_K_M" — 131072 is 128k, not 131k. */
            val contextLabel: String
                get() = listOfNotNull(
                    ModelCatalog.localContextLabel(max_context_length).takeIf { it.isNotEmpty() },
                    quantization,
                ).joinToString(" · ")

            /**
             * Tools from `capabilities`, vision from `type == "vlm"`. Always
             * **known** (non-null): LM Studio reports these explicitly, so an
             * empty set means "supports neither", not "unknown".
             */
            val modelCapabilities: ModelCapabilities
                get() {
                    var caps = ModelCapabilities.NONE
                    if (capabilities?.any { it.lowercase() == "tool_use" } == true) {
                        caps += ModelCapabilities.TOOLS
                    }
                    if (type?.lowercase() == "vlm") caps += ModelCapabilities.VISION
                    return caps
                }
        }
    }
}
