// spec: teemoon-ios docs/COMPOSER_PANEL.md @ 85c544c6 (the maintainer 2026-11-12, "One panel, in 1.1.0")
package ai.teemoon.app

import ai.teemoon.app.ComposerPanel.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rounded panel holds everything: the photos strip, the image / over-limit note, the text, and one bottom row —
 * "+" · where chip · web chip … send/stop. Nothing floats beside or above it.
 */
class ComposerPanelTest {
    @Test fun theBottomRowIsPlusWhereWebThenSend() {
        assertEquals(listOf(Item.ATTACH, Item.WHERE, Item.WEB, Item.SEND), ComposerPanel.bottomRow(modelTakesTools = true))
    }

    /** New on both apps: the web chip hides when the selected model can't take tools, and the row closes the gap. */
    @Test fun theWebChipHidesForAModelThatCantTakeTools() {
        assertEquals(listOf(Item.ATTACH, Item.WHERE, Item.SEND), ComposerPanel.bottomRow(modelTakesTools = false))
    }

    /** The text grows to 6 lines then scrolls; 4 at accessibility sizes, where the chips turn icon-only. */
    @Test fun sixLinesThenItScrollsFourAtAccessibilitySizes() {
        assertEquals(6, ComposerPanel.maxLines(fontScale = 1.0f))
        assertEquals(6, ComposerPanel.maxLines(fontScale = 1.3f))
        assertEquals(4, ComposerPanel.maxLines(fontScale = 2.0f))
        assertFalse(ComposerPanel.iconOnlyChips(fontScale = 1.3f))
        assertTrue(ComposerPanel.iconOnlyChips(fontScale = 2.0f))
    }

    /** TalkBack: the strip's thumbnails (each with its x) first, then the text, "+", where, web, send (or stop). */
    @Test fun talkBackReadsTextPlusWhereWebSend() {
        assertEquals(listOf(Item.STRIP, Item.TEXT, Item.ATTACH, Item.WHERE, Item.WEB, Item.SEND), ComposerPanel.readingOrder)
        assertTrue(ComposerPanel.readingOrder.zipWithNext().all { (a, b) -> ComposerPanel.traversal(a) < ComposerPanel.traversal(b) })
    }
}
