// android-only: the share sheet's intent for an image (the maintainer's Pixel, 2026-11-07: no "Copy", no preview).
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageShareTest {
    /** The URI rides as ClipData too, with the image's own type and a read grant: the Sharesheet then previews it and offers Copy. */
    @Test fun anImageIsSharedWithClipDataTypeAndAReadGrant() {
        val uri = "content://ai.teemoon.dev.files/attachments/a1.jpg"
        val s = ImageShare.of(uri, "image/jpeg")
        assertEquals(ImageShare.ACTION_SEND, s.action)
        assertEquals("image/jpeg", s.type)
        assertEquals(uri, s.stream)
        assertEquals("ClipData carries the same URI", uri, s.clip)
        assertTrue(s.grantRead)
        // the ClipData names the image's type: without it (ClipData.newRawUri) the Pixel's chooser offered no Copy
        assertEquals(listOf("image/jpeg"), s.clipMimeTypes)
        assertEquals("a PNG keeps its own type", "image/png", ImageShare.of(uri, "image/png").type)
    }

    /**
     * The viewer's "copy" (review item android-viewer-copy, the maintainer 2026-11-07): the current image on the clipboard
     * as an image clip — its content URI with its own MIME type — so a paste into another app takes the image.
     */
    @Test fun aCopiedImageIsAnImageClip() {
        val uri = "content://ai.teemoon.dev.files/attachments/a1.png"
        val c = ai.teemoon.app.imaging.ImageClip.of(uri, "image/png")
        assertEquals("image", c.label)
        assertEquals(listOf("image/png"), c.mimeTypes)
        assertEquals(uri, c.uri)
    }

    /** iOS ZoomableImage: a double tap goes to 2.5× and back to 1× from any zoom; a pinch stays within 1× and 6×. */
    @Test fun aDoubleTapTogglesOneAndTwoAndAHalf() {
        val z = ai.teemoon.app.imaging.ViewerZoom
        assertEquals(2.5f, z.doubleTap(1f))
        assertEquals(1f, z.doubleTap(2.5f))
        assertEquals("a pinched zoom comes back to 1×", 1f, z.doubleTap(4.2f))
        assertEquals(6f, z.clamp(9f)); assertEquals(1f, z.clamp(0.4f))
    }

    /**
     * The viewer's "save" (review item android-viewer-save-zoom, the maintainer 2026-11-07): the image goes to
     * Pictures/teemoon through MediaStore — no storage permission at minSdk 29 — named for the image, with its own type.
     */
    @Test fun aSavedImageGoesToPicturesTeemoonWithItsOwnType() {
        val j = ai.teemoon.app.imaging.ImageSave.of("a1", "image/jpeg")
        assertEquals("Pictures/teemoon", j.relativePath)
        assertEquals("image/jpeg", j.mimeType)
        assertEquals("teemoon-a1.jpg", j.displayName)
        assertEquals("teemoon-b2.png", ai.teemoon.app.imaging.ImageSave.of("b2", "image/png").displayName)
        assertEquals("teemoon-c3.gif", ai.teemoon.app.imaging.ImageSave.of("c3", "image/gif").displayName)
        assertEquals("an imported image's id is made a file name", "teemoon-near-c-u-0.jpg", ai.teemoon.app.imaging.ImageSave.of("near:c:u#0", "image/jpeg").displayName)
    }

    /**
     * After a save (the maintainer 2026-11-07, via pm): a brief toast "saved to photos" when the gallery took the image; a
     * failed save shows nothing new.
     */
    @Test fun aSaveSaysSoOnlyWhenItSaved() {
        assertEquals("saved to photos", ai.teemoon.app.imaging.ImageSave.toastAfter(saved = true))
        assertEquals(null, ai.teemoon.app.imaging.ImageSave.toastAfter(saved = false))
    }

    /**
     * The maintainer 2026-11-12, second pass: "copy and share dont belong in the share view". Share is the plain system
     * sheet on every Android: the image, its ClipData, type and read grant, and nothing else (e0735cfb's custom copy and
     * save actions are gone, with the API-34 split).
     */
    @Test fun theShareSheetIsThePlainSystemSheet() {
        val uri = "content://ai.teemoon.dev.files/attachments/a1.jpg"
        assertEquals(ImageShare(ImageShare.ACTION_SEND, "image/jpeg", uri, clip = uri, grantRead = true, clipMimeTypes = listOf("image/jpeg")),
            ImageShare.of(uri, "image/jpeg"))
        assertEquals("no custom-action fields", setOf("action", "type", "stream", "clip", "grantRead", "clipMimeTypes"),
            ImageShare::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    /** A long press is the image's Material menu on every Android: copy, save, share, in iOS's order, each with its icon. */
    @Test fun theImageMenuIsCopySaveShareWithIcons() {
        val all = ImageActions(copy = {}, save = {}, share = {})
        assertEquals(listOf(ImageMenu.Row("copy", "content_copy"), ImageMenu.Row("save", "download"), ImageMenu.Row("share", "share")), ImageMenu.rows(all))
        assertEquals("a missing action is left out", listOf("copy", "share"), ImageMenu.rows(ImageActions(copy = {}, share = {})).map { it.title })
    }
}
