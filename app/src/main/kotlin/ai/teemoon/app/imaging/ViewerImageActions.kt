// ports: teemoon/Views/Chat/MessageImages.swift @ 8d41474c (ZoomableImage: the double tap and the zoom bounds)
// android-only: the viewer's "save" (review item android-viewer-save-zoom, the maintainer 2026-11-07)
package ai.teemoon.app.imaging

/** iOS ZoomableImage: a pinch stays within 1× and 6×; a double tap goes to 2.5×, or back to 1× from any zoom. */
object ViewerZoom {
    const val MAX = 6f
    const val DOUBLE_TAP = 2.5f
    fun clamp(scale: Float) = scale.coerceIn(1f, MAX)
    fun doubleTap(scale: Float) = if (scale > 1f) 1f else DOUBLE_TAP
}

/**
 * Where the viewer's "save" puts an image: Pictures/teemoon through MediaStore, which needs no storage permission at
 * minSdk 29. An imported image's id ("near:c:u#0") is not a file name, so only letters, digits, _ and - are kept.
 * The bytes are the stored image — a JPEG or PNG our isolated decoder wrote — under its own type.
 */
data class ImageSave(val displayName: String, val mimeType: String, val relativePath: String) {
    companion object {
        const val RELATIVE_PATH = "Pictures/teemoon"
        /** A brief toast once the gallery took the image (the maintainer 2026-11-07); a failed save shows nothing new. */
        fun toastAfter(saved: Boolean): String? = if (saved) ImageCopy.SAVED else null
        fun of(id: String, mime: String) = ImageSave("teemoon-" + id.replace(Regex("[^A-Za-z0-9_-]"), "-") + "." + when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; else -> "jpg" }, mime, RELATIVE_PATH)
    }
}

/**
 * Writes [bytes] to the gallery as [save]: pending while written, so the gallery never shows half an image; a failed
 * write deletes its row and answers null.
 */
fun ImageSave.write(resolver: android.content.ContentResolver, bytes: ByteArray): android.net.Uri? {
    val m = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(m, values) ?: return null
    return try {
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null); null
    }
}
