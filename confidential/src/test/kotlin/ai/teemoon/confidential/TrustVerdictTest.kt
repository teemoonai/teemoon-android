// ports: teemoonTests/TrustVerdictTests.swift @ v1.0.4, @ 8bfe33cb (trust-leaks-wording: leaksReviewQualifiesTheHero,
//        theReadingClaimNeedsAReviewedBuild, aReviewedLeakOutranksAnUnpublishedImage)
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

    /** trust-leaks-wording (the maintainer 2026-11-06), row 3: the reviewer's own sentence, never a fixed one. */
    @Test fun leaksReviewQualifiesTheHero() {
        val found = "every prompt is written to the container log and shipped to telemetry."
        val v = TrustVerdict.make(input { copy(auditState = EverydayAuditState.LEAKS, leaksExposure = found) })
        assertEquals("encrypted to glm-5.2", v.heroTitle)
        assertEquals("sealed to the model's hardware on the way in. a review of this exact build found: $found", v.heroSubtitle)
        assertTrue(v.everydayClaims.any { it.id == "audit" && it.status == TrustClaim.Status.ALERT && it.title == found })
        assertFalse("the fixed sentence is gone", v.everydayClaims.any { it.title.contains("copies what you type") })
        // nor the blanket claim in its detail: untrue where chat never passes the leaking service (pm, FLUX-only pages)
        assertEquals("The seal still protects your message on the way in; this is about what happens after it's unlocked.",
            v.everydayClaims.first { it.id == "audit" }.detail)
        val bare = TrustVerdict.make(input { copy(auditState = EverydayAuditState.LEAKS) })
        assertEquals("no exposure recorded: line A", AuditedSealText.NOT_REVIEWED, bare.heroSubtitle)
        assertEquals("audits never gate", TrustSendPolicy.ALLOW, bare.sendPolicy)
    }

    /** Rows 1 and 2: "only it can read this" only when reviewed; otherwise the tail drops and line A shows. */
    @Test fun theReadingClaimNeedsAReviewedBuild() {
        for (state in listOf(null, EverydayAuditState.INCOMPLETE, EverydayAuditState.REVIEWED_WITH_CAVEAT)) {
            val v = TrustVerdict.make(input { copy(auditState = state) })
            assertEquals("$state", "encrypted to glm-5.2", v.heroTitle)
            assertEquals(AuditedSealText.NOT_REVIEWED, v.heroSubtitle)
            assertEquals("row 5: the chip is unchanged", "end-to-end encrypted", v.chipCaption)
        }
        assertEquals("only you and glm-5.2's sealed hardware can read this chat.", TrustVerdict.make(input()).heroSubtitle)
        val unchecked = TrustVerdict.make(input { copy(provenanceUnchecked = true) })
        assertEquals("encrypted to glm-5.2", unchecked.heroTitle)
        assertEquals(PROVENANCE_UNCHECKED_HEADER, unchecked.heroSubtitle)
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
        val v = TrustVerdict.make(input { copy(auditState = EverydayAuditState.LEAKS, unpublishedButSealed = true, leaksExposure = "x.") })
        assertEquals("encrypted to glm-5.2", v.heroTitle)
        assertTrue(v.heroSubtitle.endsWith("found: x."))
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
        // Full coverage, every class private or a qualified pass → reviewed.
        assertEquals(EverydayAuditState.REVIEWED, everydayAuditState(listOf(PRIVATE_REVIEW, QUALIFIED_PASS), allTouchersCovered = true))
        // trust-audit-claim (the maintainer 2026-11-06): a compromisable page is the caveat, not green — the old rule let it pass…
        assertEquals(EverydayAuditState.REVIEWED_WITH_CAVEAT,
            everydayAuditState(listOf(PRIVATE_REVIEW, QUALIFIED_PASS, COMPROMISABLE), allTouchersCovered = true))
        // …and a page with no class, or one this build doesn't know, is a gap — it used to be dropped.
        assertEquals(EverydayAuditState.INCOMPLETE, everydayAuditState(listOf(PRIVATE_REVIEW, null), allTouchersCovered = true))
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

    /** trust-audit-claim: the caveat's words, approved 2026-11-06. */
    @Test fun theCaveatClaimSaysWhatTheCatchIs() {
        val v = TrustVerdict.make(TrustVerdict.Input(attestationState = AttestationState.OK, modelName = "m", auditState = EverydayAuditState.REVIEWED_WITH_CAVEAT))
        val c = v.everydayClaims.first { it.id == "audit" }
        assertEquals(TrustClaim.Status.INFO, c.status)
        assertEquals("the code that can read your message was reviewed, with a catch", c.title)
        assertEquals("teemoon's AI-assisted review found nothing set to copy your words in this exact setup, but someone with near.ai's operator access could change that.", c.detail)
    }
}
