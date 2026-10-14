// ports: teemoonTests/TLSAttestationVerifierTests.swift @ v1.0.1 — all seven, over the kit's tls class (a live
//        include_tls_fingerprint=true exchange from a near.ai model host, that connection's certificate, and the
//        outcome production answered per case) + android-only: the probe end to end over the seam.
package ai.teemoon.confidential

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.io.IOException

object TlsKit {
    class Case(val note: String, val nonceHex: String, val tlsCertFingerprintHex: String, val certDerB64: String, val outcome: String, val reasonContains: String?)
    private val v by lazy { Json.parseToJsonElement(TlsKit::class.java.getResourceAsStream("/tls.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject }
    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.takeIf { it.isString }?.content
    val nonce get() = s(v, "nonce")!!
    val certDer: ByteArray get() = Base64.getDecoder().decode(s(v, "certDerB64")!!)
    val intelQuote get() = s(v, "intelQuote")!!
    val signingAddress get() = s(v, "signingAddress")!!
    val signingAlgo get() = s(v, "signingAlgo")!!
    val tlsCertFingerprint get() = s(v, "tlsCertFingerprint")!!
    val spkiSha256 get() = s(v, "spkiSha256")!!
    val cases: List<Case> get() = (v["cases"] as JsonArray).map { it.jsonObject }.map { Case(s(it, "note")!!, s(it, "nonceHex")!!, s(it, "tlsCertFingerprintHex")!!, s(it, "certDerB64")!!, s(it, "outcome")!!, s(it, "reasonContains")) }
    val malformedCertsB64: List<String> get() = (v["malformedCertsB64"] as JsonArray).map { it.jsonPrimitive.content }
}

class TlsAttestationVerifierTest {
    private fun kind(v: TlsAttestation) = when (v) { TlsAttestation.Verified -> "verified"; is TlsAttestation.Failed -> "failed"; is TlsAttestation.Inconclusive -> "inconclusive"; TlsAttestation.NotPerformed -> "notPerformed" }
    private fun decodeOrEmpty(b64: String) = runCatching { Base64.getDecoder().decode(b64) }.getOrDefault(ByteArray(0))

    /** The probe must carry NO Authorization header, ever — it rides a connection that accepts any server trust. */
    @Test fun probeRequest_carriesNoAuthorizationHeader() {
        val r = TlsAttestationVerifier.makeProbeRequest("https://model.example/v1/attestation/report?include_tls_fingerprint=true&signing_algo=ecdsa&nonce=00")
        assertNull(r.header("Authorization")); assertTrue(r.headers.names().none { it.equals("authorization", ignoreCase = true) })
    }

    @Test fun spkiHashMatchesAttestedFingerprint() {
        assertEquals(TlsKit.tlsCertFingerprint, TlsAttestationVerifier.spkiSha256(TlsKit.certDer)); assertEquals(TlsKit.spkiSha256, TlsKit.tlsCertFingerprint)
    }

    /** realExchangeVerifies, wrongNonce, differentCert, tamperedFingerprint — each answers what production answered. */
    @Test fun everyRecordedCase_answersWhatProductionAnswered() {
        val cases = TlsKit.cases; assertEquals(4, cases.size)
        for (c in cases) {
            val result = TlsAttestationVerifier.verifyBindings(TlsKit.intelQuote, TlsKit.signingAddress, TlsKit.signingAlgo, c.tlsCertFingerprintHex, decodeOrEmpty(c.certDerB64), c.nonceHex)
            assertEquals(c.note, c.outcome, kind(result))
            c.reasonContains?.let { needle -> assertTrue("${c.note}: '${result.reason}' should mention '$needle'", result.reason?.contains(needle) == true) }
        }
        assertEquals("verified", cases.first().outcome); assertTrue(cases.drop(1).none { it.outcome == "verified" })
    }

    @Test fun malformedCert_returnsNullSPKI() {
        for (b64 in TlsKit.malformedCertsB64) assertNull(b64, TlsAttestationVerifier.spkiSha256(decodeOrEmpty(b64)))
    }

    /** Android-only: the probe path end to end — the recorded exchange over the seam verifies; a redirect-free non-200 or a missing certificate is inconclusive, never hard. */
    @Test fun probe_endToEndOverTheSeam() = runBlocking {
        val body = """{"intel_quote":"${TlsKit.intelQuote}","signing_address":"${TlsKit.signingAddress}","signing_algo":"${TlsKit.signingAlgo}","tls_cert_fingerprint":"${TlsKit.tlsCertFingerprint}"}"""
        // The verifier mints its own nonce, so the recorded quote (bound to the recorded nonce) reads as a replay — a hard failure, which is the honest answer.
        var seenUrl: String? = null
        val replay = TlsAttestationVerifier(TlsProbe { url -> seenUrl = url; TlsProbeResult(200, body.toByteArray(), TlsKit.certDer) }).verify("https://glm-5-1.completions.near.ai/v1")
        assertTrue(seenUrl!!.startsWith("https://glm-5-1.completions.near.ai/v1/attestation/report?include_tls_fingerprint=true&signing_algo=ecdsa&nonce="))
        assertTrue(replay is TlsAttestation.Failed); assertTrue(replay.reason!!.contains("nonce"))
        val noCert = TlsAttestationVerifier(TlsProbe { TlsProbeResult(200, body.toByteArray(), null) }).verify("https://x/v1")
        assertTrue(noCert is TlsAttestation.Inconclusive); assertFalse(noCert.isHardFailure)
        val down = TlsAttestationVerifier(TlsProbe { throw IOException("down") }).verify("https://x/v1")
        assertTrue(down is TlsAttestation.Inconclusive)
        val missing = TlsAttestationVerifier(TlsProbe { TlsProbeResult(200, "{}".toByteArray(), TlsKit.certDer) }).verify("https://x/v1")
        assertTrue(missing.reason!!.contains("missing"))
    }
}
