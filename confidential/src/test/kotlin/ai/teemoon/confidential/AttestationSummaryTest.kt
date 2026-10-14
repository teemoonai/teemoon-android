// ports: teemoonTests/AttestationSummaryTests.swift @ v1.0.1 (AttestationSummaryTests + AttestationSummaryProvenanceTests)
package ai.teemoon.confidential

import ai.teemoon.confidential.AttestationFixtures.clone
import ai.teemoon.confidential.AttestationFixtures.fetchFailed
import ai.teemoon.confidential.AttestationFixtures.ref
import ai.teemoon.providers.Provider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestationSummaryTest {

    /** Builds a summary with sensible defaults for the healthy live case. */
    private fun makeSummary(
        attestation: AttestationRecord?,
        state: AttestationState = AttestationState.OK,
        timedOut: Boolean = false,
        provider: Provider? = Provider.nearAI,
        lastRequestUsedE2EE: Boolean? = null,
        lastE2EEFailReason: String? = null,
        verifiedResponseCount: Int = 0,
        mismatchedResponseCount: Int = 0,
        attestationFetchFailed: Boolean = false,
        imageProvenance: ManifestProvenance? = null,
    ) = AttestationSummary(
        attestation = attestation, state = state, timedOut = timedOut, provider = provider,
        lastRequestUsedE2EE = lastRequestUsedE2EE, lastE2EEFailReason = lastE2EEFailReason,
        verifiedResponseCount = verifiedResponseCount, mismatchedResponseCount = mismatchedResponseCount,
        attestationFetchFailed = attestationFetchFailed, imageProvenance = imageProvenance,
    )

    // MARK: - All checks pass

    @Test fun allChecksPass_previewRecord() {
        val summary = makeSummary(clone(AttestationRecord.preview))
        assertEquals(true, summary.e2eePassed)
        assertEquals(true, summary.gwTdxSignatureValid)
        assertEquals(true, summary.gwCertChainValid)
        assertEquals(true, summary.keyBoundToHardware)
        assertEquals(true, summary.modelTdxValid)
        assertEquals(true, summary.e2eeKeyBound)
        // No responses yet — response-signature check hidden.
        assertNull(summary.responseSigValid)
        assertEquals(true, summary.codeIdentityValid)
        assertEquals(7, summary.checksTotal)
        assertEquals(7, summary.checksPassed)
        assertTrue(summary.allChecksPassed)
        assertEquals("end-to-end encrypted", summary.headerTitle)
        assertEquals("verified just now", summary.timestampText())
    }

    @Test fun allChecksPass_includesResponseSignaturesOnceSeen() {
        val summary = makeSummary(clone(AttestationRecord.preview), verifiedResponseCount = 7)
        assertEquals(true, summary.responseSigValid)
        assertEquals(8, summary.checksTotal)
        assertEquals(8, summary.checksPassed)
        assertTrue(summary.allChecksPassed)
    }

    @Test fun signatureMismatch_failsResponseCheck() {
        val summary = makeSummary(clone(AttestationRecord.preview), verifiedResponseCount = 2, mismatchedResponseCount = 1)
        assertEquals(false, summary.responseSigValid)
        assertEquals(8, summary.checksTotal)
        assertEquals(7, summary.checksPassed)
        assertFalse(summary.allChecksPassed)
    }

    // MARK: - E2EE failed on last request

    @Test fun e2eeFailed_lastRequestPlaintext_withReason() {
        val summary = makeSummary(clone(AttestationRecord.preview), state = AttestationState.DEGRADED,
            lastRequestUsedE2EE = false, lastE2EEFailReason = "Server rejected key (HTTP 421)")
        assertEquals(false, summary.e2eePassed)
        assertEquals("Server rejected key (HTTP 421)", summary.e2eeFailDetail)
        // Hardware + code-identity checks still pass; only the E2EE check fails.
        assertEquals(7, summary.checksTotal)
        assertEquals(6, summary.checksPassed)
        assertFalse(summary.allChecksPassed)
        assertEquals("verified hardware", summary.headerTitle)
    }

    @Test fun e2eeFailed_lastRequestPlaintext_withoutReason() {
        val summary = makeSummary(clone(AttestationRecord.preview), state = AttestationState.DEGRADED, lastRequestUsedE2EE = false)
        assertEquals(false, summary.e2eePassed)
        assertEquals("Encryption failed on last request", summary.e2eeFailDetail)
    }

    @Test fun e2eeUnavailable_noEd25519Key() {
        val summary = makeSummary(clone(AttestationRecord.previewDegraded), state = AttestationState.DEGRADED)
        assertEquals(false, summary.e2eePassed)
        assertEquals("Ed25519 encryption key unavailable", summary.e2eeFailDetail)
        // No model attestation on the degraded record → model checks hidden.
        assertNull(summary.modelTdxValid)
        assertNull(summary.e2eeKeyBound)
        assertEquals(4, summary.checksTotal)
        assertEquals(3, summary.checksPassed)
    }

    // MARK: - Attestation fetch failed

    @Test fun attestationFetchFailed_appendsStaleNote() {
        val summary = makeSummary(clone(AttestationRecord.preview), attestationFetchFailed = true)
        assertEquals("verified just now (refresh failed)", summary.timestampText())
        // Fetch failure alone doesn't flip the checks — they reflect the last record.
        assertTrue(summary.allChecksPassed)
    }

    @Test fun attestationFetchFailed_noRecordAtAll() {
        val summary = makeSummary(attestation = null, state = AttestationState.DEGRADED, attestationFetchFailed = true)
        assertEquals("No attestation data", summary.timestampText())
        assertEquals(false, summary.e2eePassed)
        assertEquals("Ed25519 encryption key unavailable", summary.e2eeFailDetail)
        // Only the E2EE row is derivable without a record.
        assertEquals(1, summary.checksTotal)
        assertEquals(0, summary.checksPassed)
    }

    // MARK: - Missing model attestation

    @Test fun missingModelAttestation_e2eeKeyUnverified() {
        val record = clone(AttestationRecord.preview, modelQuoteVerification = null)
        val summary = makeSummary(record)
        assertEquals(false, summary.e2eePassed)
        assertEquals("Model TEE attestation unavailable", summary.e2eeFailDetail)
        assertNull(summary.modelTdxValid)
        assertNull(summary.e2eeKeyBound)
        // Gateway checks unaffected.
        assertEquals(true, summary.gwTdxSignatureValid)
        // Code identity still binds via the gateway quote.
        assertEquals(true, summary.codeIdentityValid)
        assertEquals(5, summary.checksTotal)
        assertEquals(4, summary.checksPassed)
        assertFalse(summary.allChecksPassed)
    }

    // MARK: - Share report

    @Test fun shareText_verifiedRecord_formatsReport() {
        val record = clone(AttestationRecord.preview)
        val summary = makeSummary(record)
        val text = summary.shareText(e2eeMessageCount = 14, verifiedResponseCount = 7, mismatchedResponseCount = 0)
        val lines = text.split("\n")

        assertEquals("near.ai TEE Attestation (VERIFIED)", lines[0])
        assertEquals("Fetched: ${AttestationSummary.mediumDateShortTime.format(record.fetchedAt)}", lines[1])
        assertEquals("", lines[2])
        assertEquals("E2EE messages this session: 14", lines[3])
        assertEquals("Signed responses this session: 7", lines[4])
        // No mismatches — the mismatch line is omitted entirely.
        assertFalse(text.contains("Signature mismatches this session"))
        assertEquals("", lines[5])
        assertEquals("Gateway TDX Signature: Valid", lines[6])
        assertEquals("Gateway Cert Chain: Valid", lines[7])
        // Preview record has no GPU quote → GPU node lines omitted.
        assertFalse(text.contains("GPU Node TDX Signature"))
        // Hex-formatted measurement lines with aligned padding.
        assertTrue(lines.contains("MRTD (Intel TDX):         ${record.mrtd}"))
        assertTrue(lines.contains("Compose hash (mr_config): ${record.composeHash}"))
        assertTrue(lines.contains("GPU TEE compose hash:     ${record.gpuNodeComposeHash}"))
        assertTrue(lines.contains("Model YAML SHA256:        ${record.modelFileHash}"))
        // 32 bytes of 0xAB → 64 lowercase hex chars.
        assertTrue(lines.contains("Model Ed25519 pubkey:     ${"ab".repeat(32)}"))
        assertTrue(lines.contains("Gateway signing address:  ${record.signingAddress}"))
        assertTrue(lines.contains("GPU signing address:      ${record.gpuSigningAddress}"))
        assertEquals("Verify: https://cloud-api.near.ai/v1/attestation/report?nonce=manual&signing_algo=ecdsa", lines.last())
    }

    @Test fun shareText_noCounts_omitsSessionBlock() {
        val text = makeSummary(clone(AttestationRecord.preview)).shareText(0, 0, 0)
        val lines = text.split("\n")
        assertFalse(text.contains("this session"))
        // Session block omitted → gateway lines follow the header blank line directly.
        assertEquals("Gateway TDX Signature: Valid", lines[3])
    }

    @Test fun shareText_noRecord_returnsPlaceholder() {
        val text = makeSummary(attestation = null, state = AttestationState.DEGRADED).shareText(0, 0, 0)
        assertEquals("near.ai TEE Attestation\nNo data available.", text)
    }

    // MARK: Android-only — the clock branches of timestampText

    @Test fun timestampTextCountsMinutesThenFallsBackToTheClock() {
        val now = Instant.parse("2026-09-02T10:00:00Z")
        fun at(secondsAgo: Long) = makeSummary(clone(AttestationRecord.preview, fetchedAt = now.minusSeconds(secondsAgo))).timestampText(now)
        assertEquals("verified 1 min ago", at(60))
        assertEquals("verified 5 mins ago", at(330))
        assertTrue(at(7200).startsWith("verified ") && !at(7200).contains("ago"))
    }
}

/** The image-provenance row and its place in the pass/fail count. */
class AttestationSummaryProvenanceTest {
    private fun makeSummary(imageProvenance: ManifestProvenance?) = AttestationSummary(
        attestation = AttestationRecord.preview, state = AttestationState.OK, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = null, lastE2EEFailReason = null,
        verifiedResponseCount = 0, mismatchedResponseCount = 0,
        attestationFetchFailed = false, imageProvenance = imageProvenance,
    )
    private val ref = ref("nearaidev/cloud-api")

    @Test fun pending_showsLiveRow_notCounted() {
        val s = makeSummary(null)
        assertTrue(s.provenanceState == StepState.LIVE || s.provenanceState == StepState.PENDING)
        // Pending provenance is not part of the pass/fail count.
        assertEquals(7, s.checksTotal)   // preview's 7 (incl. code identity), provenance excluded
    }

    @Test fun allVerified_addsGreenCheck() {
        val s = makeSummary(ManifestProvenance.AllVerified(verified = listOf(ref), thirdParty = emptyList()))
        assertEquals(StepState.DONE, s.provenanceState)
        assertEquals(8, s.checksTotal)
        assertEquals(8, s.checksPassed)
    }

    @Test fun incomplete_addsFailingCheck() {
        val failure = ProvenanceFailure(ref, fetchFailed())
        val s = makeSummary(ManifestProvenance.Incomplete(verified = emptyList(), failures = listOf(failure), thirdParty = emptyList()))
        assertEquals(StepState.STUCK, s.provenanceState)
        assertEquals(8, s.checksTotal)
        assertEquals(7, s.checksPassed)  // provenance fails
    }

    @Test fun incompleteDetail_namesTheFailingImageAndReason() {
        val failing = ProvenanceFailure(ref, fetchFailed())
        val s = makeSummary(ManifestProvenance.Incomplete(
            verified = listOf(ref("nearaidev/mesh", 'b')), failures = listOf(failing), thirdParty = emptyList()))
        // Names the specific image (last path component) and the plain reason,
        // not just a bare "1 of 2" count.
        assertTrue(s.provenanceDetail.contains("cloud-api"))
        assertTrue(s.provenanceDetail.contains("GitHub 404"))
        assertTrue(s.provenanceDetail.contains("1 of 2"))
    }

    @Test fun namedFailures_boundsTheListAndCountsTheRest() {
        val failures = (0 until 4).map { i -> ProvenanceFailure(ref("nearaidev/img$i", ('0' + i)), fetchFailed()) }
        val text = AttestationSummary.namedFailures(failures)
        assertTrue(text.contains("img0"))
        assertTrue(text.contains("img1"))
        assertFalse(text.contains("img2"))       // beyond the limit
        assertTrue(text.contains("+2 more"))
    }

    @Test fun thirdPartySidecars_doNotFailProvenance_andAreDisclosed() {
        val sidecar = ref("datadog/agent", '1')
        val s = makeSummary(ManifestProvenance.AllVerified(verified = listOf(ref), thirdParty = listOf(sidecar)))
        assertEquals(StepState.DONE, s.provenanceState)
        assertEquals(s.checksTotal, s.checksPassed)
        assertTrue(s.provenanceDetail.contains("third-party"))
    }
}
