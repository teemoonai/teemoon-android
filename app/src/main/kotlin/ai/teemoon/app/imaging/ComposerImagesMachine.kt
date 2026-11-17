// spec: teemoon-ios docs/IMAGES.md §4d @ 5722f76d (composer images as one state machine; the maintainer 2026-11-11: "+"
// always opens the attach sheet, a paste always attaches) and conformance/composerimages.json (kit 0.7.0), the contract
package ai.teemoon.app.imaging

/**
 * The composer's images as one state machine (IMAGES.md §4d, the maintainer 2026-11-11, after his Pixel lost attached
 * images on a model switch). The composer runs the effects in the order given; it decides nothing about images itself.
 * Invariants the kit's generator asserts: images leave only by remove or a send (with them, or "send without images"),
 * never on a model switch; images are sent only to a model that reads them. "+" always opens the attach sheet — on a
 * model that can't see images it says so there and offers the where sheet filtered to ones that can — and a paste
 * always attaches. See ComposerImagesKitTest, which holds every cell to iOS's table.
 */
object ComposerImagesMachine {
    enum class State(val key: String) { EMPTY("empty"), ATTACHED("attached") }

    enum class Event(val key: String) {
        TAP_PLUS_SEES("tapPlus/sees"), TAP_PLUS_BLIND("tapPlus/blind"),
        /** The attach sheet's "choose an image-capable model" (DRAFT), on a model that can't see images. */
        ATTACH_SHEET_CHOOSE_MODEL("attachSheetChooseModel"),
        PICKED("picked"), PASTE("paste"), REMOVE_ONE("removeOne"), REMOVE_LAST("removeLast"),
        /** Any switch, the where sheet's pick included: it never touches the images. */
        MODEL_SWITCHED("modelSwitched"),
        /** The where chip: with images attached the sheet opens on "reads images", a soft filter. */
        TAP_WHERE_CHIP("tapWhereChip"),
        SEND_SEES("send/sees"), SEND_BLIND("send/blind"), ALERT_CHOOSE_MODEL("alertChooseModel"),
        /** The send-refused alert's "send without images" (DRAFT), shown only when there is text. */
        SEND_WITHOUT_IMAGES("sendWithoutImages"),
        /** A declined still-checking question comes back with every image it had. */
        QUESTION_RETURNED("questionReturned"),
    }

    enum class Effect(val key: String) {
        OPEN_ATTACH_SHEET("openAttachSheet"),
        /** The attach sheet with "<model> can't see images." and "choose an image-capable model" at its top. */
        OPEN_ATTACH_SHEET_CANT_SEE("openAttachSheet(cantSee)"),
        CLOSE_ATTACH_SHEET("closeAttachSheet"),
        OPEN_WHERE("openWhere(readsImages)"),
        /** The where sheet as the chip opens it with nothing attached: tab all, no filter. */
        OPEN_WHERE_PLAIN("openWhere"),
        ATTACH("attach"), REMOVE("remove"), REMOVE_ALL("removeAll"), SEND_IMAGES("sendImages"), SEND_TEXT("sendText"),
        SHOW_REFUSED_ALERT("showRefusedAlert"),
    }

    data class Step(val to: State, val effects: List<Effect>)

    fun next(state: State, event: Event): Step {
        val has = state == State.ATTACHED
        val stay = Step(state, emptyList())
        return when (event) {
            Event.TAP_PLUS_SEES -> Step(state, listOf(Effect.OPEN_ATTACH_SHEET))
            Event.TAP_PLUS_BLIND -> Step(state, listOf(Effect.OPEN_ATTACH_SHEET_CANT_SEE))
            // the attach sheet goes first, then where opens filtered; a pick there is a model switch
            Event.ATTACH_SHEET_CHOOSE_MODEL -> Step(state, listOf(Effect.CLOSE_ATTACH_SHEET, Effect.OPEN_WHERE))
            Event.PICKED, Event.PASTE, Event.QUESTION_RETURNED -> Step(State.ATTACHED, listOf(Effect.ATTACH))
            Event.REMOVE_ONE -> if (has) Step(State.ATTACHED, listOf(Effect.REMOVE)) else stay
            Event.REMOVE_LAST -> if (has) Step(State.EMPTY, listOf(Effect.REMOVE)) else stay
            Event.MODEL_SWITCHED -> stay
            Event.TAP_WHERE_CHIP -> Step(state, listOf(if (has) Effect.OPEN_WHERE else Effect.OPEN_WHERE_PLAIN))
            Event.SEND_SEES -> if (has) Step(State.EMPTY, listOf(Effect.SEND_IMAGES)) else stay
            Event.SEND_BLIND -> if (has) Step(State.ATTACHED, listOf(Effect.SHOW_REFUSED_ALERT)) else stay
            Event.ALERT_CHOOSE_MODEL -> if (has) Step(State.ATTACHED, listOf(Effect.OPEN_WHERE)) else stay
            Event.SEND_WITHOUT_IMAGES -> if (has) Step(State.EMPTY, listOf(Effect.REMOVE_ALL, Effect.SEND_TEXT)) else stay
        }
    }
}

/**
 * The effects that open a sheet over the composer: the keyboard goes first, so the sheet rises over the composer and
 * focus can't come back to the field as the send-refused alert closes (the maintainer 2026-11-12, found on iOS: the
 * composer and keyboard showed for a moment before the where sheet).
 */
object SheetFocus {
    fun dropsKeyboard(effect: ComposerImagesMachine.Effect): Boolean = effect in SHEETS
    private val SHEETS = setOf(ComposerImagesMachine.Effect.OPEN_ATTACH_SHEET, ComposerImagesMachine.Effect.OPEN_ATTACH_SHEET_CANT_SEE,
        ComposerImagesMachine.Effect.OPEN_WHERE, ComposerImagesMachine.Effect.OPEN_WHERE_PLAIN)
}

/**
 * What a send with the composer's images fires into the machine before anything leaves: send/blind on a model that
 * can't see them, which keeps them and shows the refused alert (the maintainer's Pixel, 2026-11-12: the composer cleared
 * first and the view model refused after, so the images and the text were lost behind the alert, and its "choose an
 * image-capable model" opened nothing). null: no images, an ordinary send.
 */
object ComposerSend {
    fun event(hasImages: Boolean, cantSee: Boolean): ComposerImagesMachine.Event? = when {
        !hasImages -> null
        cantSee -> ComposerImagesMachine.Event.SEND_BLIND
        else -> ComposerImagesMachine.Event.SEND_SEES
    }
}
