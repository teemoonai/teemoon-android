package ai.teemoon.app

import ai.teemoon.app.imaging.ImageCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewer's bar is iOS's toolbar (MessageImages.swift ImageViewer): done on the left, one share icon on the right.
 * Copy and save live in the long press, in the chat and in the viewer (the maintainer 2026-11-12: "it's already in
 * the long press"); the bar listed share, copy and save as words too.
 */
class ViewerBarTest {
    @Test fun doneOnTheLeftAndOneShareOnTheRightCopyAndSaveOnlyInTheLongPress() {
        assertEquals(listOf(ImageCopy.DONE, ImageCopy.SHARE), ViewerBar.items(canShare = true, canCopy = true, canSave = true))
        assertEquals("nothing to share: done alone", listOf(ImageCopy.DONE), ViewerBar.items(canShare = false, canCopy = true, canSave = true))
    }
}
