// ports: teemoon/Confidential/CollateralService.swift @ v1.0.1
//
// Fetches Intel PCS collateral for a DCAP quote and assembles the JSON
// dcap-qvl's verify() expects — a port of the fetch recipe in
// dcap_qvl::collateral::CollateralClient, validated end-to-end against a real
// near.ai quote (the kit's collateral class).
//
// Two correctness traps the port preserves (both silently break verification
// if violated):
//  - `tcb_info` / `qe_identity` must be the BYTE-EXACT sub-object from the
//    PCS response body — the Intel signature covers those exact bytes, so
//    they are extracted as raw substrings, never parsed-and-re-serialized.
//  - Collateral is time-varying (fresh issueDate/nextUpdate per fetch), so
//    verify(nowSecs) must be given a time inside the returned window.
//
// Fail-closed: any fetch or parse failure throws, which DcapVerifier reports
// as an explicit verification failure with the reason.
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.net.URI

/** Minimal HTTP seam so tests can stub the PCS responses. GETs [url]; returns the body and headers for HTTP 2xx, throws otherwise. */
fun interface CollateralHttpClient {
    suspend fun get(url: String): Pair<ByteArray, Map<String, String>>
}

/** A non-2xx (or missing) response from a collateral endpoint, carrying which host/path failed so the reason survives to the UI. */
class CollateralHttpException(val status: Int, val host: String, val path: String) : Exception(
    if (status < 0) "no response from $host" else "HTTP $status from $host (${path.split('/').lastOrNull { it.isNotEmpty() } ?: path})")

class OkHttpCollateralHttp(private val client: OkHttpClient = TeemoonHttp.builder().callTimeout(30, TimeUnit.SECONDS).build()) : CollateralHttpClient {
    override suspend fun get(url: String): Pair<ByteArray, Map<String, String>> = withContext(Dispatchers.IO) {
        val u = URI(url)
        val resp = try { client.newCall(Request.Builder().url(url).get().build()).execute() } catch (e: Exception) { throw CollateralHttpException(-1, u.host ?: url, u.path ?: "") }
        resp.use {
            if (it.code !in 200..299) throw CollateralHttpException(it.code, u.host ?: "", u.path ?: "")
            val headers = HashMap<String, String>(); for (name in it.headers.names()) it.header(name)?.let { v -> headers[name] = v }
            it.body.bytes() to headers
        }
    }
}

/** Supplies PCCS collateral for a quote. Throws with a descriptive reason so a failure is diagnosable from the UI, not swallowed into a bare null. */
fun interface CollateralProvider {
    suspend fun collateralJson(rawQuote: ByteArray): ByteArray
}

sealed class CollateralException(message: String) : Exception(message) {
    object QuoteMissingPckChain : CollateralException("quote does not embed a PCK certificate chain (cert_type != 5)")
    class MissingHeader(name: String) : CollateralException("PCS response missing header $name")
    class MalformedResponse(what: String) : CollateralException("malformed PCS response: $what")
}

/**
 * Fetches PCCS collateral from Intel PCS (the collateral authority itself — no
 * third-party PCCS in the trust path) and assembles dcap-qvl's
 * QuoteCollateralV3 JSON.
 *
 * Caches the per-FMSPC collateral (TCB info, QE identity, CRLs — identical for
 * every machine of the same CPU SKU) so a record's gateway/model/GPU quotes
 * cost one PCS round trip, not three. Crucially the PCK certificate chain is
 * NOT part of that cache: it is machine-specific, so it is taken from each
 * quote individually. Caching it served one machine's PCK chain for another's
 * quote, which dcap-qvl rejects as "Signature is invalid for qe_report in quote".
 */
class IntelPcsCollateralProvider(
    private val http: CollateralHttpClient = OkHttpCollateralHttp(),
    private val baseUrl: String = INTEL_PCS,
    private val warn: (String) -> Unit = {},
) : CollateralProvider {
    private val lock = Mutex()
    private val fmspcCache = HashMap<String, Map<String, String>>()

    override suspend fun collateralJson(rawQuote: ByteArray): ByteArray = try { assemble(rawQuote) } catch (e: Exception) { warn("[collateral] fetch/assembly failed: $e"); throw e }

    /** The fields, before serialization — what the tests compare byte-exact per field, since JSON key order is not the contract. */
    suspend fun collateralFields(rawQuote: ByteArray): Map<String, String> {
        val quote = try { QuoteParser.parse(rawQuote) } catch (e: QuoteParseException) { throw CollateralException.MalformedResponse("quote: ${e.message}") }
        // near.ai quotes embed the PCK chain (cert_type 5); the encrypted-PPID PCK lookup path is deliberately unsupported (fail-closed).
        val pck = PckCertInfo.from(quote) ?: throw CollateralException.QuoteMissingPckChain
        val tee = "tdx"
        val shared = fmspcFields(pck.fmspc, pck.ca, tee)
        return shared + ("pck_certificate_chain" to pck.certChainPem)
    }

    private suspend fun assemble(rawQuote: ByteArray): ByteArray {
        val fields = collateralFields(rawQuote)
        return Json.encodeToString(JsonObject.serializer(), JsonObject(fields.mapValues { JsonPrimitive(it.value) })).toByteArray()
    }

    /** The eight collateral fields identical for every machine of the same FMSPC. Fetched once per FMSPC and cached; the PCK chain is added by the caller, never here. */
    private suspend fun fmspcFields(fmspc: String, ca: String, tee: String): Map<String, String> {
        val key = "$tee|${fmspc.lowercase()}|$ca"
        lock.withLock { fmspcCache[key] }?.let { return it }

        val tcb = get("$baseUrl/$tee/certification/v4/tcb?fmspc=${fmspc.lowercase()}")
        val tcbIssuerChain = issuerChain(tcb.second, "TCB-Info-Issuer-Chain", "SGX-TCB-Info-Issuer-Chain")
        val (tcbInfo, tcbSignature) = signedBody(tcb.first, "tcbInfo", "TCB info")

        val qe = get("$baseUrl/$tee/certification/v4/qe/identity?update=standard")
        val qeIssuerChain = issuerChain(qe.second, "SGX-Enclave-Identity-Issuer-Chain")
        val (qeIdentity, qeSignature) = signedBody(qe.first, "enclaveIdentity", "QE identity")

        val pckCrl = get("$baseUrl/sgx/certification/v4/pckcrl?ca=$ca&encoding=der")
        val pckCrlIssuerChain = issuerChain(pckCrl.second, "SGX-PCK-CRL-Issuer-Chain")

        // Intel PCS has no /rootcacrl endpoint — the CRL lives at the distribution point named by the root cert of the QE identity issuer chain.
        val rootCaCrl = fetchRootCaCrl(qeIssuerChain)

        // Byte fields are hex strings: dcap-qvl's collateral serde is hex in human-readable formats.
        val fields = mapOf(
            "pck_crl_issuer_chain" to pckCrlIssuerChain,
            "root_ca_crl" to rootCaCrl.toHex(),
            "pck_crl" to pckCrl.first.toHex(),
            "tcb_info_issuer_chain" to tcbIssuerChain,
            "tcb_info" to tcbInfo,
            "tcb_info_signature" to tcbSignature.toHex(),
            "qe_identity_issuer_chain" to qeIssuerChain,
            "qe_identity" to qeIdentity,
            "qe_identity_signature" to qeSignature.toHex(),
        )
        lock.withLock { fmspcCache[key] = fields }
        return fields
    }

    private suspend fun get(url: String): Pair<ByteArray, Map<String, String>> = http.get(url)

    /** The first present issuer-chain header, URL-decoded to PEM. */
    private fun issuerChain(headers: Map<String, String>, vararg names: String): String {
        for (name in names) {
            val raw = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: continue
            return try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { throw CollateralException.MalformedResponse("undecodable header $name") }
        }
        throw CollateralException.MissingHeader(names[0])
    }

    /** Splits a signed PCS body `{"<key>":{…},"signature":"hex"}` into the byte-exact raw sub-object and the decoded signature. */
    private fun signedBody(body: ByteArray, key: String, what: String): Pair<String, ByteArray> {
        val raw = rawJsonObject(key, body) ?: throw CollateralException.MalformedResponse("$what missing \"$key\" object")
        val signatureHex = runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["signature"]?.let { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
        val signature = signatureHex?.hexToBytesOrNull() ?: throw CollateralException.MalformedResponse("$what missing signature")
        return raw to signature
    }

    private suspend fun fetchRootCaCrl(qeIssuerChain: String): ByteArray {
        if (baseUrl != INTEL_PCS) {
            runCatching { get("$baseUrl/sgx/certification/v4/rootcacrl").first }.getOrNull()?.let { body -> String(body).trim().hexToBytesOrNull()?.let { return it } }
        }
        val rootDer = pemCertificatesDer(qeIssuerChain).lastOrNull()
        val crlUrl = rootDer?.let { crlDistributionPointUri(it) } ?: throw CollateralException.MalformedResponse("no CRL distribution point in root CA cert")
        return get(crlUrl).first
    }

    companion object {
        const val INTEL_PCS = "https://api.trustedservices.intel.com"

        /** The raw `{…}` object following `"key":` in [body], byte-exact (brace-balanced, string- and escape-aware). */
        fun rawJsonObject(key: String, body: ByteArray): String? {
            val pattern = "\"$key\"".toByteArray()
            var i = indexOf(body, pattern)?.let { it + pattern.size } ?: return null
            while (i < body.size && (body[i] == 0x20.toByte() || body[i] == 0x0A.toByte() || body[i] == 0x0D.toByte() || body[i] == 0x09.toByte())) i++
            if (i >= body.size || body[i] != ':'.code.toByte()) return null
            i++
            while (i < body.size && (body[i] == 0x20.toByte() || body[i] == 0x0A.toByte() || body[i] == 0x0D.toByte() || body[i] == 0x09.toByte())) i++
            if (i >= body.size || body[i] != '{'.code.toByte()) return null
            val start = i; var depth = 0; var inString = false; var escaped = false
            while (i < body.size) {
                val b = body[i].toInt().toChar()
                if (inString) { if (escaped) escaped = false else if (b == '\\') escaped = true else if (b == '"') inString = false }
                else when (b) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return String(body, start, i - start + 1, Charsets.UTF_8) }
                    else -> {}
                }
                i++
            }
            return null
        }

        /** The first http(s) URI in the certificate's CRL Distribution Points extension (OID 2.5.29.31), by a minimal DER walk. */
        fun crlDistributionPointUri(certDer: ByteArray): String? {
            val oid = byteArrayOf(0x06, 0x03, 0x55, 0x1D, 0x1F)
            var i = indexOf(certDer, oid)?.let { it + oid.size } ?: return null
            if (i < certDer.size && certDer[i] == 0x01.toByte()) i += 3   // optional BOOLEAN critical
            if (i >= certDer.size || certDer[i] != 0x04.toByte()) return null
            i++
            val (octetLen, afterLen) = derLength(certDer, i) ?: return null
            i = afterLen
            val octetEnd = minOf(i + octetLen, certDer.size)
            while (i < octetEnd) {
                if (certDer[i] == 0x86.toByte()) {
                    val (len, after) = derLength(certDer, i + 1) ?: return null
                    if (after + len <= octetEnd) { val uri = String(certDer, after, len, Charsets.UTF_8); if (uri.startsWith("http")) return uri }
                }
                i++
            }
            return null
        }

        /** The DER bytes of each PEM certificate in [pem], in order. */
        fun pemCertificatesDer(pem: String): List<ByteArray> {
            val out = ArrayList<ByteArray>(); var rest = pem
            while (true) {
                val b = rest.indexOf("-----BEGIN CERTIFICATE-----"); if (b < 0) break
                val e = rest.indexOf("-----END CERTIFICATE-----", b); if (e < 0) break
                val base64 = rest.substring(b + "-----BEGIN CERTIFICATE-----".length, e).replace("\n", "").replace("\r", "")
                runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.let { out.add(it) }
                rest = rest.substring(e + "-----END CERTIFICATE-----".length)
            }
            return out
        }

        private fun derLength(bytes: ByteArray, idx: Int): Pair<Int, Int>? {
            if (idx >= bytes.size) return null
            val first = bytes[idx].toInt() and 0xff
            if (first and 0x80 == 0) return first to idx + 1
            val count = first and 0x7f
            if (count == 0 || count > 4 || idx + count >= bytes.size) return null
            var len = 0; for (j in 0 until count) len = (len shl 8) or (bytes[idx + 1 + j].toInt() and 0xff)
            return len to idx + 1 + count
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
            if (needle.isEmpty() || haystack.size < needle.size) return null
            outer@ for (s in 0..haystack.size - needle.size) { for (j in needle.indices) if (haystack[s + j] != needle[j]) continue@outer; return s }
            return null
        }
    }
}
