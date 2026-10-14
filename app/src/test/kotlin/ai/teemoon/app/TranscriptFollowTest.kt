// ports: teemoonUITests/LongThreadStreamingUITests.swift @ v1.0.1 — the seven follow behaviours as pure-policy tests
//        (the UI tests themselves are XCUI; the Compose transcript is driven by this policy)
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowTest {
    private var t = 100.0
    private fun follow() = TranscriptFollow { t }

    @Test fun sendingLandsInASettleAndThenFollowsThrottled() {
        val f = follow(); f.update(generating = true)
        assertEquals(TranscriptViewport.PinToEnd(PinMode.THROTTLED), f.intent())
        assertTrue(f.mayPin(PinMode.THROTTLED)); t += 0.01; assertFalse("40 ms floor", f.mayPin(PinMode.THROTTLED)); t += 0.04; assertTrue(f.mayPin(PinMode.THROTTLED))
        f.update(generating = false)
        assertEquals("the end of a followed turn settles immediately", TranscriptViewport.PinToEnd(PinMode.IMMEDIATE), f.intent())
        t += 1.1; assertEquals(TranscriptViewport.Free, f.intent())
    }
    @Test fun scrollingAwayMidAnswerStopsTheFollow() {
        val f = follow(); f.update(true); var flips = ArrayList<Boolean>(); f.onInterruptionChanged = { flips += it }
        f.onDragBegan(offsetY = 1000.0); f.onScrolled(offsetY = 940.0, distanceFromEnd = 300.0, userDriving = true); f.onDragEnded()
        assertTrue(f.interrupted); assertEquals(listOf(true), flips); assertEquals(TranscriptViewport.Free, f.intent())
    }
    @Test fun aSmallDragUnderTheThresholdDoesNotStopTheFollow() {
        val f = follow(); f.update(true)
        f.onDragBegan(1000.0); f.onScrolled(970.0, 300.0, true); f.onDragEnded()
        assertFalse(f.interrupted); assertEquals(TranscriptViewport.PinToEnd(PinMode.THROTTLED), f.intent())
    }
    @Test fun aTapMidAnswerDoesNotStopTheFollow() {
        val f = follow(); f.update(true)
        f.onDragBegan(1000.0); f.onDragEnded()   // no travel at all
        assertFalse(f.interrupted)
    }
    @Test fun scrollingBackToTheEndResumesTheFollow() {
        val f = follow(); f.update(true)
        f.onDragBegan(1000.0); f.onScrolled(900.0, 400.0, true); assertTrue(f.interrupted)
        f.onScrolled(1300.0, 20.0, true); assertFalse("within 50 pt of the end clears it", f.interrupted)
    }
    @Test fun theFollowReArmsOnTheNextSend() {
        val f = follow(); f.update(true); f.onDragBegan(1000.0); f.onScrolled(900.0, 400.0, true); f.update(false)
        assertTrue(f.interrupted)
        f.onScrolled(1300.0, 0.0, false)   // the next send scrolls to the end programmatically
        f.update(true)
        assertFalse(f.interrupted); assertEquals(TranscriptViewport.PinToEnd(PinMode.THROTTLED), f.intent())
    }
    @Test fun scrollingAwaySurvivesTheEndOfTheTurn_theHandoffHoldsTheOffset() {
        val f = follow(); f.update(true)
        f.onDragBegan(1000.0); f.onScrolled(600.0, 900.0, true); f.onDragEnded()
        f.update(false)
        assertEquals("a reader who left is held at their ABSOLUTE offset for a beat", TranscriptViewport.Hold(600.0), f.intent())
        t += 1.1; assertEquals("the hold expires on its own", TranscriptViewport.Free, f.intent())
    }
    @Test fun aTouchReleasesTheHold() {
        val f = follow(); f.update(true); f.onDragBegan(1000.0); f.onScrolled(600.0, 900.0, true); f.update(false)
        assertTrue(f.intent() is TranscriptViewport.Hold)
        f.onDragBegan(600.0); assertEquals(TranscriptViewport.Free, f.intent())
    }
}
