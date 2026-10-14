// ports: teemoon/Confidential/TLSAttestationVerifier.swift @ v1.0.1 (the verifier; TlsAttestation is its own file)
//
// TLS attestation — proves the HTTPS connection terminates INSIDE the model
// TEE, so nothing between the device and the enclave (network, load
// balancers, near.ai's own infrastructure) can read the traffic.
//
// near.ai's documented flow (docs.near.ai/cloud/verification/tls):
//  1. Request `…/attestation/report?include_tls_fingerprint=true` from the
//     model's direct host, capturing the live server certificate over the
//     SAME TLS connection (a second connection could hit a different backend
//     and mismatch). CA validation is intentionally skipped — the enclave
//     self-signs; trust comes from the hardware attestation, not a CA.
//  2. With the flag, `report_data[0:32] == SHA256(signing_address ‖ tls_cert_fingerprint)`,
//     where `tls_cert_fingerprint` = SHA256(cert SubjectPublicKeyInfo), and
//     `report_data[32:64] == nonce`.
//  3. The live certificate's own SPKI hash must equal the attested
//     `tls_cert_fingerprint` — that's what proves the connection ends in the TEE.
//
// Because the flag changes the report_data[0:32] layout, this runs as a
// SEPARATE attestation request, independent of the main attestation flow.
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** One probe's answer: the response body, and the leaf certificate of the connection it came over. */
data class TlsProbeResult(val status: Int, val body: ByteArray, val leafCertificateDer: ByteArray?)

/** The probe seam: GET [url] over a fresh connection, accepting ANY server certificate, never following redirects. */
fun interface TlsProbe {
    suspend fun get(url: String): TlsProbeResult
}

/**
 * The real probe. A dedicated client so accepting the enclave's self-signed
 * certificate is scoped to THIS request and never affects the app's other
 * traffic. Never follows redirects: the probe's evidence only means anything
 * on the ORIGINAL connection, and a client that accepts any server trust must
 * not be steerable to a different host.
 */
class OkHttpTlsProbe : TlsProbe {
    /**
     * The leaf of the LAST handshake, captured where it is guaranteed to be
     * seen: the trust manager. Under TLS 1.3 with a custom trust manager the
     * JDK's session refuses `getPeerCertificates` and OkHttp reports an empty
     * chain (observed live against a near.ai direct host, 2026-09-03), so the
     * handshake API cannot be the source.
     *
     * The trust manager is only consulted on a FULL handshake. A resumed
     * session (TLS 1.3 PSK, TLS 1.2 ticket) skips certificate verification
     * entirely, and the session cache lives in the SSLContext — so a shared
     * context made every re-verify after the first come back with no leaf
     * ("inconclusive — no server certificate captured", observed on the
     * emulator against near.ai, 2026-09-04). Each probe therefore builds its
     * own SSLContext and client: no shared session cache, no pooled
     * connection, so the last handshake is this probe's, and it is full.
     */
    @Volatile private var lastLeafDer: ByteArray? = null
    private fun freshClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { lastLeafDer = chain.firstOrNull()?.encoded }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return TeemoonHttp.builder().sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
            .followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS)).build()
    }

    override suspend fun get(url: String): TlsProbeResult = withContext(Dispatchers.IO) {
        lastLeafDer = null
        val client = freshClient()
        try {
            client.newCall(TlsAttestationVerifier.makeProbeRequest(url)).execute().use { r ->
                TlsProbeResult(r.code, r.body.bytes(), lastLeafDer ?: r.handshake?.peerCertificates?.firstOrNull()?.encoded)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}

class TlsAttestationVerifier(private val probe: TlsProbe = OkHttpTlsProbe(), private val log: (String) -> Unit = {}) {

    /** Fetches attestation with `include_tls_fingerprint=true` over a fresh TLS connection to [directBaseUrl], captures that connection's certificate, and verifies the bindings. */
    suspend fun verify(directBaseUrl: String): TlsAttestation {
        val nonce = randomNonce()
        val url = directBaseUrl.trimEnd('/') + "/attestation/report?include_tls_fingerprint=true&signing_algo=ecdsa&nonce=$nonce"
        val result = try { probe.get(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { return TlsAttestation.Inconclusive("TLS attestation request failed") }
        if (result.status != 200) return TlsAttestation.Inconclusive("TLS attestation request failed")
        val certDer = result.leafCertificateDer ?: return TlsAttestation.Inconclusive("no server certificate captured")
        val report = runCatching { Json.parseToJsonElement(String(result.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        fun field(k: String) = (report?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val quote = field("intel_quote"); val address = field("signing_address"); val fingerprint = field("tls_cert_fingerprint")
        if (quote == null || address == null || fingerprint == null) return TlsAttestation.Inconclusive("attestation response missing TLS fields")
        val outcome = verifyBindings(quote, address, field("signing_algo") ?: "ecdsa", fingerprint, certDer, nonce)
        if (outcome is TlsAttestation.Failed) log("[tls] ${outcome.reason}")
        return outcome
    }

    companion object {
        /**
         * Builds the probe request. DELIBERATELY UNAUTHENTICATED — no
         * Authorization header, ever. The attestation report is public
         * evidence, and this request rides a client that accepts ANY server
         * trust *before* the fingerprint binding is checked — so a bearer
         * token attached here would be handed to whoever answers, including an
         * on-path attacker.
         */
        fun makeProbeRequest(url: String): Request = Request.Builder().url(url).get().build()

        /** Verifies the three TLS-attestation bindings. Pure — no network. */
        fun verifyBindings(quoteHex: String, signingAddress: String, signingAlgo: String, tlsCertFingerprintHex: String, liveCertDer: ByteArray, nonceHex: String): TlsAttestation {
            // 1. The live certificate's SPKI hash must equal the attested value — this is what proves the connection terminates in the TEE.
            val liveSpki = spkiSha256(liveCertDer) ?: return TlsAttestation.Inconclusive("could not read the TLS certificate’s public key")
            if (!liveSpki.equals(tlsCertFingerprintHex, ignoreCase = true)) return TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint")
            // 2. report_data[0:32] == SHA256(signing_address ‖ tls_cert_fingerprint)
            val reportData = runCatching { QuoteParser.parseHex(quoteHex).body.reportData }.getOrNull()
                ?: return TlsAttestation.Inconclusive("could not parse the attestation quote")
            if (reportData.size < 64) return TlsAttestation.Inconclusive("unexpected report_data length")
            val addrHex = if (signingAlgo.lowercase() == "ecdsa" && signingAddress.startsWith("0x")) signingAddress.substring(2) else signingAddress
            val addrBytes = addrHex.hexToBytesOrNull(); val fpBytes = tlsCertFingerprintHex.hexToBytesOrNull()
            if (addrBytes == null || fpBytes == null) return TlsAttestation.Inconclusive("bad signing address or fingerprint encoding")
            val expected = MessageDigest.getInstance("SHA-256").digest(addrBytes + fpBytes)
            if (!reportData.copyOfRange(0, 32).contentEquals(expected)) return TlsAttestation.Failed("report_data does not bind the signing key + TLS certificate")
            // 3. report_data[32:64] == nonce (freshness)
            val nonceBytes = nonceHex.hexToBytesOrNull()
            if (nonceBytes == null || !reportData.copyOfRange(32, 64).contentEquals(nonceBytes)) return TlsAttestation.Failed("report_data nonce mismatch (possible replay)")
            return TlsAttestation.Verified
        }

        /**
         * SHA-256 of the certificate's SubjectPublicKeyInfo, hex. The same DER
         * walk as iOS (Certificate → tbsCertificate → skip [0]version?, serial,
         * sigAlg, issuer, validity, subject → SPKI), so both platforms refuse
         * the same malformed blobs.
         */
        fun spkiSha256(certDer: ByteArray): String? {
            val b = certDer
            val cert = readTlv(b, 0) ?: return null
            val tbs = readTlv(b, cert.contentStart) ?: return null
            var i = tbs.contentStart
            val first = readTlv(b, i) ?: return null
            if (first.tag == 0xA0) i = first.next   // optional explicit version [0]
            repeat(5) { val e = readTlv(b, i) ?: return null; i = e.next }   // serial, sigAlg, issuer, validity, subject
            val spki = readTlv(b, i) ?: return null
            if (spki.next > b.size) return null
            return MessageDigest.getInstance("SHA-256").digest(b.copyOfRange(i, spki.next)).toHex()
        }

        private class Tlv(val tag: Int, val contentStart: Int, val contentLen: Int, val next: Int)

        /** Reads one DER TLV at [i]. */
        private fun readTlv(b: ByteArray, i: Int): Tlv? {
            if (i + 1 >= b.size) return null
            val tag = b[i].toInt() and 0xFF
            var j = i + 1
            var len = b[j].toInt() and 0xFF; j += 1
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || n > 4 || j + n > b.size) return null
                len = 0
                repeat(n) { k -> len = (len shl 8) or (b[j + k].toInt() and 0xFF) }
                j += n
            }
            if (j + len > b.size) return null
            return Tlv(tag, j, len, j + len)
        }

        private fun randomNonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
    }
}
