// ports: teemoonTests/SendChoicesTests.swift @ f600e4e
//
// The E2EE send confirmation and the mobile-data question are action sheets (pm, 2026-10-25: HIG puts choices in an
// action sheet, not an alert; Android draws them as bottom sheets). Same order on both platforms, the way out last; a
// hard break never offers send. 2026-10-30 (the maintainer, via pm): on iOS 26.5 the E2EE sheet is a popover and iOS hid its
// role-.cancel button, leaving an unlabelled tap outside as the only safe exit. It now ends in a plain "don't send"
// that keeps the draft, "send without encryption" is red, and the soft-degrade body is the maintainer's text, exact.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendChoicesTest {
    @Test fun theE2EEQuestionKeepsItsOrderAndNeverOffersSendOnAHardBreak() {
        assertEquals(listOf("retry verification", "send without encryption", "don't send"),
            E2EESendChoice.choices(hardFailure = false, provenanceOnly = false).map { it.label })
        assertEquals(listOf("retry verification", "send anyway (still encrypted)", "don't send"),
            E2EESendChoice.choices(hardFailure = false, provenanceOnly = true).map { it.label })
        assertEquals(listOf("retry verification", "don't send"),
            E2EESendChoice.choices(hardFailure = true, provenanceOnly = true).map { it.label })
    }

    @Test fun onlySendingUnsealedIsRed() {
        val all = E2EESendChoice.choices(hardFailure = false, provenanceOnly = false) +
            E2EESendChoice.choices(hardFailure = false, provenanceOnly = true)
        assertEquals(listOf<E2EESendChoice>(E2EESendChoice.SendAnyway(stillEncrypted = false)), all.filter { it.isDestructive })
    }

    @Test fun theE2EEQuestionSaysNothingWasSent() {
        assertEquals("teemoon couldn't seal this message to the model's enclave key. nothing has been sent.",
            E2EESendChoice.message(hardFailure = false, provenanceOnly = false, cause = "couldn't reach the attestation server."))
        assertTrue(E2EESendChoice.message(hardFailure = true, provenanceOnly = false, cause = "the hardware quote failed intel dcap verification.")
            .startsWith("the hardware quote failed intel dcap verification. sending is blocked"))
        assertTrue(E2EESendChoice.message(hardFailure = false, provenanceOnly = true, cause = null)
            .startsWith("your message would still be sealed"))
    }

    @Test fun theMobileDataQuestionKeepsItsOrder() {
        assertEquals(listOf("download now", "wait for wi-fi", "not now"), CellularDownloadChoice.entries.map { it.label })
    }

    /** S30 (the maintainer 2026-11-07, "The wording is good"; iOS SendChoicesTests @ 38647a07): the two provenance questions, exact. */
    @Test fun theProvenanceQuestionsSayExactlyThis() {
        org.junit.Assert.assertEquals("couldn't confirm near.ai's software yet", E2EEAsk.PROVENANCE_PENDING_TITLE)
        org.junit.Assert.assertEquals("your message would still be sealed to the model's verified hardware key, but teemoon hasn't yet confirmed that the software running on it is the published code.", E2EEAsk.PROVENANCE_PENDING_MESSAGE)
        org.junit.Assert.assertEquals("couldn't confirm near.ai's software", E2EEAsk.PROVENANCE_UNCHECKED_TITLE)
        org.junit.Assert.assertEquals("your message would still be sealed to the model's verified hardware key, but teemoon couldn't reach GitHub or Docker Hub to confirm that the software running on it is the published code.", E2EEAsk.PROVENANCE_UNCHECKED_MESSAGE)
    }

    /** Which question the gate shows (iOS eachPendingKindAsksItsOwnQuestion). */
    @Test fun eachPendingKindAsksItsOwnQuestion() {
        fun ask(pending: ai.teemoon.confidential.ConfidentialSession.PendingKind? = null, unchecked: Boolean = false, soft: Boolean = true, hard: Boolean = false) =
            E2EEAsk.make(hardFailure = hard, pendingAsk = pending, provenanceUnchecked = unchecked, softButSealed = soft, provenanceBlocked = false, cause = null)
        val prov = ai.teemoon.confidential.ConfidentialSession.PendingKind.PROVENANCE; val hw = ai.teemoon.confidential.ConfidentialSession.PendingKind.HARDWARE
        org.junit.Assert.assertEquals(E2EEAsk("couldn't confirm near.ai's software yet", E2EEAsk.PROVENANCE_PENDING_MESSAGE, E2EESendChoice.STILL_CHECKING), ask(pending = prov))
        org.junit.Assert.assertEquals(listOf("send anyway", "wait"), ask(pending = prov).choices.map { it.label })
        org.junit.Assert.assertEquals("still checking near.ai's hardware", ask(pending = hw).title)
        org.junit.Assert.assertEquals(listOf("send anyway", "wait"), ask(pending = hw).choices.map { it.label })
        org.junit.Assert.assertEquals(E2EEAsk("couldn't confirm near.ai's software", E2EEAsk.PROVENANCE_UNCHECKED_MESSAGE, E2EESendChoice.choices(hardFailure = false, provenanceOnly = true)), ask(unchecked = true))
        org.junit.Assert.assertEquals(listOf("retry verification", "send anyway (still encrypted)", "don't send"), ask(unchecked = true).choices.map { it.label })
        org.junit.Assert.assertEquals("a soft seal-holding state keeps its question", "still checking near.ai's hardware", ask().title)
        org.junit.Assert.assertEquals("verification failed — sending blocked", ask(pending = prov, hard = true).title)
    }
}

/**
 * near.ai's tier list never came within the deadline (iOS verify-deadline, pm 2026-11-14, DRAFT words): the send asks
 * "couldn't confirm near.ai's hardware", the body is the tier reason, and the way out is "don't send" — no "wait",
 * since nothing reloads the list (as the provenance case). "still checking" stays only while a check is pending.
 */
class TierGaveUpAskTest {
    @Test fun aGivenUpTierAsksItsOwnQuestionWithoutWait() {
        val ask = E2EEAsk.make(hardFailure = false, pendingAsk = null, provenanceUnchecked = false, softButSealed = true,
            provenanceBlocked = false, cause = ai.teemoon.confidential.ConfidentialSession.TIER_REASON, tierGaveUp = true)
        org.junit.Assert.assertEquals("couldn't confirm near.ai's hardware", ask.title)
        org.junit.Assert.assertEquals("near.ai's model list didn't load, so teemoon couldn't confirm this model runs on its own hardware.", ask.message)
        org.junit.Assert.assertEquals(listOf("send anyway", "don't send"), ask.choices.map { it.label })
    }

    /**
     * 1.1.1 audit integ read, item 6 (iOS SendChoices.swift @ 01a337a4 E2EEAsk.make): the gate test that pins which ask wins when several
     * conditions hold at once. iOS's order: provenancePending, THEN tier-unchecked (needs softButSealed), THEN provenance-unchecked, THEN the
     * failed list, THEN still-checking, else the hard/unavailable sheet. tierGaveUp is Android's deadlinePassed.
     */
    @Test fun theAskChosenWhenSeveralConditionsHoldAtOnce() {
        fun ask(provenanceUnchecked: Boolean = false, softButSealed: Boolean = false, tier: Boolean = false, listFailed: Boolean = false,
                pending: ai.teemoon.confidential.ConfidentialSession.PendingKind? = null) =
            E2EEAsk.make(hardFailure = false, pendingAsk = pending, provenanceUnchecked = provenanceUnchecked, softButSealed = softButSealed,
                provenanceBlocked = false, cause = null, tierGaveUp = tier, enclaveListFailed = listFailed).title
        val tierTitle = E2EEAsk.TIER_GAVE_UP_TITLE
        // the tier ask comes before the provenance-unchecked one, and before the failed list
        org.junit.Assert.assertEquals(tierTitle, ask(provenanceUnchecked = true, softButSealed = true, tier = true))
        org.junit.Assert.assertEquals(tierTitle, ask(softButSealed = true, tier = true, listFailed = true))
        org.junit.Assert.assertEquals(tierTitle, ask(provenanceUnchecked = true, softButSealed = true, tier = true, listFailed = true))
        // it needs softButSealed: without it the next ask in iOS's order answers
        org.junit.Assert.assertEquals(E2EEAsk.PROVENANCE_UNCHECKED_TITLE, ask(provenanceUnchecked = true, tier = true))
        org.junit.Assert.assertEquals(E2EESendChoice.LIST_FAILED_TITLE, ask(tier = true, listFailed = true))
        org.junit.Assert.assertEquals("end-to-end encryption unavailable", ask(tier = true))
        // provenance still pending outranks all of them; a pending hardware check outranks the tier ask
        org.junit.Assert.assertEquals(E2EEAsk.PROVENANCE_PENDING_TITLE, ask(provenanceUnchecked = true, softButSealed = true, tier = true,
            pending = ai.teemoon.confidential.ConfidentialSession.PendingKind.PROVENANCE))
        org.junit.Assert.assertEquals(E2EESendChoice.STILL_CHECKING_TITLE, ask(softButSealed = true, tier = true,
            pending = ai.teemoon.confidential.ConfidentialSession.PendingKind.HARDWARE))
    }

    @Test fun aGenuinelyPendingCheckStillAsksStillChecking() {
        val ask = E2EEAsk.make(hardFailure = false, pendingAsk = ai.teemoon.confidential.ConfidentialSession.PendingKind.HARDWARE, provenanceUnchecked = false,
            softButSealed = true, provenanceBlocked = false, cause = null, tierGaveUp = true)
        org.junit.Assert.assertEquals(E2EESendChoice.STILL_CHECKING_TITLE, ask.title)
    }

    @Test fun aHardBreakStillWinsOverTheTier() {
        val ask = E2EEAsk.make(hardFailure = true, pendingAsk = null, provenanceUnchecked = false, softButSealed = false,
            provenanceBlocked = false, cause = "the model's hardware attestation failed verification.", tierGaveUp = true)
        org.junit.Assert.assertEquals("verification failed — sending blocked", ask.title)
    }

    /** Item 1 (iOS TierDowngradeTests @ d2d70d45): the downgrade sheet is [send without encryption (red) | don't send], in the approved words. */
    @Test fun theTierDowngradeSheetIsSendWithoutEncryptionThenDontSend() {
        val ask = E2EEAsk.tierDowngrade(ai.teemoon.confidential.TierDowngradeAsk(
            ai.teemoon.confidential.TierDowngrade.title("glm 5.3"), ai.teemoon.confidential.TierDowngrade.MESSAGE))
        assertEquals("glm 5.3 isn't end-to-end encrypted anymore", ask.title)
        assertEquals(listOf("send without encryption", "don't send"), ask.choices.map { it.label })
        assertEquals(listOf(true, false), ask.choices.map { it.isDestructive })
    }
}
