package ai.teemoon.app

// android-only: settings → appearance → width did nothing (the Pixel, 2026-10-04) — the theme never read it. It is drawn
// on the system's Roboto Flex now; these pin the axis values and that a device without the font keeps the sans. That the
// width is DRAWN needs the real font file, which only a device has: checked on the AVD and the Pixel.

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FontWidthTest {
    @Test fun everyNonStandardWidthHasAnAxisValueAndStandardHasNone() {
        listOf(AppFontWidth.COMPRESSED, AppFontWidth.CONDENSED, AppFontWidth.EXPANDED).forEach { assertNotNull(it.name, it.wdth()) }
        assertNull(AppFontWidth.STANDARD.wdth())
        assertNull(AppFontWidth.STANDARD.flexFamily())
    }

    @Test fun theWidthsRunNarrowToWideInsideRobotoFlexsAxis() {
        val w = listOf(AppFontWidth.COMPRESSED, AppFontWidth.CONDENSED, AppFontWidth.EXPANDED).map { it.wdth()!! }
        assertEquals(w.sorted(), w)
        w.forEach { assert(it in 25f..151f) }
    }

    @Test fun withoutRobotoFlexTheWidthReadsAsStandard() {
        assertNull(AppFontWidth.CONDENSED.flexFamily(File("/nonexistent/RobotoFlex-Regular.ttf")))
        // the JVM has no /system/fonts: the standard design keeps the sans rather than failing
        assertEquals(FontFamily.SansSerif, AppFontDesign.STANDARD.family(AppFontWidth.CONDENSED))
    }

    @Test fun onlyTheStandardDesignTakesAWidth() {
        assertEquals(FontFamily.Serif, AppFontDesign.SERIF.family(AppFontWidth.EXPANDED))
        assertEquals(FontFamily.Monospace, AppFontDesign.MONOSPACED.family(AppFontWidth.COMPRESSED))
        assertNotEquals(FontFamily.Serif, AppFontDesign.STANDARD.family(AppFontWidth.STANDARD))
    }
}
