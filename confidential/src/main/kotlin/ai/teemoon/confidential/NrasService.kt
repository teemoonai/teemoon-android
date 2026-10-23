// ports: teemoon/Confidential/NRASService.swift @ d382a55 (the service; NrasVerification is its own file)
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
//  3. The overall JWT is ES384-signed by a key in NVIDIA's JWKS, its `iss`
//     is NRAS, it is inside nbf..exp, and its `eat_nonce` is our nonce
//     (nearai/inference-sdk utils/nvidia.py).
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.doubleOrNull
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
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

/**
 * NVIDIA's signing keys. They rotate several times a day, so the live
 * source refetches on an unknown kid rather than pinning.
 */
fun interface NrasKeySource {
    /** The JWKS bytes; throws when the key set cannot be fetched. */
    suspend fun jwks(refresh: Boolean): ByteArray
}

/** The live key set from NRAS itself (the §4 host already in use), cached for an hour. */
object LiveNrasKeys : NrasKeySource {
    const val URL = "https://nras.attestation.nvidia.com/.well-known/jwks.json"
    private val client by lazy { TeemoonHttp.builder().callTimeout(60, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    private var cached: Pair<ByteArray, Long>? = null

    override suspend fun jwks(refresh: Boolean): ByteArray = lock.withLock {
        val hit = cached
        if (!refresh && hit != null && System.currentTimeMillis() - hit.second < 3_600_000) return@withLock hit.first
        val data = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(URL).get().build()).execute().use { r ->
                if (r.code != 200) throw IOException("HTTP ${r.code} from NRAS JWKS")
                r.body.bytes()
            }
        }
        cached = data to System.currentTimeMillis()
        data
    }
}

/**
 * [now] is the clock the token's validity window is checked against, in unix
 * seconds; tests pin it to the capture instant.
 */
class NrasService(
    private val http: NrasHttpClient = OkHttpNrasHttp(),
    private val keys: NrasKeySource = LiveNrasKeys,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val log: (String) -> Unit = {},
) {

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
        val jwt = overallJwt(response) ?: return NrasVerification.Inconclusive("unrecognized NRAS response")
        checkToken(jwt, payloadNonce)?.let { return it }
        val verdict = overallVerdict(response) ?: return NrasVerification.Inconclusive("unrecognized NRAS response")
        if (!verdict) { log("[nras] NVIDIA verdict: FAIL"); return NrasVerification.Failed("NVIDIA attestation verdict: FAIL") }
        log("[nras] GPU attestation verified — verdict PASS, nonce matched")
        return NrasVerification.Verified
    }

    /** null when the overall token is authentic, fresh and ours; otherwise the verdict to return. */
    suspend fun checkToken(jwt: String, nonceHex: String): NrasVerification? {
        val segments = jwt.split('.')
        val header = segments.takeIf { it.size == 3 }?.let { base64UrlDecode(it[0]) }
            ?.let { runCatching { Json.parseToJsonElement(String(it, Charsets.UTF_8)) as? JsonObject }.getOrNull() }
        val claims = decodeJwtClaims(jwt)
        val signature = segments.takeIf { it.size == 3 }?.let { base64UrlDecode(it[2]) }
        if (header == null || claims == null || signature == null) return NrasVerification.Inconclusive("unrecognized NRAS token")
        if (string(header, "alg") != "ES384") return NrasVerification.Failed("NRAS token is not ES384-signed")
        val kid = string(header, "kid") ?: return NrasVerification.Failed("NRAS token names no signing key")
        if (string(claims, "iss") != ISSUER) return NrasVerification.Failed("NRAS token issuer is not NVIDIA")
        val nonce = string(claims, "eat_nonce")
        if (nonce == null || !nonce.equals(nonceHex, ignoreCase = true)) return NrasVerification.Failed("NRAS token nonce mismatch (possible replay)")
        val nbf = number(claims, "nbf"); val exp = number(claims, "exp")
        if (nbf == null || exp == null || number(claims, "iat") == null) return NrasVerification.Failed("NRAS token has no validity window")
        val t = now()
        if (!(t + LEEWAY >= nbf && t - LEEWAY <= exp)) return NrasVerification.Failed("NRAS token expired or not yet valid")

        var key: PublicKey? = null
        for (refresh in listOf(false, true)) {
            if (key != null) break
            val data = try { keys.jwks(refresh) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("[nras] signing keys unreachable: $e")
                return NrasVerification.Inconclusive("NVIDIA signing keys unreachable")
            }
            key = publicKey(kid, data)
        }
        if (key == null) return NrasVerification.Inconclusive("NRAS signing key $kid not in NVIDIA's key set")
        val signed = "${segments[0]}.${segments[1]}".toByteArray(Charsets.UTF_8)
        val valid = joseToDer(signature)?.let { der ->
            runCatching { Signature.getInstance("SHA384withECDSA").run { initVerify(key); update(signed); verify(der) } }.getOrDefault(false)
        } ?: false
        if (!valid) {
            log("[nras] token signature invalid")
            return NrasVerification.Failed("NRAS token signature invalid")
        }
        return null
    }

    companion object {
        const val ENDPOINT = "https://nras.attestation.nvidia.com/v3/attest/gpu"
        const val ISSUER = "https://nras.attestation.nvidia.com"
        /** Clock skew allowed on nbf/exp, seconds. */
        const val LEEWAY = 120.0

        private fun string(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun number(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        private val p384: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp384r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }

        /** The P-384 key [kid] names in [jwks], or null (absent, not EC P-384, or not a point on the curve). */
        fun publicKey(kid: String, jwks: ByteArray): PublicKey? {
            val obj = runCatching { Json.parseToJsonElement(String(jwks, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
            val jwk = (obj["keys"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { string(it, "kid") == kid } ?: return null
            if (string(jwk, "kty") != "EC" || string(jwk, "crv") != "P-384") return null
            val x = string(jwk, "x")?.let(::base64UrlDecode)?.takeIf { it.size == 48 } ?: return null
            val y = string(jwk, "y")?.let(::base64UrlDecode)?.takeIf { it.size == 48 } ?: return null
            val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
            // CryptoKit's x963 import rejects an off-curve point; the JDK's KeyFactory does not, so check here.
            val curve = p384.curve
            val prime = (curve.field as ECFieldFp).p
            if (point.affineX >= prime || point.affineY >= prime) return null
            val lhs = point.affineY.modPow(BigInteger.valueOf(2), prime)
            val rhs = point.affineX.pow(3).add(curve.a.multiply(point.affineX)).add(curve.b).mod(prime)
            if (lhs != rhs) return null
            return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p384)) }.getOrNull()
        }

        /** JOSE's raw r||s (48 + 48 bytes for ES384) as the DER SEQUENCE the JDK's ECDSA verifier takes. */
        fun joseToDer(raw: ByteArray): ByteArray? {
            if (raw.size != 96) return null
            fun integer(half: ByteArray): ByteArray {
                val v = half.dropWhile { it == 0.toByte() }.toByteArray().let { if (it.isEmpty()) byteArrayOf(0) else it }
                val body = if (v[0].toInt() and 0x80 != 0) byteArrayOf(0) + v else v
                return byteArrayOf(0x02, body.size.toByte()) + body
            }
            val seq = integer(raw.copyOfRange(0, 48)) + integer(raw.copyOfRange(48, 96))
            return byteArrayOf(0x30, seq.size.toByte()) + seq // at most 102 bytes: short-form length suffices
        }

        fun base64UrlDecode(s: String): ByteArray? {
            var b = s.replace('-', '+').replace('_', '/')
            while (b.length % 4 != 0) b += "="
            return runCatching { Base64.getDecoder().decode(b) }.getOrNull()
        }

        /** The overall JWT from `[["JWT", <token>], {per-GPU tokens}]`. */
        fun overallJwt(response: ByteArray): String? {
            val array = runCatching { Json.parseToJsonElement(String(response, Charsets.UTF_8)) as? JsonArray }.getOrNull() ?: return null
            for (element in array) {
                val pair = (element as? JsonArray)?.takeIf { it.size == 2 } ?: continue
                val label = (pair[0] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val token = (pair[1] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (label == "JWT" && token != null) return token
            }
            return null
        }

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

        /** Decodes a JWT's payload segment. Reads claims only; [checkToken] verifies. */
        fun decodeJwtClaims(jwt: String): JsonObject? {
            val segments = jwt.split('.')
            if (segments.size != 3) return null
            val data = runCatching { Base64.getUrlDecoder().decode(segments[1]) }.getOrNull() ?: return null
            return runCatching { Json.parseToJsonElement(String(data, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        }
    }
}
