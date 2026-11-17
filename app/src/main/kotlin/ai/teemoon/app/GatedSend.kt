// The message a composer send was gated on: iOS ChatView.generateBypassing → performGenerate(acceptedDegrade:), which
// reads the composer at the tap. That is the bug pm's state-machine sweep found on both (Tier 3, "send anyway re-sends
// the current draft"): this holds the message instead.
package ai.teemoon.app

/**
 * What the composer held when the send gate raised its question. "send anyway" sends THIS — the prompt and the picked
 * images the question was about — never whatever the composer holds by the time the answer comes. Reading the
 * composer at the tap sent a draft typed meanwhile, and the Android port never sent the picked images at all.
 */
data class GatedSend<I>(val prompt: String, val images: List<I>) {
    /** The composer after the send: emptied if it still holds the gated message, as a plain send leaves it; a draft typed since is kept. */
    fun promptAfter(current: String): String = if (current == prompt) "" else current
    fun imagesAfter(current: List<I>): List<I> = if (current == images) emptyList() else current

    companion object {
        /**
         * What "send anyway" sends: the gated message, else the composer — where a fresh question a Re-verify declined
         * went back (iOS generateBypassing → performGenerate). Null: nothing to send.
         */
        fun <I> orComposer(gated: GatedSend<I>?, prompt: String, images: List<I>): GatedSend<I>? =
            gated ?: GatedSend(prompt, images).takeIf { prompt.isNotBlank() || images.isNotEmpty() }
    }
}
