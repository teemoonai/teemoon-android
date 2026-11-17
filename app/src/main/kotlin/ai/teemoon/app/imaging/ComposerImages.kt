// spec: IMAGES.md draft 3 §4 (teemoon-ios 67b39a4, ChatViewModel.addImages): what the composer keeps of a pick.
package ai.teemoon.app.imaging

/** The composer's picked images after a pick or a photo, and the note it shows (null: none). */
object ComposerImages {
    data class Added(val kept: List<ImageDecodeFrame.Decoded>, val note: String?)

    /**
     * Adds [got] to [current], at most [limit] in all ([ImageCaps.perSend]). One past the limit says so with the limit that
     * applied; an unreadable one says so unless the limit already did (iOS: the too-many note wins).
     */
    /**
     * The composer already holds more than [limit] (a question returned to it, or a switch to a place with a lower
     * limit): every image is kept, this note shows, and send waits until enough are removed — never dropped (pm, 2026-11-06).
     */
    fun overLimitNote(count: Int, limit: Int): String? = if (count > limit) ImageCopy.upTo(limit) else null

    /**
     * Whether send is enabled: something to send, nothing over the limit, and — the maintainer 2026-11-14, after the
     * pending-photo prototype, "Yes" — no photo still being read or decoded ([pending]): a send in that window went out
     * without the photo, which then landed in the emptied composer.
     */
    /**
     * How many more a pick or a paste may bring: the limit less the photos in AND the ones still on their way (iOS's design
     * review: a paste showed five tiles with one slot left, then four vanished).
     */
    fun room(limit: Int, ready: Int, pending: Int): Int = (limit - ready - pending).coerceAtLeast(0)

    fun canSend(promptBlank: Boolean, picked: Int, pending: Int, limit: Int): Boolean =
        pending == 0 && (!promptBlank || picked > 0) && overLimitNote(picked, limit) == null

    fun add(current: List<ImageDecodeFrame.Decoded>, got: List<ImageDecodeFrame>, limit: Int): Added {
        val kept = current.toMutableList(); var note: String? = null
        for (frame in got) when (frame) {
            is ImageDecodeFrame.Decoded -> if (kept.size < limit) kept += frame else note = ImageCopy.upTo(limit)
            is ImageDecodeFrame.Rejected -> note = note ?: ImageCopy.UNREADABLE
        }
        return Added(kept, note)
    }
}
