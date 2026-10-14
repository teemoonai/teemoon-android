// ports: teemoon/Views/Chat/TranscriptCollectionView.swift @ v1.0.1 (anchorGeometry(): inkBelow = streamingHeight + contentSize.height - frame.maxY)
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reserve is measured from the ink below the prompt — all of it. iOS reads `contentSize.height - frame.maxY`; the
 * lazy list reads the last visible row before the slack. What the 2026-09-17 band was: the reply row alone, read once.
 */
class TranscriptInkTest {
    private fun band(ink: Double) = TranscriptAnchorGeometry(userHeight = 100.0, visibleHeight = 1000.0, topFade = 100.0, chrome = 200.0, inkBelow = ink).placeholder

    @Test fun nothingBelowThePromptReservesTheWholeBand() {
        assertEquals(0.0, TranscriptInk.below(promptBottom = 300.0, lastVisibleTailEnd = null, tailContinuesPastViewport = false), 0.0)
        assertEquals(600.0, band(0.0), 0.0)   // 1000 - 100 fade - 200 chrome - 100 prompt
    }

    @Test fun theReplyAndWhateverStandsUnderItAreTheInk() {
        val replyOnly = TranscriptInk.below(promptBottom = 300.0, lastVisibleTailEnd = 500.0, tailContinuesPastViewport = false)
        assertEquals(200.0, replyOnly, 0.0)
        // the developer card opened under the reply: the same measure, taller — the reserve shrinks by exactly that
        val withCard = TranscriptInk.below(promptBottom = 300.0, lastVisibleTailEnd = 850.0, tailContinuesPastViewport = false)
        assertEquals(550.0, withCard, 0.0)
        assertEquals(400.0, band(replyOnly), 0.0)
        assertEquals(50.0, band(withCard), 0.0)
    }

    @Test fun inkPastTheViewportLeavesNothingToReserve() {
        val ink = TranscriptInk.below(promptBottom = 300.0, lastVisibleTailEnd = 900.0, tailContinuesPastViewport = true)
        assertEquals(Double.POSITIVE_INFINITY, ink, 0.0)
        assertEquals(0.0, band(ink), 0.0)
    }

    @Test fun aTailNotYetComposedIsNotUnbounded() {
        // the prompt is on screen and the reply row has not composed yet (the send): measure nothing, do not vanish
        assertEquals(0.0, TranscriptInk.below(promptBottom = 300.0, lastVisibleTailEnd = null, tailContinuesPastViewport = true), 0.0)
    }
}
