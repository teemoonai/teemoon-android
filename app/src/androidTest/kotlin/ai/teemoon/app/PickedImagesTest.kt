package ai.teemoon.app

// IMAGES.md §4 and the audit's picker contract (1.1.0): what PickedImages does with a content URI, through a real
// ContentProvider and the real isolated decoder. ImageDecodeServiceTest holds the decoder to its bytes; this holds the
// URI half — a camera photo with real EXIF GPS tags and a rotation comes back upright with no metadata at all, and a URI
// whose bytes are not an image, are too many, or cannot be opened is a refusal, never a crash.

import ai.teemoon.app.imaging.ImageCaps
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ImageRejection
import ai.teemoon.app.imaging.IsolatedImageDecoder
import ai.teemoon.app.imaging.PickedImages
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickedImagesTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "camera").also { it.mkdirs() }
    private val written = ArrayList<File>()

    @After fun clean() { written.forEach { it.delete() } }

    /** A file in the camera's FileProvider path, as another app would hand it over: a content:// URI, not a path. */
    private fun uri(name: String, bytes: ByteArray): Uri {
        val f = File(dir, name).also { it.writeBytes(bytes); written += it }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    private fun hasGps(f: File) = ExifInterface(f.path).getLatLong(FloatArray(2))
    private fun contains(hay: ByteArray, needle: ByteArray) = (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test fun aGpsTaggedRotatedPhotoComesBackUprightWithNoMetadata() = runBlocking {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 200)) }
        val photo = File(dir, "photo.jpg").also { written += it }
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "38/1,43/1,20/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "9/1,8/1,22/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "TeemoonTestCam")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue("the source carries GPS", hasGps(photo))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", photo)

        val out = PickedImages.read(ctx.contentResolver, uri, IsolatedImageDecoder(ctx))
        assertTrue("decoded: $out", out is ImageDecodeFrame.Decoded)
        out as ImageDecodeFrame.Decoded
        assertEquals("image/jpeg", out.mime)
        assertEquals("rotated upright before the metadata went", 480 to 640, out.width to out.height)
        for (b in listOf(out.image, out.thumbnail)) {
            for (marker in listOf("Exif", "TeemoonTestCam")) assertTrue("$marker survived", !contains(b, marker.toByteArray()))
            val back = File(dir, "back.jpg").also { it.writeBytes(b); written += it }
            assertTrue("GPS survived", !hasGps(back))
        }
    }

    /** A JPEG's segments up to the image data: each marker's name and size (APP0 = JFIF, APP1 = Exif/XMP, COM = comment). */
    private fun segments(b: ByteArray): List<String> {
        val out = ArrayList<String>(); var i = 2
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xDA) { out += "SOS"; break }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val name = when (m) { in 0xE0..0xEF -> "APP${m - 0xE0}"; 0xFE -> "COM"; 0xDB -> "DQT"; 0xC4 -> "DHT"; in 0xC0..0xCF -> "SOF"; 0xDD -> "DRI"; else -> "FF%02X".format(m) }
            out += "$name($len)"; i += 2 + len
        }
        return out
    }

    /**
     * pm 2026-11-12 (iOS found ImageIO writing a small Exif block on its re-encode): the README says a sent photo's
     * location and camera details are removed. A camera photo with every identifying tag — make, model, software,
     * artist, dates, description, user comment, GPS — and a JPEG comment comes back, as the stored image, its thumbnail
     * AND the phone model's re-encode (OnDeviceImage), with no APP1 (Exif/XMP) and no COM segment at all — only JFIF and,
     * on the stored image, the ICC colour profile Skia writes — and ExifInterface reads none of those tags. The segments
     * are logged for the record.
     */
    @Test fun noIdentifyingTagOrSegmentSurvivesAnyReencode() = runBlocking {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 40)) }
        val photo = File(dir, "tagged.jpg").also { written += it }
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val tags = mapOf(ExifInterface.TAG_MAKE to "TeemoonMake", ExifInterface.TAG_MODEL to "TeemoonModel", ExifInterface.TAG_SOFTWARE to "TeemoonSoft",
            ExifInterface.TAG_ARTIST to "TeemoonArtist", ExifInterface.TAG_DATETIME to "2026:11:12 10:00:00", ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:12 10:00:00",
            ExifInterface.TAG_IMAGE_DESCRIPTION to "TeemoonDescription", ExifInterface.TAG_USER_COMMENT to "TeemoonComment",
            ExifInterface.TAG_GPS_LATITUDE to "38/1,43/1,20/1", ExifInterface.TAG_GPS_LATITUDE_REF to "N", ExifInterface.TAG_GPS_LONGITUDE to "9/1,8/1,22/1", ExifInterface.TAG_GPS_LONGITUDE_REF to "W")
        ExifInterface(photo.path).apply { tags.forEach { (k, v) -> setAttribute(k, v) }; saveAttributes() }
        // and a JPEG COM segment after SOI
        val src = photo.readBytes(); val com = "TeemoonCOM".toByteArray()
        photo.writeBytes(src.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, (com.size + 2).toByte()) + com + src.copyOfRange(2, src.size))
        assertTrue("the source carries the tags", ExifInterface(photo.path).getAttribute(ExifInterface.TAG_MODEL) == "TeemoonModel" && segments(photo.readBytes()).any { it.startsWith("COM") })

        val out = PickedImages.read(ctx.contentResolver, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", photo), IsolatedImageDecoder(ctx)) as ImageDecodeFrame.Decoded
        val phone = ai.teemoon.app.imaging.OnDeviceImage.prepare(out.image)!!
        for ((label, b) in listOf("image" to out.image, "thumbnail" to out.thumbnail, "phone model" to phone)) {
            val segs = segments(b)
            android.util.Log.w("teemoon", "[bench] SEGMENTS $label: ${segs.joinToString(" ")}")
            // APP2 is ICC_PROFILE when Skia writes the bitmap's colour space: log its readable strings for the record
            val app2 = String(b, Charsets.ISO_8859_1).indexOf("ICC_PROFILE")
            if (app2 >= 0) android.util.Log.w("teemoon", "[bench] ICC $label: " + Regex("[ -~]{4,}").findAll(String(b.copyOfRange(app2, minOf(b.size, app2 + 600)), Charsets.ISO_8859_1)).joinToString(" | ") { it.value })
            // APP0 (JFIF) and APP2 = ICC_PROFILE (the colour space Skia writes: primaries, white point, curves — no maker,
            // model, date or place; Pixel 10 Pro 2026-11-12) only; never APP1 (Exif/XMP), COM, or any other APPn
            assertTrue("$label carries APP1, COM or another APPn: $segs", segs.none { it.startsWith("APP1") || it.startsWith("COM") || (it.startsWith("APP") && !it.startsWith("APP0") && !it.startsWith("APP2")) })
            if (segs.any { it.startsWith("APP2") }) assertTrue("$label: an APP2 that is not a colour profile", String(b, Charsets.ISO_8859_1).contains("ICC_PROFILE"))
            val back = File(dir, "back-$label.jpg".replace(' ', '-')).also { it.writeBytes(b); written += it }
            val exif = ExifInterface(back.path)
            val left = tags.keys.mapNotNull { k -> exif.getAttribute(k)?.let { "$k=$it" } }
            android.util.Log.w("teemoon", "[bench] TAGS $label: ${left.ifEmpty { listOf("none") }.joinToString(", ")}")
            assertTrue("$label kept $left", left.isEmpty())
            for (marker in listOf("Teemoon", "Exif")) assertTrue("$label: '$marker' bytes survived", !contains(b, marker.toByteArray()))
        }
    }

    @Test fun bytesThatAreNoImageAreRefusedWhateverTheUriSays() = runBlocking {
        // A HEIC's ftyp box under a .jpg name: the name and the provider's type are not trusted, the magic number is. Since
        // 0f890941 a HEIC is read, so a HEIC header over junk is UNREADABLE (it was UNSUPPORTED_FORMAT before HEIC was in).
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(256)
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE), PickedImages.read(ctx.contentResolver, uri("fake.jpg", heic), IsolatedImageDecoder(ctx)))
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.UNSUPPORTED_FORMAT),
            PickedImages.read(ctx.contentResolver, uri("page.jpg", "<html><script>x()</script>".toByteArray()), IsolatedImageDecoder(ctx)))
    }

    @Test fun aStreamOverTheCapIsRefusedUnread() = runBlocking {
        val big = ByteArray(ImageCaps.MAX_BYTES + 1024).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        assertEquals(ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_BYTES), PickedImages.read(ctx.contentResolver, uri("big.jpg", big), IsolatedImageDecoder(ctx)))
    }

    @Test fun aUriThatCannotBeOpenedIsARefusalNotACrash() = runBlocking {
        val gone = Uri.parse("content://${ctx.packageName}.files/camera/never-written.jpg")
        assertTrue(PickedImages.read(ctx.contentResolver, gone, IsolatedImageDecoder(ctx)) is ImageDecodeFrame.Rejected)
        assertTrue(PickedImages.read(ctx.contentResolver, Uri.parse("content://no.such.provider/x"), IsolatedImageDecoder(ctx)) is ImageDecodeFrame.Rejected)
    }
}
