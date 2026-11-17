package ai.teemoon.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-panel composer's spacing table (teemoon-ios docs/COMPOSER_PANEL.md @ 06c7e759; the maintainer 2026-11-12, "too
 * tight", other chat apps' composers as the reference): radius 30 with a hairline edge; the text 16 from the sides, 17
 * from the top and 17 down to the row; every row item 34 tall; 8 between items and 8 to the left, right and bottom
 * edges. Hit areas stay 48dp: Compose widens a smaller clickable's touch bounds to the minimum touch target on its own.
 */
class ComposerSpacingTest {
    @Test fun thePanelIsRadiusThirtyWithAHairline() {
        assertEquals(30.dp, ComposerSpacing.PANEL_RADIUS)
        // a hairline is one device pixel, as iOS's; 1dp is three on the Pixel (design review of e51604dd)
        assertEquals(Dp.Hairline, ComposerSpacing.PANEL_EDGE)
    }

    @Test fun theTextIsSixteenInAndSeventeenAboveAndBelow() {
        assertEquals(16.dp, ComposerSpacing.FIELD_START)
        assertEquals(17.dp, ComposerSpacing.TEXT_TOP)
        // "panel top → first line of text (or photos strip top)": 17 either way
        assertEquals(17.dp, ComposerSpacing.THUMBS_TOP)
        assertEquals(17.dp, ComposerSpacing.TEXT_TO_ROW)
    }

    @Test fun everyRowItemIsThirtyFourWithEightAroundAndBetween() {
        assertEquals(34.dp, ComposerSpacing.ROW_ITEM)
        assertEquals(14.dp, ComposerSpacing.CHIP_SIDE)
        assertEquals(8.dp, ComposerSpacing.ROW_GAP)
        assertEquals(8.dp, ComposerSpacing.ROW_EDGE)
    }

    @Test fun thumbnailsStartAtTheTextAndAreEightApart() {
        assertEquals(ComposerSpacing.FIELD_START, ComposerSpacing.THUMBS_START)
        assertEquals(8.dp, ComposerSpacing.THUMB_GAP)
    }
}
