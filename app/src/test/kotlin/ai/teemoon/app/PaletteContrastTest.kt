// ports: teemoonTests/PaletteContrastTests.swift @ 65b00fc
//
// the maintainer 2026-10-28: 1.1.0 needs a light theme "as aesthetic as the dark mode". The brand orange on white is 2.6:1, so
// text takes a darker shade in light; these pin WCAG AA (4.5:1) for every text colour on the ground it sits on, in
// both appearances, and that the tint really changes with the appearance.
package ai.teemoon.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (la, lb) = a.luminance().toDouble() to b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test fun everyTextColourReadsAtAAOnWhiteInLightAndOnBlackInDark() {
        for ((name, pair) in Palette.textPairs) {
            assertTrue("$name light ${contrast(pair.light, Color.White)}", contrast(pair.light, Color.White) >= 4.5)
            assertTrue("$name dark ${contrast(pair.dark, Color.Black)}", contrast(pair.dark, Color.Black) >= 4.5)
        }
    }

    @Test fun theBrandOrangeFailsAsTextOnWhiteWhichIsWhyFillsAndTextAreSeparateTokens() {
        assertTrue(contrast(Palette.accentFill.light, Color.White) < 4.5)
    }

    @Test fun theOrangeTintResolvesToTheTextShadePerAppearance() {
        assertEquals(0xFFC2410C.toInt(), TeemoonPalette.light(AppTintColor.ORANGE).tint.toArgb())
        assertEquals(0xFFFF7A1A.toInt(), TeemoonPalette.dark(AppTintColor.ORANGE).tint.toArgb())
        assertEquals(0xFFFF7A1A.toInt(), TeemoonPalette.light(AppTintColor.ORANGE).accentFill.toArgb())
    }

    /** The palette lands behind the switch: until the maintainer has seen light, the app stays pinned dark and dark is unchanged. */
    @Test fun theAppStaysPinnedDarkAndDarkIsUnchanged() {
        assertFalse(FOLLOWS_SYSTEM_APPEARANCE)
        val d = TeemoonPalette.dark(AppTintColor.ORANGE)
        assertEquals(listOf(0xFF30D158, 0xFFFF9F0A, 0xFFFF7A1A).map { it.toInt() }, listOf(d.green, d.orange, d.tint).map { it.toArgb() })
    }
}
