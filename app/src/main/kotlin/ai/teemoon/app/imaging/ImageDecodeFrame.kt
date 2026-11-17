package ai.teemoon.app.imaging

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The one message the isolated decoder writes back: a status, then — on success — the re-encoded image and its
 * thumbnail. Read with every length checked against the caps: the reader does not trust the other process either.
 */
sealed interface ImageDecodeFrame {
    data class Decoded(val mime: String, val width: Int, val height: Int, val image: ByteArray, val thumbnail: ByteArray) : ImageDecodeFrame {
        override fun equals(other: Any?) = other is Decoded && mime == other.mime && width == other.width && height == other.height &&
            image.contentEquals(other.image) && thumbnail.contentEquals(other.thumbnail)
        override fun hashCode() = 31 * width + height
    }
    data class Rejected(val why: ImageRejection) : ImageDecodeFrame

    companion object {
        private const val MAGIC = 0x7E1D_EC0D
        /** A re-encoded 2048-px image is a few MB; anything claiming more is not a frame this decoder wrote. */
        private const val MAX_OUT = ImageCaps.MAX_BYTES

        fun write(frame: ImageDecodeFrame, out: OutputStream) {
            val d = DataOutputStream(out.buffered())
            d.writeInt(MAGIC)
            when (frame) {
                is Decoded -> {
                    d.writeInt(-1); d.writeUTF(frame.mime); d.writeInt(frame.width); d.writeInt(frame.height)
                    d.writeInt(frame.image.size); d.write(frame.image); d.writeInt(frame.thumbnail.size); d.write(frame.thumbnail)
                }
                is Rejected -> d.writeInt(frame.why.ordinal)
            }
            d.flush()
        }

        fun read(input: InputStream): ImageDecodeFrame = runCatching {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != MAGIC) return Rejected(ImageRejection.DECODER_FAILED)
            val code = d.readInt()
            if (code >= 0) return ImageRejection.entries.getOrNull(code)?.let(::Rejected) ?: Rejected(ImageRejection.DECODER_FAILED)
            val mime = d.readUTF().takeIf { it == "image/jpeg" || it == "image/png" } ?: return Rejected(ImageRejection.DECODER_FAILED)
            val w = d.readInt(); val h = d.readInt()
            if (w !in 1..ImageCaps.MAX_LONG_SIDE || h !in 1..ImageCaps.MAX_LONG_SIDE) return Rejected(ImageRejection.DECODER_FAILED)
            val n = d.readInt(); if (n !in 1..MAX_OUT) return Rejected(ImageRejection.DECODER_FAILED)
            val image = ByteArray(n).also { d.readFully(it) }
            val t = d.readInt(); if (t !in 1..MAX_OUT) return Rejected(ImageRejection.DECODER_FAILED)
            val thumb = ByteArray(t).also { d.readFully(it) }
            Decoded(mime, w, h, image, thumb)
        }.getOrElse { Rejected(ImageRejection.DECODER_FAILED) }
    }
}
