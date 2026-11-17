// android-only: ReverifyGate, ChatScreen's half of the re-verify flow (iOS's 1.0.6 finding, pm 2026-11-07).
package ai.teemoon.app

import ai.teemoon.app.ChatViewModel.AfterReverify
import ai.teemoon.app.ReverifyGate.Then
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverifyGateTest {
    /**
     * Re-verify on a gated retry, then — inside its wait — a new message the gate stops, then a clean result: the new
     * message goes out exactly once, by its own alert's "send anyway". The re-verify sent the composer too: twice.
     */
    @Test fun aNewGateDuringTheWaitSendsItsMessageOnce() {
        val g = ReverifyGate()
        g.raised()                         // the retry the gate stopped
        val ticket = g.tapped()            // Re-verify
        g.raised()                         // a new message, stopped by the gate: its own alert is up
        var sends = 0
        if (g.then(AfterReverify.GO, ticket, gatedRetry = false) == Then.SEND_COMPOSER) sends++
        sends++                            // the newer alert's "send anyway"
        assertEquals("the new message went out more than once", 1, sends)
        assertEquals(Then.NOTHING, g.then(AfterReverify.ASK, ticket, gatedRetry = true))
    }

    /** With no newer gate, the wait decides as iOS's retryE2EEAndGenerate does. */
    @Test fun theGateItWasTappedOnDecides() {
        val g = ReverifyGate(); g.raised(); val t = g.tapped()
        assertEquals(Then.SHOW_BLOCKED, g.then(AfterReverify.BLOCKED, t, gatedRetry = false))
        assertEquals(Then.SHOW_ASK, g.then(AfterReverify.ASK, t, gatedRetry = true))
        assertEquals(Then.RESEND_RETRY, g.then(AfterReverify.GO, t, gatedRetry = true))
        assertEquals(Then.SEND_COMPOSER, g.then(AfterReverify.GO, t, gatedRetry = false))
        assertEquals("a newer turn started (G6)", Then.NOTHING, g.then(null, t, gatedRetry = true))
    }
}
