package ai.teemoon.app

// pm ruling (2026-11-06, both platforms, no new words): when the composer holds more images than the place allows — a
// question returned to it, or a switch from a cloud model (10) to this phone (4) — every image is kept, the existing
// "up to N images per message" note shows, and send waits until enough are removed. An image is never dropped.

import ai.teemoon.app.imaging.ComposerImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerImageLimitTest {
    @Test fun overTheLimitTheNoteShowsAndSendWaits() {
        assertEquals("up to 4 images per message", ComposerImages.overLimitNote(count = 6, limit = 4))
        assertNull(ComposerImages.overLimitNote(count = 4, limit = 4))
        assertNull(ComposerImages.overLimitNote(count = 0, limit = 4))
    }
}
