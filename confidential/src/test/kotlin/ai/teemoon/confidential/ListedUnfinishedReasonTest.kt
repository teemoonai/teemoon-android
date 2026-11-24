// ports: teemoonTests/ListedUnfinishedReasonTests.swift @ 36d53c97 (aListedEnclavesUncheckedNVIDIANamesTheListedEnclaves)
// ports: teemoonTests/ListedUnfinishedReasonTests.swift @ 38850f79
//
// pm 2026-11-20 (reuses A7 copy): a LISTED enclave's DCAP that Intel couldn't check names the listed enclaves, never the
// read enclave's "intel's hardware check couldn't be completed (service unreachable).", which keeps its own line.
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Test

class ListedUnfinishedReasonTest {
    private val intel = "intel's hardware check couldn't be completed (service unreachable)."
    private val unchecked = DcapVerification.Unchecked("collateral: pcs unreachable")
    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    private fun session() = SendGateRuleTest().also { it.offline() }.baselineSession(
        AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean))))

    @Test fun aListedEnclavesUncheckedDcapNamesTheListedEnclaves() {
        val s = session()
        s.dcapVerification = s.dcapVerification!!.copy(listed = listOf(unchecked))
        assertEquals("teemoon couldn't finish checking the enclaves near.ai listed.", s.e2eeDegradedReason)
        assertEquals(KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED), s.e2eeDegradedReason)
    }

    @Test fun theReadEnclavesUncheckedDcapKeepsItsLine() {
        val read = session()
        read.dcapVerification = read.dcapVerification!!.copy(listed = listOf(upToDate), model = unchecked)
        assertEquals(intel, read.e2eeDegradedReason)

        val both = session()
        both.dcapVerification = both.dcapVerification!!.copy(listed = listOf(unchecked), gateway = unchecked)
        assertEquals("the read leg is named first", intel, both.e2eeDegradedReason)
    }

    /** iOS 36d53c97 (A10 gap, pm 2026-11-20): a listed enclave's NVIDIA leg that couldn't be checked takes the same A7 line, and asks. */
    @Test fun aListedEnclavesUncheckedNvidiaNamesTheListedEnclaves() {
        for (nvidia in listOf("NVIDIA attestation service unreachable", NrasVerification.EVIDENCE_ABSENT, NrasVerification.TEEMOON_FAULT)) {
            val s = session()
            s.gpuAttestation = NrasVerification.ListedInconclusive(nvidia)
            assertEquals(nvidia, KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED), s.e2eeDegradedReason)
            assertEquals(nvidia, TrustSendPolicy.CONFIRM, s.sendPolicy)
        }
        val read = session()
        read.gpuAttestation = NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT)
        assertEquals("the read enclave's own line is unchanged", NrasVerification.EVIDENCE_ABSENT, read.e2eeDegradedReason)
    }
}
