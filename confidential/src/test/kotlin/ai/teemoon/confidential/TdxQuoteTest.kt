// conformance: quotes.json (kit 0.2.0, §7 item 2) — the acceptance for QuoteParser / TdxQuoteVerifier / PckCertInfo.
// ports: Packages/TDXQuoteVerifier tests have no Swift suite of their own; the kit is the suite.
package ai.teemoon.confidential

import ai.teemoon.confidential.KitFixtures.s
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class TdxQuoteTest {
    @Test fun realQuote_parsesToTheRecordedMeasurements() {
        val r = TdxQuoteVerifier.verify(KitFixtures.quoteHex); val m = KitFixtures.measurements
        for ((k, v) in mapOf("mrtd" to r.measurements.mrtd, "mrSeam" to r.measurements.mrSeam, "mrConfigID" to r.measurements.mrConfigId, "mrOwner" to r.measurements.mrOwner,
            "rtmr0" to r.measurements.rtmr0, "rtmr1" to r.measurements.rtmr1, "rtmr2" to r.measurements.rtmr2, "rtmr3" to r.measurements.rtmr3, "reportData" to r.measurements.reportData))
            assertEquals(k, m.s(k), v.toHex())
        assertEquals(KitFixtures.realQuote["signatureValid"]!!.toString(), r.signatureValid.toString())
        assertEquals(KitFixtures.realQuote["certChainValid"]!!.toString(), r.certChainValid.toString())
        assertTrue(r.isVerified)
        val q = r.quote!!
        assertEquals(4, q.header.version); assertTrue(q.header.isTdx); assertTrue(q.header.isEcdsa256)
        assertEquals(3, q.signature.certificateChainPem.size)   // PCK, Platform CA, Root CA
    }

    /** The MRTD-flipped negative: the signature over the report no longer verifies. */
    @Test fun tamperedQuote_failsTheSignature() {
        val neg = KitFixtures.quoteNegatives.first { it.s("note")!!.contains("MRTD") }
        val r = runCatching { TdxQuoteVerifier.verify(neg.s("quoteHex")!!) }
        val recordedThrows = neg["swiftParseThrows"]!!.toString() == "true"
        assertTrue("iOS recorded throws-or-invalid", recordedThrows)
        assertTrue(r.isFailure || !r.getOrThrow().signatureValid)
    }

    @Test fun pckCertInfo_readsFmspcAndCaFromTheLeaf() {
        val info = PckCertInfo.from(QuoteParser.parseHex(KitFixtures.quoteHex))!!
        // The captured PCS URLs name them: tcb?fmspc=b0c06f000000 and pckcrl?ca=platform.
        val urls = KitFixtures.StubHttp().responses.keys
        assertTrue(urls.any { it.contains("fmspc=${info.fmspc}") }); assertTrue(urls.any { it.contains("ca=${info.ca}") })
        assertEquals(KitFixtures.assembledFields["pck_certificate_chain"], info.certChainPem)
    }

    /** The pinned root and the root Intel PCS serves are two encodings of ONE key; both fingerprints are the recorded ones. */
    @Test fun pinnedRootSharesItsKeyWithIntelPcsAndBothFingerprintsHold() {
        val factory = CertificateFactory.getInstance("X.509")
        val pinDer = TdxQuoteVerifier.pemToDer(TdxQuoteVerifier.INTEL_SGX_ROOT_CA_PEM)
        assertEquals(TdxQuoteVerifier.INTEL_ROOT_CA_FINGERPRINT, MessageDigest.getInstance("SHA-256").digest(pinDer).toHex())
        val qeUrl = KitFixtures.StubHttp().responses.keys.first { it.contains("qe/identity") }
        val pem = URLDecoder.decode(KitFixtures.StubHttp().responses[qeUrl]!!.second["SGX-Enclave-Identity-Issuer-Chain"]!!.replace("+", "%2B"), "UTF-8")
        val pcsDer = IntelPcsCollateralProvider.pemCertificatesDer(pem).last()
        assertEquals(TdxQuoteVerifier.INTEL_ROOT_CA_FINGERPRINT_PCS, MessageDigest.getInstance("SHA-256").digest(pcsDer).toHex())
        val pin = factory.generateCertificate(ByteArrayInputStream(pinDer)) as X509Certificate
        val pcs = factory.generateCertificate(ByteArrayInputStream(pcsDer)) as X509Certificate
        assertEquals(pin.subjectX500Principal, pcs.subjectX500Principal)
        assertTrue(pin.publicKey.encoded.contentEquals(pcs.publicKey.encoded))
    }

    @Test fun malformedQuotes_throwParseErrors() {
        assertTrue(runCatching { QuoteParser.parse(ByteArray(10)) }.exceptionOrNull() is QuoteParseException.DataTooShort)
        val v3 = KitFixtures.quoteHex.hexToBytes().copyOf(); v3[0] = 3
        assertTrue(runCatching { QuoteParser.parse(v3) }.exceptionOrNull() is QuoteParseException.UnsupportedVersion)
        assertFalse(runCatching { TdxQuoteVerifier.verify("zz") }.isSuccess)
    }
}
