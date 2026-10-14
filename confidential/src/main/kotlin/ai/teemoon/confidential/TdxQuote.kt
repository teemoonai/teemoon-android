// ports: Packages/TDXQuoteVerifier/Sources/TDXQuoteVerifier/TDXQuote.swift @ v1.0.1 (QuoteParser, TDXQuote)
//        Packages/TDXQuoteVerifier/Sources/TDXQuoteVerifier/TDXQuoteVerifier.swift @ v1.0.1 (the on-device verifier)
//        Packages/TDXQuoteVerifier/Sources/TDXQuoteVerifier/IntelRootCA.swift @ v1.0.1 (the pinned root)
//
// Intel TDX Quote v4 binary structure, and the minimal on-device verifier:
// the ECDSA P-256 signature over (header || body) and the PCK certificate
// chain back to Intel's SGX Root CA. It does NOT perform TCB evaluation —
// that is dcap-qvl's job (DcapVerifier) — and stays as the display parser and
// the cross-check tripwire. Acceptance is the kit's quotes class.
package ai.teemoon.confidential

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64

/** Parsed Intel TDX Quote v4. */
class TdxQuote(val header: Header, val body: TdReportBody, val signature: SignatureData) {
    /** 48-byte quote header. */
    class Header(val version: Int, val attestationKeyType: Int, val teeType: Long, val qeVendorId: ByteArray, val userData: ByteArray) {
        val isTdx: Boolean get() = teeType == 0x81L
        val isEcdsa256: Boolean get() = attestationKeyType == 2
    }

    /** 584-byte TD Report Body containing TEE measurements. */
    class TdReportBody(
        val teeTcbSvn: ByteArray, val mrSeam: ByteArray, val mrSignerSeam: ByteArray, val seamAttributes: ByteArray,
        val tdAttributes: ByteArray, val xfam: ByteArray, val mrtd: ByteArray, val mrConfigId: ByteArray, val mrOwner: ByteArray,
        val mrOwnerConfig: ByteArray, val rtmr0: ByteArray, val rtmr1: ByteArray, val rtmr2: ByteArray, val rtmr3: ByteArray, val reportData: ByteArray,
    ) {
        val measurements: TdxMeasurements get() = TdxMeasurements(mrtd, mrSeam, mrConfigId, mrOwner, rtmr0, rtmr1, rtmr2, rtmr3, reportData)
    }

    /** Signature section following the quote body. */
    class SignatureData(
        /** ECDSA P-256 signature over (header || body): r || s, 64 bytes. */
        val ecdsaSignature: ByteArray,
        /** ECDSA attestation public key: x || y, 64 bytes. */
        val attestationPublicKey: ByteArray,
        /** PEM-encoded certificate chain: typically [PCK cert, Platform CA cert, Root CA cert]. */
        val certificateChainPem: List<String>,
        val rawCertificationData: ByteArray,
    )
}

sealed class QuoteParseException(message: String) : Exception(message) {
    class DataTooShort(expected: Int, got: Int) : QuoteParseException("quote too short: expected $expected, got $got")
    class UnsupportedVersion(v: Int) : QuoteParseException("unsupported quote version $v")
    class UnsupportedTeeType(t: Long) : QuoteParseException("unsupported TEE type 0x${t.toString(16)}")
    object InvalidSignatureDataSize : QuoteParseException("invalid signature data size")
}

object QuoteParser {
    const val HEADER_SIZE = 48
    const val TD_REPORT_BODY_SIZE = 584
    private const val MIN_QUOTE_SIZE = HEADER_SIZE + TD_REPORT_BODY_SIZE + 4

    fun parse(data: ByteArray): TdxQuote {
        if (data.size < MIN_QUOTE_SIZE) throw QuoteParseException.DataTooShort(MIN_QUOTE_SIZE, data.size)
        val header = TdxQuote.Header(u16(data, 0), u16(data, 2), u32(data, 4), data.copyOfRange(12, 28), data.copyOfRange(28, 48))
        if (header.version != 4) throw QuoteParseException.UnsupportedVersion(header.version)
        if (!header.isTdx) throw QuoteParseException.UnsupportedTeeType(header.teeType)
        var p = HEADER_SIZE
        fun read(n: Int): ByteArray { val r = data.copyOfRange(p, p + n); p += n; return r }
        val body = TdxQuote.TdReportBody(read(16), read(48), read(48), read(8), read(8), read(8), read(48), read(48), read(48), read(48), read(48), read(48), read(48), read(48), read(64))
        return TdxQuote(header, body, parseSignatureData(data, HEADER_SIZE + TD_REPORT_BODY_SIZE))
    }

    fun parseHex(hex: String): TdxQuote = parse(hex.removePrefix("0x").hexToBytesOrNull() ?: throw QuoteParseException.DataTooShort(MIN_QUOTE_SIZE, 0))

    private fun parseSignatureData(data: ByteArray, o: Int): TdxQuote.SignatureData {
        if (data.size < o + 4) throw QuoteParseException.InvalidSignatureDataSize
        val sigDataSize = u32(data, o).toInt(); val sigStart = o + 4
        if (data.size < sigStart + sigDataSize || sigDataSize < 128) throw QuoteParseException.InvalidSignatureDataSize
        val ecdsaSig = data.copyOfRange(sigStart, sigStart + 64)
        val attestKey = data.copyOfRange(sigStart + 64, sigStart + 128)
        // QE Certification Data: uint16 type, uint32 size, data. Type 6 wraps a QE report + signature + auth data + nested cert data (type 5 = PEM chain).
        val remaining = data.copyOfRange(sigStart + 128, sigStart + sigDataSize)
        var certData = remaining; var pemCerts = emptyList<String>()
        if (remaining.size >= 6) {
            val outerType = u16(remaining, 0); val outerSize = u32(remaining, 2).toInt(); val start = 6
            if (outerType == 5 && start + outerSize <= remaining.size) {
                val payload = remaining.copyOfRange(start, start + outerSize); pemCerts = extractPemCertificates(payload); certData = payload
            } else if (outerType == 6 && start + outerSize <= remaining.size) {
                val inner = remaining.copyOfRange(start, start + outerSize)
                var p = 384 + 64
                if (p + 2 <= inner.size) {
                    p += 2 + u16(inner, p)
                    if (p + 6 <= inner.size) {
                        p += 2; val nestedSize = u32(inner, p).toInt(); p += 4
                        if (p + nestedSize <= inner.size) { val nested = inner.copyOfRange(p, p + nestedSize); pemCerts = extractPemCertificates(nested); certData = nested }
                    }
                }
            }
        }
        if (pemCerts.isEmpty()) pemCerts = extractPemCertificates(remaining)   // fallback: scan everything
        return TdxQuote.SignatureData(ecdsaSig, attestKey, pemCerts, certData)
    }

    /** Scans raw bytes for PEM certificate blocks. */
    fun extractPemCertificates(data: ByteArray): List<String> {
        val text = String(data, Charsets.ISO_8859_1)
        val certs = ArrayList<String>(); var from = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN CERTIFICATE-----", from); if (begin < 0) break
            val end = text.indexOf("-----END CERTIFICATE-----", begin); if (end < 0) break
            val stop = end + "-----END CERTIFICATE-----".length
            certs.add(text.substring(begin, stop).trim()); from = stop
        }
        return certs
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)
}

sealed class TdxVerificationException(message: String) : Exception(message) {
    class ParseError(val cause_: QuoteParseException) : TdxVerificationException(cause_.message ?: "parse error")
}

/** The on-device verifier: parse, ECDSA over (header || body), PCK chain to the pinned Intel root. Throws only when the quote cannot be parsed. */
object TdxQuoteVerifier {
    private val p256: X9ECParameters = CustomNamedCurves.getByName("secp256r1")

    fun verify(quoteData: ByteArray): TdxVerificationResult {
        val quote = try { QuoteParser.parse(quoteData) } catch (e: QuoteParseException) { throw TdxVerificationException.ParseError(e) }
        val signed = quoteData.copyOfRange(0, QuoteParser.HEADER_SIZE + QuoteParser.TD_REPORT_BODY_SIZE)
        val sigValid = verifyEcdsa(quote.signature.ecdsaSignature, quote.signature.attestationPublicKey, signed)
        val (certValid, certError) = verifyCertificateChain(quote.signature.certificateChainPem)
        return TdxVerificationResult(quote.body.measurements, sigValid, certValid, certError, quote)
    }

    fun verify(quoteHex: String): TdxVerificationResult =
        verify(quoteHex.removePrefix("0x").hexToBytesOrNull() ?: throw TdxVerificationException.ParseError(QuoteParseException.DataTooShort(636, 0)))

    /** Raw r||s (64 bytes) over SHA-256(message), with a raw x||y (64 bytes) public key. */
    private fun verifyEcdsa(signature: ByteArray, publicKey: ByteArray, message: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 64) return false
        return try {
            val point = p256.curve.decodePoint(byteArrayOf(0x04) + publicKey)
            val signer = ECDSASigner()
            signer.init(false, ECPublicKeyParameters(point, ECDomainParameters(p256.curve, p256.g, p256.n, p256.h)))
            signer.verifySignature(MessageDigest.getInstance("SHA-256").digest(message), BigInteger(1, signature.copyOfRange(0, 32)), BigInteger(1, signature.copyOfRange(32, 64)))
        } catch (_: Exception) { false }
    }

    /** PKIX path validation of the leaf through the chain to the pinned Intel SGX Root CA, revocation off (the CRLs are dcap-qvl's job). */
    private fun verifyCertificateChain(pemCerts: List<String>): Pair<Boolean, String?> {
        if (pemCerts.isEmpty()) return false to "No certificates found in quote"
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val certs = pemCerts.map { factory.generateCertificate(ByteArrayInputStream(pemToDer(it))) as X509Certificate }
            val root = factory.generateCertificate(ByteArrayInputStream(pemToDer(INTEL_SGX_ROOT_CA_PEM))) as X509Certificate
            // The chain minus any copy of the root itself: the anchor is supplied, not trusted from the quote.
            val path = factory.generateCertPath(certs.filter { it.subjectX500Principal != root.subjectX500Principal || !it.publicKey.encoded.contentEquals(root.publicKey.encoded) })
            val params = PKIXParameters(setOf(TrustAnchor(root, null))).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(path, params)
            true to null
        } catch (e: Exception) { false to (e.message ?: e.javaClass.simpleName) }
    }

    fun pemToDer(pem: String): ByteArray = Base64.getMimeDecoder().decode(
        pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "").trim())

    /**
     * Intel SGX Root CA (self-signed): CN=Intel SGX Root CA, valid 2018-05-21 to
     * 2049-12-31, ECDSA P-256. The trust anchor for all Intel SGX and TDX
     * attestation. Source: https://certificates.trustedservices.intel.com/IntelSGXRootCA.der
     */
    const val INTEL_SGX_ROOT_CA_PEM = """-----BEGIN CERTIFICATE-----
MIICjzCCAjSgAwIBAgIUImUM1lqdNInzg7SVUr9QGzknBqwwCgYIKoZIzj0EAwIw
aDEaMBgGA1UEAwwRSW50ZWwgU0dYIFJvb3QgQ0ExGjAYBgNVBAoMEUludGVsIENv
cnBvcmF0aW9uMRQwEgYDVQQHDAtTYW50YSBDbGFyYTELMAkGA1UECAwCQ0ExCzAJ
BgNVBAYTAlVTMB4XDTE4MDUyMTEwNDExMVoXDTQ5MTIzMTIzNTk1OVowaDEaMBgG
A1UEAwwRSW50ZWwgU0dYIFJvb3QgQ0ExGjAYBgNVBAoMEUludGVsIENvcnBvcmF0
aW9uMRQwEgYDVQQHDAtTYW50YSBDbGFyYTELMAkGA1UECAwCQ0ExCzAJBgNVBAYT
AlVTMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEC6nEwMDIYZOj/iPWsCzaEKi7
1OiOSLRFhWGjbnBVJfVnkY4u3IjkDYYL0MxO4mqsyYjlBalTVYxFP2sJBK5zlKOB
uzCBuDAfBgNVHSMEGDAWgBQiZQzWWp00ifODtJVSv1AbOScGrDBSBgNVHR8ESzBJ
MEegRaBDhkFodHRwczovL2NlcnRpZmljYXRlcy50cnVzdGVkc2VydmljZXMuaW50
ZWwuY29tL0ludGVsU0dYUm9vdENBLmRlcjAdBgNVHQ4EFgQUImUM1lqdNInzg7SV
Ur9QGzknBqwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQEwCgYI
KoZIzj0EAwIDSQAwRgIhAOW/5QkR+S9CiSDcNoowLuPRLsWGf/Yi7GSX94BgwTwg
AiEA4fQ+jnO5kDrAhLUo0uX2cp1L7wQUNBJdBAe4zJpuHgg=
-----END CERTIFICATE-----"""

    /**
     * SHA-256 of the DER of the PEM above. The Intel SGX Root CA exists in two
     * encodings with the SAME subject and key: the one iOS pins (this PEM) and
     * the one Intel PCS serves today at the end of every issuer chain
     * ([INTEL_ROOT_CA_FINGERPRINT_PCS]). PKIX anchors by key, so both chains
     * validate; TdxQuoteTest pins the KEY equality and both fingerprints.
     * iOS's IntelRootCA.swift comment carries a third value ("06a4d221…") that
     * matches neither — a doc defect, flagged.
     */
    const val INTEL_ROOT_CA_FINGERPRINT = "93904fec979aa67314a1fce9097956023eeb2146c63e7c1ca6edc79ffb40dcbc"
    /** SHA-256 of the root DER Intel PCS served in the captured collateral (kit 0.2.0). */
    const val INTEL_ROOT_CA_FINGERPRINT_PCS = "44a0196b2b99f889b8e149e95b807a350e7424964399e885a7cbb8ccfab674d3"
}

/**
 * What the collateral assembler needs from a quote's PCK certificate: the
 * platform's FMSPC and which Intel CA issued the PCK cert. iOS reads these
 * from dcap-qvl's parseQuote; here they come from the leaf cert's SGX
 * extension (OID 1.2.840.113741.1.13.1, FMSPC at .4) and issuer CN.
 */
data class PckCertInfo(val fmspc: String, val ca: String, val certChainPem: String) {
    companion object {
        private const val SGX_EXTENSION_OID = "1.2.840.113741.1.13.1"
        private const val FMSPC_OID = "1.2.840.113741.1.13.1.4"

        fun from(quote: TdxQuote): PckCertInfo? {
            val chain = quote.signature.certificateChainPem
            if (chain.isEmpty()) return null
            val leaf = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(TdxQuoteVerifier.pemToDer(chain[0]))) as X509Certificate
            val ext = leaf.getExtensionValue(SGX_EXTENSION_OID) ?: return null
            val seq = ASN1Sequence.getInstance(ASN1OctetString.getInstance(ext).octets)
            var fmspc: String? = null
            for (item in seq) {
                val pair = item as? ASN1Sequence ?: continue
                if ((pair.getObjectAt(0) as? ASN1ObjectIdentifier)?.id == FMSPC_OID) fmspc = ASN1OctetString.getInstance(pair.getObjectAt(1)).octets.toHex()
            }
            val issuer = leaf.issuerX500Principal.name
            val ca = when {
                issuer.contains("Platform CA", ignoreCase = true) -> "platform"
                issuer.contains("Processor CA", ignoreCase = true) -> "processor"
                else -> return null
            }
            return PckCertInfo(fmspc ?: return null, ca, chain.joinToString("\n") + "\n")
        }
    }
}
