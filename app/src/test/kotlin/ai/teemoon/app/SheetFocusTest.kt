package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerImagesMachine.Effect
import ai.teemoon.app.imaging.SheetFocus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maintainer 2026-11-12 (found on iOS): "choose an image-capable model" from the send-refused alert showed the
 * composer and keyboard for a moment before the where sheet rose. Every effect that opens a sheet over the composer —
 * the attach sheet, and the where sheet from the alert, the attach sheet's button or the chip — puts the keyboard
 * away first, so focus can't return to the field as the alert closes.
 */
class SheetFocusTest {
    @Test fun everySheetOverTheComposerPutsTheKeyboardAwayFirst() {
        for (e in listOf(Effect.OPEN_ATTACH_SHEET, Effect.OPEN_ATTACH_SHEET_CANT_SEE, Effect.OPEN_WHERE, Effect.OPEN_WHERE_PLAIN)) assertTrue("$e", SheetFocus.dropsKeyboard(e))
        for (e in listOf(Effect.ATTACH, Effect.REMOVE, Effect.REMOVE_ALL)) assertFalse("$e", SheetFocus.dropsKeyboard(e))
    }
}
