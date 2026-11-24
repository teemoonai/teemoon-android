package ai.teemoon.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.1.1 audit cut review item 1, the reverse case: the gate alert redraws live when a verdict lands, so "the tier-downgrade
 * ask is on screen" is read from the ask being drawn, never kept from when the alert was raised. A flag that says false
 * while the downgrade ask shows only costs one extra tap; a flag that says true while another ask shows would let that
 * ask's "send anyway" accept the downgrade, and must be impossible.
 */
class DowngradeAskOnScreenTest {
    @Test fun onlyTheDowngradeAskDrawnNowCountsAsShown() {
        assertTrue("the downgrade ask is what is drawn", downgradeAskOnScreen(askingStillChecking = false, askIsTierDowngrade = true))
        assertFalse("the alert redrew to the still-checking ask", downgradeAskOnScreen(askingStillChecking = true, askIsTierDowngrade = true))
        assertFalse("the alert redrew to another ask", downgradeAskOnScreen(askingStillChecking = false, askIsTierDowngrade = false))
    }

    /** The flag is computed in the same composition that draws the ask, so a redraw recomputes it; nothing remembers it. */
    @Test fun chatScreenReadsTheFlagFromTheAskItDraws() {
        val src = File("src/main/kotlin/ai/teemoon/app/ChatScreen.kt").readText()
        assertFalse("the flag must not be remembered from when the alert was raised",
            Regex("""remember\([^)]*\)\s*\{\s*trust\.askIsTierDowngrade""").containsMatchIn(src))
        assertTrue(src.contains("downgradeAskOnScreen(askingStillChecking = asking, askIsTierDowngrade = trust.askIsTierDowngrade)"))
    }
}
