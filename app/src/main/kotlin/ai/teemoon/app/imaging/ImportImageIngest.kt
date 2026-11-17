package ai.teemoon.app.imaging

import ai.teemoon.chat.AttachmentFiles
import ai.teemoon.chat.imports.ImageIngest
import ai.teemoon.chat.imports.IngestedImage
import java.security.MessageDigest

/**
 * The import's images (IMAGES.md §3): a `data:` payload through the gate (ImageGate) and the isolated decoder, and the
 * re-encoded result into a pending file. Nothing the export carried is stored as it came — only pixels the decoder
 * produced — and nothing is fetched: a remote URL never reaches here (the source passes no payload for one).
 */
class ImportImageIngest(private val decoder: ImageDecoderPort, private val files: AttachmentFiles) : ImageIngest {
    override suspend fun ingest(payload: String, attachmentId: String): IngestedImage? {
        val bytes = (DataUrl.decode(payload) as? DataUrl.Result.Bytes)?.bytes ?: return null
        val out = decoder.decode(bytes) as? ImageDecodeFrame.Decoded ?: return null
        val ext = if (out.mime == "image/png") "png" else "jpg"
        val pending = files.newPending(ext).apply { writeBytes(out.image) }
        return IngestedImage(out.mime, out.width, out.height, out.thumbnail, pending, fileName(attachmentId, ext))
    }

    companion object {
        /** The app's source encoder, for device tests that build their own writer. */
        val encodeSourcesForTest = ai.teemoon.app.ChatImportRunner.encodeSources

        /** A row's file: the id hashed, so no key character (`:`, `#`, `/`) ever becomes part of a path. */
        fun fileName(attachmentId: String, ext: String): String =
            MessageDigest.getInstance("SHA-256").digest(attachmentId.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + "." + ext
    }
}
