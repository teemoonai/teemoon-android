package ai.teemoon.confidential

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.1 audit A15: the four mutants of the listed-enclave check (C3a) that no test killed at f2f859bc. Each test here
 * goes red under its mutant: A03 drops the key-in-quote compare (ModelCandidates.check), A05 drops QuoteChecks on a
 * sibling, A10 drops candidatesBroken from the binding rule (ConfidentialSession.e2eeBindingIntact), A19 asks the list
 * with a nonce other than the one it checks against (AttestationService.fetchCandidateList).
 */
class CandidateMutantPinsTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A03: the entry names the sealed key, but its genuine quote's report_data carries a different one. */
    @Test fun aSiblingWhoseQuoteDoesNotCarryTheNamedKeyIsKeyNotInQuote() {
        val c = LiveCandidates.load()
        val claimed = ByteArray(32) { (0xA0 + it).toByte() }
        val entry = JsonObject(c.list[1] + ("signing_public_key" to JsonPrimitive(hex(claimed))))
        assertTrue("precondition: the quote itself is genuine",
            TdxQuoteVerifier.verify(entry.getValue("intel_quote").jsonPrimitive.content).isVerified)
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.KEY_NOT_IN_QUOTE), ModelCandidates.check(entry, claimed, c.nonce))
    }

    /** A05: a sibling advertising a report_data that is not its quote's own fails the quote policy. */
    @Test fun aSiblingAdvertisingAnotherReportDataFailsTheQuotePolicy() {
        val c = LiveCandidates.load()
        val entry = c.list[1]
        assertEquals("precondition: the genuine sibling is clean", ModelCandidates.Result.Clean, ModelCandidates.check(entry, c.key, c.nonce))
        val rd = TdxQuoteVerifier.verify(entry.getValue("intel_quote").jsonPrimitive.content).measurements.reportData
        val other = rd.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        val advertised = JsonObject(entry + ("report_data" to JsonPrimitive(hex(other))))
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.QUOTE_POLICY), ModelCandidates.check(advertised, c.key, c.nonce))
    }

    /** A10: one broken listed enclave means the key is not intact, whatever else holds (the unpublished-image exemption reads it). */
    @Test fun aBrokenListedEnclaveIsNeverAnIntactBinding() {
        fun session(candidates: ModelCandidates.Listed?) =
            SendGateRuleTest().also { it.offline() }.baselineSession(AttestationRecord.preview.copy(modelCandidates = candidates))
        assertTrue("precondition: the baseline binding is intact", session(null).e2eeBindingIntact)
        assertTrue("precondition: an all-clean list keeps it", session(ModelCandidatesTest.listedFor(
            listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean))).e2eeBindingIntact)
        assertFalse(session(ModelCandidatesTest.listedFor(
            listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Mismatch(ModelCandidates.Why.MR_CONFIG)))).e2eeBindingIntact)
    }

    /** A19: the nonce the list is checked against is the one its request carried. */
    @Test fun theListIsCheckedAgainstTheNonceItsRequestCarried() = runBlocking {
        val body = LiveCandidates.load().body
        val urls = mutableListOf<String>()
        val got = AttestationService.fetchCandidateList("https://cloud-api.near.ai/v1", "zai-org/GLM-5.3-Flash", "k") { url, _ -> urls += url; 200 to body }
        assertNotNull(got)
        assertEquals(1, urls.size)
        assertTrue("the request carried nonce=${got!!.second}: ${urls[0]}", urls[0].contains("nonce=${got.second}"))
        assertEquals(64, got.second.length)
    }
}
