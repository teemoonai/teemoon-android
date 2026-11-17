package ai.teemoon.app.imaging

import java.util.Base64

/**
 * IMAGES.md §4, the last-request view: each image in a request body reads `image · jpeg · 412 KB · 1536×2048` — never its
 * base64, on screen or in the copied text. The bytes it measures are our own re-encoded JPEG/PNG (only those are ever
 * sent), and only their headers are read, by hand.
 */
object ImageBodyText {
    private val dataUrl = Regex(""""data:image/([a-z]+);base64,([A-Za-z0-9+/=]+)"""")

    fun summarize(body: String): String = dataUrl.replace(body) { m ->
        val bytes = runCatching { Base64.getDecoder().decode(m.groupValues[2]) }.getOrNull()
        val (w, h) = bytes?.let(::size) ?: (0 to 0)
        "\"" + ImageCopy.requestLine(m.groupValues[1], bytes?.size ?: 0, w, h) + "\""
    }

    /** Width and height from a PNG's IHDR or a JPEG's SOF marker; null when neither is found. */
    internal fun size(b: ByteArray): Pair<Int, Int>? {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        if (b.size >= 24 && ImageFormat.sniff(b) == ImageFormat.PNG) return u32(16) to u32(20)
        if (ImageFormat.sniff(b) != ImageFormat.JPEG) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xC3) return u16(i + 7) to u16(i + 5)
            if (marker == 0xD9 || marker == 0xDA) return null
            i += 2 + u16(i + 2)
        }
        return null
    }
}
