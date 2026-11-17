// ports: teemoonTests/SendChoicesTests.swift @ f600e4e
//
// The E2EE send confirmation and the mobile-data question are action sheets (pm, 2026-10-25: HIG puts choices in an
// action sheet, not an alert; Android draws them as bottom sheets). Same order on both platforms, the way out last; a
// hard break never offers send. 2026-10-30 (the maintainer, via pm): on iOS 26.5 the E2EE sheet is a popover and iOS hid its
// role-.cancel button, leaving an unlabelled tap outside as the only safe exit. It now ends in a plain "don't send"
// that keeps the draft, "send unencrypted" is red, and the soft-degrade body is the maintainer's text, exact.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendChoicesTest {
    @Test fun theE2EEQuestionKeepsItsOrderAndNeverOffersSendOnAHardBreak() {
        assertEquals(listOf("retry verification", "send unencrypted", "don't send"),
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
        assertEquals("teemoon couldn't seal this message so only the model can read it. nothing has been sent.",
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
}
