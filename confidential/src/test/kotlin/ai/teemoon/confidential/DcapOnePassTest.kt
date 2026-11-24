// ports: teemoonTests/ListedTrustScopeTests.swift @ e86b13cd (1.1.1 audit A4 read: eachQuoteOfARecordGoesToIntelOnce). iOS verified every listed quote twice
//        (DCAPService.verify into RecordDCAPVerification.listed, then again as the trust scope's node DCAP) and fixed it with DCAPService.forOneRecord()'s
//        memo. Android's listed scopes are derived from the one verification (ListedNearScopeTest), so that double pass never existed; what can still
//        repeat is a quote two of the record's legs carry (the read enclave is itself one of the listed), and DcapService.verify now answers it once.
//
// Audit read of 1fe69e3d: one record's quotes each go to Intel once (listed nodes, compose managers, the record's own legs).
package ai.teemoon.confidential

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class DcapOnePassTest {
    private fun hex(q: String) = q.removePrefix("0x").hexToBytes()

    private fun counted(record: AttestationRecord): Pair<RecordDcapVerification, List<ByteArray>> {
        val seen = Collections.synchronizedList(mutableListOf<ByteArray>())
        val collateral = CollateralProvider { quote -> seen.add(quote); throw java.io.IOException("timed out") }
        var result: RecordDcapVerification? = null
        runTest { result = DcapService(collateral, DcapQvlEngine { _, _, _ -> throw AssertionError("no collateral, no engine") }).verify(record) }
        return result!! to seen.toList()
    }

    /** The listed verdicts and the listed scopes' compose managers: every quote of the record asked once. */
    @Test fun eachQuoteOfARecordGoesToIntelOnce() {
        val c = LiveCandidates.load()
        val (result, asked) = counted(AttestationRecord.preview.copy(modelCandidates = ModelCandidates.enclaves(c.body, c.nonce, c.key)))
        assertEquals(3, result.listed.size)
        for (entry in c.list) {
            val quote = hex(entry.getValue("intel_quote").jsonPrimitive.content)
            assertEquals("a listed quote went to Intel ${asked.count { it.contentEquals(quote) }} times", 1, asked.count { it.contentEquals(quote) })
        }
        assertTrue("the compose managers' quotes were never asked", asked.size > 3)
        assertEquals("${asked.size - asked.map { it.toList() }.toSet().size} quote(s) went to Intel twice", asked.size, asked.map { it.toList() }.toSet().size)
    }

    /** The read enclave is one of the listed: its quote is the model leg's and listed 1's, and goes to Intel once. */
    @Test fun aQuoteTwoLegsCarryGoesToIntelOnce() {
        val c = LiveCandidates.load()
        val first = c.list[0].getValue("intel_quote").jsonPrimitive.content
        val record = AttestationRecord.preview.copy(modelIntelQuote = first, modelCandidates = ModelCandidates.enclaves(c.body, c.nonce, c.key))
        val (_, asked) = counted(record)
        val q = hex(first)
        assertEquals("the read enclave's quote went to Intel ${asked.count { it.contentEquals(q) }} times", 1, asked.count { it.contentEquals(q) })
    }
}
