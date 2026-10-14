// ports: teemoon/Confidential/AttestationService.swift @ v1.0.4 (fetch: three independent legs; only a blackout throws)
//        teemoon/Confidential/AttestationService+Reports.swift @ v1.0.1
//        teemoon/Confidential/AttestationService+Model.swift @ v1.0.4 (a 4xx is final)
//        teemoon/Confidential/AttestationService+GPU.swift @ v1.0.4 (status before decode; a 4xx is final)
//        teemoon/Confidential/AttestationService+Routing.swift @ v1.0.1
//        teemoon/Inference/ConfidentialLanguageModel.swift @ v1.0.1 (TEEContext only)
//
// Orchestrates the near.ai remote attestation report: wire envelopes, host
// routing, the GPU node fetch, and the model Ed25519 race. The resulting data
// model is AttestationRecord. The network is a seam ([AttestationHttp]) so
// every path here runs offline under test.
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.URLEncoder

/** GET [url] with [headers] → (status, body). Throws on transport failure. */
typealias AttestationHttp = suspend (url: String, headers: Map<String, String>) -> Pair<Int, ByteArray>

object AttestationService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val okHttp: AttestationHttp = run {
        val client: OkHttpClient by lazy { TeemoonHttp.builder().callTimeout(15, TimeUnit.SECONDS).build() }   // lazy: class-init OkHttp fails under layoutlib (see EndpointDirectory.liveClient)
        val fn: AttestationHttp = { url, headers ->
            withContext(Dispatchers.IO) {
                val b = Request.Builder().url(url).get(); headers.forEach { (k, v) -> b.header(k, v) }
                client.newCall(b.build()).execute().use { it.code to it.body.bytes() }
            }
        }
        fn
    }

    fun randomNonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()

    /** The attestation URL carrying a fresh random nonce, so the caller can verify its echo in report_data[32..64] (anti-replay). */
    fun attestationUrl(baseUrl: String, nonce: String, signingAlgo: String = "ecdsa", model: String? = null): String {
        val base = baseUrl.trimEnd('/') + "/attestation/report"
        val params = buildList {
            if (model != null) add("model=" + URLEncoder.encode(model, "UTF-8"))
            add("signing_algo=$signingAlgo"); add("nonce=$nonce")
        }
        return base + "?" + params.joinToString("&")
    }

    private fun authHeaders(apiKey: String) = if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    // MARK: - Wire envelopes (+Reports)

    /** `/v1/attestation/report`: `{ "gateway_attestation": { "intel_quote", "signing_address", "info": { "compose_hash", "os_image_hash", "tcb_info": { "mrtd", "app_compose" } } } }` */
    @Serializable data class Report(@SerialName("gateway_attestation") val gatewayAttestation: GatewayAttestation)
    @Serializable data class GatewayAttestation(@SerialName("intel_quote") val intelQuote: String? = null, val info: Info? = null, @SerialName("signing_address") val signingAddress: String? = null)
    @Serializable data class Info(@SerialName("compose_hash") val composeHash: String? = null, @SerialName("os_image_hash") val osImageHash: String? = null, @SerialName("tcb_info") val tcbInfo: TcbInfo? = null)
    @Serializable data class TcbInfo(val mrtd: String? = null, @SerialName("app_compose") val appCompose: String? = null)

    /** Flat response from a direct GPU inference node. `nvidia_payload` arrives as a JSON-encoded string OR an object; both are read. */
    class GpuNodeReport(
        val arch: String?, val composeHash: String?, val composeManifest: String?, val actions: List<CmAction>,
        val signingAddress: String?, val intelQuote: String?,
        /** The model this node says it serves — verified against the requested model before any of the report's data is trusted. */
        val modelName: String?,
    ) {
        @Serializable data class CmAction(val action: String? = null, val file: String? = null, val commit: String? = null, val tag: String? = null, val timestamp: String? = null, @SerialName("file_sha256") val fileSha256: String? = null)
        /** The most recent compose_up action (the currently running model YAML). */
        val latestComposeUp: CmAction? get() = actions.lastOrNull { it.action == "compose_up" }
        /** Newest and previous compose_up timestamps — the operator's clock inside the signed action log. */
        val composeUpTimestamps: Pair<String?, String?> get() { val ups = actions.filter { it.action == "compose_up" }.mapNotNull { it.timestamp }; return ups.lastOrNull() to ups.getOrNull(ups.size - 2) }
        val latestModelFileHash: String? get() = latestComposeUp?.fileSha256

        companion object {
            fun decode(data: ByteArray): GpuNodeReport? {
                val o = runCatching { Json.parseToJsonElement(String(data, Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
                fun str(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val payload = when (val p = o["nvidia_payload"]) {
                    is JsonPrimitive -> p.contentOrNull?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    is JsonObject -> p
                    else -> null
                }
                val info = o["info"] as? JsonObject
                val actions = (o["compose_manager_attestation"] as? JsonObject)?.get("actions")?.let { a -> runCatching { a.jsonArray.map { json.decodeFromJsonElement(CmAction.serializer(), it) } }.getOrNull() } ?: emptyList()
                return GpuNodeReport(str(payload, "arch"), str(info, "compose_hash"), str(info?.get("tcb_info") as? JsonObject, "app_compose"), actions,
                    str(o, "signing_address"), str(o, "intel_quote"), str(o, "model_name"))
            }
        }
    }

    /** Data extracted from a GPU inference node attestation report. */
    data class GpuNodeData(
        val arch: String?, val composeHash: String?, val composeManifest: String?, val modelFileHash: String?, val modelComposePath: String?,
        val modelComposeCommit: String?, val modelComposeTag: String?, val modelDeployedAt: String?, val modelPreviouslyDeployedAt: String?,
        val signingAddress: String?, val quoteVerification: TdxVerificationResult?, val intelQuote: String?, val nonce: String?,
    )

    /** `/v1/attestation/report?signing_algo=ed25519[&model=…]`: the current format (top-level + `all_attestations`) and the legacy `model_attestations` array. */
    @Serializable data class Ed25519Report(
        @SerialName("model_name") val modelName: String? = null, @SerialName("signing_public_key") val signingPublicKey: String? = null,
        @SerialName("intel_quote") val intelQuote: String? = null, @SerialName("nvidia_payload") val nvidiaPayload: String? = null,
        val info: NodeInfo? = null, @SerialName("all_attestations") val allAttestations: List<ModelAttestation>? = null,
        @SerialName("model_attestations") val modelAttestations: List<ModelAttestation>? = null,
    ) {
        @Serializable data class NodeInfo(@SerialName("os_image_hash") val osImageHash: String? = null)
        @Serializable data class ModelAttestation(
            @SerialName("model_name") val modelName: String? = null, @SerialName("signing_public_key") val signingPublicKey: String? = null,
            @SerialName("intel_quote") val intelQuote: String? = null, @SerialName("nvidia_payload") val nvidiaPayload: String? = null, val info: NodeInfo? = null)
        private val first get() = allAttestations?.firstOrNull() ?: modelAttestations?.firstOrNull()
        val resolvedModelName get() = modelName ?: first?.modelName
        val resolvedKey get() = signingPublicKey ?: first?.signingPublicKey
        val resolvedQuote get() = intelQuote ?: first?.intelQuote
        val resolvedNvidiaPayload get() = nvidiaPayload ?: first?.nvidiaPayload
        /** Model-node OS image hash from whichever shape arrived: flat `info.os_image_hash` or `model_attestations[0].info.os_image_hash`. */
        val resolvedOSImageHash get() = info?.osImageHash ?: first?.info?.osImageHash
    }

    /** Result of fetching the model's Ed25519 attestation data. */
    data class ModelAttestationData(val ed25519PubKey: ByteArray, val quoteVerification: TdxVerificationResult?, val gpuArch: String?, val intelQuote: String?, val nonce: String?, val nvidiaPayload: String?, val osImageHash: String?)

    /** Test hook (not debug-gated on Android): the model node's `os_image_hash` from a raw model-attestation body, both wire shapes. */
    fun modelOSImageHash(fromModelReport: ByteArray): String? = runCatching { json.decodeFromString(Ed25519Report.serializer(), String(fromModelReport, Charsets.UTF_8)) }.getOrNull()?.resolvedOSImageHash

    // MARK: - Fetch

    /**
     * Fetches the attestation report as three independent claims — the
     * gateway's own quote, the model node's quote + Ed25519 key, the GPU
     * node's evidence. Any leg may be missing; its fields are then null/empty.
     * Throws only when no leg produced anything. The model leg's wait is
     * BOUNDED ([modelWaitMs]): the gateway record verifies in <1s, while the
     * model endpoint can be slow (TEE cold start) or down independently.
     */
    suspend fun fetch(
        baseUrl: String, apiKey: String, model: String = "", providerID: String, gpuNodeUrl: String? = null,
        http: AttestationHttp = okHttp, directBase: suspend (String) -> String? = { NearAIModelCatalog.endpointDirectory.directBase(it) },
        now: () -> Instant = { Instant.now() }, modelWaitMs: Long = 15_000, log: (String) -> Unit = {},
    ): AttestationRecord = coroutineScope {
        val gpuTask = gpuNodeUrl?.let { url -> async { fetchGpuData(url, apiKey, model, http, directBase, log) } }
        val modelAttTask = if (model.isEmpty()) null else async { fetchModelAttestation(baseUrl, model, apiKey, http, directBase, log) }

        val gateway = fetchGatewayAttestation(baseUrl, apiKey, http, log)
        val gpu = gpuTask?.await()
        // Do not block the record on the model leg's full retry budget: what
        // the other legs proved is usable now; refreshAttestationIfStale refetches.
        val modelAtt = modelAttTask?.let { task -> withTimeoutOrNull(modelWaitMs) { task.await() } ?: run { task.cancel(); null } }
        log("fetch() results: gateway=${if (gateway != null) "ok" else "unavailable"}, modelAtt=${if (modelAtt != null) "key present" else "unavailable"}, gpu=${gpu != null}")

        if (gateway == null && modelAtt == null && gpu == null)
            throw IOException("Attestation fetch failed: no evidence from the gateway, the model node, or the GPU node")

        // Every gateway field below reads through `gw`; a missing gateway must
        // leave them null/empty — never verify or report a quote it did not serve.
        val gw = gateway?.first?.gatewayAttestation
        val quoteVerification = gw?.intelQuote?.takeIf { it.isNotEmpty() }?.let { runCatching { TdxQuoteVerifier.verify(it) }.getOrNull() }
        AttestationRecord(
            composeHash = gw?.info?.composeHash ?: "", mrtd = gw?.info?.tcbInfo?.mrtd ?: "", osImageHash = gw?.info?.osImageHash ?: "",
            intelQuote = gw?.intelQuote ?: "", composeManifest = gw?.info?.tcbInfo?.appCompose,
            // Prefer GPU arch from the model attestation (always available from the gateway), fall back to the direct GPU node.
            gpuArch = modelAtt?.gpuArch ?: gpu?.arch,
            gpuNodeComposeHash = gpu?.composeHash, modelFileHash = gpu?.modelFileHash,
            signingAddress = gw?.signingAddress, gpuSigningAddress = gpu?.signingAddress, modelEd25519PubKey = modelAtt?.ed25519PubKey,
            quoteVerification = quoteVerification, gpuQuoteVerification = gpu?.quoteVerification, modelQuoteVerification = modelAtt?.quoteVerification,
            fetchedAt = now(), providerID = providerID,
            // The gateway's own report never carries the model node's hash (no `model=` param is sent); only the model leg can supply it.
            modelOSImageHash = modelAtt?.osImageHash, modelIntelQuote = modelAtt?.intelQuote, gpuIntelQuote = gpu?.intelQuote,
            gatewayNonce = gateway?.second, modelNonce = modelAtt?.nonce, gpuNonce = gpu?.nonce, nvidiaPayload = modelAtt?.nvidiaPayload,
            gpuNodeComposeManifest = gpu?.composeManifest, modelComposePath = gpu?.modelComposePath, modelComposeCommit = gpu?.modelComposeCommit,
            modelComposeTag = gpu?.modelComposeTag, modelDeployedAt = gpu?.modelDeployedAt, modelPreviouslyDeployedAt = gpu?.modelPreviouslyDeployedAt, model = model)
    }

    /**
     * The gateway's own attestation and the nonce it must echo, or null when
     * the gateway gave nothing usable. Up to 3 attempts (1s, 2s backoff) for
     * a thrown transport error or a 5xx; a 4xx or an undecodable body is
     * final on the first answer — retrying a 401 cannot succeed. Never throw
     * from here: a gateway failure must not discard the other legs (iOS 2371250 —
     * a Mac send refused "E2EE could not be established" while the direct host
     * had ALREADY served a valid key: an answered 401's body reached the decoder,
     * and the decode error escaped with the good key).
     */
    suspend fun fetchGatewayAttestation(baseUrl: String, apiKey: String, http: AttestationHttp, log: (String) -> Unit = {}): Pair<Report, String>? {
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(1_000L * (1 shl (attempt - 1)))
            val nonce = randomNonce()
            val (status, data) = try { http(attestationUrl(baseUrl, nonce), authHeaders(apiKey)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("Gateway fetch attempt ${attempt + 1} failed: ${e.message}"); continue
            }
            if (status >= 500) { log("Gateway fetch attempt ${attempt + 1} HTTP $status: server error"); continue }
            if (status >= 400) { log("Gateway fetch HTTP $status: no gateway attestation"); return null }
            val report = runCatching { json.decodeFromString(Report.serializer(), String(data, Charsets.UTF_8)) }.getOrNull()
                ?: run { log("Gateway fetch HTTP $status: decode failed"); return null }
            return report to nonce
        }
        log("Gateway attestation unavailable after 3 attempt(s)")
        return null
    }

    /** The shape ConfidentialSession.Services.fetchRecord takes. */
    fun recordFetcher(http: AttestationHttp = okHttp, log: (String) -> Unit = {}): suspend (String, String, String, String, String) -> AttestationRecord =
        { baseUrl, apiKey, model, providerID, gpuNodeUrl -> fetch(baseUrl, apiKey, model, providerID, gpuNodeUrl.ifEmpty { null }, http = http, log = log) }

    // MARK: - The model Ed25519 race (+Model)

    /**
     * Fetches the model's Ed25519 E2EE key + TDX attestation, racing the
     * documented sources and returning the first VALID attestation: the direct
     * completions host (the host *is* the model — no DB-backed resolution) and
     * the gateway (`?model=`) as a concurrent fallback. A fast failure never
     * beats a slower success. The winner's own nonce is returned.
     */
    suspend fun fetchModelAttestation(
        baseUrl: String, model: String, apiKey: String, http: AttestationHttp, directBase: suspend (String) -> String?, log: (String) -> Unit = {},
    ): ModelAttestationData? {
        val sources = directSources(model, directBase)
        return firstSuccess(sources.map { src -> suspend { fetchModelAttestation(src.base, false, model, apiKey, if (src.authoritative) 8 else 1, src.label, http, log) } } +
            listOf(suspend { fetchModelAttestation(baseUrl, true, model, apiKey, 8, "gateway", http, log) }))
    }

    data class DirectSource(val base: String, val label: String, val authoritative: Boolean)

    /** The direct hosts to try for [model], most-trusted first: the endpoints directory, else best-effort candidates derived from the id — all gated by the model_name check. */
    suspend fun directSources(model: String, directBase: suspend (String) -> String?): List<DirectSource> {
        directBase(model)?.let { return listOf(DirectSource(it, "direct", true)) }
        return derivedDirectHostSlugs(model).map { slug -> DirectSource("https://$slug.completions.near.ai/v1", "direct?$slug", false) }
    }

    /** One model-attestation source: the retry loop against a single endpoint. `expectedModel` is verified against the response's `model_name` — a mismatch is rejected so E2EE never binds to the wrong model. */
    suspend fun fetchModelAttestation(
        endpoint: String, includeModelParam: Boolean, expectedModel: String, apiKey: String, maxAttempts: Int, label: String, http: AttestationHttp, log: (String) -> Unit = {},
    ): ModelAttestationData? {
        val nonce = randomNonce()
        val url = attestationUrl(endpoint, nonce, "ed25519", if (includeModelParam) expectedModel else null)
        for (attempt in 0 until maxOf(1, maxAttempts)) {
            if (!currentCoroutineContext().isActive) return null
            if (attempt > 0) { delay(minOf(2_000L * (1L shl minOf(attempt - 1, 3)), 8_000L)); if (!currentCoroutineContext().isActive) return null }
            val (status, data) = try { http(url, authHeaders(apiKey)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("[$label] model attestation attempt ${attempt + 1}: network request failed"); continue
            }
            if (status >= 500) { log("[$label] model attestation attempt ${attempt + 1} HTTP $status: server error"); continue }
            // Retry taxonomy, shared with fetchGatewayAttestation: a thrown transport error or a 5xx is retried; a 4xx
            // is final — a 401 cannot succeed on retry. A 2xx without a key yet (TEE cold start) stays transient.
            if (status >= 400) { log("[$label] model attestation HTTP $status: no attestation from this source"); return null }
            val report = runCatching { json.decodeFromString(Ed25519Report.serializer(), String(data, Charsets.UTF_8)) }.getOrNull()
            if (report == null) { log("[$label] model attestation attempt ${attempt + 1} HTTP $status: decode failed"); continue }
            // Reject a host serving a different model than requested — otherwise E2EE would encrypt to the wrong model's key.
            val served = report.resolvedModelName
            if (served != null && !served.equals(expectedModel, ignoreCase = true)) { log("[$label] model attestation: host serves $served, expected $expectedModel — rejected"); return null }
            val hex = report.resolvedKey
            if (hex == null) { log("[$label] model attestation attempt ${attempt + 1} HTTP $status: signing_public_key missing"); continue }
            val keyData = hex.hexToBytesOrNull()?.takeIf { it.size == 32 }
            if (keyData == null) { log("[$label] model attestation attempt ${attempt + 1}: invalid key hex"); continue }
            val quoteVerification = report.resolvedQuote?.let { runCatching { TdxQuoteVerifier.verify(it) }.getOrNull() }
            val gpuArch = report.resolvedNvidiaPayload?.let { p -> runCatching { (Json.parseToJsonElement(p).jsonObject["arch"] as? JsonPrimitive)?.contentOrNull }.getOrNull() }
            quoteVerification?.let { qv ->
                val bound = qv.measurements.reportData.size >= 32 && qv.measurements.reportData.copyOfRange(0, 32).contentEquals(keyData)
                log("[$label] model attestation: quote ${if (qv.isVerified) "verified" else "FAILED"}, key bound: $bound, gpu: ${gpuArch ?: "nil"}")
            }
            return ModelAttestationData(keyData, quoteVerification, gpuArch, report.resolvedQuote, nonce, report.resolvedNvidiaPayload, report.resolvedOSImageHash)
        }
        log("[$label] model attestation: Ed25519 key unavailable after ${maxOf(1, maxAttempts)} attempt(s)")
        return null
    }

    /** Awaits the first task to produce a non-null value, then cancels the rest. A task that finishes with null does not win. Null only if all fail. */
    suspend fun <T : Any> firstSuccess(tasks: List<suspend () -> T?>): T? = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope null
        val results = Channel<T?>(tasks.size)
        val jobs = tasks.map { t -> launch { results.send(try { t() } catch (e: CancellationException) { null } catch (_: Exception) { null }) } }
        var winner: T? = null
        for (i in tasks.indices) { val v = results.receive(); if (v != null) { winner = v; break } }
        jobs.forEach { it.cancel() }
        winner
    }

    // MARK: - GPU node (+GPU)

    /** Used by the TEE signature verifier to check for key rotation on mismatch. */
    suspend fun fetchSigningAddress(nodeUrl: String, apiKey: String, http: AttestationHttp = okHttp, log: (String) -> Unit = {}): String? {
        val data = try { http(attestationUrl(nodeUrl, randomNonce()), authHeaders(apiKey)).second } catch (e: CancellationException) { throw e } catch (_: Exception) { log("GPU signing address fetch failed — no data from ${host(nodeUrl)}"); return null }
        val report = GpuNodeReport.decode(data) ?: run { log("GPU report decode failed"); return null }
        report.signingAddress?.let { return it }
        return addressFromReportData(report.intelQuote)
    }

    /** An older node with no signing_address: the address lives in the first 20 bytes of report_data. */
    private fun addressFromReportData(quoteHex: String?): String? {
        val quote = quoteHex?.let { runCatching { QuoteParser.parseHex(it) }.getOrNull() } ?: return null
        val rd = quote.body.reportData
        if (rd.size < 20 || rd.copyOfRange(0, 20).all { it == 0.toByte() }) return null
        return "0x" + rd.copyOfRange(0, 20).toHex()
    }

    /** Fetches GPU attestation data from a direct model inference node. Retries up to 3 times (1s, 2s, 4s) to handle cold-start delays. */
    suspend fun fetchGpuData(nodeUrl: String, apiKey: String, expectedModel: String, http: AttestationHttp, directBase: suspend (String) -> String?, log: (String) -> Unit = {}): GpuNodeData? {
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(1_000L * (1 shl (attempt - 1)))
            val nonce = randomNonce()
            val (status, data) = try { http(attestationUrl(nodeUrl, nonce), authHeaders(apiKey)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("GPU fetch attempt ${attempt + 1} failed for ${host(nodeUrl)}: ${e.message}"); continue
            }
            // Same taxonomy as the gateway and model legs. Check the status BEFORE decoding: every field of
            // GpuNodeReport is optional, so an error body decodes cleanly into empty "evidence".
            if (status >= 500) { log("GPU fetch attempt ${attempt + 1} HTTP $status: server error"); continue }
            if (status >= 400) { log("GPU fetch HTTP $status: no GPU attestation"); return null }
            val report = GpuNodeReport.decode(data)
            if (report == null) { log("GPU fetch attempt ${attempt + 1} HTTP $status — decode failed"); continue }
            // Reject a misrouted node before trusting anything in the report. A retry re-rolls the load balancer; if every attempt
            // misroutes, null leaves the model-layer fields empty (honest) rather than displaying another model's attested stack.
            if (report.modelName != null && !report.modelName.equals(expectedModel, ignoreCase = true)) {
                val servedHost = directBase(report.modelName)?.let(::host); val expectedHost = directBase(expectedModel)?.let(::host)
                if (!gpuReportServesExpectedModel(report.modelName, expectedModel, expectedHost, servedHost)) {
                    log("GPU node ${host(nodeUrl)} attempt ${attempt + 1} serves ${report.modelName}, expected $expectedModel — rejected (misrouted node)"); continue
                }
            }
            val gpuQuoteVerification = report.intelQuote?.let { runCatching { TdxQuoteVerifier.verify(it) }.getOrNull() }
            val resolvedAddr = report.signingAddress ?: addressFromReportData(report.intelQuote)
            val (latest, previous) = report.composeUpTimestamps
            return GpuNodeData(report.arch, report.composeHash, report.composeManifest, report.latestModelFileHash, report.latestComposeUp?.file,
                report.latestComposeUp?.commit, report.latestComposeUp?.tag, latest, previous, resolvedAddr, gpuQuoteVerification, report.intelQuote, nonce)
        }
        return null
    }

    private fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

    // MARK: - Routing (+Routing)

    /**
     * Whether a GPU node report may be trusted for [expected], given the model
     * the node says it serves and where the directory maps that served id.
     * Rules: no expectation, or the node doesn't say → accept; case-insensitive
     * id match → accept; served id is a directory ALIAS of the expected one
     * (both resolve to the SAME host) → accept; anything else → misrouted, reject.
     *
     * The alias check compares the served host to the EXPECTED model's host,
     * NOT to whatever host we happened to query: a fallback that landed on a
     * different model's host would otherwise let that model authenticate itself.
     */
    fun gpuReportServesExpectedModel(served: String?, expected: String, expectedModelHost: String?, servedModelHost: String?): Boolean {
        if (expected.isEmpty() || served.isNullOrEmpty()) return true
        if (served.equals(expected, ignoreCase = true)) return true
        if (expectedModelHost != null && servedModelHost != null && expectedModelHost == servedModelHost) return true
        return false
    }

    /** Best-effort direct-host slug candidates from a model id: `zai-org/GLM-5.1-FP8` → `glm-5-1`, `Qwen/Qwen3.5-122B-A10B` → `qwen35-122b`. The model_name check picks the right one. */
    fun derivedDirectHostSlugs(model: String): List<String> {
        val name = (model.split('/').lastOrNull { it.isNotEmpty() } ?: model).lowercase()
        val base = name.replace(Regex("(-fp8|-fp16|-bf16|-int8|-int4|-a\\d+b|-\\d{4})+$"), "")
        val slugs = ArrayList<String>()
        for (variant in listOf(base.replace(".", "-"), base.replace(".", ""), base)) {
            val slug = variant.filter { it.isLetterOrDigit() || it == '-' }
            if (slug.isNotEmpty() && slug !in slugs) slugs.add(slug)
        }
        return slugs
    }
}

/** Everything a TEE-attested request needs beyond the plain provider config. */
class TeeContext(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val attestation: AttestationRecord,
    /** Direct GPU node URL for re-fetching attestation on signing key rotation. */
    val gpuNodeUrl: String?,
    /** E2EE peer; null when the model has no Ed25519 public key (or it converts to a low-order point). */
    val e2eePeer: E2EEPeer?,
) {
    companion object {
        /**
         * Derives the context for one attested request. Completions are routed
         * through the gateway, which generates and stores ECDSA signatures;
         * direct GPU-node URLs do not reliably sign.
         */
        fun from(provider: Provider, apiKey: String, attestation: AttestationRecord, log: (String) -> Unit = {}): TeeContext? {
            val gwBase = provider.openAIBaseUrl ?: return null
            val peer = attestation.modelEd25519PubKey?.let { key ->
                try { E2EEPeer(key) } catch (e: Exception) { log("[E2EE] peer creation failed: $e"); null }
            }
            return TeeContext(gwBase, provider.model, apiKey, attestation, provider.directGPUNodeUrl, peer)
        }
    }
}
