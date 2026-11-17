package ai.teemoon.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * IMAGES.md §4b: what the on-device model is given — one image per message, at most 768 px on the long side (the
 * model's own preprocessing size; larger only costs memory). Made from teemoon's stored image, which the isolated
 * decoder already re-encoded: these are our own bytes, so the platform decoder may read them here.
 */
object OnDeviceImage {
    const val LONG_SIDE = 768

    fun prepare(stored: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(stored, 0, stored.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(stored, 0, stored.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (w, h) = ImageCaps.targetSize(bmp.width, bmp.height, LONG_SIDE)
        val scaled = if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, ImageCaps.JPEG_QUALITY, it) }.toByteArray()
    }
}
