// spec: IMAGES.md draft 1 §2 (teemoon-ios b2c2ea2) + pm's defaults 2026-10-25. The gate before any decoder: the format
// from the bytes, the size caps, the data-URL rule, the half-up downscale, and EXIF orientation read without trusting
// a single offset.
package ai.teemoon.app

import ai.teemoon.app.imaging.DataUrl
import ai.teemoon.app.imaging.ExifOrientation
import ai.teemoon.app.imaging.ImageCaps
import ai.teemoon.app.imaging.ImageFormat
import ai.teemoon.app.imaging.ImageRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class ImageGateTest {
    private val png1x1 = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

    @Test fun theFormatComesFromTheBytesNeverTheLabel() {
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(png1x1))
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(ImageFormat.GIF, ImageFormat.sniff("GIF89a....".toByteArray()))
        // SVG, WebP, HEIC, AVIF, BMP, HTML: not in the untrusted set.
        for (other in listOf("<svg xmlns=", "RIFF\u0000\u0000\u0000\u0000WEBP", "\u0000\u0000\u0000\u0018ftypheic", "\u0000\u0000\u0000\u001cftypavif", "BM6\u0000", "<html>"))
            assertNull(other, ImageFormat.sniff(other.toByteArray(Charsets.ISO_8859_1)))
        // A lying label is a placeholder (IMPORT_NEAR.md draft 9): a PNG declared as JPEG is not decoded at all.
        assertEquals(DataUrl.Result.Rejected(ImageRejection.UNSUPPORTED_FORMAT), DataUrl.decode("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(png1x1)))
        // The header is case-insensitive after "data:", as the reference reads it.
        assertEquals(ImageFormat.PNG, (DataUrl.decode("data:IMAGE/PNG;BASE64," + Base64.getEncoder().encodeToString(png1x1)) as DataUrl.Result.Bytes).format)
    }

    @Test fun onlyABase64ImageDataUrlIsDecoded() {
        assertTrue(DataUrl.decode("data:image/png;base64," + Base64.getEncoder().encodeToString(png1x1)) is DataUrl.Result.Bytes)
        for ((url, why) in listOf(
            "https://example.com/cat.png" to ImageRejection.NOT_AN_IMAGE_URL,
            "http://example.com/cat.png" to ImageRejection.NOT_AN_IMAGE_URL,
            "data:text/html;base64,PHNjcmlwdD4=" to ImageRejection.NOT_AN_IMAGE_URL,
            "data:image/png,rawbytes" to ImageRejection.NOT_AN_IMAGE_URL,
            "data:image/webp;base64,UklGRg==" to ImageRejection.UNSUPPORTED_FORMAT,
            "data:image/png;charset=x;base64,AAAA" to ImageRejection.NOT_AN_IMAGE_URL,
            "data:image/png;base64,!!!not base64!!!" to ImageRejection.BAD_BASE64,
            "data:image/png;base64," to ImageRejection.BAD_BASE64,
        )) assertEquals(url, DataUrl.Result.Rejected(why), DataUrl.decode(url))
        // A five-character scheme lines up with "data:" for everything after it: only the prefix check refuses these
        // (audit mutation pass on 05c07fa — without it the http(s) cases above still failed on the comma, so it was
        // never the line that decided). Nothing here is fetched, and a real image under another scheme is not decoded.
        val payload = Base64.getEncoder().encodeToString(png1x1)
        for (url in listOf("file:image/png;base64,$payload", "blob:image/png;base64,$payload"))
            assertEquals(url, DataUrl.Result.Rejected(ImageRejection.NOT_AN_IMAGE_URL), DataUrl.decode(url))
    }

    @Test fun anOversizedPayloadIsRefusedBeforeItIsDecoded() {
        val tooBig = "data:image/png;base64," + "A".repeat((ImageCaps.MAX_BYTES / 3 + 2) * 4)
        assertEquals(DataUrl.Result.Rejected(ImageRejection.TOO_MANY_BYTES), DataUrl.decode(tooBig))
    }

    @Test fun theLongSideBecomes2048AndTheShortSideRoundsHalfUp() {
        assertEquals(1024 to 768, ImageCaps.targetSize(1024, 768))           // fits: unchanged
        assertEquals(2048 to 1536, ImageCaps.targetSize(4000, 3000))
        assertEquals(1536 to 2048, ImageCaps.targetSize(3000, 4000))         // portrait
        assertEquals(2048 to 1365, ImageCaps.targetSize(6000, 4000))         // 1365.33
        assertEquals(2048 to 1153, ImageCaps.targetSize(4096, 2305))         // 1152.5 → 1153, half up
        assertEquals(2048 to 1, ImageCaps.targetSize(100_000, 1))            // never below 1
        assertEquals(256 to 192, ImageCaps.targetSize(2048, 1536, ImageCaps.THUMB_LONG_SIDE))
    }

    private fun jpegWithOrientation(o: Int, little: Boolean): ByteArray {
        fun s(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun l(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            else byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val tiff = (if (little) "II" else "MM").toByteArray() + s(42) + l(8) + s(1) + s(0x0112) + s(3) + l(1) + s(o) + s(0) + l(0)
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + app1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test fun theOrientationIsReadInEitherByteOrder() {
        for (o in 1..8) { assertEquals(o, ExifOrientation.of(jpegWithOrientation(o, true))); assertEquals(o, ExifOrientation.of(jpegWithOrientation(o, false))) }
        assertEquals("out of range reads as stored", 1, ExifOrientation.of(jpegWithOrientation(9, true)))
        assertEquals(1, ExifOrientation.of(png1x1))
    }

    /** Hostile EXIF: every truncation and random bytes after the header read as 1, and never throw. */
    @Test fun aMalformedExifNeverThrowsAndReadsAsStored() {
        val good = jpegWithOrientation(6, false)
        for (cut in 0 until good.size) ExifOrientation.of(good.copyOf(cut))
        val r = Random(42)
        repeat(2000) {
            val junk = good.copyOf().also { b -> repeat(4) { b[2 + r.nextInt(b.size - 2)] = r.nextInt(256).toByte() } }
            assertTrue(ExifOrientation.of(junk) in 1..8)
        }
    }

    /** An imported message keeps as many images as a sent one carries (the maintainer 2026-11-07; iOS 8952827c pins the same). */
    @Test fun anImportedMessageKeepsAsManyImagesAsACloudSend() {
        assertEquals(ImageCaps.CLOUD_PER_SEND, ImageCaps.PER_IMPORTED_MESSAGE)
    }

    // The maintainer, 2026-11-11: a picked or pasted WebP or HEIC is read (Android's ImageDecoder in the isolated process)
    // and a BMP by Wuffs; the import keeps Wuffs' JPEG/PNG/GIF only.

    private fun riff(kind: String) = "RIFF".toByteArray() + byteArrayOf(0x40, 0, 0, 0) + "WEBP$kind".toByteArray() + ByteArray(8)
    private fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 0x18) + "ftyp$brand".toByteArray() + ByteArray(12)

    @Test fun aBmpIsWuffs() {
        assertEquals(ai.teemoon.app.imaging.ImageFormat.BMP, ai.teemoon.app.imaging.ImageFormat.sniff("BM".toByteArray() + ByteArray(30)))
        assertEquals("too short for a header", null, ai.teemoon.app.imaging.ImageFormat.sniff("BM".toByteArray()))
    }

    @Test fun webpAndHeifAreThePlatformDecoders() {
        val sniff = ai.teemoon.app.imaging.PlatformImageFormat::sniff
        for (k in listOf("VP8 ", "VP8L", "VP8X")) assertEquals(k, ai.teemoon.app.imaging.PlatformImageFormat.WEBP, sniff(riff(k)))
        for (b in listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"))
            assertEquals(b, ai.teemoon.app.imaging.PlatformImageFormat.HEIF, sniff(ftyp(b)))
        for (b in listOf("avif", "avis")) assertEquals(b, ai.teemoon.app.imaging.PlatformImageFormat.AVIF, sniff(ftyp(b)))
        assertEquals(ai.teemoon.app.imaging.PlatformImageFormat.TIFF, sniff("II*".toByteArray() + byteArrayOf(0) + ByteArray(12)))
        assertEquals(ai.teemoon.app.imaging.PlatformImageFormat.TIFF, sniff("MM".toByteArray() + byteArrayOf(0, 42) + ByteArray(12)))
        for (b in listOf("isom", "mp42", "qt  ")) assertEquals("not an image: $b", null, sniff(ftyp(b)))
        for (other in listOf("<svg xmlns=\"http://www.w3.org/2000/svg\">", "%PDF-1.7\n", "\u0000\u0000\u0001\u0000ico header"))
            assertEquals(other.take(6), null, sniff(other.toByteArray() + ByteArray(16)))
        assertEquals("RIFF that is not WebP (a WAV)", null, sniff("RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray()))
        assertEquals("JPEG stays Wuffs'", null, sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    /** Each platform format's MIME family, which ImageDecoder's own detected type must match (iOS S42). */
    @Test fun theDecodersTypeMustBeTheSniffedFamily() {
        assertTrue(ai.teemoon.app.imaging.PlatformImageFormat.WEBP.matches("image/webp")); assertTrue(ai.teemoon.app.imaging.PlatformImageFormat.HEIF.matches("image/heif")); assertTrue(ai.teemoon.app.imaging.PlatformImageFormat.HEIF.matches("image/heic"))
        assertTrue(ai.teemoon.app.imaging.PlatformImageFormat.AVIF.matches("image/avif")); assertTrue(ai.teemoon.app.imaging.PlatformImageFormat.TIFF.matches("image/tiff"))
        assertTrue("a lying container", !ai.teemoon.app.imaging.PlatformImageFormat.WEBP.matches("image/jpeg")); assertTrue(!ai.teemoon.app.imaging.PlatformImageFormat.HEIF.matches("image/avif")); assertTrue(!ai.teemoon.app.imaging.PlatformImageFormat.AVIF.matches(null))
    }

    /** The import's gate is unchanged: a WebP, HEIC or BMP data URL is never decoded. */
    @Test fun theImportStillTakesOnlyJpegPngGif() {
        val b64 = java.util.Base64.getEncoder()
        for ((type, bytes) in listOf("image/webp" to riff("VP8 "), "image/heic" to ftyp("heic"), "image/avif" to ftyp("avif"), "image/bmp" to "BM".toByteArray() + ByteArray(30)))
            assertEquals(type, ai.teemoon.app.imaging.DataUrl.Result.Rejected(ai.teemoon.app.imaging.ImageRejection.UNSUPPORTED_FORMAT),
                ai.teemoon.app.imaging.DataUrl.decode("data:$type;base64," + b64.encodeToString(bytes)))
    }
}

