// Photos on their way into the composer (the maintainer 2026-11-14: "Claude disables the send button while image is
// copying/rendering. Ive never seen that for ours"; the prototype approved, "Yes to androids", with the photo fading in).
// Between the pick and the thumbnail nothing knew a photo was coming: a send in that window went out without it, and
// the photo then landed in the emptied composer. Now each photo on its way is a pending tile, send waits for all of
// them, and a photo that can't be read takes its tile away and leaves the existing note.
package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerImages
import ai.teemoon.app.imaging.ImageCopy
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ImageRejection
import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPhotosTest {
    private val photo = ImageDecodeFrame.Decoded("image/png", 2, 2, byteArrayOf(1), byteArrayOf(1))
    private val unreadable = ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)
    private fun vm() = ChatViewModel(provider = { Provider.fireworks }).also { it.restore(emptyList()) }

    @Test fun sendWaitsForEveryPendingPhoto() {
        assertFalse("text typed, a photo still on its way", ComposerImages.canSend(promptBlank = false, picked = 0, pending = 1, limit = 10))
        assertFalse("a photo in, another on its way", ComposerImages.canSend(promptBlank = true, picked = 1, pending = 1, limit = 10))
        assertTrue("all in", ComposerImages.canSend(promptBlank = true, picked = 2, pending = 0, limit = 10))
        assertTrue("text alone", ComposerImages.canSend(promptBlank = false, picked = 0, pending = 0, limit = 10))
        assertFalse("nothing", ComposerImages.canSend(promptBlank = true, picked = 0, pending = 0, limit = 10))
        assertFalse("over the limit still waits for a removal", ComposerImages.canSend(promptBlank = false, picked = 11, pending = 0, limit = 10))
    }

    @Test fun aPickIsATileAtOnceThenItsPhotoInOneWrite() {
        val vm = vm()
        vm.pendingStarted(2)
        assertEquals(2, vm.draftPending)
        assertTrue(vm.draftImages.isEmpty())
        vm.pendingLanded(2, listOf(photo, photo), limit = 10)
        assertEquals(0, vm.draftPending)
        assertEquals(2, vm.draftImages.size)
        assertNull(vm.draftImageNote)
    }

    @Test fun aPhotoThatCantBeReadTakesItsTileAwayAndLeavesTheNote() {
        val vm = vm()
        vm.pendingStarted(2)
        vm.pendingLanded(2, listOf(photo, unreadable), limit = 10)
        assertEquals("both tiles gone", 0, vm.draftPending)
        assertEquals("the one that read is in", 1, vm.draftImages.size)
        assertEquals(ImageCopy.UNREADABLE, vm.draftImageNote)
    }

    @Test fun twoPicksInFlightLandIndependently() {
        val vm = vm()
        vm.pendingStarted(1); vm.pendingStarted(2)
        assertEquals(3, vm.draftPending)
        vm.pendingLanded(2, listOf(photo, photo), limit = 10)
        assertEquals("the other pick's tile stays", 1, vm.draftPending)
        vm.pendingLanded(1, listOf(photo), limit = 10)
        assertEquals(0, vm.draftPending); assertEquals(3, vm.draftImages.size)
    }

    /** A read that will never land (its screen went away): the tiles go, and the count never goes below zero. */
    @Test fun aDroppedReadTakesItsTilesAway() {
        val vm = vm()
        vm.pendingStarted(1)
        vm.pendingDropped(1)
        assertEquals(0, vm.draftPending)
        vm.pendingDropped(1)
        assertEquals(0, vm.draftPending)
    }

    /** A share's own note (ShareIn's counts) wins over the composer's when the share lands. */
    @Test fun aShareLandsWithItsOwnNote() {
        val vm = vm()
        vm.pendingStarted(2)
        vm.pendingLanded(2, listOf(photo, unreadable), limit = 10, note = "1 image couldn't be read")
        assertEquals("1 image couldn't be read", vm.draftImageNote)
    }

    /** A pick or a paste takes only what fits beside the photos in and the ones still loading (iOS design review). */
    @Test fun aPasteCapsItsTilesAtTheRoomLeft() {
        assertEquals("one slot left: one tile, not five", 1, ComposerImages.room(limit = 5, ready = 0, pending = 4))
        assertEquals(2, ComposerImages.room(limit = 10, ready = 6, pending = 2))
        assertEquals("full: nothing", 0, ComposerImages.room(limit = 4, ready = 3, pending = 2))
        assertEquals(10, ComposerImages.room(limit = 10, ready = 0, pending = 0))
    }
}
