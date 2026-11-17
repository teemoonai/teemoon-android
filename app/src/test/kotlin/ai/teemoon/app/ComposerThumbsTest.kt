package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerThumbs
import ai.teemoon.app.imaging.ComposerThumbs.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IMAGES.md §4d (teemoon-ios d14c703a; the maintainer 2026-11-12: "shouldnt Xing out a composer image animate it away?
 * right now its abrupt"): the x removes a thumbnail by the arrival in reverse, so the thumbnail stays drawn, leaving, in
 * its place while the others slide over; send clears the row at once. Thumbnails are known by their image, never by
 * their position: an x mid-animation acts on its own image.
 */
class ComposerThumbsTest {
    @Test fun anXedThumbnailStaysInItsPlaceLeavingAndASentOneGoesAtOnce() {
        val before = listOf(Shown(1, false), Shown(2, false), Shown(3, false))
        assertEquals("x on 2: it stays where it was, leaving",
            listOf(Shown(1, false), Shown(2, true), Shown(3, false)), ComposerThumbs.next(before, listOf(1, 3), xed = setOf(2)))
        assertEquals("send: the row clears at once", emptyList<Shown>(), ComposerThumbs.next(before, emptyList(), xed = emptySet()))
        assertEquals("its exit done (no longer x-ed): gone",
            listOf(Shown(1, false), Shown(3, false)), ComposerThumbs.next(listOf(Shown(1, false), Shown(2, true), Shown(3, false)), listOf(1, 3), xed = emptySet()))
        assertEquals("a new one arrives at the end",
            listOf(Shown(1, false), Shown(2, true), Shown(3, false), Shown(4, false)), ComposerThumbs.next(listOf(Shown(1, false), Shown(2, true), Shown(3, false)), listOf(1, 3, 4), xed = setOf(2)))
    }

    @Test fun anXActsOnItsOwnImageNotThePositionItWasDrawnAt() {
        assertEquals(1, ComposerThumbs.indexOf(listOf(1, 3, 4), 3))
        assertNull("already gone: nothing else is removed in its place", ComposerThumbs.indexOf(listOf(1, 3, 4), 2))
    }

    /**
     * The maintainer 2026-11-12 (every send dismisses the keyboard; the photo send must be ONE motion — iOS's cause: the
     * strip cleared outside the animation and snapped while the keyboard slid): a send folds the whole strip with the
     * keyboard, so while it folds it still draws the row it had; an x keeps its own per-thumbnail rule.
     */
    @Test fun aSendFoldsTheStripWithItsLastRowDrawn() {
        val row = listOf(Shown(1, false), Shown(2, false))
        assertEquals("a send: the last row stays drawn while the strip folds", row, ComposerThumbs.drawn(shown = emptyList(), lastRow = row))
        assertEquals("with images: what is shown", row, ComposerThumbs.drawn(shown = row, lastRow = emptyList()))
        assertEquals("an x that leaves one leaving: that one, as before", listOf(Shown(2, true)), ComposerThumbs.drawn(shown = listOf(Shown(2, true)), lastRow = row))
        assertEquals("the last one x-ed and gone: nothing is drawn again", emptyList<Shown>(), ComposerThumbs.drawn(shown = emptyList(), lastRow = listOf(Shown(2, true))))
    }

    /**
     * The maintainer 2026-11-13 ("Ios way"): a send takes the photos on the send frame, as it does the text — the last row keeps its
     * place (so the panel's height can close on the keyboard's spring) but is not drawn. An x is not a send.
     */
    @Test fun aSendDrawsNoPhoto() {
        val row = listOf(Shown(1, false), Shown(2, false))
        assertEquals(true, ComposerThumbs.sent(shown = emptyList(), lastRow = row))
        assertEquals("photos still in the composer", false, ComposerThumbs.sent(shown = row, lastRow = row))
        assertEquals("the last one x-ed: it folds as before", false, ComposerThumbs.sent(shown = emptyList(), lastRow = listOf(Shown(2, true))))
        assertEquals("never had a photo", false, ComposerThumbs.sent(shown = emptyList(), lastRow = emptyList()))
    }
}
