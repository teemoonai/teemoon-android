// spec: the paste rule shared with iOS (pm for the maintainer, 2026-11-07; iOS ChatComposer.imagePasteDestination), and
// IMAGES.md §4c a data: URL as text (iOS ec43cc58) and an image item's URL is not text (iOS 1af3cd79; the maintainer
// 2026-11-08 both): a
// pasted image enters the composer as a picked one does.
package ai.teemoon.app.imaging

/**
 * What the composer takes from a paste. A clip that names no image type is text, and the field's as before. Otherwise
 * its image items' URIs, in the clip's order, are read as picked photos are (PickedImages: the bytes' magic number
 * decides — JPEG, PNG or GIF — never the clip's word for the type; anything else gets the unreadable note). One past the
 * room left is read, so the composer says "up to n images per message" as a pick does; the rest are never read.
 *
 * IMAGES.md §4c, an image item's URL is not text (the maintainer 2026-11-08, "behave like Messages does"; iOS 1af3cd79):
 * when the strings a paste would put in, joined as the field joins them, are once trimmed one URL (`http://`, `https://`
 * or `data:`, any case, no blank inside), every image item attaches and no string goes in — not even a text-only
 * item's URL beside it (the maintainer 2026-11-09, "operate like messages app"). Any other text beside an image (words, a URL and words, two URLs) is text, and nothing is
 * attached. Only a paste reaches this (the field's content receiver): typing, autocorrect, suggestions and dictation
 * never read the clipboard.
 */
object ComposerPaste {
    /** One clip item: its URI and its text. An image item's URI is the image; a `data:` or web URI is a URL, not one. */
    data class Item(val uri: String?, val text: String?) {
        val isImage: Boolean get() = uri != null && URL_SCHEMES.none { uri.startsWith(it, ignoreCase = true) }

        /** What the field would put in for this item. */
        val string: String? get() = text ?: uri?.takeUnless { isImage }
    }

    private val URL_SCHEMES = listOf("http://", "https://", "data:")

    /** The image items' URIs, in clip order, when this paste attaches them; empty when it is text. */
    @Suppress("UNUSED_PARAMETER")
    fun images(clipMimeTypes: List<String>, items: List<Item>): List<String> {
        val images = items.filter { it.isImage }.mapNotNull { it.uri }
        val strings = items.mapNotNull { it.string }
        // "any images copied to clipboard should work" (the maintainer 2026-11-09; iOS S42, trip.com): the clip's types
        // don't decide — an app's own type, octet-stream or a file manager's is read like image/*, beside its URL too.
        // The bytes decide, and anything that isn't an image gets the note. A paste is never silent.
        return if (strings.isEmpty() || isUrl(strings.joinToString("\n"))) images else emptyList()
    }

    fun imageUris(clipMimeTypes: List<String>, items: List<Item>, room: Int): List<String> =
        images(clipMimeTypes, items).take(maxOf(0, room) + 1)

    /**
     * When [images] attach, the text-only items' strings as one `data:` URL, or null: the data-URL rule holds for any
     * text a paste would put in (ios 2026-11-08), so that URL attaches after the image items and is never text.
     */
    fun dataUrlBeside(items: List<Item>): String? =
        items.filter { !it.isImage }.mapNotNull { it.string }.takeIf { it.isNotEmpty() }?.joinToString("\n")?.let(::soleDataUrl)

    /** Once trimmed, one URL: `http://`, `https://` or `data:` in any case, and no blank inside. */
    fun isUrl(text: String): Boolean {
        val t = text.trim()
        return t.none { it.isWhitespace() } && URL_SCHEMES.any { t.startsWith(it, ignoreCase = true) }
    }

    /**
     * IMAGES.md §4c, a `data:` URL as text (the maintainer 2026-11-08; iOS ec43cc58): a browser can copy an image as its
     * data URL in plain text. When the text a paste would put in is, once trimmed, one `data:` URL and nothing else (no
     * blank inside, a comma after its header; scheme and type in any case), it is never text. The scheme comes back
     * lowercased for [DataUrl], whose header check is the import's own. Text with a data URL among other words stays text.
     */
    fun soleDataUrl(text: String): String? {
        val t = text.trim()
        if (t.length < 5 || !t.regionMatches(0, "data:", 0, 5, ignoreCase = true)) return null
        if (t.any { it.isWhitespace() } || ',' !in t) return null
        return "data:" + t.substring(5)
    }

    /**
     * The clip's one data URL, if that is all its text says: each item's text, else a `data:` URI item (a browser's URL
     * representation), the same URL however many items carry it. An image beside it is not attached; the URL's is.
     */
    fun dataUrl(itemTexts: List<String?>, itemUris: List<String?>): String? {
        val pieces = itemTexts.indices.mapNotNull { i ->
            itemTexts[i] ?: itemUris.getOrNull(i)?.takeIf { it.regionMatches(0, "data:", 0, 5, ignoreCase = true) }
        }.map { it.trim() }.distinct()
        return pieces.singleOrNull()?.let(::soleDataUrl)
    }

    /**
     * The URL's bytes through the import's data-URL gate (image/jpeg|png|gif;base64, the 20 MiB cap, the magic number
     * matching the declared type), or null: any other data URL "couldn't be read" and no byte of it reaches a decoder.
     */
    fun dataUrlBytes(url: String): ByteArray? = (DataUrl.decode(url) as? DataUrl.Result.Bytes)?.bytes
}
