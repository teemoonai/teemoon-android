// ports: teemoon/Chat/ChatViewModel.swift @ a27f0621 (composerAfterReturn only)
//
// reply-machine.json composerReturn: where a question lands when the still-checking ask is declined on a fresh send
// (ReplyEffect.ReturnQuestionToComposer). iOS keeps this as a static on ChatViewModel; it touches no view state, so
// here it is a pure function in :chat, generic over the composer's image type, with the bytes as the identity.
package ai.teemoon.chat

object ComposerReturn {
    data class Result<I>(val text: String, val images: List<I>, val overLimit: Boolean)

    /**
     * Question first, a blank line, then the draft; every image kept, the draft's only where its bytes are new.
     * [Result.overLimit] is reported, never acted on here — no image is ever dropped.
     */
    fun <I> afterReturn(
        questionText: String, questionImages: List<I>,
        draftText: String, draftImages: List<I>,
        limit: Int, bytes: (I) -> ByteArray,
    ): Result<I> {
        // Swift: trimmingCharacters(in: .whitespacesAndNewlines).isEmpty — isBlank, as in ReplyMachine.Live.hasText.
        val text = if (draftText.isBlank()) questionText else questionText + "\n\n" + draftText
        val images = questionImages.toMutableList()
        for (image in draftImages) {
            val data = bytes(image)
            if (images.none { bytes(it).contentEquals(data) }) images += image
        }
        return Result(text, images, images.size > limit)
    }
}
