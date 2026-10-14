// ports: teemoon/Providers/Adapters/OllamaAdapter.swift @ v1.0.1 (pullModel + OllamaPullProgress added 2026-09-02; deleteModel ported with the add/edit form)
package ai.teemoon.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Ollama-specific behavior, contained in one file.
 * Ollama speaks OpenAI-compat at `{host}/v1`, but its native surface —
 * model list *with capabilities*, and server-side model pull — lives at the
 * host ROOT under `/api` (siblings of `/v1`).
 *
 * Why native, not the generic /v1/models probe:
 *  • /v1/models returns bare ids (no capability metadata), so tools/vision can't
 *    be modeled. /api/show carries `capabilities`, which is how teemoon gates a
 *    web_search tool away from a non-tool model *before* the request (no 400).
 *  • /api/pull enables pulling a model to the server from the phone (no SSH).
 *
 * Wire contract — https://github.com/ollama/ollama/blob/main/docs/api.md
 */
object OllamaAdapter {

    internal val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val defaultClient by lazy { TeemoonHttp.client() }   // lazy: see EndpointDirectory.liveClient

    fun rootUrl(from: String): String = EndpointModelCatalog.rootUrl(from)

    // MARK: Detection

    /**
     * Cheap probe: is this endpoint an Ollama server? `GET {root}/api/version`
     * returns `{"version":"…"}`. Swallows all errors → false.
     */
    suspend fun isOllama(baseUrl: String, client: OkHttpClient = defaultClient): Boolean =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${rootUrl(baseUrl)}/api/version")
                .get()
                .build()
            val callClient = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            val body = runCatching {
                callClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    resp.body.string()
                }
            }.getOrNull() ?: return@withContext false
            runCatching { json.decodeFromString<OllamaVersionResponse>(body) }.getOrNull() != null
        }

    // MARK: List models (+ capabilities)

    /**
     * Native model list that doubles as the connection test. Lists installed
     * models via `/api/tags`, then fills each one's `capabilities` via `/api/show`.
     *
     * AN EMPTY OLLAMA IS CONNECTED, NOT BROKEN. It answered /api/tags with a
     * valid, empty list — the server is healthy and has nothing installed yet,
     * which is the normal state of a fresh install. Reporting `.badResponse`
     * hid "download a model": that button only renders in the `.connected`
     * branch.
     */
    suspend fun listModels(
        baseUrl: String,
        client: OkHttpClient = defaultClient,
    ): EndpointModelCatalog.ProbeResult = withContext(Dispatchers.IO) {
        val root = rootUrl(baseUrl)
        val req = Request.Builder().url("$root/api/tags").get().build()
        val callClient = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
        val (status, body) = try {
            callClient.newCall(req).execute().use { resp ->
                resp.code to resp.body.bytes()
            }
        } catch (_: ConnectException) {
            return@withContext EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.NOTHING_LISTENING,
            )
        } catch (_: UnknownHostException) {
            return@withContext EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.NOTHING_LISTENING,
            )
        } catch (_: SocketTimeoutException) {
            return@withContext EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.NOTHING_LISTENING,
            )
        } catch (_: SSLException) {
            return@withContext EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.HTTP_BLOCKED,
            )
        } catch (_: Exception) {
            return@withContext EndpointModelCatalog.ProbeResult.Failed(
                EndpointModelCatalog.FailureKind.OFFLINE,
            )
        }
        interpretTags(status, body, root, callClient)
    }

    internal fun interpretTags(
        status: Int,
        body: ByteArray,
        root: String? = null,
        client: OkHttpClient? = null,
    ): EndpointModelCatalog.ProbeResult {
        if (status != 200) {
            return EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        }
        val tags = runCatching {
            json.decodeFromString<OllamaTagsResponse>(body.decodeToString())
        }.getOrNull() ?: return EndpointModelCatalog.ProbeResult.Failed(
            EndpointModelCatalog.FailureKind.BAD_RESPONSE,
        )
        if (tags.models.isEmpty()) {
            return EndpointModelCatalog.ProbeResult.Connected(emptyList())
        }
        val models = tags.models.map { m ->
            val meta = if (root != null && client != null) showMeta(m.name, root, client) else OllamaModelMeta(null, "")
            KnownModel(
                id = m.name,
                displayName = m.name,
                vendor = m.details.family.orEmpty(),
                price = "",
                contextWindow = meta.contextWindow,
                capabilities = meta.caps?.toCapabilitySet(),
                huggingFaceURL = huggingFaceURL(m.name),
                modelPageURL = libraryURL(m.name),
                quantization = meta.quantization,
                parameterSize = m.details.parameter_size,
            )
        }
        return EndpointModelCatalog.ProbeResult.Connected(models)
    }

    data class OllamaModelMeta(
        val caps: ModelCapabilities?,
        val contextWindow: String,
        val quantization: String? = null,
    )

    private fun showMeta(model: String, root: String, client: OkHttpClient): OllamaModelMeta {
        val body = json.encodeToString(OllamaShowRequest.serializer(), OllamaShowRequest(model))
        val req = Request.Builder()
            .url("$root/api/show")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val callClient = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val bytes = runCatching {
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return OllamaModelMeta(null, "")
                resp.body.bytes()
            }
        }.getOrNull() ?: return OllamaModelMeta(null, "")
        val obj = runCatching {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrNull() ?: return OllamaModelMeta(null, "")
        val capList = obj["capabilities"]?.jsonArray?.mapNotNull { el ->
            el.jsonPrimitive.contentOrNull
        } ?: emptyList()
        val caps = ModelCapabilities.fromOllama(capList)
        val quant = ((obj["details"] as? JsonObject)?.get("quantization_level") as? JsonPrimitive)
            ?.contentOrNull
        var ctxLen: Long? = null
        val mi = obj["model_info"] as? JsonObject
        if (mi != null) {
            for ((k, v) in mi) {
                if (k.endsWith(".context_length")) {
                    ctxLen = (v as? JsonPrimitive)?.longOrNull
                    break
                }
            }
        }
        val parts = listOfNotNull(ModelCatalog.localContextLabel(ctxLen?.toInt()), quant)
            .filter { it.isNotEmpty() }
        return OllamaModelMeta(caps = caps, contextWindow = parts.joinToString(" · "), quantization = quant)
    }

    /**
     * The model's page in Ollama's own library — `gemma4:e2b` →
     * `ollama.com/library/gemma4`.
     *
     * nil for a namespaced pull (`user/model`, `hf.co/...`) — those are not in
     * the library and the URL would be wrong.
     */
    fun libraryURL(forOllamaName: String): String? {
        val base = forOllamaName.split(':').first()
        if (base.isEmpty() || '/' in base) return null
        return "https://ollama.com/library/$base"
    }

    /**
     * The Hugging Face repo, but ONLY when the name already says so.
     * Mapping `gemma4:e2b` to `google/gemma4` would be invention.
     */
    fun huggingFaceURL(forOllamaName: String): String? {
        val base = forOllamaName.split(':').first()
        val lower = base.lowercase()
        for (prefix in listOf("hf.co/", "huggingface.co/")) {
            if (lower.startsWith(prefix)) {
                val path = base.drop(prefix.length)
                return if ('/' in path) "https://huggingface.co/$path" else null
            }
        }
        return null
    }

    // MARK: Loaded / warm state

    /** `GET {root}/api/ps` — names of models currently loaded in memory (warm). */
    suspend fun loadedModels(baseUrl: String, client: OkHttpClient = defaultClient): Set<String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${rootUrl(baseUrl)}/api/ps").get().build()
            val callClient = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
            val body = runCatching {
                callClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptySet()
                    resp.body.string()
                }
            }.getOrNull() ?: return@withContext emptySet()
            val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext emptySet()
            val models = obj["models"]?.jsonArray ?: return@withContext emptySet()
            models.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.toSet()
        }

    /**
     * Loads a model into the server's memory *before* the user sends anything.
     *
     * Measured on an M-series 16 GB box with gemma4:latest (8B Q4): **14.3s cold,
     * 10.8s with the weights still in the page cache, 0.6s once resident.**
     *
     * `POST {root}/api/generate` with a model and no prompt is Ollama's
     * documented preload idiom. **Sends no `options`, deliberately.** Ollama
     * keys a loaded runner by model *plus* options, so a warm-up carrying
     * `num_ctx` that disagrees with the server's default causes the next real
     * request to evict this runner and reload.
     *
     * Fire and forget. Every failure is swallowed.
     */
    suspend fun warmUp(baseUrl: String, model: String, client: OkHttpClient = defaultClient) {
        withContext(Dispatchers.IO) {
            val body = Json.encodeToString(
                JsonObject.serializer(),
                JsonObject(mapOf("model" to JsonPrimitive(model))),
            )
            val req = Request.Builder()
                .url("${rootUrl(baseUrl)}/api/generate")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val callClient = client.newBuilder().callTimeout(120, TimeUnit.SECONDS).build()
            runCatching { callClient.newCall(req).execute().close() }
        }
    }

    // MARK: Pull refs

    /**
     * The installed model this ref would pull, or null when the server hasn't got it.
     *
     * Ollama's ids carry a tag and a bare name means `:latest` — `gemma4` and
     * `gemma4:latest` are the same model. Deliberately returns the INSTALLED id
     * rather than a Bool: it is the server's own spelling.
     */
    fun installedMatch(ref: String, installed: List<String>): String? {
        fun tagged(s: String): String {
            val name = s.trim().lowercase()
            if (name.isEmpty()) return name
            if ('@' in name) return name
            val lastComponent = name.split('/').last()
            return if (':' in lastComponent) name else "$name:latest"
        }
        val target = tagged(normalizePullRef(ref))
        if (target.isEmpty()) return null
        return installed.firstOrNull { tagged(it) == target }
    }

    /**
     * What's wrong with the pasted link, or null to go ahead.
     *
     * **Plain URL validation.** The ref reaches the server as a JSON string
     * field and every request is built with a path component on a fixed path,
     * so the text never enters a url, a header, a query or a shell.
     */
    fun refProblem(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = asLink(trimmed) ?: return null
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "only http and https links work here."
        }
        val host = url.host?.lowercase() ?: return "that link has no address in it."
        val path = url.path.split('/').filter { it.isNotEmpty() }
        return when (host) {
            "ollama.com", "www.ollama.com" -> {
                if (path.firstOrNull() == "search" || path.isEmpty()) {
                    "that's ollama's search page. open a model from it, then copy that link."
                } else if (path == listOf("library")) {
                    "that's ollama's library index. open one model, then copy its link."
                } else {
                    null
                }
            }
            "huggingface.co", "www.huggingface.co", "hf.co" -> {
                if (path.firstOrNull() == "models" || path.isEmpty()) {
                    "that's hugging face's model list. open a repo, then copy its link."
                } else if (path.firstOrNull() in listOf("datasets", "spaces", "docs", "blog", "papers")) {
                    "that's a hugging face ${path.first()} page, not a model repo."
                } else if (path.size < 2) {
                    "that link is missing the repo — a model page looks like huggingface.co/user/repo."
                } else {
                    null
                }
            }
            else ->
                "that link isn't from ollama.com or hugging face. open the model's page on one of those and copy the link from there."
        }
    }

    /**
     * The pasted text as a URL, or null when it isn't a link at all.
     *
     * `URI(string)` alone can't answer this: `gemma4:26b` parses with the scheme
     * "gemma4", so every shortlist tag would be judged as a link with a bad scheme.
     */
    private fun asLink(s: String): URI? {
        val knownHosts = setOf(
            "ollama.com", "www.ollama.com",
            "huggingface.co", "www.huggingface.co", "hf.co",
        )
        if ("://" in s) return runCatching { URI(s) }.getOrNull()
        val schemelessURIs = listOf("javascript:", "data:", "mailto:", "tel:", "blob:", "about:")
        val prefix = schemelessURIs.firstOrNull { s.lowercase().startsWith(it) }
        if (prefix != null) {
            return runCatching { URI(prefix + "//placeholder") }.getOrNull() ?: URI("about:blank")
        }
        val first = s.split('/', limit = 2).first().lowercase()
        if (first in knownHosts) return runCatching { URI("https://$s") }.getOrNull()
        return null
    }

    /** Server-side delete of an installed model (`DELETE /api/delete`). Throws [OllamaPullException] on a non-2xx. */
    suspend fun deleteModel(id: String, baseUrl: String, client: OkHttpClient = defaultClient) = withContext(Dispatchers.IO) {
        val body = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("model" to JsonPrimitive(id))))
        val req = Request.Builder().url("${rootUrl(baseUrl)}/api/delete").delete(body.toRequestBody("application/json".toMediaType())).header("Content-Type", "application/json").build()
        client.newCall(req).execute().use { resp -> if (resp.code !in 200..299) throw OllamaPullException(resp.code, resp.body.string()) }
    }

    /**
     * Normalize a user-pasted model reference into what `/api/pull` accepts.
     *
     * Left alone, `ollama.com/library/qwen3.5` was passed to `/api/pull` verbatim —
     * a ref whose first path component reads as a REGISTRY HOST.
     */
    fun normalizePullRef(raw: String): String {
        var s = raw.trim()
        for (prefix in listOf("ollama run ", "ollama pull ")) {
            if (s.startsWith(prefix)) s = s.drop(prefix.length).trim()
        }
        val schemeAt = s.indexOf("://")
        if (schemeAt >= 0) s = s.substring(schemeAt + 3)
        val cut = s.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        val parts = s.split('/').filter { it.isNotEmpty() }
        val host = parts.firstOrNull()?.lowercase() ?: return s

        if (host == "ollama.com" || host == "www.ollama.com") {
            var path = parts.drop(1).toMutableList()
            val idx = path.indexOfFirst { it == "tags" || it == "blobs" }
            if (idx >= 0) path = path.subList(0, idx).toMutableList()
            if (path.firstOrNull() == "library") path = path.drop(1).toMutableList()
            val ref = path.take(2).joinToString("/")
            return ref.ifEmpty { s }
        }
        if (host != "huggingface.co" && host != "hf.co") return s
        val path = parts.drop(1).take(2).joinToString("/")
        return if (path.isEmpty()) s else "hf.co/$path"
    }
}

/**
 * `POST {root}/api/pull` — streams NDJSON progress, one [OllamaPullProgress]
 * per line, terminating with `status == "success"`. [ref] may be an Ollama
 * library name (`qwen3.5`) or a HuggingFace GGUF reference
 * (`hf.co/{user}/{repo}[:{quant}]`); normalize a pasted value with
 * [OllamaAdapter.normalizePullRef] first. The pull happens on the SERVER, not the phone.
 *
 * Throws [OllamaPullException] on a non-2xx status or an in-band `{"error":…}` line.
 */
fun OllamaAdapter.pullModel(ref: String, baseUrl: String, client: OkHttpClient = TeemoonHttp.client()): Flow<OllamaPullProgress> = flow {
    val body = Json.encodeToString(OllamaPullRequest.serializer(), OllamaPullRequest(model = ref))
    val req = Request.Builder().url("${rootUrl(baseUrl)}/api/pull")
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    // Idle (between-bytes) timeout. Must be generous: after the blob downloads,
    // Ollama runs "verifying sha256 digest" which streams NO data while it hashes
    // multi-GB blobs — a short timeout there makes the stream drop right before
    // "success" and loop forever.
    val callClient = client.newBuilder().readTimeout(600, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    callClient.newCall(req).execute().use { resp ->
        val respBody = resp.body
        if (resp.code >= 400) throw OllamaPullException(resp.code, respBody.string())
        val source = respBody.source()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            OllamaAdapter.parsePullLine(line)?.let { emit(it) }
        }
    }
}.flowOn(Dispatchers.IO)

/** One NDJSON line: blank → null; undecodable → null; `{"error":…}` → throws (Ollama reports errors in-band too). */
internal fun OllamaAdapter.parsePullLine(raw: String): OllamaPullProgress? {
    val line = raw.trimEnd('\r')
    if (line.isBlank()) return null
    val p = runCatching { json.decodeFromString(OllamaPullProgress.serializer(), line) }.getOrNull() ?: return null
    p.error?.let { throw OllamaPullException(200, it) }
    return p
}

@Serializable
internal data class OllamaPullRequest(val model: String, val stream: Boolean = true, val insecure: Boolean = false)

/**
 * One NDJSON line from `POST /api/pull`. Sequence: `{"status":"pulling manifest"}` →
 * repeated `{"status":"pulling <digest>","digest":…,"total":…,"completed":…}` →
 * `{"status":"verifying sha256 digest"}` → `{"status":"writing manifest"}` →
 * `{"status":"success"}`. Errors may arrive in-band as `{"error":"…"}`.
 */
@Serializable
data class OllamaPullProgress(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = status == "success"
    /** 0.0…1.0 when a byte-total is known, else null (indeterminate phase). */
    val fractionCompleted: Double?
        get() { val t = total; val c = completed; return if (t != null && t > 0 && c != null) minOf(1.0, c.toDouble() / t.toDouble()) else null }
}

/** A non-2xx `/api/pull`, or an in-band `{"error":…}` line. */
class OllamaPullException(val status: Int, val rawMessage: String) :
    Exception(rawMessage.ifEmpty { "Ollama pull failed (HTTP $status)" })

@Serializable
internal data class OllamaVersionResponse(val version: String)

@Serializable
internal data class OllamaTagsResponse(val models: List<Model> = emptyList()) {
    @Serializable
    data class Model(
        val name: String,
        val model: String? = null,
        val size: Long? = null,
        val digest: String? = null,
        val details: Details = Details(),
    ) {
        @Serializable
        data class Details(
            val family: String? = null,
            val parameter_size: String? = null,
            val quantization_level: String? = null,
        )
    }
}

@Serializable
internal data class OllamaShowRequest(val model: String)
