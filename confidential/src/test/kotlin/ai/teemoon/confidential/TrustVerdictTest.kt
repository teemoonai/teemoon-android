// ports: teemoonTests/TrustVerdictTests.swift @ v1.0.4
//        teemoonTests/AuditIndexTests.swift @ v1.0.4 (everydayRollupWorstClassWins only)
package ai.teemoon.confidential

import ai.teemoon.confidential.AuditVerdictClass.COMPROMISABLE
import ai.teemoon.confidential.AuditVerdictClass.INCONCLUSIVE
import ai.teemoon.confidential.AuditVerdictClass.LEAKS
import ai.teemoon.confidential.AuditVerdictClass.PRIVATE_REVIEW
import ai.teemoon.confidential.AuditVerdictClass.QUALIFIED_PASS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences a reviewer will quote. Authored by TrustVerdict, not a View. */
class TrustVerdictTest {

    private fun input(mutate: TrustVerdict.Input.() -> TrustVerdict.Input = { this }): TrustVerdict.Input =
        TrustVerdict.Input(
            attestationState = AttestationState.OK,
            modelName = "glm-5.2",
            providerDisplayName = "near.ai",
            e2eeKeyBound = true,
            provenanceState = StepState.DONE,
            hasSigningAddress = true,
            auditState = EverydayAuditState.REVIEWED,
            deviceBoundary = "your phone",
            deviceSubject = "your phone",
        ).mutate()

    @Test fun verifiedHeroIsTheSealClaim() {
        val v = TrustVerdict.make(input())
        assertEquals("encrypted to glm-5.2 — only it can read this", v.heroTitle)
        assertEquals(TrustSendPolicy.ALLOW, v.sendPolicy)
        assertEquals("end-to-end encrypted", v.chipCaption)
    }

    @Test fun leaksReviewQualifiesTheHero() {
        val v = TrustVerdict.make(input { copy(auditState = EverydayAuditState.LEAKS) })
        assertEquals("encrypted to glm-5.2 — but its logs copy what you type", v.heroTitle)
        assertTrue(v.everydayClaims.any { it.id == "audit" && it.status == TrustClaim.Status.ALERT })
    }

    @Test fun hardFailureBlocksSending() {
        val v = TrustVerdict.make(input {
            copy(attestationState = AttestationState.DEGRADED, requiresConfirmation = true, degradeIsHardFailure = true)
        })
        assertEquals("sending blocked", v.heroTitle)
        assertEquals(TrustSendPolicy.BLOCK, v.sendPolicy)
        assertEquals("verification failed — sending blocked", v.chipCaption)
    }

    @Test fun softDegradeAsksBeforeSending() {
        val v = TrustVerdict.make(input {
            copy(attestationState = AttestationState.DEGRADED, requiresConfirmation = true, degradeIsHardFailure = false)
        })
        assertEquals("sending paused", v.heroTitle)
        assertEquals(TrustSendPolicy.CONFIRM, v.sendPolicy)
        assertEquals("not end-to-end encrypted", v.chipCaption)
    }

    /** 4b9d154: since 1102bc8 a sealed session re-sweeping provenance reads VERIFYING; a failed reply stays on the chip through it. */
    @Test fun aMismatchStaysOnTheChipThroughAProvenanceSweep() {
        val v = TrustVerdict.make(input { copy(attestationState = AttestationState.VERIFYING, mismatchedResponseCount = 1) })
        assertEquals(TrustChipMode.MISMATCH, v.chipMode)
    }

    @Test fun mismatchCaptionNamesTheFailedReply() {
        val v = TrustVerdict.make(input { copy(attestationState = AttestationState.OK, mismatchedResponseCount = 1) })
        assertEquals(TrustChipMode.MISMATCH, v.chipMode)
        assertEquals("a reply didn’t check out", v.chipCaption)
        assertEquals(TrustSendPolicy.ALLOW, v.sendPolicy)
        assertEquals("one reply didn't check out", v.heroTitle)
    }

    /**
     * GLM-5.3 flash, 2026-09-12: the sheet opened on "but its logs copy what you type" (a reviewed leak) and flipped to
     * "one image unpublished" the moment provenance landed — the weaker line hid the stronger one.
     */
    @Test fun aReviewedLeakOutranksAnUnpublishedImage() {
        val v = TrustVerdict.make(input { copy(auditState = EverydayAuditState.LEAKS, unpublishedButSealed = true) })
        assertTrue(v.heroTitle.contains("logs copy what you type"))
        assertFalse(v.heroTitle.contains("unpublished"))
        assertTrue(v.heroSubtitle.contains("monitoring logs"))
    }

    @Test fun unpublishedButSealedDoesNotSayUnencrypted() {
        val v = TrustVerdict.make(input {
            copy(attestationState = AttestationState.DEGRADED, requiresConfirmation = false, unpublishedButSealed = true)
        })
        assertEquals(TrustSendPolicy.ALLOW, v.sendPolicy)
        assertEquals(TrustChipMode.SOFT_DEGRADE, v.chipMode)
        assertEquals("encrypted — image unpublished", v.chipCaption)
        assertEquals("encrypted to glm-5.2 — one image unpublished", v.heroTitle)
        assertFalse(v.chipCaption.contains("not end-to-end encrypted"))
    }

    @Test fun onDeviceChipIsNotSilent() {
        val v = TrustVerdict.make(input { copy(attestationState = AttestationState.NONE, isLocal = true) })
        assertEquals(TrustChipMode.ON_DEVICE, v.chipMode)
        assertEquals("on this device", v.chipCaption)
    }

    // MARK: everydayAuditState (AuditIndexTests.everydayRollupWorstClassWins)

    @Test fun everydayRollupWorstClassWins() {
        // Any leaks → alert, regardless of coverage.
        assertEquals(EverydayAuditState.LEAKS, everydayAuditState(listOf(PRIVATE_REVIEW, LEAKS), allTouchersCovered = true))
        assertEquals(EverydayAuditState.LEAKS, everydayAuditState(listOf(LEAKS), allTouchersCovered = false))
        // Full coverage, no leaks/inconclusive → reviewed. compromisable
        // control-plane pages don't demote it (their verdicts state no
        // data-plane reach); qualified-pass doesn't either.
        assertEquals(EverydayAuditState.REVIEWED,
            everydayAuditState(listOf(PRIVATE_REVIEW, QUALIFIED_PASS, COMPROMISABLE), allTouchersCovered = true))
        // A coverage gap can never read as an all-clear…
        assertEquals(EverydayAuditState.INCOMPLETE, everydayAuditState(listOf(PRIVATE_REVIEW), allTouchersCovered = false))
        // …nor can an empty class set (offline, no index)…
        assertEquals(EverydayAuditState.INCOMPLETE, everydayAuditState(emptyList(), allTouchersCovered = true))
        // …nor a review that made no claim.
        assertEquals(EverydayAuditState.INCOMPLETE, everydayAuditState(listOf(PRIVATE_REVIEW, INCONCLUSIVE), allTouchersCovered = true))
    }

    // MARK: Android-only — the branches the Swift suite leaves to the views

    @Test fun verifyingHeroAndPendingClaims() {
        val v = TrustVerdict.make(input {
            copy(attestationState = AttestationState.VERIFYING, e2eeKeyBound = null, provenanceState = null, hasSigningAddress = false)
        })
        assertEquals("verifying…", v.heroTitle)
        assertEquals("confirming this conversation runs inside glm-5.2's sealed hardware.", v.heroSubtitle)
        assertEquals(TrustChipMode.VERIFYING, v.chipMode)
        assertEquals("verifying…", v.chipCaption)
        assertEquals(listOf("msg", "sealed", "identity", "audit", "signed"), v.everydayClaims.map { it.id })
        assertEquals(TrustClaim.Status.PENDING, v.everydayClaims.first { it.id == "sealed" }.status)
        assertEquals(TrustClaim.Status.PENDING, v.everydayClaims.first { it.id == "identity" }.status)
        // While verifying, the signature node is present even with no signing address, and not yet green.
        val signed = v.everydayClaims.first { it.id == "signed" }
        assertEquals(TrustClaim.Status.PENDING, signed.status)
        assertEquals("checking each reply's signature", signed.title)
    }

    @Test fun selfHostedAndPlainCloudChips() {
        val self = TrustVerdict.make(input { copy(attestationState = AttestationState.NONE, isSelfHosted = true) })
        assertEquals(TrustChipMode.SELF_HOSTED, self.chipMode)
        assertEquals("on your own machine", self.chipCaption)
        val none = TrustVerdict.make(input { copy(attestationState = AttestationState.NONE) })
        assertEquals(TrustChipMode.NONE, none.chipMode)
        assertEquals("", none.chipCaption)
    }

    @Test fun hardFailureWinsOverMismatchAndCarriesTheDegradeReason() {
        val v = TrustVerdict.make(input {
            copy(attestationState = AttestationState.OK, mismatchedResponseCount = 2, requiresConfirmation = true,
                degradeIsHardFailure = true, e2eeDegradedReason = "the enclave's key changed.")
        })
        assertEquals(TrustChipMode.HARD_BLOCK, v.chipMode)
        assertEquals("the enclave's key changed. sending is blocked until it re-verifies.", v.heroSubtitle)
        val soft = TrustVerdict.make(input { copy(attestationState = AttestationState.DEGRADED, requiresConfirmation = true) })
        assertEquals("end-to-end encryption isn't established. nothing is sent without your confirmation.", soft.heroSubtitle)
    }

    @Test fun pluralMismatchAndQuantLabel() {
        val v = TrustVerdict.make(input { copy(mismatchedResponseCount = 3) })
        assertEquals("3 replies didn't check out", v.heroTitle)
        assertTrue(v.heroSubtitle.contains("but 3 replies couldn't be verified"))
        val q = TrustVerdict.make(input { copy(quant = "fp8") })
        assertEquals("encrypted to glm-5.2 · fp8 — only it can read this", q.heroTitle)
    }

    @Test fun failedClaimsCarryTheirDetail() {
        val v = TrustVerdict.make(input {
            copy(e2eeKeyBound = false, provenanceState = StepState.STUCK, provenanceDetail = "digest mismatch",
                responseSigState = StepState.STUCK, quantDrift = true, auditState = EverydayAuditState.INCOMPLETE)
        })
        val byId = v.everydayClaims.associateBy { it.id }
        assertEquals(TrustClaim.Status.FAIL, byId.getValue("sealed").status)
        assertEquals("your phone couldn't confirm glm-5.2 holds the key — so nothing has been sent.", byId.getValue("sealed").detail)
        assertEquals(TrustClaim.Status.FAIL, byId.getValue("identity").status)
        assertEquals("digest mismatch", byId.getValue("identity").detail)
        assertEquals(TrustClaim.Status.INFO, byId.getValue("audit").status)
        assertEquals(TrustClaim.Status.INFO, byId.getValue("drift").status)
        assertEquals(TrustClaim.Status.FAIL, byId.getValue("signed").status)
        assertEquals("a reply didn't come back signed", byId.getValue("signed").title)
        // The reviewed audit claim jumps to the proof; the incomplete one has nowhere to jump.
        assertNull(byId.getValue("audit").jumpTarget)
        assertEquals("known code", TrustVerdict.make(input()).everydayClaims.first { it.id == "audit" }.jumpTarget)
    }

    @Test fun noAuditStateMeansNoAuditClaimAndNoSignatureNodeWithoutAnAddress() {
        val v = TrustVerdict.make(input { copy(auditState = null, hasSigningAddress = false) })
        assertEquals(listOf("msg", "sealed", "identity"), v.everydayClaims.map { it.id })
    }

    @Test fun verdictClassRawValuesMatchTheIndex() {
        assertEquals(AuditVerdictClass.QUALIFIED_PASS, AuditVerdictClass.fromRaw("qualified-pass"))
        assertEquals(AuditVerdictClass.PRIVATE_REVIEW, AuditVerdictClass.fromRaw("private"))
        assertNull(AuditVerdictClass.fromRaw("catastrophic"))
        assertEquals("qualified pass", AuditVerdictClass.QUALIFIED_PASS.label)
    }
}
