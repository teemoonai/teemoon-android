// mirrors: teemoonTests/ComposerImageTests.swift @ 67b39a4 (tenToTheCloudFourOnDeviceAndAnUnreadableOneSaysSo) and
// teemoonTests/ImageSendTests.swift @ 67b39a4 (theBudgetIsTenImagesNewestFirstWithThePromptFirst), against IMAGES.md
// draft 3 §4: 10 per cloud message and per request with the 8 MiB cap kept; the on-device model keeps 4 until the probe.
package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerImages
import ai.teemoon.app.imaging.ImageCaps
import ai.teemoon.app.imaging.ImageCopy
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ImageHistory
import ai.teemoon.app.imaging.ImageRejection
import ai.teemoon.inference.WireImage
import ai.teemoon.inference.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerImagesTest {
    private fun kept(n: Int) = ImageDecodeFrame.Decoded("image/jpeg", 1, 1, byteArrayOf(n.toByte()), byteArrayOf(n.toByte()))

    @Test fun tenToTheCloudTenOnThePhoneAndAnUnreadableOneSaysSo() {
        assertEquals(10, ImageCaps.perSend(onDevice = false))
        assertEquals("the phone too where the memory check passes (the maintainer 2026-11-12)", 10, ImageCaps.perSend(onDevice = true))
        assertEquals(10, ImageHistory.MAX_IMAGES); assertEquals(8L * 1024 * 1024, ImageHistory.MAX_BYTES)
        val cloud = ComposerImages.add(emptyList(), (1..11).map(::kept), limit = 10)
        assertEquals(10, cloud.kept.size)
        assertEquals("up to 10 images per message", cloud.note)

        var a = ComposerImages.add(emptyList(), listOf(kept(1), kept(2), kept(3)), limit = 4)
        assertEquals(3, a.kept.size); assertNull(a.note)
        a = ComposerImages.add(a.kept, listOf(kept(4), kept(5)), limit = 4)
        assertEquals(4, a.kept.size)
        assertEquals("up to 4 images per message", a.note)
        a = ComposerImages.add(a.kept.drop(1), listOf(ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)), limit = 4)
        assertEquals(3, a.kept.size); assertEquals(ImageCopy.UNREADABLE, a.note)
    }

    @Test fun theBudgetIsTenImagesNewestFirstWithThePromptFirst() {
        fun img(tag: Int) = WireImage("image/jpeg", ByteArray(10) { tag.toByte() })
        val history = (0 until 6).flatMap { t -> listOf(WireMessage("user", "question $t", listOf(img(2 * t), img(2 * t + 1))), WireMessage("assistant", "answer $t")) }
        val r = ImageHistory.apply(history, listOf(img(99)), vision = true)
        // q0, a0 … q5, a5: the prompt takes 1, q5…q2 get 2 each, q1 gets 1, q0 none — 12 history images, 3 left out.
        assertEquals(1, r.promptImages.size)
        assertEquals(listOf(0, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0), r.history.map { it.images.size })
        assertEquals(3, r.leftOut)
        assertEquals("question 0\n\n[image earlier in this chat]\n\n[image earlier in this chat]", r.history[0].content)
        assertEquals("question 1\n\n[image earlier in this chat]", r.history[2].content)
    }

    /**
     * IMAGES.md §4d (the 1.1.0 image flow, the maintainer 2026-11-11): "+" opens the attach sheet, two big buttons side
     * by side — take photo on the left, choose photos on the right — and the footnote under them. With no camera app
     * there is no "take photo" (no CAMERA permission is declared, so the system camera is the only one).
     */
    @Test fun theAttachSheetIsTakePhotoThenChoosePhotos() {
        assertEquals(listOf(ai.teemoon.app.imaging.ImageCopy.TAKE_PHOTO, ai.teemoon.app.imaging.ImageCopy.CHOOSE_PHOTOS),
            ai.teemoon.app.imaging.ImageCopy.attachSheet(hasCamera = true))
        assertEquals(listOf(ai.teemoon.app.imaging.ImageCopy.CHOOSE_PHOTOS), ai.teemoon.app.imaging.ImageCopy.attachSheet(hasCamera = false))
        assertEquals("photos are resized on this phone and their location is removed before they're sent.", ai.teemoon.app.imaging.ImageCopy.ATTACH_FOOTNOTE)
    }
}
