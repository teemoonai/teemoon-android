package ai.teemoon.app.imaging

import ai.teemoon.app.BuildConfig
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * The image decoder, in an isolated process (manifest: `android:isolatedProcess="true"`, `android:process=":decode"`,
 * not exported): its own UID, no permissions, no app files, no network. the maintainer, 2026-10-25: "I dont want parsing errors to
 * throw access to process." Untrusted bytes are parsed only here: by the memory-safe decoder (Wuffs), or — for a picked
 * or pasted WebP or HEIC only (the maintainer 2026-11-11) — by Android's ImageDecoder; a crash or an exploit in either ends
 * here, and the app gets a placeholder (IsolatedImageDecoder).
 *
 * Out comes only what this process ENCODED from pixels: JPEG, or PNG when the image has transparency. No byte of the
 * input — and so no EXIF, XMP or ICC profile, no camera GPS — survives into the result.
 */
class ImageDecodeService : Service() {
    private val binder = object : IImageDecodeService.Stub() {
        override fun decode(input: ParcelFileDescriptor, output: ParcelFileDescriptor, maxLongSide: Int, thumbLongSide: Int): Int {
            val frame = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(input).use { decodeFrame(readCapped(it), maxLongSide, thumbLongSide) }
            }.getOrElse { ImageDecodeFrame.Rejected(ImageRejection.DECODER_FAILED) }
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(output).use { ImageDecodeFrame.write(frame, it) } }
            return (frame as? ImageDecodeFrame.Rejected)?.why?.ordinal ?: -1
        }

        override fun crashForTest() {
            if (BuildConfig.DEBUG) android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        /** At most one byte over the cap, so an oversized input is known as such without being held whole. */
        internal fun readCapped(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > ImageCaps.MAX_BYTES) return null
            }
            return out.toByteArray()
        }

        internal fun decodeFrame(bytes: ByteArray?, maxLongSide: Int, thumbLongSide: Int): ImageDecodeFrame {
            if (bytes == null) return ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_BYTES)
            val format = ImageFormat.sniff(bytes)
                ?: return PlatformImageFormat.sniff(bytes)?.let { platformFrame(bytes, it, maxLongSide, thumbLongSide) }
                    ?: ImageDecodeFrame.Rejected(ImageRejection.UNSUPPORTED_FORMAT)
            if (!WuffsImages.available) return ImageDecodeFrame.Rejected(ImageRejection.DECODER_FAILED)
            // Header first: a "bomb" declaring 100k × 100k is refused here, before a byte is allocated for its pixels.
            val dims = WuffsImages.probe(bytes) ?: return ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)
            val (w, h) = dims[0] to dims[1]
            if (w <= 0 || h <= 0) return ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)
            if (w > ImageCaps.MAX_SIDE || h > ImageCaps.MAX_SIDE || w.toLong() * h > ImageCaps.MAX_PIXELS) return ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_PIXELS)
            val rgba = WuffsImages.decodeRgba(bytes, w, h) ?: return ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)
            if (rgba.size.toLong() != w.toLong() * h * 4) return ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)

            // From here on everything is pixels this process produced: no parser of the input is involved.
            var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
            if (format == ImageFormat.JPEG) bmp = oriented(bmp, ExifOrientation.of(bytes))
            return reencoded(bmp, format != ImageFormat.JPEG && hasTransparency(rgba), maxLongSide, thumbLongSide)
        }

        /**
         * A WebP, HEIC, AVIF or TIFF (PlatformImageFormat; the maintainer 2026-11-11, iOS S42) through Android's ImageDecoder,
         * here in the isolated process. The decoder's own detected type must be the sniffed family (a lying container is
         * refused), and the header's size is checked against the same caps before a pixel is allocated; the bitmap is decoded
         * in software at the stored size, and the output is re-encoded from pixels as Wuffs' is: no byte of the input,
         * no EXIF or XMP, survives. ImageDecoder applies the orientation itself. Any failure, including a HEIC this
         * process's codecs can't open, is the "couldn't be read" note — never the input passed through.
         */
        private fun platformFrame(bytes: ByteArray, format: PlatformImageFormat, maxLongSide: Int, thumbLongSide: Int): ImageDecodeFrame {
            var why = ImageRejection.UNREADABLE
            val decoded = try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                    if (!format.matches(info.mimeType)) { why = ImageRejection.UNSUPPORTED_FORMAT; throw IllegalStateException("not $format") }
                    val (w, h) = info.size.width to info.size.height
                    if (w <= 0 || h <= 0) throw IllegalStateException("no size")
                    if (w > ImageCaps.MAX_SIDE || h > ImageCaps.MAX_SIDE || w.toLong() * h > ImageCaps.MAX_PIXELS) {
                        why = ImageRejection.TOO_MANY_PIXELS; throw IllegalStateException("over the pixel cap")
                    }
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val (tw, th) = ImageCaps.targetSize(w, h, maxLongSide)
                    d.setTargetSize(tw, th)
                }
            } catch (_: Exception) { return ImageDecodeFrame.Rejected(why) }
            // HDR or wide-gamut sources can come back as F16; the encoders below take 8-bit RGBA.
            val bmp = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
                ?: return ImageDecodeFrame.Rejected(ImageRejection.UNREADABLE)
            val alpha = bmp.hasAlpha() && IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }.any { (it ushr 24) != 0xFF }
            return reencoded(bmp, alpha, maxLongSide, thumbLongSide)
        }

        /** Pixels this process holds, at the stored size and as a thumbnail: JPEG, or PNG when [alpha]. */
        private fun reencoded(bmp: Bitmap, alpha: Boolean, maxLongSide: Int, thumbLongSide: Int): ImageDecodeFrame {
            val (tw, th) = ImageCaps.targetSize(bmp.width, bmp.height, maxLongSide)
            val stored = if (tw == bmp.width && th == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, tw, th, true)
            val image = encode(stored, png = alpha)
            val (sw, sh) = ImageCaps.targetSize(stored.width, stored.height, thumbLongSide)
            val small = Bitmap.createScaledBitmap(stored, sw, sh, true)
            val thumb = encode(if (alpha) onWhite(small) else small, png = false)
            return ImageDecodeFrame.Decoded(if (alpha) "image/png" else "image/jpeg", stored.width, stored.height, image, thumb)
        }

        private fun hasTransparency(rgba: ByteArray): Boolean {
            var i = 3
            while (i < rgba.size) { if (rgba[i] != 0xFF.toByte()) return true; i += 4 }
            return false
        }

        /** EXIF orientations 2–8 as a transform of the stored pixels, so the upright photo is what gets re-encoded. */
        private fun oriented(b: Bitmap, o: Int): Bitmap {
            if (o == 1) return b
            val m = Matrix()
            when (o) {
                2 -> m.setScale(-1f, 1f)
                3 -> m.setRotate(180f)
                4 -> m.setScale(1f, -1f)
                5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.setRotate(90f)
                7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.setRotate(-90f)
            }
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }

        private fun onWhite(b: Bitmap): Bitmap =
            Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) } }

        private fun encode(b: Bitmap, png: Boolean): ByteArray = ByteArrayOutputStream().also {
            b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, ImageCaps.JPEG_QUALITY, it)
        }.toByteArray()
    }
}
