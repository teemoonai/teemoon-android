// spec: teemoon-ios docs/SHARE_IN.md @ 1f60fe9c (the maintainer 2026-11-12, "I want in 1.1.0")
package ai.teemoon.app.imaging

/**
 * Share to teemoon: what another app's share sheet hands over. A single near.ai export (application/json) is the
 * import, as before (IncomingExport); photos, text and a URL become a draft in a new chat, never sent. Android opens
 * teemoon straight into that chat, so iOS's "saved to teemoon. open teemoon to finish your message." has no place here;
 * the over-cap and couldn't-read lines are the composer's note in the new chat, the caption paste already uses.
 */
object ShareIn {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /**
     * The caps, checked before anything is read: 10 images, each at most 20 MB as shared, 100,000 characters. 20 MB is
     * the picker's and the paste's own cap (ImageCaps.MAX_BYTES, which readCapped enforces anyway): at 50 MB a 30 MB
     * photo passed this check and then landed as "couldn't read" (pm for the maintainer, 2026-11-14).
     */
    const val MAX_IMAGES = 10
    const val MAX_BYTES_AS_SHARED = ImageCaps.MAX_BYTES.toLong()
    const val MAX_TEXT = 100_000

    /** DRAFT strings, card share-to-teemoon. */
    const val OVER_CAP = "that's more than teemoon can take at once: up to 10 photos, 20 MB each."
    const val UNREADABLE = "teemoon couldn't read what was shared."
    /** Over the text cap alone (pm 2026-11-14, DRAFT): the photo line named caps the share never met. */
    const val TEXT_OVER_CAP = "that's more text than teemoon can take at once: up to 100,000 characters."
    /**
     * Some photos landed and [n] didn't (pm 2026-11-14, DRAFT): the whole-share line read as if nothing arrived. In the
     * composer note's own voice, as "this image couldn't be read" is: "image", no full stop.
     */
    fun notRead(n: Int): String = if (n == 1) "1 image couldn't be read" else "$n images couldn't be read"

    enum class Kind { IMPORT, DRAFT, NONE }

    /**
     * What an intent is: the import first (one content URI of application/json, VIEW or SEND — IncomingExport.accepts),
     * then a draft for SEND / SEND_MULTIPLE of images or text, else nothing (movies, PDFs, a launch).
     */
    /**
     * A shared stream teemoon will open: another app's content URI only. teemoon reads a share with its OWN access, so a
     * `file://` path (its private files, by path) or a URI of its own provider (`<package>.files`: attachments, camera
     * shots) would hand the sender teemoon's files as a draft. [IncomingExport.accepts] already takes `content` only.
     */
    fun readableStream(scheme: String?, authority: String?, ownPackage: String): Boolean =
        scheme == "content" && !authority.isNullOrEmpty() && authority != ownPackage && !authority.startsWith("$ownPackage.")

    fun kind(action: String?, type: String?, streams: Int, scheme: String?, hasText: Boolean): Kind {
        if (streams <= 1 && action != ACTION_SEND_MULTIPLE && ai.teemoon.app.IncomingExport.accepts(action, type, scheme)) return Kind.IMPORT
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return Kind.NONE
        val mime = type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            streams > 0 && mime.startsWith("image/") -> Kind.DRAFT
            streams == 0 && hasText && mime.startsWith("text/") -> Kind.DRAFT
            else -> Kind.NONE
        }
    }

    /** A share teemoon was offered but can't use (an image SEND with no stream): it says so in a new chat, not silence. */
    fun unusable(action: String?, kind: Kind): Boolean = (action == ACTION_SEND || action == ACTION_SEND_MULTIPLE) && kind == Kind.NONE

    /** Over any cap: nothing is kept ([overCapDraft]). An unknown size is not held against the share. */
    fun overCap(images: Int, sizes: List<Long?>, textLength: Int): Boolean =
        images > MAX_IMAGES || sizes.any { (it ?: 0L) > MAX_BYTES_AS_SHARED } || textLength > MAX_TEXT

    /** What lands in the new chat's composer: the text (a URL as text), the images that decoded, in order, and a note. */
    data class Draft(val text: String, val images: List<ImageDecodeFrame.Decoded>, val note: String?,
                     /** Photos still being read when the chat opens: a tile each until [later] brings them. */
                     val pending: Int = 0, val later: kotlinx.coroutines.Deferred<List<ImageDecodeFrame>>? = null)

    fun overCapDraft(note: String = OVER_CAP): Draft = Draft("", emptyList(), note)

    /** Which cap a share went over, as its note. */
    fun overCapNote(images: Int, sizes: List<Long?>, textLength: Int): String =
        if (images > MAX_IMAGES || sizes.any { (it ?: 0L) > MAX_BYTES_AS_SHARED }) OVER_CAP else TEXT_OVER_CAP

    /** What [unusable] lands: an empty new chat that says it couldn't read what was shared. */
    fun unusableDraft(): Draft = Draft("", emptyList(), UNREADABLE)

    /** The decoded images kept in order; any that couldn't be read leave the note (the rest still land). */
    fun draft(text: String, frames: List<ImageDecodeFrame>): Draft {
        val decoded = frames.filterIsInstance<ImageDecodeFrame.Decoded>()
        val missed = frames.size - decoded.size
        return Draft(text, decoded, when { missed == 0 -> null; decoded.isEmpty() -> UNREADABLE; else -> notRead(missed) })
    }
}
