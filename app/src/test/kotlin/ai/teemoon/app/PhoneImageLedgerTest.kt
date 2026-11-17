package ai.teemoon.app

import ai.teemoon.app.imaging.PhoneImageLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMAGES.md §4b (the maintainer 2026-11-12): the ceiling counts the most memory this app has seen free here (it only
 * rises); an image turn the OS killed is remembered for that model on this phone — the next one is refused with the
 * permanent line, never retried. A kill ends the process mid-turn, so it is found at the next launch: the turn's
 * marker is still set.
 */
class PhoneImageLedgerTest {
    @Test fun theHighestAvailableOnlyRisesAndSurvivesARestart() {
        val prefs = InMemoryPrefs()
        val l = PhoneImageLedger(prefs)
        l.sawAvailable(3100); l.sawAvailable(2500); l.sawAvailable(3400)
        assertEquals(3400, PhoneImageLedger(prefs).highestAvailMB)
    }

    @Test fun aTurnThatNeverEndedIsAKillFoundAtTheNextLaunch() {
        val prefs = InMemoryPrefs()
        PhoneImageLedger(prefs).beginImageTurn("gemma-e4b")            // the process dies here
        val next = PhoneImageLedger(prefs).also { it.onLaunch(lowMemoryKill = true) }
        assertTrue(next.killed("gemma-e4b"))
        assertFalse("another model is untouched", next.killed("gemma-e2b"))
        // a turn that ends is no kill
        next.beginImageTurn("gemma-e2b"); next.endImageTurn()
        assertFalse(PhoneImageLedger(prefs).also { it.onLaunch(lowMemoryKill = true) }.killed("gemma-e2b"))
        assertTrue("a kill is remembered", PhoneImageLedger(prefs).killed("gemma-e4b"))
    }

    /**
     * The design review of e793238b: only the OS's low-memory kill counts. A swipe-away, a force stop, an update, a crash
     * or a reboot mid-turn also leaves the mark, and refusing the model for good on a phone that fits it would be wrong.
     */
    @Test fun aDeathThatWasNotALowMemoryKillRefusesNothing() {
        val prefs = InMemoryPrefs()
        PhoneImageLedger(prefs).beginImageTurn("gemma-e2b")
        val next = PhoneImageLedger(prefs).also { it.onLaunch(lowMemoryKill = false) }
        assertFalse(next.killed("gemma-e2b"))
        assertFalse("the mark is cleared either way", PhoneImageLedger(prefs).also { it.onLaunch(lowMemoryKill = true) }.killed("gemma-e2b"))
    }
}
