// spec: IMAGES.md draft 2 §4: the last-request view shows each image as "image · type · size · w×h", never base64 — on
// screen and in the copied text.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageBodyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageBodyTextTest {
    private val png1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /** A baseline JPEG header with an SOF0 of 1536 × 2048, padded to 400 KB. */
    private fun jpeg(): ByteArray {
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (2048 shr 8).toByte(), (2048 and 0xFF).toByte(), (1536 shr 8).toByte(), (1536 and 0xFF).toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0) + sof + ByteArray(400 * 1024)
    }

    @Test fun anImageReadsAsItsTypeSizeAndDimensionsNeverItsBase64() {
        val b64 = Base64.getEncoder().encodeToString(jpeg())
        val body = """{"messages":[{"content":[{"text":"what is this?","type":"text"},{"image_url":{"url":"data:image/jpeg;base64,$b64"},"type":"image_url"}],"role":"user"}]}"""
        val shown = ImageBodyText.summarize(body)
        assertTrue(shown, shown.contains(""""url":"image · jpeg · 400 KB · 1536×2048""""))
        assertFalse("base64 survived", shown.contains(b64.take(40)))
        assertTrue(shown.contains("what is this?"))
    }

    @Test fun aPngReadsItsHeaderAndATinyOneIsOneKilobyte() {
        assertEquals(""""image · png · 1 KB · 1×1"""", ImageBodyText.summarize(""""data:image/png;base64,$png1x1""""))
    }

    @Test fun aBodyWithoutImagesIsUnchanged() {
        val body = """{"messages":[{"content":"hello","role":"user"}],"model":"m"}"""
        assertEquals(body, ImageBodyText.summarize(body))
    }
}
