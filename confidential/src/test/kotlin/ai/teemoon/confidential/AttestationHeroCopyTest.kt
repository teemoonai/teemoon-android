// ports: teemoonTests/AttestationHeroCopyTests.swift @ v1.0.1, @ 8bfe33cb (okState_claimsReadingOnlyOverAReviewedBuild)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attestation sheet's hero is the app's headline security claim. Two ways
 * it used to overclaim: the soft-degrade branch said "verified hardware" when
 * the attestation NEVER ARRIVED, and it said "E2EE is unavailable" for degrades
 * where the send is still sealed — false, and false in the direction that talks
 * a user out of encryption they still have.
 */
class AttestationHeroCopyTest {

    private fun summary(
        attestation: AttestationRecord? = AttestationRecord.preview,
        state: AttestationState = AttestationState.OK,
        attestationFetchFailed: Boolean = false,
        imageProvenance: ManifestProvenance? = null,
        degradeIsHardFailure: Boolean = false,
        e2eeIntact: Boolean = false,
        auditState: EverydayAuditState? = EverydayAuditState.REVIEWED,
        leaksExposure: String? = null,
    ) = AttestationSummary(
        attestation = attestation, state = state, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = null, lastE2EEFailReason = null,
        verifiedResponseCount = 0, mismatchedResponseCount = 0,
        attestationFetchFailed = attestationFetchFailed, imageProvenance = imageProvenance,
        dcapVerification = null, nrasVerification = null, tlsAttestation = null,
        modelLayerVerification = null,
        degradeIsHardFailure = degradeIsHardFailure,
        e2eeIntact = e2eeIntact,
        auditState = auditState, leaksExposure = leaksExposure,
    )

    // MARK: - Nothing was verified

    @Test fun fetchFailedDegrade_claimsNoVerification() {
        // The attestation request failed: there is no record, so no hardware,
        // no code identity and no key was ever checked.
        val s = summary(attestation = null, state = AttestationState.DEGRADED, attestationFetchFailed = true)
        assertEquals("verification unavailable", s.headerTitle)
        assertFalse(s.headerTitle.contains("verified hardware"))
        assertTrue(s.headerSubtitle.contains("couldn’t reach the attestation service"))
        // No "tamper-proof hardware verified" claim anywhere in the hero.
        assertFalse(s.headerSubtitle.contains("tamper-proof hardware verified"))
        // Still an advisory, not a red security break — nothing attacked us.
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, s.headerSeverity)
        // And no lock glyph picturing a guarantee that was never established.
        assertEquals("exclamationmark.triangle.fill", s.headerIcon)
    }

    // MARK: - Degraded, but the send is still sealed

    @Test fun unpublishedImageDegrade_doesNotRetractE2EE() {
        // A clean GitHub 404 on an image digest. The key is still bound to a
        // verified model TEE, so the bytes on the wire are still encrypted.
        val s = summary(state = AttestationState.DEGRADED, e2eeIntact = true)
        assertEquals("verified hardware", s.headerTitle)
        assertFalse(s.headerSubtitle.contains("E2EE is unavailable"))
        assertTrue(s.headerSubtitle.contains("still sealed"))
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, s.headerSeverity)
        assertEquals("lock.trianglebadge.exclamationmark.fill", s.headerIcon)
    }

    // MARK: - Degraded because E2EE really is off

    @Test fun e2eeOffDegrade_saysSo() {
        val s = summary(state = AttestationState.DEGRADED, e2eeIntact = false)
        assertEquals("verified hardware", s.headerTitle)
        assertEquals("tamper-proof hardware verified, but E2EE is unavailable.", s.headerSubtitle)
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, s.headerSeverity)
    }

    // MARK: - Hard break and the healthy case are unchanged

    @Test fun hardFailureDegrade_staysRed() {
        val s = summary(state = AttestationState.DEGRADED, degradeIsHardFailure = true, e2eeIntact = false)
        assertEquals("verification failed", s.headerTitle)
        assertEquals("xmark.shield.fill", s.headerIcon)
        assertEquals(AttestationSummary.HeaderSeverity.FAILED, s.headerSeverity)
        assertTrue(s.headerSubtitle.contains("treat this chat as unverified"))
    }

    /** A hard break wins over e2eeIntact — the flag must never be able to soften a tamper/replay/MITM verdict. */
    @Test fun hardFailureDegrade_isNotSoftenedByE2EEIntact() {
        val s = summary(state = AttestationState.DEGRADED, degradeIsHardFailure = true, e2eeIntact = true)
        assertEquals("verification failed", s.headerTitle)
        assertEquals(AttestationSummary.HeaderSeverity.FAILED, s.headerSeverity)
    }

    /** trust-leaks-wording (the maintainer 2026-11-06; iOS 8bfe33cb): no "only you … can read" over an unreviewed or leaking build. */
    @Test fun okState_claimsReadingOnlyOverAReviewedBuild() {
        for (a in listOf(null, EverydayAuditState.INCOMPLETE, EverydayAuditState.REVIEWED_WITH_CAVEAT)) {
            assertEquals("$a", AuditedSealText.NOT_REVIEWED, summary(state = AttestationState.OK, auditState = a).headerSubtitle)
        }
        assertEquals("no exposure recorded", AuditedSealText.NOT_REVIEWED, summary(state = AttestationState.OK, auditState = EverydayAuditState.LEAKS).headerSubtitle)
        assertEquals("sealed to the model's hardware on the way in. a review of this exact build found: every prompt is logged.",
            summary(state = AttestationState.OK, auditState = EverydayAuditState.LEAKS, leaksExposure = "every prompt is logged.").headerSubtitle)
    }

    @Test fun okState_isUnchanged() {
        val s = summary(state = AttestationState.OK)
        assertEquals("end-to-end encrypted", s.headerTitle)
        assertEquals("sealed to the attested enclave key. near.ai's key service decides which enclaves hold it.", s.headerSubtitle)
        assertEquals("checkmark.shield.fill", s.headerIcon)
        assertEquals(AttestationSummary.HeaderSeverity.OK, s.headerSeverity)
    }

    // MARK: Android-only — the run-result demotion and the remaining header branches

    /** The hero is never greener than the worst check below it: a flagged run demotes ok to advisory, a failed one to failed. */
    @Test fun runResultDemotesTheHero() {
        // Provenance and the engine resolved, so no pending step outranks the flag (running beats flagged in the rollup).
        val resolved = summary(imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList()))
            .copy(modelLayerVerification = ModelLayerVerification.VERIFIED,
                dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", "")))
        val flagged = resolved.copy(tlsAttestation = TlsAttestation.Inconclusive("verifier unreachable"))
        assertEquals(SectionResult.FLAGGED, flagged.runResult)
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, flagged.headerSeverity)
        assertEquals("verified — with a warning", flagged.headerTitle)
        assertEquals("exclamationmark.shield.fill", flagged.headerIcon)
        assertTrue(flagged.headerSubtitle.contains("one check couldn’t be completed"))
        val failed = resolved.copy(tlsAttestation = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint"))
        assertEquals(SectionResult.FAILED, failed.runResult)
        assertEquals(AttestationSummary.HeaderSeverity.FAILED, failed.headerSeverity)
        assertEquals("verification failed", failed.headerTitle)
        assertEquals("xmark.shield.fill", failed.headerIcon)
        assertTrue(failed.headerSubtitle.contains("treat it as unencrypted"))
    }

    @Test fun verifyingTimedOutNoneAndVerifyingHeaders() {
        val timedOut = summary(state = AttestationState.VERIFYING).copy(timedOut = true)
        assertEquals("can’t reach verifier", timedOut.headerTitle)
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, timedOut.headerSeverity)
        assertEquals("exclamationmark.triangle.fill", timedOut.headerIcon)
        val verifying = summary(state = AttestationState.VERIFYING)
        assertEquals("verifying…", verifying.headerTitle)
        assertEquals("lock.fill", verifying.headerIcon)
        assertEquals(AttestationSummary.HeaderSeverity.NEUTRAL, verifying.headerSeverity)
        val none = summary(state = AttestationState.NONE)
        assertEquals("connection info", none.headerTitle)
        assertEquals("this provider does not support hardware attestation.", none.headerSubtitle)
        assertEquals("globe", none.headerIcon)
    }

    /** 1.1.1 audit A9 (iOS 37de10d9): the private pillar says what the quote holds and what the messages are sealed to,
     *  never that the key was made in or lives in an enclave, or that only it can read them. */
    @Test fun privacyPillar_saysTheKeyIsInTheQuote() {
        val p = summary(state = AttestationState.OK).pillars.first { it.id == "private" }
        assertEquals("Messages are end-to-end encrypted to this enclave's key. near.ai's key service can give that key to other enclaves; teemoon checks the ones near.ai lists.", p.summaryLine)
        assertEquals("The key is in that enclave's hardware quote", p.checks.first { it.label == "Encryption key bound to TEE" }.detail)
        assertEquals("The response-signing key is in that enclave's hardware quote", p.checks.first { it.label == "Signing key bound to hardware" }.detail)
    }
}
