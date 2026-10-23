// ports: teemoon/Chat/ReplySupersession.swift @ 14b2974
package ai.teemoon.app

/**
 * Think Harder's old reply, while the new one is on its way. In memory only: the old reply stays in the store until the
 * new one is saved, so a failed, stopped or killed Think Harder leaves the chat as it was.
 *
 * Immutable, where the Swift struct mutates: the stream writes it from the transport's thread while the screen reads
 * it, so every change is a new value (ChatViewModel's `_state.update` rule).
 */
data class ReplySupersession(
    /** The replies after the question being re-asked. */
    val replyIDs: Set<String> = emptySet(),
    /** The new reply has shown text: the old one leaves the transcript. */
    val swapped: Boolean = false,
) {
    val isActive: Boolean get() = replyIDs.isNotEmpty()

    /** Shown at reduced opacity while the new reply has no text yet. */
    fun isDimmed(id: String): Boolean = !swapped && id in replyIDs

    /** The transcript's rows: the old reply until the swap, then not. */
    fun visible(messages: List<ChatMessage>): List<ChatMessage> =
        if (swapped) messages.filter { it.id !in replyIDs } else messages

    /** What the model is sent: never the reply it is replacing. */
    fun history(messages: List<ChatMessage>): List<ChatMessage> =
        if (isActive) messages.filter { it.id !in replyIDs } else messages

    /** The first visible text of the new reply. */
    fun noteText(text: String): ReplySupersession =
        if (!isActive || swapped || text.isEmpty()) this else copy(swapped = true)

    sealed interface Outcome {
        /** Delete these and save the new reply in the same save. */
        data class Replace(val replyIDs: Set<String>) : Outcome
        /** Keep the old reply as it is; do not save a partial new one. */
        data object Restore : Outcome
    }

    /** null when no Think Harder was in flight. */
    fun outcome(failed: Boolean, cancelled: Boolean, output: String): Outcome? {
        if (!isActive) return null
        if (failed || cancelled || output.isBlank()) return Outcome.Restore
        return Outcome.Replace(replyIDs)
    }
}
