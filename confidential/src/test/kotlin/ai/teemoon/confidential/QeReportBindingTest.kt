// ports: Packages/TDXQuoteVerifier/Tests/TDXQuoteVerifierTests/QEReportBindingTests.swift @ ba82440f (1.1.1 audit A2: the TD QE identity — 21f04a5e, + MISCSELECT ba82440f; the binding tests ba0842d1)
//
// The attestation key must be the Quoting Enclave's (QE report signed by the PCK leaf, its report_data committing to the key).
// Fixtures are the app's: live glm-5.3-flash enclaves and audit's forge of entry [1].
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QeReportBindingTest {
    private fun quote(i: Int) = LiveCandidates.load().list[i].let { (it.getValue("intel_quote") as kotlinx.serialization.json.JsonPrimitive).content }.removePrefix("0x").hexToBytes()

    @Test fun everyLiveListedQuoteVerifiesItsAttestationKeyBoundToTheChain() {
        val list = LiveCandidates.load().list
        assertEquals(3, list.size)
        for (i in list.indices) {
            val r = TdxQuoteVerifier.verify(quote(i))
            assertTrue(r.isVerified)
            assertTrue(TdxQuoteVerifier.attestationKeyIsBoundToChain(r.quote!!.signature))
        }
    }

    @Test fun aBodySignedByAKeyTheQuoteCarriesItselfBesideAGenuineChainIsNotVerified() {
        val r = TdxQuoteVerifier.verify(ForgedSibling.load().quoteHex)
        assertTrue("the copied chain is genuine", r.certChainValid)
        assertFalse(r.isVerified)
    }

    @Test fun aQeReportNotSignedByThePckLeafIsNotVerified() {
        val q = quote(0)
        val at = 632 + 4 + 128 + 6   // the QE report: after the signature-data size, signature, attestation key and the type-6 header
        q[at + 16] = (q[at + 16].toInt() xor 1).toByte()   // inside the QE report body, not its report_data
        assertFalse(TdxQuoteVerifier.verify(q).isVerified)
    }

    private fun liveQeReport(): ByteArray = TdxQuoteVerifier.verify(quote(0)).quote!!.signature.qeReport!!
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun theLiveQeReportIsIntelsTdQuotingEnclave() = assertTrue(TdxQuoteVerifier.reportIsIntelsTdQuotingEnclave(liveQeReport()))

    /** One mutation per compare: each must refuse alone (the others hold, so a dropped compare reads as a failure here). */
    @Test fun anotherSignerProductMiscselectOrADebugEnclaveIsNotTheTdQuotingEnclave() {
        val good = liveQeReport()
        fun mutated(at: Int, f: (Int) -> Int) = good.copyOf().also { it[at] = f(it[at].toInt()).toByte() }
        for ((name, report) in listOf(
            "mrsigner" to mutated(140) { it xor 1 },
            "isvprodid" to mutated(256) { 1 },
            "debug" to mutated(48) { it or 2 },
            "miscselect" to mutated(16) { it xor 1 },
        )) assertFalse(name, TdxQuoteVerifier.reportIsIntelsTdQuotingEnclave(report))
    }

    @Test fun thePinnedTdQeIdentityIsIntelsSignedQeIdentityInTheConformanceKit() {
        val kit = Json.parseToJsonElement(javaClass.getResourceAsStream("/collateral.json")!!.reader().readText()).jsonObject
        val raw = kit.getValue("vectors").jsonObject.getValue("assembledCollateralFields").jsonObject.getValue("qe_identity").jsonPrimitive.content
        val identity = Json.parseToJsonElement(raw).jsonObject
        fun str(k: String) = identity.getValue(k).jsonPrimitive.content.lowercase()
        assertEquals("TD_QE", identity.getValue("id").jsonPrimitive.content)
        assertEquals(str("mrsigner"), hex(TdxQuoteVerifier.TD_QE_MRSIGNER))
        assertEquals(identity.getValue("isvprodid").jsonPrimitive.content.toInt(), TdxQuoteVerifier.TD_QE_PRODID)
        assertEquals(str("miscselect"), hex(TdxQuoteVerifier.TD_QE_MISCSELECT))
        assertEquals(str("miscselectMask"), hex(TdxQuoteVerifier.TD_QE_MISCSELECT_MASK))
        assertEquals(str("attributes"), hex(TdxQuoteVerifier.TD_QE_ATTRIBUTES))
        assertEquals(str("attributesMask"), hex(TdxQuoteVerifier.TD_QE_ATTRIBUTES_MASK))
    }

    @Test fun aGenuineQuoteWhoseQeReportIsNotTheTdQuotingEnclaveIsNotBound() {
        val sig = TdxQuoteVerifier.verify(quote(0)).quote!!.signature
        assertTrue(TdxQuoteVerifier.attestationKeyIsBoundToChain(sig))
        assertFalse(TdxQuoteVerifier.attestationKeyIsBoundToChain(sig, isTdQe = { false }))
    }

    /** Every leg's captured quote (gateway, model, GPU node, TLS, PCS snapshot, kit) still verifies with the binding; only the two built to fail don't. */
    @Test fun everyGenuineFixtureQuoteStillVerifies() {
        val builtToFail = setOf("src/test/resources/fixtures/nearai-trust/audit_forged_sibling_entry_20261120.json#0", "../conformance/vendor/quotes.json#1")
        val re = Regex("\"(?:0x)?(0[45]000[0-9a-f]{3}[0-9a-f]{1200,})\"")
        var seen = 0
        for (dir in listOf("../conformance/vendor", "src/test/resources")) {
            java.io.File(dir).walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.forEach { f ->
                val name = f.path
                re.findAll(f.readText()).forEachIndexed { i, m ->
                    seen++
                    val verified = runCatching { TdxQuoteVerifier.verify(m.groupValues[1]).isVerified }.getOrDefault(false)
                    assertEquals("$name#$i", "$name#$i" !in builtToFail, verified)
                }
            }
        }
        assertTrue("saw $seen quotes", seen >= 15)
    }
}
