// android-only: the viewer's "share" for the current page's image (iOS ImageViewer's ShareLink, MessageImages.swift @ 85451a58).
package ai.teemoon.app.imaging

/**
 * What the share sheet is handed for one image. A content URI as EXTRA_STREAM alone gave a sheet with no preview (the
 * maintainer's Pixel, 2026-11-07): the Sharesheet previews it only when the intent also carries the URI as ClipData
 * whose description names the image's MIME type, with a read grant (FileProvider, so nothing leaves our files but this
 * one). It is the plain system sheet on every Android: copy and save are the long-press menu's (ImageContextMenu), not
 * the sheet's (the maintainer 2026-11-12: "copy and share dont belong in the share view").
 */
data class ImageShare(val action: String, val type: String, val stream: String, val clip: String?, val grantRead: Boolean,
                      val clipMimeTypes: List<String> = emptyList()) {
    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        fun of(contentUri: String, mime: String): ImageShare =
            ImageShare(ACTION_SEND, mime, contentUri, clip = contentUri, grantRead = true, clipMimeTypes = listOf(mime))
    }
}

/** The share sheet for [share]: the intent with its ClipData and read grant, wrapped in the system chooser, nothing added. */
fun ImageShare.chooser(): android.content.Intent {
    val uri = android.net.Uri.parse(stream)
    val send = android.content.Intent(action).setType(type).putExtra(android.content.Intent.EXTRA_STREAM, uri)
    // the ClipData names the image's type: a raw-URI ClipData carries none, and the Pixel's chooser then showed no preview
    clip?.let { send.clipData = android.content.ClipData(android.content.ClipDescription(null, clipMimeTypes.toTypedArray()), android.content.ClipData.Item(android.net.Uri.parse(it))) }
    if (grantRead) send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return android.content.Intent.createChooser(send, null)
}

/**
 * The viewer's "copy" (review item android-viewer-copy, the maintainer 2026-11-07): AOSP's chooser offers Copy only
 * for text, so an image is copied here. The clip is the image's content URI under its own MIME type; the clipboard
 * grants the pasting app read access to it, and nothing else of ours.
 */
data class ImageClip(val label: String, val mimeTypes: List<String>, val uri: String) {
    companion object { fun of(contentUri: String, mime: String): ImageClip = ImageClip("image", listOf(mime), contentUri) }
}

fun ImageClip.clipData(): android.content.ClipData =
    android.content.ClipData(android.content.ClipDescription(label, mimeTypes.toTypedArray()), android.content.ClipData.Item(android.net.Uri.parse(uri)))
