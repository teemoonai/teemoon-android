// ports: teemoon/Providers/EndpointModelCatalog.swift @ v1.0.4 (FailureKind, ProbeResult, failureKind,
//        Source, probe, fetchList, OpenAIModelsResponse. LocalServerKind is its own file.)
package ai.teemoon.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.net.URI

/**
 * Probes an arbitrary OpenAI-compatible endpoint. `GET {baseURL}/models`
 * doubles as the connection test AND the model-list fetch for the verify-first
 * add-provider flow. This is the provider-agnostic generalization of
 * [NearAIModelCatalog.fetchLive] — no near.ai coupling (no tier recording, no
 * curated merge, no EndpointDirectory). Cloud confidentiality tiers stay in
 * NearAIModelCatalog; this just lists whatever models an endpoint reports.
 */
object EndpointModelCatalog {

    const val PROBE_TIMEOUT_MS = 12_000L

    /**
     * Specific, user-facing failure reasons the add-provider UI renders as one
     * inline line.
     */
    enum class FailureKind {
        NOTHING_LISTENING,
        NO_MODELS_ENDPOINT,
        HTTP_BLOCKED,
        UNAUTHORIZED,
        /**
         * 403 when no key was even sent — the server refused the REQUEST, not a
         * credential. The case that motivated it: Ollama behind `tailscale
         * serve` 403s every proxied call, because the proxy forwards the
         * original `Host` and Ollama only trusts local hostnames. Reported as
         * "key rejected" that sends you looking for a key Ollama doesn't have.
         *
         * The fix we surface is to **rewrite `Host` in the proxy**. Setting
         * `OLLAMA_HOST=0.0.0.0` also clears the 403 — but it binds every
         * interface and Ollama has no authentication whatsoever. Wrong trade
         * to put in an error message.
         */
        FORBIDDEN,
        PAYMENT_REQUIRED,
        RATE_LIMITED,
        BAD_RESPONSE,
        OFFLINE,
    }

    sealed class ProbeResult {
        data class Connected(val models: List<KnownModel>) : ProbeResult()
        data class Failed(val kind: FailureKind) : ProbeResult()
    }

    /**
     * Which catalogue serves an endpoint. Cloud providers publish richer
     * catalogues than the OpenAI-compat `/models` list (price, context window,
     * capabilities, tiers), and routing to them is what keeps the model picker
     * from rendering bare ids. Ollama is NOT here — it's detected with a live
     * probe (`/api/version`), not by host.
     */
    enum class Source {
        NEAR_AI,
        XAI,
        FIREWORKS,
        OPEN_ROUTER,
        NVIDIA,
        /** Plain `GET {base}/models` — a self-hosted server or an unknown cloud. */
        GENERIC,
        ;

        companion object {
            fun resolve(host: String?): Source {
                val h = host?.lowercase() ?: return GENERIC
                if (h == "near.ai" || h.endsWith(".near.ai")) return NEAR_AI
                if (XAIAdapter.handles(h)) return XAI
                if (FireworksAdapter.handles(h)) return FIREWORKS
                if (OpenRouterAdapter.handles(h)) return OPEN_ROUTER
                if (NVIDIAAdapter.handles(h)) return NVIDIA
                return GENERIC
            }
        }
    }

    /** The result of one listing call: a decoded body, or the reason there isn't one. */
    sealed class Fetched<out T> {
        data class Success<T>(val value: T) : Fetched<T>()
        data class Failure(val kind: FailureKind) : Fetched<Nothing>()
    }

    /**
     * ONE AUTHENTICATED GET, DECODED. Every catalogue adapter lists through this
     * so the status-code rule lands once rather than being re-derived per vendor
     * — xAI's 400-means-bad-key, the 403-without-a-key case, the cleartext block.
     * Each was learned from a real endpoint and each would otherwise have to be
     * remembered five times.
     *
     * Auth is attached ONLY when the key is non-empty, and `sentKey` below stays
     * tied to that same condition. Untie them and a 403 answering a keyless call
     * gets blamed on a key that does not exist — which is the Ollama-behind-
     * `tailscale serve` case reading as "your key was refused".
     */
    suspend fun <T> fetchList(
        url: String,
        apiKey: String,
        authHeaderName: String? = null,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> = ::liveFetch,
        decode: (String) -> T,
    ): Fetched<T> {
        val key = apiKey.trim()
        val headers = LinkedHashMap<String, String>()
        CredentialHeaders.attach(headers, key, authHeaderName)
        val (status, data) = try {
            fetch(url, headers, PROBE_TIMEOUT_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // a cancelled probe is cancelled, not "offline" — Kotlin's CancellationException IS an Exception
        } catch (e: Exception) {
            return Fetched.Failure(failureKindForTransport(e))
        }
        failureKind(status, data, sentKey = key.isNotEmpty())?.let { return Fetched.Failure(it) }
        return try {
            Fetched.Success(decode(data.decodeToString()))
        } catch (_: Exception) {
            Fetched.Failure(FailureKind.BAD_RESPONSE)
        }
    }

    /**
     * A self-hosted server's native API lives at the host ROOT, a sibling of the
     * OpenAI-compat `/v1` (Ollama `/api` and LM Studio `/api/v0` on the root), so the
     * configured base URL's path has to come off first.
     */
    /** The host of a base URL, or null when it is not a URL at all. */
    fun hostOf(baseUrl: String): String? =
        runCatching { URI(baseUrl).host }.getOrNull()

    fun rootUrl(from: String): String {
        val u = URI(from)
        return URI(u.scheme, u.userInfo, u.host, u.port, null, null, null).toString()
    }

    /**
     * HTTP status → failure, or null when the status is a success.
     *
     * `body` matters because not every provider uses 401 for a bad key: xAI
     * answers **HTTP 400** with `{"code":"invalid-argument","error":"Incorrect
     * API key provided…"}`. Classified on status alone that reads as `.offline`.
     *
     * [sentKey] separates "your key was refused" from "the server refused the
     * request". A 403 answering a call that carried NO key cannot be about the
     * key — the Ollama-behind-`tailscale serve` case.
     */
    fun failureKind(status: Int, body: ByteArray? = null, sentKey: Boolean = true): FailureKind? {
        return when {
            status in 200 until 300 -> null
            status == 403 && !sentKey -> FailureKind.FORBIDDEN
            status == 401 || status == 403 -> FailureKind.UNAUTHORIZED
            status == 402 -> FailureKind.PAYMENT_REQUIRED
            status == 404 -> FailureKind.NO_MODELS_ENDPOINT
            status == 429 -> FailureKind.RATE_LIMITED
            status in 400 until 500 && saysKeyIsBad(body) -> FailureKind.UNAUTHORIZED
            else -> FailureKind.OFFLINE
        }
    }

    /**
     * Transport error → failure. Distinguishes "nothing is listening there"
     * from a cleartext block, which need different fixes from the user.
     *
     * iOS maps `URLError.appTransportSecurityRequiresSecureConnection` to
     * [FailureKind.HTTP_BLOCKED]. Android's analogue is the network-security-config
     * cleartext gate (`CLEARTEXT communication not permitted`); there is no ATS.
     */
    fun failureKindForTransport(error: Throwable): FailureKind {
        val chain = generateSequence(error) { it.cause }
        if (chain.any { it.message?.contains("CLEARTEXT", ignoreCase = true) == true }) {
            return FailureKind.HTTP_BLOCKED
        }
        return when (error) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                FailureKind.NOTHING_LISTENING
            else -> FailureKind.OFFLINE
        }
    }

    /**
     * Whether an error body blames the API key. Deliberately narrow: it must
     * mention a key or authentication, so a validation error about some other
     * field isn't mistaken for a credential problem.
     */
    private fun saysKeyIsBad(body: ByteArray?): Boolean {
        if (body == null || body.size >= 8192) return false
        val text = body.decodeToString().lowercase()
        val mentionsKey = text.contains("api key") || text.contains("api_key")
            || text.contains("apikey") || text.contains("authentication")
            || text.contains("unauthorized")
        val soundsWrong = text.contains("incorrect") || text.contains("invalid")
            || text.contains("missing") || text.contains("provided")
            || text.contains("expired") || text.contains("unauthorized")
        return mentionsKey && soundsWrong
    }

    /**
     * `GET {baseURL}/models` with optional auth. [authHeaderName] mirrors
     * `Provider.authHeaderName` (null → `Authorization: Bearer <key>`, set → used
     * as-is). Auth is sent only when the key is non-empty, so keyless local
     * servers work. Returns the parsed model list, or a specific failure.
     *
     * [fetch] is the injected HTTP seam. Production is OkHttp with an explicit
     * 12 s timeout.
     */
    suspend fun probe(
        baseUrl: String,
        authHeaderName: String? = null,
        apiKey: String = "",
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray> = ::liveFetch,
    ): ProbeResult {
        val url = baseUrl.trimEnd('/') + "/models"
        val key = apiKey.trim()
        val headers = LinkedHashMap<String, String>()
        CredentialHeaders.attach(headers, key, authHeaderName)

        val list = when (val r = fetchList(url, apiKey, authHeaderName, fetch) { decode(it) }) {
            is Fetched.Failure -> return ProbeResult.Failed(r.kind)
            is Fetched.Success -> r.value
        }
        if (list.data.isEmpty()) return ProbeResult.Failed(FailureKind.BAD_RESPONSE)

        val seen = HashSet<String>()
        val models = ArrayList<KnownModel>()
        for (m in list.data) {
            if (ModelCatalog.isNonChat(m.id)) continue
            if (!seen.add(m.id.lowercase())) continue
            // A self-hosted id is usually `{uploader}/{repo}:{quant}`
            // (`bartowski/Qwen2.5-7B-Instruct-GGUF:Q4_K_M`), so the namespace
            // names whoever published the GGUF, not the model's vendor —
            // grouping by it filed Qwen under "Bartowski". Family first here,
            // namespace only as the fallback.
            models.add(
                KnownModel(
                    id = m.id,
                    displayName = ModelCatalog.displayName(m.id),
                    vendor = ModelCatalog.familyVendor(ModelCatalog.slug(m.id))
                        ?: ModelCatalog.vendorLabel(m.id),
                    // A plain list carries no price. For a FIRST-PARTY VENDOR host the shipped
                    // snapshot fills it — exact model match only, blank otherwise. A self-hosted
                    // server stays blank: there is no list price for someone's own machine.
                    price = ListPriceSnapshot.price(hostOf(baseUrl), m.id),
                    contextWindow = m.contextLabel,
                ),
            )
        }
        return if (models.isEmpty()) ProbeResult.Failed(FailureKind.BAD_RESPONSE)
        else ProbeResult.Connected(models)
    }

    fun decode(body: String): OpenAIModelsResponse =
        wire.decodeFromString(OpenAIModelsResponse.serializer(), body)

    private val wire = Json { ignoreUnknownKeys = true }

    // public, not internal: an `internal` suspend function used as another
    // file's default argument crashes the Kotlin backend during IR lowering
    // ("has no continuation"). The adapters all default to it.
    suspend fun liveFetch(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        // The browser calls this from a composition scope. Off the main thread or
        // Android throws NetworkOnMainThreadException, which the near.ai branch's
        // runCatching swallowed into "OFFLINE" (emulator, 2026-09-04).
        val client = TeemoonHttp.builder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(url).get()
        for ((k, v) in headers) req.addHeader(k, v)
        client.newCall(req.build()).execute().use { resp ->
            resp.code to resp.body.bytes()
        }
    }
}

/**
 * `GET {base}/models` — the OpenAI "list models" response, plus the extensions
 * self-hosted servers add. Every field below was observed on a live server;
 * they are null on servers that don't send them.
 */
@Serializable
data class OpenAIModelsResponse(
    val `object`: String? = null,
    val data: List<Model>,
) {
    @Serializable
    data class Model(
        /** The id you send as `model` in a chat request. */
        val id: String,
        val `object`: String? = null,
        val created: Int? = null,
        /**
         * "llamacpp" · "library" (Ollama) · "organization_owner" (LM Studio) —
         * too generic to identify the server, which is why kind comes from a
         * detection probe instead.
         */
        val owned_by: String? = null,
        val aliases: List<String>? = null,
        val tags: List<String>? = null,
        val status: Status? = null,
        /**
         * Modalities the loaded model accepts. Deliberately NOT mapped into
         * [ModelCapability]: that type expresses "known" as a whole set, so
         * recording vision here would also assert "known: no tools" and make
         * teemoon withhold tools from a llama.cpp model that supports them
         * (null = unknown = optimistic).
         */
        val architecture: Architecture? = null,
        val source: String? = null,
        val can_remove: Boolean? = null,
        val meta: Meta? = null,
    ) {
        @Serializable
        data class Status(
            val value: String? = null,
            val args: List<String>? = null,
        )
        @Serializable
        data class Architecture(
            val input_modalities: List<String>? = null,
            val output_modalities: List<String>? = null,
        )
        @Serializable
        data class Meta(
            val n_ctx_train: Int? = null,
            val n_ctx: Int? = null,
            val n_params: Long? = null,
            val n_embd: Int? = null,
            val n_vocab: Int? = null,
            val size: Long? = null,
            val ftype: String? = null,
        )

        /**
         * "32k · Q4_K_M" from `meta` when the server sends it, else empty —
         * the row then shows nothing rather than a guess.
         */
        val contextLabel: String
            get() {
                val ctx = ModelCatalog.contextLabel(meta?.n_ctx ?: meta?.n_ctx_train)
                return listOfNotNull(ctx, meta?.ftype)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
            }
    }
}
