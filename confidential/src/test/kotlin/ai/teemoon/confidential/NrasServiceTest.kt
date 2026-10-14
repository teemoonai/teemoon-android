// ports: teemoonTests/NRASServiceTests.swift @ v1.0.1 — all seven, over the kit's nras class (the recorded
//        near.ai nvidia_payload, its request nonce, NVIDIA's actual response, and the outcome production answered per case).
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

object NrasKit {
    class Case(val note: String, val expectedNonceHex: String?, val response: String?, val outcome: String, val reasonContains: String?)
    private val v by lazy { Json.parseToJsonElement(NrasKit::class.java.getResourceAsStream("/nras.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject }
    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.takeIf { it.isString }?.content
    val nvidiaPayload get() = s(v, "nvidiaPayload")!!
    val requestNonce get() = s(v, "requestNonce")!!
    val nrasResponse get() = s(v, "nrasResponse")!!
    val overallVerdict get() = v["overallVerdict"]!!.jsonPrimitive.content.toBoolean()
    val cases: List<Case> get() = (v["cases"] as JsonArray).map { it.jsonObject }.map { Case(s(it, "note")!!, s(it, "expectedNonceHex"), s(it, "response"), s(it, "outcome")!!, s(it, "reasonContains")) }
}

class NrasServiceTest {
    private fun stub(response: String?) = NrasHttpClient { _, _ -> response?.toByteArray() ?: throw IOException("unreachable") }
    private fun kind(v: NrasVerification) = when (v) { NrasVerification.Verified -> "verified"; is NrasVerification.Failed -> "failed"; is NrasVerification.Inconclusive -> "inconclusive" }
    private fun reason(v: NrasVerification) = when (v) { is NrasVerification.Failed -> v.reason; is NrasVerification.Inconclusive -> v.reason; else -> null }

    /** overallVerdict_parsesRealResponse: NVIDIA's actual response reads PASS. */
    @Test fun overallVerdict_parsesRealResponse() {
        assertTrue(NrasKit.overallVerdict); assertEquals(true, NrasService.overallVerdict(NrasKit.nrasResponse.toByteArray()))
    }

    /** realEvidence_verifies, nonceMismatch, missingRecordedNonce, unreachableNRAS, failVerdict, malformedResponse — each answers what production answered. */
    @Test fun everyRecordedCase_answersWhatProductionAnswered() = runBlocking {
        val cases = NrasKit.cases; assertEquals(6, cases.size)
        for (c in cases) {
            val result = NrasService(stub(c.response)).verify(NrasKit.nvidiaPayload, c.expectedNonceHex)
            assertEquals(c.note, c.outcome, kind(result))
            c.reasonContains?.let { needle -> assertTrue("${c.note}: '${reason(result)}' should mention '$needle'", reason(result)?.contains(needle) == true) }
        }
        assertEquals("verified", cases.first().outcome)
        // The hard tier is only a genuine negative — the nonce replay and NVIDIA's FAIL; every other non-verified case is soft.
        assertEquals(2, cases.count { it.outcome == "failed" }); assertEquals(3, cases.count { it.outcome == "inconclusive" })
    }

    /** A nonce mismatch must fail without any HTTP call. */
    @Test fun nonceMismatch_failsBeforeContactingNRAS() = runBlocking {
        var calls = 0
        val result = NrasService(NrasHttpClient { _, _ -> calls++; NrasKit.nrasResponse.toByteArray() }).verify(NrasKit.nvidiaPayload, "00".repeat(32))
        assertTrue(result is NrasVerification.Failed); assertTrue(result.isHardFailure); assertEquals(0, calls)
    }

    /** Unreachable NRAS fails CLOSED (degrades) but is NOT hard — a network blip must never be branded tamper. */
    @Test fun unreachableNRAS_failsClosedButSoft() = runBlocking {
        val result = NrasService(stub(null)).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
        assertFalse(result.isVerified); assertFalse(result.isHardFailure); assertTrue(result is NrasVerification.Inconclusive)
    }

    @Test fun jwtClaims_decodeBase64UrlWithoutPadding() {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"x-nvidia-overall-att-result":"PASS"}""".toByteArray())
        assertEquals("PASS", NrasService.decodeJwtClaims("h.$claims.s")!!["x-nvidia-overall-att-result"]!!.jsonPrimitive.content)
        assertNull(NrasService.decodeJwtClaims("only.two")); assertNull(NrasService.decodeJwtClaims("a.!!!.c"))
        assertEquals(true, NrasService.overallVerdict("""[["JWT","h.$claims.s"]]""".toByteArray()))
        assertNull(NrasService.overallVerdict("{}".toByteArray()))
    }
}
