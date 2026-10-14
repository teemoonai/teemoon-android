// ports: teemoon/Confidential/NRASService.swift @ v1.0.1 (the service; NrasVerification is its own file)
//
// Verifies the GPU attestation evidence (`nvidia_payload`) from a near.ai
// model attestation against NVIDIA's Remote Attestation Service (NRAS) —
// the same check near.ai's own verifier performs (model_verifier.check_gpu):
//
//  1. The payload's embedded nonce must equal the nonce teemoon sent with
//     the attestation request (freshness — the evidence was generated for
//     this request, not replayed).
//  2. POST the payload to NRAS; the response is [["JWT", <overall>], {per-GPU}].
//     The overall JWT's `x-nvidia-overall-att-result` claim must be true.
//
// Trust model (matches the reference verifier): the verdict is trusted via
// TLS to nras.attestation.nvidia.com — the JWT's own signature is not
// independently verified against NVIDIA's JWKS.
//
// Fail-closed: unreachable NRAS, malformed payload/response, nonce
// mismatch, or a false verdict all yield a non-verified outcome (never
// silently green).
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.io.IOException

/** Minimal HTTP seam so tests can stub the NRAS response. Throws on transport failure or a non-2xx status. */
fun interface NrasHttpClient {
    suspend fun post(url: String, body: ByteArray): ByteArray
}

class OkHttpNrasHttp(private val client: OkHttpClient = TeemoonHttp.builder().callTimeout(60, TimeUnit.SECONDS).build()) : NrasHttpClient {
    override suspend fun post(url: String, body: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).header("Content-Type", "application/json").build()
        client.newCall(request).execute().use { r ->
            if (r.code !in 200..299) throw IOException("HTTP ${r.code} from NRAS")
            r.body.bytes()
        }
    }
}

class NrasService(private val http: NrasHttpClient = OkHttpNrasHttp(), private val log: (String) -> Unit = {}) {

    /**
     * Verifies one `nvidia_payload` JSON string. [expectedNonceHex] is the
     * nonce teemoon sent with the attestation request that returned this
     * payload; without it freshness cannot be proven, so the check fails.
     */
    suspend fun verify(payloadJson: String, expectedNonceHex: String?): NrasVerification {
        val payloadNonce = runCatching { (Json.parseToJsonElement(payloadJson) as? JsonObject)?.get("nonce") }.getOrNull()
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?: return NrasVerification.Inconclusive("GPU evidence payload unparseable")
        if (expectedNonceHex == null) return NrasVerification.Inconclusive("no request nonce recorded for GPU evidence")
        if (!payloadNonce.equals(expectedNonceHex, ignoreCase = true)) {
            log("[nras] evidence nonce mismatch — possible replay")
            return NrasVerification.Failed("GPU evidence nonce mismatch (possible replay)")
        }
        val response = try { http.post(ENDPOINT, payloadJson.toByteArray(Charsets.UTF_8)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("[nras] unreachable: $e")
            return NrasVerification.Inconclusive("NVIDIA attestation service unreachable")
        }
        val verdict = overallVerdict(response) ?: return NrasVerification.Inconclusive("unrecognized NRAS response")
        if (!verdict) { log("[nras] NVIDIA verdict: FAIL"); return NrasVerification.Failed("NVIDIA attestation verdict: FAIL") }
        log("[nras] GPU attestation verified — verdict PASS, nonce matched")
        return NrasVerification.Verified
    }

    companion object {
        const val ENDPOINT = "https://nras.attestation.nvidia.com/v3/attest/gpu"

        /** Extracts `x-nvidia-overall-att-result` from the overall JWT in an NRAS response (`[["JWT", <token>], {per-GPU tokens}]`). */
        fun overallVerdict(response: ByteArray): Boolean? {
            val array = runCatching { Json.parseToJsonElement(String(response, Charsets.UTF_8)) as? JsonArray }.getOrNull() ?: return null
            for (element in array) {
                val pair = (element as? JsonArray)?.takeIf { it.size == 2 } ?: continue
                val label = (pair[0] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val token = (pair[1] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (label != "JWT" || token == null) continue
                val claims = decodeJwtClaims(token) ?: return null
                val result = claims["x-nvidia-overall-att-result"] as? JsonPrimitive ?: return null
                return if (result.isString) (result.content.uppercase() == "PASS" || result.content.lowercase() == "true") else result.booleanOrNull
            }
            return null
        }

        /** Decodes a JWT's payload segment (base64url, no signature verification — see the trust-model note in the header). */
        fun decodeJwtClaims(jwt: String): JsonObject? {
            val segments = jwt.split('.')
            if (segments.size != 3) return null
            val data = runCatching { Base64.getUrlDecoder().decode(segments[1]) }.getOrNull() ?: return null
            return runCatching { Json.parseToJsonElement(String(data, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        }
    }
}
