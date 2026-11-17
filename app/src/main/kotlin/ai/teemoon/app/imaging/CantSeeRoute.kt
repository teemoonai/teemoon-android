// spec: teemoon-ios docs/IMAGES.md §4d @ ab914c3e (the can't-see state's words, 1.1.0 image flow; the maintainer 2026-11-11)
package ai.teemoon.app.imaging

/**
 * The can't-see state's words (IMAGES.md §4d @ teemoon-ios 5722f76d). What happens is ComposerImagesMachine's: "+"
 * always opens the attach sheet, which on a model that can't see images says so and offers the where sheet filtered to
 * models that can; a paste always attaches; the send-refused alert offers the same where sheet, or the text alone.
 */
object CantSeeRoute {
    /** DRAFT (5722f76d): the attach sheet's top line on a model that can't see images, with the chip's model name. */
    fun sheetLine(model: String?): String = "${model ?: "this model"} can't see images."
    /** ChatUiState.imageRefusal's value while the send-refused alert is up (its words are [alertTitle] / [alertMessage]). */
    const val REFUSED = "refused"
    /** DRAFT (the maintainer 2026-11-11, replacing "switch to …"): the alert's first button, which opens the filtered sheet. */
    const val CHOOSE = "choose an image-capable model"
    /** The alert's last button (teemoon-ios e350ce46, the maintainer 2026-11-12; it read "ok"). */
    const val CANCEL = "cancel"
    /**
     * The send-refused alert, short (the maintainer 2026-11-12, via pm; all DRAFT, behaviour unchanged): the title names
     * the model, the message says the message wasn't sent, "send text only" only when there is text.
     */
    fun alertTitle(model: String?): String = "${model ?: "this model"} can't see images"
    fun alertMessage(hasText: Boolean): String =
        if (hasText) "your message wasn't sent. pick a model that can, or send just the text." else "your message wasn't sent. pick a model that can."
    /**
     * IMAGES.md §4b (the maintainer 2026-11-12, run time): a phone model this phone can never give the memory images need
     * (or whose image turn the OS killed here) — the permanent line, never "close some apps". DRAFT
     * (wherefilter.json phoneImages.refusals.permanent): "use <smaller model>" only where a smaller phone model fits.
     */
    fun permanent(model: String?): String = "this phone doesn't have enough memory for images with ${model ?: "this model"}."
    fun useSmaller(model: String): String = "use $model"
    const val OK = "ok"
    const val ALERT_CHOOSE = "choose model"
    const val ALERT_SEND_TEXT = "send text only"
    /** The alert's buttons, top to bottom (choose model first, the main one). */
    fun alertButtons(hasText: Boolean): List<String> = listOfNotNull(ALERT_CHOOSE, ALERT_SEND_TEXT.takeIf { hasText }, CANCEL)
}
