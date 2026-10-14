// ports: teemoonTests/ChatFadeBandTests.swift @ v1.0.1 — all 15 tests (the retired "band must land on the chrome" is a
//        comment there and here: the requirement was aimed at the wrong object; the inset's own background covers the chrome)
package ai.teemoon.app

import kotlin.math.abs
import org.junit.Test

// JUnit 4 with the Swift Testing shapes the port reads naturally in
private fun assertEquals(expected: Double, actual: Double, message: String? = null) = org.junit.Assert.assertEquals(message, expected, actual, 1e-9)
private fun assertEquals(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)
private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
private fun assertNotNull(value: Any?, message: String) = org.junit.Assert.assertNotNull(message, value)

/**
 * The chat's bottom edge has been got wrong three separate ways, each time in a manner no preview could show (they
 * render without a keyboard) and no test could catch (the arithmetic lived inline in a mask builder). These pin all
 * three. Geometry is the real thing, measured on an iPhone 17 Pro: a 132pt inset, 16pt of padding above a 44pt chip,
 * an 8pt gap, then the 48pt composer.
 */
class ChatFadeBandTest {
    private val inset = 132.0
    private val chipTop = 16.0
    private val chipBottom = 60.0      // 16 + 44
    private val band get() = ChatFadeBand(chipTop, chipBottom, inset)

    // Regression 1: text bleeding through the gap between the capsules

    /** THE recurring bug: the gap sits ~40% along a ramp stretched over the whole inset, ~78% opaque — plainly readable. */
    @Test fun theGapBetweenChipAndComposerIsFullyHidden() {
        var offset = chipBottom
        while (offset <= chipBottom + 8) { assertEquals(0.0, band.alpha(offset), "transcript visible ${offset - chipBottom}pt into the chip/composer gap"); offset += 1.0 }
    }

    /** Nothing below the chip may show at all — gap, composer, or the padding under it. */
    @Test fun nothingBelowTheChipIsEverPartiallyVisible() {
        var offset = chipBottom
        while (offset <= inset) { assertEquals(0.0, band.alpha(offset), "transcript visible at ${offset}pt, below the chip"); offset += 0.5 }
    }

    // Regression 2: the dissolve starting above the chip

    /** Sizing the band to the whole inset put its top above the chip: text dissolved into empty background ~40pt early. */
    @Test fun nothingAboveTheChipFades() {
        var offset = -200.0
        while (offset <= chipTop) { assertEquals(1.0, band.alpha(offset), "transcript dimmed at ${offset}pt, above the chip"); offset += 1.0 }
    }

    /** A hard cut slices glyphs; that treatment was tried and reverted. The ramp must actually ramp. */
    @Test fun theDissolveIsGradual_notAHardEdge() {
        val quarter = band.alpha(chipTop + band.ramp * 0.4)
        val half = band.alpha(chipTop + band.ramp * 0.6)
        val most = band.alpha(chipTop + band.ramp * 0.9)
        assertTrue(quarter > half); assertTrue(half > most); assertTrue(most > 0)
    }

    // Regression 3: overflowing the mask's container

    /** If the band ever exceeds the container the spacer collapses and the band is drawn far above the chrome (the keyboard-up case). */
    @Test fun theBandNeverOutgrowsTheChromeItCovers() {
        assertEquals(inset - chipTop, band.total); assertTrue(band.total <= inset)
    }

    /** The measured keyboard-up case: a 291pt container must still fit the band. */
    @Test fun theBandFitsTheKeyboardUpContainer() { assertTrue(band.total <= 291) }

    // Degenerate geometry

    @Test fun aZeroHeightChipStillHidesEverythingBelowIt() {
        val collapsed = ChatFadeBand(16.0, 16.0, 132.0)
        assertEquals(0.0, collapsed.ramp); assertEquals(0.0, collapsed.alpha(16.0)); assertEquals(1.0, collapsed.alpha(15.0))
    }

    @Test fun nonsenseOrderingIsClamped_notNegative() {
        val backwards = ChatFadeBand(90.0, 20.0, 10.0)
        assertTrue(backwards.ramp >= 0); assertTrue(backwards.hidden >= 0); assertTrue(backwards.total >= 0)
    }

    // The UIKit transcript's home-indicator shift

    @Test fun theRampLandsOnTheChipWhenTheContainerIncludesTheHomeIndicator() {
        val home = 34.0; val screen = 874.0
        val chromeTop = screen - home - inset
        assertEquals((chromeTop + chipTop)..(chromeTop + chipBottom), band.rampRange(screen, home))
        assertEquals(chromeTop + chipBottom, band.hiddenRange(screen, home).start)
    }

    /** Keyboard up: the collection view has shrunk and the indicator is gone; a short container still places the ramp at its own bottom. */
    @Test fun noHomeIndicatorKeepsTheRampOnTheChrome() {
        val container = 291.0
        assertEquals(container - band.hidden, band.rampRange(container, 0.0).endInclusive)
    }

    @Test fun navOverlapIsTheTitleBand_notABandOnTheFirstBodyLine() {
        val chrome = ChatChrome(bottom = band)
        assertEquals(96.0, chrome.navOverlap(96.0)); assertEquals(0.0, chrome.navOverlap(0.0))
        assertEquals(96.0, ChatChrome(topInset = 96.0, bottom = band).navOverlap(0.0))
    }

    /** One-line titles and the two-line e2ee header differ: the overlay is the measured title plus the approach, never statusBar + a constant. */
    @Test fun topOverlayIsTheMeasuredTitle_notStatusPlusAConstant() {
        val chrome = ChatChrome(bottom = band)
        assertEquals(118.0, chrome.topChromeHeight(118.0))
        assertEquals(118.0 + ChatGlassFade.TOP_APPROACH, chrome.topOverlayHeight(118.0))
        assertEquals(96.0 + ChatGlassFade.TOP_APPROACH, chrome.topOverlayHeight(96.0))
        assertEquals(0.0, chrome.topOverlayHeight(0.0)); assertEquals(0.0, chrome.topOverlayHeight(0.4))
    }

    @Test fun theTallerOfHostAndMeasuredTitleIsTheOverlay() {
        val chrome = ChatChrome(topInset = 130.0, bottom = band)
        assertEquals(130.0, chrome.topChromeHeight(96.0))
        assertEquals(140.0 + ChatGlassFade.TOP_APPROACH, chrome.topOverlayHeight(140.0))
    }

    /**
     * The dim RUNS OUT BELOW THE TITLE, it does not stop on its edge.
     *
     * This reverses the earlier rule that the first body line under the header is full brightness. That was fine while
     * whatever sat there was arbitrary; the anchor now parks the prompt at `U.minY - topFade`, so there is always body
     * text on that boundary and it read as the previous reply colliding with the header (measured 147/255 at the
     * title's edge on the capture fixture).
     *
     * The two bounds below are the ones that stay: never an opaque shelf, never a hard edge. Both were tried on device
     * and reverted.
     */
    @Test fun theGlassTopFadeRunsOutBelowTheTitle_notOnItsEdge() {
        // A run-out, not a body-line band: 64pt was rejected for that.
        assertTrue(ChatGlassFade.TOP_APPROACH > 16); assertTrue(ChatGlassFade.TOP_APPROACH < 40)
        val stops = ChatGlassFade.topStops(118.0)
        assertTrue(stops.first().dim < 0.85); assertTrue(stops.first().blur > 0.5)   // never the opaque shelf
        assertEquals(0.0, stops.last().dim); assertEquals(0.0, stops.last().blur)   // never a hard edge
        assertTrue(stops.all { it.dim < 1 })
        // Still substantially dimmed where the title ends — this is the assertion that changed, and the whole point of the change.
        val titleEnd = stops.first { abs(it.location - 118 / (118 + ChatGlassFade.TOP_APPROACH)) < 0.02 }
        assertTrue(titleEnd.dim > 0.3); assertTrue(titleEnd.dim < 0.6)
        // The peak sits under the title, and every stop after it falls.
        val dims = stops.map { it.dim }
        assertEquals(dims.max(), dims[1]); assertTrue(dims[1] > dims[2] && dims[2] > dims[3]); assertTrue(dims.dropLast(1).all { it > 0 })
    }

    /** The fade starts above the chip so the last line dissolves into the glass; from the chip down the dim is high. */
    @Test fun theGlassBottomFadeStartsAboveTheChip_andDoesNotEatTheAnswer() {
        assertTrue(ChatGlassFade.BOTTOM_APPROACH > 0)
        val stops = ChatGlassFade.bottomStops(band.ramp, band.hidden, 34.0)
        assertEquals(0.0, stops.first().dim); assertEquals(0.0, stops.first().blur)
        assertTrue(stops.last().dim > 0.9); assertTrue(stops.last().dim < 1)
        assertEquals(ChatGlassFade.BOTTOM_APPROACH + band.total, band.overlayHeight())
        assertNotNull(stops.firstOrNull { it.location > 0.2 && it.dim > 0.8 }, "dim must jump once the overlay reaches the chip")
    }
}
