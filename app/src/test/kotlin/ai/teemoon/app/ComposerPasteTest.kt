// the paste rule shared with iOS (pm for the maintainer, 2026-11-07): a pasted image enters the composer as a picked one.
package ai.teemoon.app

import ai.teemoon.app.imaging.ComposerPaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerPasteTest {
    private val a = "content://other.app/a"; private val b = "content://other.app/b"; private val c = "content://other.app/c"

    private fun image(uri: String, text: String? = null) = ComposerPaste.Item(uri, text)
    private fun text(t: String) = ComposerPaste.Item(null, t)

    /** Text alone is the field's, as before: nothing is taken from it. */
    @Test fun aTextPasteTakesNoImage() {
        assertEquals(emptyList<String>(), ComposerPaste.imageUris(listOf("text/plain"), listOf(text("just words")), room = 10))
    }

    /** The clip's images in its own order. */
    @Test fun anImagePasteTakesItsImagesInClipOrder() {
        assertEquals(listOf(b, a), ComposerPaste.imageUris(listOf("image/png"), listOf(image(b), image(a)), room = 10))
    }

    /** Words beside an image are text: the words go in, no image is attached — in one item or two. */
    @Test fun aPasteWithWordsAndAnImageIsText() {
        assertEquals(emptyList<String>(), ComposerPaste.imageUris(listOf("text/plain", "image/png"), listOf(text("look at this"), image(a)), room = 10))
        assertEquals("one item", emptyList<String>(), ComposerPaste.imageUris(listOf("image/png", "text/plain"), listOf(image(a, "look at this")), room = 10))
    }

    /**
     * Through the limit: one past the room left is read, so the composer says "up to n images per message" as a pick
     * does (ComposerImages.add); the rest are not read at all. The type is the bytes' (ImageGate), not the clip's word for it.
     */
    @Test fun aPasteReadsOnePastTheRoomLeft() {
        assertEquals(listOf(a, b), ComposerPaste.imageUris(listOf("image/*"), listOf(image(a), image(b), image(c)), room = 1))
        assertEquals(listOf(a, b, c), ComposerPaste.imageUris(listOf("image/webp"), listOf(image(a), image(b), image(c)), room = 4))
    }

    // IMAGES.md §4c, an image item's URL is not text (the maintainer 2026-11-08, "behave like Messages does"; iOS
    // 1af3cd79, ImagePasteTests' Android cases)

    private val page = "https://example.com/photos/cat.jpg"

    /** Cases 1 and 2: a browser's image copy, an image with its http or data: URL → the image, and no text goes in. */
    @Test fun anImageItemsUrlAttachesTheImage() {
        assertEquals(listOf(a), ComposerPaste.images(listOf("image/jpeg", "text/uri-list"), listOf(image(a, page))))
        assertEquals(listOf(a), ComposerPaste.images(listOf("image/jpeg", "text/plain"), listOf(image(a, jpegUrl))))
        assertEquals("any case, blanks around it", listOf(a), ComposerPaste.images(listOf("image/jpeg"), listOf(image(a, " HTTPS://EXAMPLE.COM/a.jpg\n"))))
        assertEquals("http too", listOf(a), ComposerPaste.images(listOf("image/jpeg"), listOf(image(a, "http://example.com/a.jpg"))))
    }

    /** Case 3: an image item whose text is words, or a URL without its scheme, is text; nothing attaches. */
    @Test fun anImageItemsWordsAreText() {
        for (t in listOf("a caption the copy carried", "example.com/a.jpg", "$page and more", "note: http is a protocol"))
            assertEquals(t, emptyList<String>(), ComposerPaste.images(listOf("image/png", "text/plain"), listOf(image(a, t))))
    }

    /** Case 5: two image items, one with the URL → both, in order; an image item's URL beside a text item's words → text. */
    @Test fun severalItems() {
        assertEquals(listOf(a, b), ComposerPaste.images(listOf("image/jpeg", "image/png"), listOf(image(a, page), image(b))))
        assertEquals(emptyList<String>(), ComposerPaste.images(listOf("image/jpeg", "text/plain"), listOf(image(a, page), text("the words"))))
        assertEquals("two URLs", emptyList<String>(), ComposerPaste.images(listOf("image/jpeg", "text/plain"), listOf(image(a, page), text(page))))
    }

    /**
     * Case 6: an image-only item beside a URL-only item → the image goes to the composer. The URL is not inserted either
     * (the maintainer 2026-11-09, "operate like messages app"): DesignKit's receiver consumes the whole clip once
     * [ComposerPaste.images] attaches — ComposerPasteDeviceTest.anImageBesideAUrlOnlyItemAttachesOnlyTheImage.
     */
    @Test fun anImageBesideAUrlOnlyItemAttaches() {
        val items = listOf(image(a), text(page))
        assertEquals(listOf(a), ComposerPaste.images(listOf("image/heic", "text/plain"), items))
        assertEquals("the URL item is not an image", listOf(true, false), items.map { it.isImage })
    }

    // "any images copied to clipboard should work" (the maintainer 2026-11-09): a URI whose clip names no image type is
    // read and its bytes decide — an image attaches, anything else gets the note; never a silent paste.

    /** A content URI under a generic or non-image type, with no text in the clip, goes to the image path. */
    @Test fun aUriUnderAGenericTypeIsReadAndItsBytesDecide() {
        for (mime in listOf("application/octet-stream", "text/plain", "application/pdf", "*/*"))
            assertEquals(mime, listOf(a), ComposerPaste.images(listOf(mime), listOf(image(a))))
        assertEquals("several, in order", listOf(a, b), ComposerPaste.images(listOf("application/octet-stream"), listOf(image(a), image(b))))
    }

    /** Words in such a clip keep it the field's, as before. */
    @Test fun aGenericClipWithWordsStaysText() {
        assertEquals(emptyList<String>(), ComposerPaste.images(listOf("text/plain"), listOf(image(a, "the words"))))
        assertEquals(emptyList<String>(), ComposerPaste.images(listOf("application/octet-stream", "text/plain"), listOf(image(a), text("the words"))))
    }

    /**
     * iOS S42 (trip.com, 2026-11-11): an app puts its image beside its page URL under its own type, not an image/ one.
     * The image attaches and the URL stays out, whatever type the clip names — the bytes decide.
     */
    @Test fun anImageUnderAnAppTypeBesideItsUrlAttaches() {
        assertEquals(listOf(a), ComposerPaste.images(listOf("application/x-app-image", "text/plain"), listOf(image(a), text(page))))
        assertEquals("one item", listOf(a), ComposerPaste.images(listOf("text/uri-list"), listOf(image(a, page))))
        assertEquals("generic", listOf(a), ComposerPaste.images(listOf("application/octet-stream", "text/plain"), listOf(image(a), text(page))))
    }

    /**
     * The data-URL rule holds for any text a paste would put in (ios 2026-11-08): an image item beside a text item that
     * is one data: URL → both attach, the image first, then the URL's, and no text goes in.
     */
    @Test fun aDataUrlBesideAnImageItemIsAnImageToo() {
        val items = listOf(image(a), text(" $jpegUrl\n"))
        assertEquals(listOf(a), ComposerPaste.images(listOf("image/png", "text/plain"), items))
        assertEquals(jpegUrl, ComposerPaste.dataUrlBeside(items))
        assertNull("an http URL beside an image is no data URL", ComposerPaste.dataUrlBeside(listOf(image(a), text(page))))
        assertNull("an image item's own data: URL is that image's", ComposerPaste.dataUrlBeside(listOf(image(a, jpegUrl))))
    }

    /** Case 7: a URL alone, as text or as a URI item, is never an image item. */
    @Test fun aUrlOnlyClipIsText() {
        assertEquals(emptyList<String>(), ComposerPaste.images(listOf("text/plain"), listOf(text(page))))
        assertEquals(emptyList<String>(), ComposerPaste.images(listOf("text/uri-list"), listOf(ComposerPaste.Item(page, null))))
        assertEquals("a web URI under an image type", emptyList<String>(), ComposerPaste.images(listOf("image/jpeg"), listOf(ComposerPaste.Item(page, null))))
        for (u in listOf(page, "HTTP://x", jpegUrl)) assertEquals(u.take(20), false, ComposerPaste.Item(u, null).isImage)
    }

    // IMAGES.md §4c, a data: URL as text (the maintainer 2026-11-08; iOS ec43cc58, ImagePasteTests' Android cases)

    private val jpegUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))

    /** Cases 1, 2, 6: the paste's text, trimmed, is one data: URL — scheme and type in any case. */
    @Test fun aPastedTextThatIsOneDataUrlIsAnImage() {
        assertEquals(jpegUrl, ComposerPaste.soleDataUrl(jpegUrl))
        assertEquals("blanks and newlines around it", jpegUrl, ComposerPaste.soleDataUrl("  \n$jpegUrl\n\t "))
        val upper = "DATA:IMAGE/JPEG;BASE64," + jpegUrl.substringAfter(',')
        assertEquals("the scheme lowercased for the decoder", "data:IMAGE/JPEG;BASE64," + jpegUrl.substringAfter(','), ComposerPaste.soleDataUrl(upper))
        assertNotNull("an upper-case URL decodes", ComposerPaste.dataUrlBytes(ComposerPaste.soleDataUrl(upper)!!))
    }

    /** Case 8: a data URL among other words, two of them, no comma, an empty one, or prose stays text. */
    @Test fun aDataUrlAmongOtherWordsStaysText() {
        for (t in listOf("look $jpegUrl", "$jpegUrl and more", "$jpegUrl $jpegUrl", "data:image", "see data:,x", "the data: part", "hello"))
            assertNull(t, ComposerPaste.soleDataUrl(t))
    }

    /** Case 7: any other data: URL, or a malformed or over-cap one, can't be read: no bytes reach a decoder. */
    @Test fun anotherDataUrlCannotBeRead() {
        assertNotNull(ComposerPaste.dataUrlBytes(jpegUrl))
        val webp = "data:image/webp;base64," + java.util.Base64.getEncoder().encodeToString("RIFF0000WEBP".toByteArray())
        val overCap = "data:image/jpeg;base64," + "A".repeat(ai.teemoon.app.imaging.ImageCaps.MAX_BYTES / 3 * 4 + 8)
        for (u in listOf("data:text/plain,hello", "data:image/png,raw", webp, "data:image/jpeg;base64,!!!!", "data:image/jpeg;base64,", overCap))
            assertNull(u.take(40), ComposerPaste.dataUrlBytes(u))
    }

    /**
     * Case 10: what a browser's clip can hold — the URL as text, as a URL item, both, or beside an image — is one image,
     * the URL's; an image clip with no data URL is the image path as before.
     */
    @Test fun everyClipShapeOfOneDataUrlIsThatUrl() {
        val content = "content://other.app/a.jpg"
        assertEquals(jpegUrl, ComposerPaste.dataUrl(listOf(jpegUrl), listOf(null)))
        assertEquals("a URL item", jpegUrl, ComposerPaste.dataUrl(listOf(null), listOf(jpegUrl)))
        assertEquals("both", jpegUrl, ComposerPaste.dataUrl(listOf(jpegUrl, null), listOf(null, jpegUrl)))
        assertEquals("the URL as text beside an image", jpegUrl, ComposerPaste.dataUrl(listOf(jpegUrl, null), listOf(null, content)))
        assertEquals("an image beside the URL", jpegUrl, ComposerPaste.dataUrl(listOf(null, null), listOf(content, jpegUrl)))
        assertNull("an image alone is the image path", ComposerPaste.dataUrl(listOf(null), listOf(content)))
        assertNull("plain text is the field's", ComposerPaste.dataUrl(listOf("hello"), listOf(null)))
    }
}

