// spec: teemoon-ios docs/SHARE_IN.md @ 1f60fe9c (the maintainer 2026-11-12, "I want in 1.1.0")
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ImageRejection
import ai.teemoon.app.imaging.ShareIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Share to teemoon: a photo, several, text or a URL shared from another app opens a new chat with them as a draft —
 * never sent. A single near.ai export (.json) still goes to the import first.
 */
class ShareInTest {
    private val send = "android.intent.action.SEND"
    private val multiple = "android.intent.action.SEND_MULTIPLE"
    private val view = "android.intent.action.VIEW"

    // android-only: a share is read with teemoon's own access, so only another app's content URI is opened.
    @Test fun onlyAnotherAppsContentIsRead() {
        val own = "ai.teemoon"
        assertTrue(ShareIn.readableStream("content", "com.google.android.apps.photos.contentprovider", own))
        assertTrue(ShareIn.readableStream("content", "media", own))
        assertFalse("a path into teemoon's own files", ShareIn.readableStream("file", null, own))
        assertFalse("teemoon's attachments and camera shots", ShareIn.readableStream("content", "ai.teemoon.files", own))
        assertFalse(ShareIn.readableStream("content", "ai.teemoon", own))
        assertFalse(ShareIn.readableStream("content", "", own))
        assertFalse(ShareIn.readableStream("http", "example.com", own))
    }

    @Test fun anExportStillGoesToTheImportFirst() {
        assertEquals(ShareIn.Kind.IMPORT, ShareIn.kind(send, "application/json", streams = 1, scheme = "content", hasText = false))
        assertEquals(ShareIn.Kind.IMPORT, ShareIn.kind(view, "application/json", streams = 1, scheme = "content", hasText = false))
        assertEquals("a .json among several is not an import (the import is one file)", ShareIn.Kind.NONE, ShareIn.kind(multiple, "application/json", streams = 2, scheme = "content", hasText = false))
    }

    @Test fun photosTextAndUrlsBecomeADraft() {
        assertEquals(ShareIn.Kind.DRAFT, ShareIn.kind(send, "image/jpeg", streams = 1, scheme = "content", hasText = false))
        assertEquals(ShareIn.Kind.DRAFT, ShareIn.kind(multiple, "image/*", streams = 3, scheme = "content", hasText = false))
        assertEquals("a URL is text/plain on Android", ShareIn.Kind.DRAFT, ShareIn.kind(send, "text/plain", streams = 0, scheme = null, hasText = true))
        assertEquals("nothing teemoon takes: movies, PDFs", ShareIn.Kind.NONE, ShareIn.kind(send, "video/mp4", streams = 1, scheme = "content", hasText = false))
        assertEquals(ShareIn.Kind.NONE, ShareIn.kind(send, "application/pdf", streams = 1, scheme = "content", hasText = false))
        assertEquals("a launch is not a share", ShareIn.Kind.NONE, ShareIn.kind("android.intent.action.MAIN", null, streams = 0, scheme = null, hasText = false))
    }

    /**
     * 10 images, 20 MB each as shared (the picker's and the paste's own cap, ImageCaps.MAX_BYTES; pm 2026-11-14 for
     * The maintainer: "20 MB each, same intake as paste" — a 30 MB photo passed a 50 MB check and then landed as "couldn't
     * read"), 100,000 characters: over any of them nothing is kept, and the note says why.
     */
    @Test fun overACapNothingIsKept() {
        assertFalse(ShareIn.overCap(images = 10, sizes = List(10) { 20L * 1024 * 1024 }, textLength = 100_000))
        assertTrue(ShareIn.overCap(images = 11, sizes = List(11) { 1L }, textLength = 0))
        assertTrue(ShareIn.overCap(images = 1, sizes = listOf(20L * 1024 * 1024 + 1), textLength = 0))
        assertTrue(ShareIn.overCap(images = 0, sizes = emptyList(), textLength = 100_001))
        val d = ShareIn.overCapDraft()
        assertEquals("", d.text); assertTrue(d.images.isEmpty())
        assertEquals("that's more than teemoon can take at once: up to 10 photos, 20 MB each.", d.note)
    }

    /**
     * The intent filter matched but there is nothing to take (an image SEND with no stream, a SEND of a type the filter
     * let through): the design review of share-in found teemoon opened on the old chat without a word. It says it
     * couldn't read what was shared, as a share with one bad photo does. The import and a plain launch are not this.
     */
    @Test fun aShareWithNothingToTakeSaysSo() {
        val nothing = ShareIn.kind(ShareIn.ACTION_SEND, "image/png", streams = 0, scheme = null, hasText = false)
        assertEquals(ShareIn.Kind.NONE, nothing)
        assertTrue(ShareIn.unusable(ShareIn.ACTION_SEND, nothing))
        assertTrue(ShareIn.unusable(ShareIn.ACTION_SEND_MULTIPLE, ShareIn.Kind.NONE))
        assertFalse(ShareIn.unusable("android.intent.action.MAIN", ShareIn.Kind.NONE))
        assertFalse(ShareIn.unusable(ShareIn.ACTION_SEND, ShareIn.Kind.IMPORT))
        assertFalse(ShareIn.unusable(ShareIn.ACTION_SEND, ShareIn.Kind.DRAFT))
    }

    @Test fun aDraftKeepsTheOrderAndTheTextAndSaysWhenSomethingCouldntBeRead() {
        val a = ImageDecodeFrame.Decoded("image/jpeg", 2, 2, byteArrayOf(1), byteArrayOf(1))
        val b = ImageDecodeFrame.Decoded("image/png", 2, 2, byteArrayOf(2), byteArrayOf(2))
        val d = ShareIn.draft("https://example.com/post", listOf(a, ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE), b))
        assertEquals("https://example.com/post", d.text)
        assertEquals(listOf(a, b), d.images)
        // some landed: the note counts what didn't (pm 2026-11-14, DRAFT; the whole-share line read as if nothing arrived)
        assertEquals("1 image couldn't be read", d.note)
        assertEquals("2 images couldn't be read", ShareIn.draft("", listOf(ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE), a, ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE))).note)
        // nothing landed: the whole-share line
        assertEquals("teemoon couldn't read what was shared.", ShareIn.draft("", listOf(ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE))).note)
        assertEquals("all read: no note", null, ShareIn.draft("hi", listOf(a)).note)
    }

    /** Over the text cap alone, the note names the text cap, not the photos' (pm 2026-11-14, DRAFT). */
    @Test fun overTheTextCapTheNoteIsAboutText() {
        assertEquals("that's more text than teemoon can take at once: up to 100,000 characters.", ShareIn.overCapNote(images = 0, sizes = emptyList(), textLength = 100_001))
        assertEquals("that's more text than teemoon can take at once: up to 100,000 characters.", ShareIn.overCapNote(images = 2, sizes = listOf(1L, 1L), textLength = 100_001))
        assertEquals(ShareIn.OVER_CAP, ShareIn.overCapNote(images = 11, sizes = List(11) { 1L }, textLength = 0))
        assertEquals(ShareIn.OVER_CAP, ShareIn.overCapNote(images = 11, sizes = List(11) { 1L }, textLength = 100_001))
    }
}
