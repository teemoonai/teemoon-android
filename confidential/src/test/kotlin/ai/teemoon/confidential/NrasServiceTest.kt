// ports: teemoonTests/NRASServiceTests.swift @ v1.1.0 — all fourteen, over the kit's nras class (the recorded
//        near.ai nvidia_payload, its request nonce, NVIDIA's actual response, the JWKS of that moment, the instant
//        every case runs at, and the outcome production answered per case). The seven token negatives mint their
//        own ES384 key, as iOS does. nras.json re-emitted at iOS ee5d1b51 (the counts are those of
//        teemoonTests/ConformanceVectorsAttestation.swift @ ee5d1b51: a lost nonce asks with the fault line, a
//        malformed NRAS response fails).
package ai.teemoon.confidential

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.io.IOException

object NrasKit {
    class Case(val note: String, val expectedNonceHex: String?, val response: String?, val outcome: String, val reasonContains: String?)
    private val v by lazy { Json.parseToJsonElement(NrasKit::class.java.getResourceAsStream("/nras.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject }
    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.takeIf { it.isString }?.content
    val nvidiaPayload get() = s(v, "nvidiaPayload")!!
    val requestNonce get() = s(v, "requestNonce")!!
    val nrasResponse get() = s(v, "nrasResponse")!!
    val overallVerdict get() = v["overallVerdict"]!!.jsonPrimitive.content.toBoolean()
    /** NVIDIA's JWKS at capture time (trimmed) and the clock every case runs at (unix seconds). */
    val jwks get() = s(v, "jwks")!!
    val verifiedAt get() = v["verifiedAt"]!!.jsonPrimitive.content.toDouble()
    val cases: List<Case> get() = (v["cases"] as JsonArray).map { it.jsonObject }.map { Case(s(it, "note")!!, s(it, "expectedNonceHex"), s(it, "response"), s(it, "outcome")!!, s(it, "reasonContains")) }
}

class NrasServiceTest {
    private fun stub(response: String?) = NrasHttpClient { _, _ -> response?.toByteArray() ?: throw IOException("unreachable") }
    private fun staticKeys(data: String?) = NrasKeySource { data?.toByteArray() ?: throw IOException("keys unreachable") }
    /** The service as it ran at capture time, against the captured key set. */
    private fun service(response: String?, jwks: String? = NrasKit.jwks, at: Double = NrasKit.verifiedAt, http: NrasHttpClient = stub(response)) =
        NrasService(http, staticKeys(jwks), now = { at })
    private fun kind(v: NrasVerification) = when (v) { NrasVerification.Verified -> "verified"; is NrasVerification.Failed, is NrasVerification.ListedFailed -> "failed"; is NrasVerification.Inconclusive, is NrasVerification.ListedInconclusive -> "inconclusive" }
    private fun reason(v: NrasVerification) = when (v) { is NrasVerification.Failed -> v.reason; is NrasVerification.Inconclusive -> v.reason; else -> null }

    /** overallVerdict_parsesRealResponse: NVIDIA's actual response reads PASS. */
    @Test fun overallVerdict_parsesRealResponse() {
        assertTrue(NrasKit.overallVerdict); assertEquals(true, NrasService.overallVerdict(NrasKit.nrasResponse.toByteArray()))
    }

    /** realEvidence_verifies, nonceMismatch, missingRecordedNonce, unreachableNRAS, failVerdict, malformedResponse — each answers what production answered. */
    @Test fun everyRecordedCase_answersWhatProductionAnswered() = runBlocking {
        val cases = NrasKit.cases; assertEquals(6, cases.size)
        for (c in cases) {
            val result = service(c.response).verify(NrasKit.nvidiaPayload, c.expectedNonceHex)
            assertEquals(c.note, c.outcome, kind(result))
            c.reasonContains?.let { needle -> assertTrue("${c.note}: '${reason(result)}' should mention '$needle'", reason(result)?.contains(needle) == true) }
        }
        assertEquals("verified", cases.first().outcome)
        // The hard tier: the nonce replay, the tampered token (its ES384 signature no longer verifies) and, since
        // near-gate-nvidia-tls-split (iOS ee5d1b51), an unrecognised NVIDIA answer — evidence. Soft: NRAS unreachable,
        // and the lost request nonce, which is teemoon's own fault and says so with the approved line.
        assertEquals(3, cases.count { it.outcome == "failed" }); assertEquals(2, cases.count { it.outcome == "inconclusive" })
    }

    /** A nonce mismatch must fail without any HTTP call. */
    @Test fun nonceMismatch_failsBeforeContactingNRAS() = runBlocking {
        var calls = 0
        val result = service(null, http = { _, _ -> calls++; NrasKit.nrasResponse.toByteArray() }).verify(NrasKit.nvidiaPayload, "00".repeat(32))
        assertTrue(result is NrasVerification.Failed); assertTrue(result.isHardFailure); assertEquals(0, calls)
    }

    /** Unreachable NRAS fails CLOSED (degrades) but is NOT hard — a network blip must never be branded tamper. */
    @Test fun unreachableNRAS_failsClosedButSoft() = runBlocking {
        val result = service(null).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertFalse(result.isVerified); assertFalse(result.isHardFailure); assertTrue(result is NrasVerification.Inconclusive)
    }

    @Test fun jwtClaims_decodeBase64UrlWithoutPadding() {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"x-nvidia-overall-att-result":"PASS"}""".toByteArray())
        assertEquals("PASS", NrasService.decodeJwtClaims("h.$claims.s")!!["x-nvidia-overall-att-result"]!!.jsonPrimitive.content)
        assertNull(NrasService.decodeJwtClaims("only.two")); assertNull(NrasService.decodeJwtClaims("a.!!!.c"))
        assertEquals(true, NrasService.overallVerdict("""[["JWT","h.$claims.s"]]""".toByteArray()))
        assertNull(NrasService.overallVerdict("{}".toByteArray()))
    }

    // MARK: token checks (1.0.6)

    private fun b64url(d: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(d)

    /** JDK DER ECDSA → JOSE r||s, 48 bytes each. */
    private fun derToJose(der: ByteArray): ByteArray {
        var i = 2
        fun int(): ByteArray { check(der[i] == 0x02.toByte()); val n = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + n); i += 2 + n; return v }
        fun fixed(v: ByteArray) = BigInteger(1, v).toByteArray().let { b -> ByteArray(48).also { out -> val t = b.takeLast(48).toByteArray(); t.copyInto(out, 48 - t.size) } }
        return fixed(int()) + fixed(int())
    }

    private fun coord(n: BigInteger) = b64url(n.toByteArray().let { b -> ByteArray(48).also { out -> val t = b.takeLast(48).toByteArray(); t.copyInto(out, 48 - t.size) } })

    /** A token signed by a throwaway key, with that key as the only JWKS entry. */
    private fun minted(claims: JsonObject, alg: String = "ES384"): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val header = b64url("""{"alg":"$alg","kid":"test-kid"}""".toByteArray())
        val body = b64url(claims.toString().toByteArray())
        val der = Signature.getInstance("SHA384withECDSA").run { initSign(pair.private); update("$header.$body".toByteArray()); sign() }
        val w = (pair.public as ECPublicKey).w
        val jwks = """{"keys":[{"kty":"EC","crv":"P-384","kid":"test-kid","x":"${coord(w.affineX)}","y":"${coord(w.affineY)}"}]}"""
        return """[["JWT","$header.$body.${b64url(derToJose(der))}"]]""" to jwks
    }

    private fun claims(nonce: String = NrasKit.requestNonce, iss: String = NrasService.ISSUER): JsonObject {
        val t = NrasKit.verifiedAt.toLong()
        return buildJsonObject {
            put("iss", iss); put("eat_nonce", nonce); put("iat", t - 60); put("nbf", t - 60); put("exp", t + 3600)
            put("x-nvidia-overall-att-result", true)
        }
    }

    private fun run(m: Pair<String, String>) = runBlocking { service(m.first, jwks = m.second).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce) }

    @Test fun mintedToken_verifies() {
        assertEquals(NrasVerification.Verified, run(minted(claims())))
    }

    @Test fun tokenForAnotherNonce_failsHard() {
        val r = run(minted(claims(nonce = "ab".repeat(32))))
        assertTrue("$r", r.isHardFailure && reason(r)?.contains("nonce") == true)
    }

    @Test fun tokenFromAnotherIssuer_failsHard() {
        val r = run(minted(claims(iss = "https://evil.example")))
        assertTrue("$r", r.isHardFailure && reason(r)?.contains("issuer") == true)
    }

    @Test fun nonES384Token_failsHard() {
        val r = run(minted(claims(), alg = "none"))
        assertTrue("$r", r.isHardFailure)
    }

    @Test fun realToken_expiredAnHourLater() = runBlocking {
        val r = service(NrasKit.nrasResponse, at = NrasKit.verifiedAt + 2 * 3600).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertTrue("$r", r.isHardFailure && reason(r)?.contains("expired") == true)
    }

    @Test fun realToken_unknownKeyIsInconclusive() = runBlocking {
        val r = service(NrasKit.nrasResponse, jwks = """{"keys":[]}""").verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertTrue("$r", r is NrasVerification.Inconclusive)
    }

    @Test fun realToken_keysUnreachableIsInconclusive() = runBlocking {
        val r = service(NrasKit.nrasResponse, jwks = null).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertTrue("$r", r is NrasVerification.Inconclusive)
    }

    /** The live source refetches on an unknown kid (NVIDIA rotates keys several times a day) before calling it inconclusive. */
    @Test fun unknownKid_refetchesOnceBeforeGivingUp() = runBlocking {
        val asked = mutableListOf<Boolean>()
        val keys = NrasKeySource { refresh -> asked += refresh; (if (refresh) NrasKit.jwks else """{"keys":[]}""").toByteArray() }
        val r = NrasService(stub(NrasKit.nrasResponse), keys, now = { NrasKit.verifiedAt }).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertEquals(NrasVerification.Verified, r); assertEquals(listOf(false, true), asked)
    }
}
