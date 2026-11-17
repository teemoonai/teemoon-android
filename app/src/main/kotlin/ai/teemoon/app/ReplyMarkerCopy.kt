// reply-markers (the maintainer approved on the review page, 2026-11-06, exactly as written): the words for
// conformance/reply-machine.json's marker ids. The ids are the kit's; the words live here and nowhere else.
package ai.teemoon.app

import ai.teemoon.chat.ReplyMarker

object ReplyMarkerCopy {
    const val DIDNT_FINISH = "this reply didn't finish."
    const val STOPPED = "you stopped this reply."
    const val CUT_SHORT = "this reply was cut short."
    const val DECRYPT_FAILED = "this reply couldn't be decrypted, so it isn't shown."

    // sent-before-confirmed-mark (the maintainer approved on the review page, 2026-11-06, exact): a reply sent sealed while
    // near.ai's hardware was still being checked; a pass removes the mark.
    const val SENT_WHILE_CHECKING = "sent while near.ai's hardware was still being checked"
    const val CHECK_FAILED_AFTER_SEND = "near.ai's hardware failed its check after this was sent. treat this reply as not private."

    fun text(id: String): String? = when (id) {
        ai.teemoon.chat.SentBeforeConfirmed.Mark.SENT_WHILE_CHECKING.rawValue -> SENT_WHILE_CHECKING
        ai.teemoon.chat.SentBeforeConfirmed.Mark.CHECK_FAILED_AFTER_SEND.rawValue -> CHECK_FAILED_AFTER_SEND
        ReplyMarker.DIDNT_FINISH.rawValue -> DIDNT_FINISH
        ReplyMarker.STOPPED.rawValue -> STOPPED
        ReplyMarker.CUT_SHORT.rawValue -> CUT_SHORT
        ReplyMarker.DECRYPT_FAILED.rawValue -> DECRYPT_FAILED
        else -> null
    }

    /** Only "didn't finish" offers retry: the turn never produced its answer (reply-markers). */
    fun offersRetry(id: String?): Boolean = id == ReplyMarker.DIDNT_FINISH.rawValue
}
