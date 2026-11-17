// The import page's bottom button, as iOS's matches Safari (teemoon-ios a919ff53, S26; pm/evidence/1.1-ui/ios/
// import-button-safari-match): the button's bottom 34pt above the screen's edge, 30pt in from each side, iOS's 54pt
// pill (the maintainer: "pill is fine"), the same place on every step; D7's tiles outlined 1.5pt in the accent.
package ai.teemoon.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportLayoutTest {
    @Test fun theButtonStandsWhereSafarisDoes() {
        assertEquals(30.dp, ImportLayout.SIDE)
        assertEquals(54.dp, ImportLayout.BUTTON_HEIGHT)
        assertEquals(1.5.dp, ImportLayout.TILE_OUTLINE)
    }

    /**
     * 34 from the screen's bottom edge, as iOS's — where a gesture bar (about 24 dp) is shorter; over a taller bar
     * (three buttons, 48 dp) the button stands on it instead of under it.
     */
    @Test fun itsBottomIs34AboveTheEdgeOrOnTheNavigationBar() {
        assertEquals(34.dp, ImportLayout.bottomGap(navigationBar = 0.dp))
        assertEquals(34.dp, ImportLayout.bottomGap(navigationBar = 24.dp))
        assertEquals(48.dp, ImportLayout.bottomGap(navigationBar = 48.dp))
    }
}
