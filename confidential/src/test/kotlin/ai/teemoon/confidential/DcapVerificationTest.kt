// ports: teemoonTests/DCAPServiceTests.swift @ v1.0.1 (hardFailurePolicy_matchesPlan and summaryDCAPRow_states
//        only — the service tests run dcap-qvl over the captured collateral snapshot and port with the
//        quote/collateral fixture classes)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DcapVerificationTest {

    @Test fun hardFailurePolicy_matchesPlan() {
        // Verified + out-of-date TCB: flagged, NOT a hard failure (near.ai parity).
        val outOfDate = DcapVerification.Verified(DcapTcbStatus.OUT_OF_DATE, "01", "")
        assertFalse(outOfDate.isHardFailure)
        // Revoked TCB and failed verification are hard failures.
        val revoked = DcapVerification.Verified(DcapTcbStatus.REVOKED, "01", "")
        assertTrue(revoked.isHardFailure)
        assertTrue(DcapVerification.Failed("no collateral").isHardFailure)

        var record = RecordDcapVerification(gateway = outOfDate)
        assertFalse(record.hasHardFailure)
        assertEquals(DcapTcbStatus.OUT_OF_DATE, record.worstTcbStatus)
        record = record.copy(model = revoked)
        assertTrue(record.hasHardFailure)
        assertEquals(DcapTcbStatus.REVOKED, record.worstTcbStatus)
    }

    /** The iOS test reads the captured quote; a minimal record with a non-empty quote exercises the same row logic. */
    @Test fun summaryDCAPRow_states() {
        val record = AttestationRecord(
            composeHash = "", mrtd = "", osImageHash = "", intelQuote = "0402deadbeef",
            composeManifest = null, gpuArch = null, gpuNodeComposeHash = null, modelFileHash = null,
            signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = null,
            quoteVerification = null, gpuQuoteVerification = null, modelQuoteVerification = null,
            fetchedAt = Instant.now(), providerID = "p",
        )
        val verified = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.OUT_OF_DATE, "01", ""))
        fun summary(dcap: RecordDcapVerification?) = AttestationSummary(
            attestation = record, state = AttestationState.DEGRADED, timedOut = false, provider = null,
            lastRequestUsedE2EE = null, lastE2EEFailReason = null,
            verifiedResponseCount = 0, mismatchedResponseCount = 0,
            attestationFetchFailed = false, dcapVerification = dcap)
        assertEquals(StepState.PENDING, summary(null).dcapState)
        assertEquals(StepState.DONE, summary(verified).dcapState)
        assertTrue(summary(verified).dcapDetail.contains("behind on Intel platform updates"))
        val failed = RecordDcapVerification(gateway = DcapVerification.Failed("PCCS collateral unavailable"))
        assertEquals(StepState.STUCK, summary(failed).dcapState)
        // Definitive results join the pass/fail banner; pending does not.
        assertEquals(summary(null).allChecks.size + 1, summary(verified).allChecks.size)
    }

    /** Android-only: the TCB status parser mirrors dcap-qvl's spellings. */
    @Test fun tcbStatusFromRaw() {
        assertEquals(DcapTcbStatus.UP_TO_DATE, DcapTcbStatus.fromRaw("UpToDate"))
        assertEquals(DcapTcbStatus.OUT_OF_DATE, DcapTcbStatus.fromRaw("OUT_OF_DATE"))
        assertEquals(DcapTcbStatus.CONFIGURATION_NEEDED, DcapTcbStatus.fromRaw("ConfigurationAndSWHardeningNeeded"))
        assertEquals(DcapTcbStatus.REVOKED, DcapTcbStatus.fromRaw("Revoked"))
        assertEquals(DcapTcbStatus.OTHER, DcapTcbStatus.fromRaw("SWHardeningNeeded"))
        assertTrue(DcapTcbStatus.UP_TO_DATE.isFullyTrusted)
        assertFalse(DcapTcbStatus.OUT_OF_DATE.isFullyTrusted)
    }
}
