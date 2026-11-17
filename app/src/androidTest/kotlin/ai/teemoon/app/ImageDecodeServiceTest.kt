package ai.teemoon.app

// IMAGES.md §2/§6 (iOS b2c2ea2) and the maintainer's requirement of 2026-10-25 — a malformed image must never get code execution
// in, or crash, the app: the decoder is an isolated process, and its death is a placeholder here.

import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ImageRejection
import ai.teemoon.app.imaging.IsolatedImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageDecodeServiceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bytesThatAreNoImageComeBackAsAPlaceholder() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.UNSUPPORTED_FORMAT), decoder.decode("<svg onload=alert(1)>".toByteArray()))
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.UNSUPPORTED_FORMAT), decoder.decode(ByteArray(4096) { it.toByte() }))
    }

    /** The decoder's process dies mid-call, as a crash or an exploit would end it: the app lives on, and the next image works. */
    @Test fun theAppSurvivesTheDecoderDying() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val me = Process.myPid()
        decoder.crashServiceForTest()
        assertEquals("the test process is still this one", me, Process.myPid())
        val after = decoder.decode("not an image".toByteArray())
        assertTrue("the next image is answered by a fresh decoder: $after", after is ImageDecodeFrame.Rejected)
        assertEquals(ImageRejection.UNSUPPORTED_FORMAT, (after as ImageDecodeFrame.Rejected).why)
    }

    /** The service runs as a different, isolated UID: it is not the app. */
    @Test fun theDecoderIsNotTheAppsProcess() {
        val info = ctx.packageManager.getServiceInfo(android.content.ComponentName(ctx, ai.teemoon.app.imaging.ImageDecodeService::class.java), 0)
        assertTrue("isolatedProcess", info.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertEquals(ctx.packageName + ":decode", info.processName)
        assertTrue("not exported", !info.exported)
    }

    // MARK: the decoder itself (Wuffs, in the isolated process). Inputs are made here from known pixels; the outputs are
    // read back with the platform decoder, which is safe for bytes OUR decoder produced.

    private fun bitmap(w: Int, h: Int, alpha: Boolean = false) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if (alpha && x < w / 2) Color.TRANSPARENT else Color.rgb(x % 256, y % 256, 128))
    }
    private fun encode(b: Bitmap, format: Bitmap.CompressFormat) = ByteArrayOutputStream().also { b.compress(format, 90, it) }.toByteArray()

    /** An APP1 EXIF segment with an orientation and a marker standing in for camera GPS, spliced in after SOI. */
    private fun withExif(jpeg: ByteArray, orientation: Int, marker: String): ByteArray {
        val m = marker.toByteArray()
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff + m
        val len = app1.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray) = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test fun aJpegIsDecodedReencodedAndStrippedOfItsMetadata() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val source = withExif(encode(bitmap(640, 480), Bitmap.CompressFormat.JPEG), orientation = 1, marker = "GPSLatitude=38.7223N")
        val out = decoder.decode(source) as ImageDecodeFrame.Decoded
        assertEquals("image/jpeg", out.mime); assertEquals(640, out.width); assertEquals(480, out.height)
        assertTrue("the EXIF block survived", !contains(out.image, "Exif".toByteArray()) && !contains(out.image, "GPSLatitude".toByteArray()))
        assertTrue(!contains(out.thumbnail, "GPSLatitude".toByteArray()))
        val back = BitmapFactory.decodeByteArray(out.image, 0, out.image.size); assertEquals(640, back.width)
        val thumb = BitmapFactory.decodeByteArray(out.thumbnail, 0, out.thumbnail.size); assertEquals(256, thumb.width); assertEquals(192, thumb.height)
    }

    /** Orientation 6 (a phone held upright): the stored image is upright, so width and height swap. */
    @Test fun theExifOrientationIsAppliedBeforeTheMetadataGoes() = runBlocking {
        val out = IsolatedImageDecoder(ctx).decode(withExif(encode(bitmap(400, 300), Bitmap.CompressFormat.JPEG), orientation = 6, marker = "x")) as ImageDecodeFrame.Decoded
        assertEquals(300, out.width); assertEquals(400, out.height)
    }

    @Test fun aBigImageIsStoredAt2048HalfUpAndATransparentOneAsPng() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val big = decoder.decode(encode(bitmap(4096, 2305), Bitmap.CompressFormat.JPEG)) as ImageDecodeFrame.Decoded
        assertEquals(2048, big.width); assertEquals(1153, big.height)
        val clear = decoder.decode(encode(bitmap(64, 64, alpha = true), Bitmap.CompressFormat.PNG)) as ImageDecodeFrame.Decoded
        assertEquals("image/png", clear.mime)
    }

    /** A PNG chunk: length, type and data, and the CRC over type and data. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val td = type.toByteArray() + data
        val crc = CRC32().apply { update(td) }.value
        val n = data.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + td +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    /** A few dozen bytes: a header declaring [w] × [h], a token data chunk (where a PNG's header ends), and the end. */
    private fun pngHeaderOnly(w: Int, h: Int): ByteArray {
        val ihdr = byteArrayOf((w ushr 24).toByte(), (w ushr 16).toByte(), (w ushr 8).toByte(), w.toByte(),
            (h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte(), 8, 6, 0, 0, 0)
        val zlibEmpty = byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", zlibEmpty) + chunk("IEND", ByteArray(0))
    }

    /** A "bomb": a few dozen bytes declaring 100k × 100k. Refused from the header, before a byte of pixels exists. */
    @Test fun aDecompressionBombIsRefusedFromItsHeader() = runBlocking {
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_PIXELS), IsolatedImageDecoder(ctx).decode(pngHeaderOnly(100_000, 100_000)))
    }

    @Test fun aTruncatedImageIsAPlaceholderNotACrash() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val jpeg = encode(bitmap(320, 240), Bitmap.CompressFormat.JPEG)
        for (cut in listOf(4, 64, jpeg.size / 2, jpeg.size - 4)) {
            val r = decoder.decode(jpeg.copyOf(cut))
            assertTrue("cut at $cut: $r", r is ImageDecodeFrame.Rejected || r is ImageDecodeFrame.Decoded)
        }
        assertTrue(decoder.decode(jpeg) is ImageDecodeFrame.Decoded)
    }

    // The maintainer, 2026-11-11: a picked or pasted WebP or HEIC is read by Android's ImageDecoder in the isolated process,
    // a BMP by Wuffs; each comes out re-encoded, like a JPEG.

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    @Test fun aWebpOfEitherKindIsDecodedAndReencoded() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        for (f in listOf(Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val out = decoder.decode(encode(bitmap(640, 480), f)) as ImageDecodeFrame.Decoded
            assertEquals("$f", "image/jpeg", out.mime); assertEquals(640, out.width); assertEquals(480, out.height)
            assertTrue("$f: not re-encoded", isJpeg(out.image) && isJpeg(out.thumbnail))
        }
        val big = decoder.decode(encode(bitmap(4096, 2305), Bitmap.CompressFormat.WEBP_LOSSY)) as ImageDecodeFrame.Decoded
        assertEquals(2048, big.width); assertEquals(1153, big.height)
        val clear = decoder.decode(encode(bitmap(64, 64, alpha = true), Bitmap.CompressFormat.WEBP_LOSSLESS)) as ImageDecodeFrame.Decoded
        assertEquals("a transparent WebP stays transparent", "image/png", clear.mime)
    }

    /**
     * A HEIC made by macOS `sips` from a 64 × 48 PNG (androidTest assets). Measured on the AVD, 2026-11-11: the platform
     * decodes it in an ordinary process (64 × 48), but NOT in the isolated one — ImageDecoder's HEIF path asks the
     * media.player service, and SELinux denies `isolated_app` that lookup (`avc: denied { find } … mediaserver_service`).
     * So a HEIC is the "couldn't be read" note, never a crash and never its bytes passed through. If this starts
     * decoding, the platform changed: check the output is re-encoded and update IMAGE_DECODER.md.
     */
    @Test fun aHeicInTheIsolatedProcessIsTheNote() = runBlocking {
        val heic = InstrumentationRegistry.getInstrumentation().context.assets.open("decode-64x48.heic").use { it.readBytes() }
        assertNote(IsolatedImageDecoder(ctx).decode(heic))
    }

    /**
     * The note, whichever reason the platform gives: the composer shows "this image couldn't be read" for every refusal
     * (ComposerImages). The AVD's decoder said UNREADABLE (2026-11-11); the maintainer's Pixel (Android 17, GrapheneOS)
     * says DECODER_FAILED for the same file (2026-11-12) — the same note to the user, never the bytes.
     */
    private fun assertNote(out: ImageDecodeFrame) {
        assertTrue("not refused: $out", out is ImageDecodeFrame.Rejected && out.why in setOf(ImageRejection.UNREADABLE, ImageRejection.DECODER_FAILED))
    }

    /** A 24-bit BMP, bottom-up rows padded to 4 bytes. */
    private fun bmp(w: Int, h: Int): ByteArray {
        val row = (w * 3 + 3) / 4 * 4
        val buf = java.nio.ByteBuffer.allocate(54 + row * h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + row * h).putInt(0).putInt(54)
        buf.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(row * h).putInt(2835).putInt(2835).putInt(0).putInt(0)
        repeat(h) { y -> repeat(w) { x -> buf.put(128.toByte()).put((y * 5).toByte()).put((x * 4).toByte()) }; repeat(row - w * 3) { buf.put(0) } }
        return buf.array()
    }

    @Test fun aBmpIsDecodedByWuffs() = runBlocking {
        val out = IsolatedImageDecoder(ctx).decode(bmp(64, 48)) as ImageDecodeFrame.Decoded
        assertEquals("image/jpeg", out.mime); assertEquals(64, out.width); assertEquals(48, out.height)
    }

    /** A WebP whose VP8X header declares 20000 × 20000 is refused, never allocated; a cut WebP is a placeholder, not a crash. */
    @Test fun aWebpBombOrACutWebpIsAPlaceholder() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val vp8x = java.nio.ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(22).put("WEBPVP8X".toByteArray()).putInt(10).putInt(0)
            .put(byteArrayOf((19999 and 0xFF).toByte(), (19999 shr 8).toByte(), 0, (19999 and 0xFF).toByte(), (19999 shr 8).toByte(), 0)).array()
        assertTrue(decoder.decode(vp8x) is ImageDecodeFrame.Rejected)
        val webp = encode(bitmap(320, 240), Bitmap.CompressFormat.WEBP_LOSSY)
        for (cut in listOf(16, 40, webp.size / 2)) assertTrue("cut at $cut", decoder.decode(webp.copyOf(cut)).let { it is ImageDecodeFrame.Rejected || it is ImageDecodeFrame.Decoded })
        assertTrue(decoder.decode(webp) is ImageDecodeFrame.Decoded)
    }

    // iOS S42 (2026-11-11): AVIF and TIFF go the same way; a container that lies is refused; a BMP over the caps is refused
    // from its header; garbage after any magic number is a placeholder, never a crash.

    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /**
     * An AVIF made by macOS `sips` from the same 64 × 48 PNG. Measured on the AVD, 2026-11-11: as HEIC, Android decodes
     * AVIF through HeifDecoderImpl and the media.player service, which the isolated process can't reach — the note
     * (the maintainer's choice (a) for HEIC). If this starts decoding, check the output is re-encoded.
     */
    @Test fun anAvifInTheIsolatedProcessIsTheNote() = runBlocking {
        assertNote(IsolatedImageDecoder(ctx).decode(asset("decode-64x48.avif")))
    }

    /** Android's ImageDecoder has no TIFF codec: a TIFF is the note, never its bytes. */
    @Test fun aTiffIsTheNote() = runBlocking {
        assertTrue(IsolatedImageDecoder(ctx).decode(asset("decode-64x48.tiff")) is ImageDecodeFrame.Rejected)
    }

    /** The AVIF fixture relabelled HEIC in its ftyp: the sniff says HEIF, the decoder doesn't — refused. */
    @Test fun aContainerThatLiesIsRefused() = runBlocking {
        val lying = asset("decode-64x48.avif").also { "heic".toByteArray().copyInto(it, 8) }
        assertTrue(IsolatedImageDecoder(ctx).decode(lying) is ImageDecodeFrame.Rejected)
    }

    @Test fun aBmpOverTheCapsIsRefusedFromItsHeader() = runBlocking {
        val huge = bmp(4, 2).also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(18, 20000).putInt(22, 20000) }
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_PIXELS), IsolatedImageDecoder(ctx).decode(huge))
    }

    @Test fun garbageAfterEveryMagicIsAPlaceholder() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val noise = ByteArray(512) { (it * 37 + 11).toByte() }
        val magics = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "GIF89a".toByteArray(), "BM".toByteArray(), "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(),
            byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray(), byteArrayOf(0, 0, 0, 0x18) + "ftypavif".toByteArray(), byteArrayOf(0x49, 0x49, 0x2A, 0))
        for (m in magics) assertTrue(m.decodeToString().take(8), decoder.decode(m + noise) is ImageDecodeFrame.Rejected)
        assertTrue("the decoder still answers", decoder.decode(encode(bitmap(8, 8), Bitmap.CompressFormat.PNG)) is ImageDecodeFrame.Decoded)
    }
}
