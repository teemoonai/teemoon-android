package ai.teemoon.app.imaging

import android.content.ContentResolver
import android.net.Uri

/**
 * What the composer does with a picked photo (IMAGES.md §4; audit's picker contract). The system Photo Picker, or the
 * documents UI it falls back to on a phone without Play, hands over a content URI — any app could have put any bytes
 * behind it, so nothing about it is trusted: not the type it claims, not the file name, not the container. This reads at
 * most 20 MiB + 1 of its bytes through the content resolver (no parsing in this process) and hands them to the isolated
 * decoder, which keeps JPEG, PNG, GIF and BMP (Wuffs) or WebP and HEIC (ImageDecoder, the maintainer 2026-11-11) by magic
 * number and returns pixels it re-encoded — so a photo's EXIF, XMP or ICC, and the GPS position a camera writes there,
 * never reach the store or a provider. Anything else — a video, a document — is refused with [ImageCopy.UNREADABLE].
 */
object PickedImages {
    suspend fun read(resolver: ContentResolver, uri: Uri, decoder: ImageDecoderPort): ImageDecodeFrame {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { ImageDecodeService.readCapped(it) } }.getOrNull()
            ?: return ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_BYTES)
        return decoder.decode(bytes)
    }

    /**
     * A paste's images: every URI's bytes read first, at once — a clip's read grant can end when the clipboard changes,
     * and decoding ten images one by one would leave the last ones waiting on it — then each decoded in clip order.
     */
    suspend fun readPasted(resolver: ContentResolver, uris: List<Uri>, decoder: ImageDecoderPort): List<ImageDecodeFrame> {
        val bytes = uris.map { uri -> runCatching { resolver.openInputStream(uri)?.use { ImageDecodeService.readCapped(it) } }.getOrNull() }
        return bytes.map { b -> b?.let { decoder.decode(it) } ?: ImageDecodeFrame.Rejected(ImageRejection.TOO_MANY_BYTES) }
    }
}
