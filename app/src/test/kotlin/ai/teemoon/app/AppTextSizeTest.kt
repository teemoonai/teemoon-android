// ports: teemoonTests/AppTextSizeTests.swift @ 407ea0b
//
// 1.1.0 (the maintainer, 2026-10-30): the size setting follows the phone. Each choice is a step on top of the phone's font
// scale; "large" became "system" and keeps its stored name; an install that never chose gets system.
package ai.teemoon.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTextSizeTest {
    private fun body(size: AppFontSize) = teemoonTypography(FontFamily.SansSerif, size.scale()).bodyLarge.fontSize

    /** iOS's Dynamic Type body sizes around "large", so a choice draws as on iOS on a default-size phone. */
    @Test fun onADefaultPhoneEachChoiceDrawsAsIOSDoes() {
        assertEquals(listOf(14f, 15f, 16f, 17f, 19f), AppFontSize.entries.map { (body(it).value * 1000).toInt() / 1000f })
    }

    /**
     * `sp` carries the phone's font scale, so every choice rides on it, the largest sizes included — and the order
     * holds at every scale. Since Android 14 large scales grow text non-linearly (14 sp at 2× is 26 px, not 28), so
     * this asserts growth, not a product.
     */
    @Test fun aChoiceRidesOnThePhonesFontScale() {
        for (scale in listOf(1.3f, 2f)) {
            val px = AppFontSize.entries.map { with(Density(1f, fontScale = scale)) { body(it).toPx() } }
            val plain = AppFontSize.entries.map { with(Density(1f, fontScale = 1f)) { body(it).toPx() } }
            for (i in px.indices) assertTrue("${AppFontSize.entries[i]} at $scale", px[i] > plain[i])
            assertEquals(px.sorted(), px)
        }
    }

    @Test fun theListReadsInSizeOrder() {
        assertEquals(listOf("xsmall", "small", "medium", "system", "xlarge"), AppFontSize.entries.map { it.name.lowercase() })
    }

    @Test fun aStoredLargeIsSystem() {
        assertEquals("large", AppFontSize.SYSTEM.stored)
        assertEquals(AppFontSize.SYSTEM, AppSettings(InMemoryPrefs().also { it.putString(UserDefaultsKey.APP_FONT_SIZE, "large") }).appFontSize)
    }

    @Test fun anInstallThatNeverChoseGetsSystemAndAChoiceIsKept() {
        assertEquals(AppFontSize.SYSTEM, AppSettings(InMemoryPrefs()).appFontSize)
        val prefs = InMemoryPrefs(); AppSettings(prefs).appFontSize = AppFontSize.SMALL
        assertEquals("small", prefs.getString(UserDefaultsKey.APP_FONT_SIZE))
        assertEquals(AppFontSize.SMALL, AppSettings(prefs).appFontSize)
    }
}
