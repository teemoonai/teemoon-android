package ai.teemoon.app.imaging

/**
 * A JPEG's EXIF orientation (1–8), read so it can be APPLIED before re-encoding strips the metadata — otherwise a phone
 * photo taken upright is stored sideways (IMAGES.md review, 2026-10-25). The memory-safe decoder returns pixels as
 * stored and does not read EXIF, so this does: a few hundred bytes of segment and IFD walking, every offset bounds
 * checked against the buffer, and 1 (as stored) for anything malformed. It runs inside the isolated process too.
 */
object ExifOrientation {
    fun of(jpeg: ByteArray): Int = runCatching { read(jpeg) }.getOrNull()?.takeIf { it in 1..8 } ?: 1

    private fun read(b: ByteArray): Int? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return null          // end of image, start of scan: no EXIF before it
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            if (marker == 0xE1) exif(b, i + 4, len - 2)?.let { return it }
            i += 2 + len
        }
        return null
    }

    /** An APP1 payload: "Exif\0\0", a TIFF header, IFD0; the orientation tag is 0x0112, a SHORT. */
    private fun exif(b: ByteArray, start: Int, length: Int): Int? {
        val end = start + length
        if (length < 14 || end > b.size) return null
        if (!(b[start] == 'E'.code.toByte() && b[start + 1] == 'x'.code.toByte() && b[start + 2] == 'i'.code.toByte() &&
                b[start + 3] == 'f'.code.toByte() && b[start + 4] == 0.toByte() && b[start + 5] == 0.toByte())) return null
        val tiff = start + 6
        val little = when {
            b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
            b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(at: Int): Int? = if (at < tiff || at + 2 > end) null else if (little)
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long? = if (at < tiff || at + 4 > end) null else {
            val x = (0 until 4).map { (b[at + it].toLong() and 0xFF) }
            if (little) x[0] or (x[1] shl 8) or (x[2] shl 16) or (x[3] shl 24) else (x[0] shl 24) or (x[1] shl 16) or (x[2] shl 8) or x[3]
        }
        if (u16(tiff + 2) != 42) return null
        val ifd0 = u32(tiff + 4) ?: return null
        if (ifd0 < 8 || tiff + ifd0 >= end) return null
        val entries = tiff + ifd0.toInt()
        val count = u16(entries) ?: return null
        for (k in 0 until minOf(count, 512)) {
            val e = entries + 2 + k * 12
            if (u16(e) == 0x0112) return if (u16(e + 2) == 3) u16(e + 8) else null
        }
        return null
    }
}
