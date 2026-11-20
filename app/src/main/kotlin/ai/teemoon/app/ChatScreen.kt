package ai.teemoon.app

import ai.teemoon.inference.ReasoningEffort
import android.os.SystemClock
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.teemoon.chat.Arrival
import ai.teemoon.chat.SendPrep
import ai.teemoon.chat.TranscriptItemsPolicy
import ai.teemoon.confidential.TrustSendPolicy
import ai.teemoon.inference.LlmError
import ai.teemoon.providers.WhereLocality
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The chip's level: the one a Think Harder request sent while it generates, else the pick — and only where a level shows
 * at all ([whereLevel] is null with developer mode off, or after a refusal), so Think Harder never adds one (the maintainer 2026-11-14).
 */
internal fun shownChipLevel(whereLevel: String?, thinkHarderLevel: String?): String? = whereLevel?.let { thinkHarderLevel ?: it }


/**
 * Whether the transcript scrolls itself: the open's landing window and the pin to the end (both effects below). Snapshots
 * turn it off: both run on wall-clock time (a second's window, 200 ms passes), so a single frame caught the transcript
 * unscrolled, part-scrolled or pinned from run to run (DesignSnapshotTest.chat_transcript_dark). The app always scrolls.
 */
val LocalAutoScrollsTranscript = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    title: String = "teemoon",
    /** The trust chip and the send gate read this; null when no provider is configured. */
    trust: TrustSnapshot? = null,
    /** An empty thread focuses the prompt (ContentView); snapshots pass false — a focus request starts a handler thread the JVM renderer cannot. */
    focusOnEmpty: Boolean = true,
    /** Bumped by the root when a sheet closes: iOS re-focuses an empty thread's prompt each time the chat is back in front. */
    focusEpoch: Int = 0,
    hasProvider: Boolean = true,
    /** Any setup at all, equipped or not (iOS `!providerStore.providers.isEmpty`): the first-run welcome's half. */
    hasAnySetup: Boolean = true,
    /** The active setup will send a key, or needs none (ProviderCredentials.hasCredential): false → "no api key". */
    hasKey: Boolean = true,
    onBack: () -> Unit = {},
    /** The gear: the app settings. Null hides it (snapshots). */
    onOpenSettings: (() -> Unit)? = null,
    /** The where chip above the composer: the Where sheet. */
    onOpenWhere: (() -> Unit)? = null,
    /** What the where chip and the title glyph say: the active model, its place, and where it runs. */
    whereModel: String? = null, wherePlace: String? = null, whereLocality: WhereLocality? = null,
    whereProgress: Double? = null, whereNeedsDownload: Boolean = false,
    /** ChatView.arrivingWaitingLabel: the active download is parked waiting for wi-fi (CellularDownloadGate.waitingLabel). */
    whereProgressLabel: String? = null,
    /** The chip's long-press menu (ChatView chipMenuItems, iOS 835d7c8); null when no model is equipped. */
    onModelInfo: (() -> Unit)? = null, onCopyModelID: (() -> Unit)? = null,
    /** The provider answers one question at a time: a "starts fresh" rule above every follow-up question. */
    singleTurn: Boolean = false,
    onOpenTrust: () -> Unit = {},
    onRetryVerification: () -> Unit = {},
    /** Debug surfaces sit behind developer mode, as on iOS. */
    developerMode: Boolean = false,
    /** Developer mode's reasoning level for the where chip (ReasoningUi.chipLevel); null: the name alone. */
    whereLevel: String? = null,
    /** What another app shared (ShareIn): the new chat's draft, taken once — never sent. */
    sharedDraft: ai.teemoon.app.imaging.ShareIn.Draft? = null,
    onSharedDraftTaken: () -> Unit = {},
    webSearchState: WebSearchChipState = WebSearchChipState.DISABLED,
    onWebSearchChip: () -> Unit = {},
    /** False when the selected model can't take tools: the composer's web chip hides (COMPOSER_PANEL.md). */
    modelTakesTools: Boolean = true,
    /** [imageDownloadOffered]: the paperclip offers the general file for images; [onImageDownload] starts it. */
    imageDownloadOffer: Boolean = false,
    onImageDownload: () -> Unit = {},
    /** Where a failing request's user should go: (url, display name), or null. */
    consoleRecovery: (LlmError) -> Pair<String, String>? = { null },
    /** A 400 with the search tool attached is probably the tool: turn grounding off and ask again. Null when grounding was not attached. */
    onDisableWebSearchAndRetry: (() -> Unit)? = null,
    groundingAttached: Boolean = false,
    onCopy: (String) -> Unit = {},
    /** A search hit to land on (TranscriptDeepLink): one-shot, scrolled to once the messages are in. */
    scrollToMessageId: String? = null,
    /** The chats setting; a send confirms with a tick (Android's confirm feedback, iOS's impact). */
    hapticsEnabled: Boolean = false,
    /**
     * IMAGES.md §4: opens the system photo picker for up to [max] images and returns each as the isolated decoder answered
     * (PickedImages). Null: no paperclip (snapshots, which have no activity to launch a picker from).
     */
    pickImages: (suspend (max: Int, chosen: (Int) -> Unit) -> List<ai.teemoon.app.imaging.ImageDecodeFrame>)? = null,
    /** The viewer's share: the user's own choice to send an image somewhere. Null: no share button. */
    onShareImage: ((ChatImage) -> Unit)? = null,
    onCopyImage: ((ChatImage) -> Unit)? = null,
    onSaveImage: ((ChatImage) -> Unit)? = null,
    /** IMAGES.md §4a: the system camera, its photo decoded in the isolated process. Null: no "take photo" (no camera app). */
    takePhoto: (suspend (taken: () -> Unit) -> ai.teemoon.app.imaging.ImageDecodeFrame?)? = null,
    /** A paste's image URIs, read at once (a clip's grant can end with the clip) and decoded as picked photos are. */
    pasteImages: (suspend (List<android.net.Uri>) -> List<ai.teemoon.app.imaging.ImageDecodeFrame>)? = null,
    /** A pasted data: URL through the import's data-URL gate and the isolated decoder (IMAGES.md §4c). */
    pasteDataUrl: (suspend (String) -> ai.teemoon.app.imaging.ImageDecodeFrame)? = null,
    /**
     * "choose an image-capable model" (IMAGES.md §4d, 1.1.0), from the attach sheet or the send-refused alert, and the
     * where chip with images attached: the where sheet at half size with "reads images" on. A pick there is a switch.
     */
    onChooseImageModel: () -> Unit = {},
    /** IMAGES.md §4b: the place is a phone model this phone can never fit for images; its refusal is the permanent line. */
    phoneImagesNever: Boolean = false,
    /** A smaller phone model that fits, by name, and switching to it: the permanent refusal's "use <smaller model>". */
    smallerPhoneModel: Pair<String, () -> Unit>? = null,
    /** Where the title band ends and the composer panel starts, in root pixels: the edge tests' regions. */
    onEdges: ((titleBottomPx: Int, panelTopPx: Int) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    // On the MAIN dispatcher, explicitly: the stream writes the view model's state from the transport's thread, and a
    // collector resumed there recomposes off the main thread. The app's own frame clock always dispatched to main; the
    // v1 Compose test rule's unconfined dispatcher did not, and crashed the app twice a suite on the Pixel
    // ("performMeasureAndLayout called during measure layout", 2026-10-05).
    val uiState by vm.state.collectAsStateWithLifecycle(context = Dispatchers.Main)
    // The transcript's rows (ChatView: `llm.superseding.visible(...)`): Think Harder's old reply until the new one shows
    // text, then not. Everything below reads the visible rows, so the follow and the reserve see what is on screen.
    val state = remember(uiState) { uiState.superseding.takeIf { it.swapped }?.let { s -> uiState.copy(messages = s.visible(uiState.messages)) } ?: uiState }
    // The draft survives leaving the chat for the trust screen or a sheet (iOS keeps it in the view's state; a
    // Compose route change would otherwise recreate this screen and drop it).
    // a share's text is the field's first value, not a frame later (no flash of an empty field; ShareIn)
    var prompt by rememberSaveable { mutableStateOf(sharedDraft?.text ?: "") }
    // Picked images, already decoded and re-encoded in the isolated process; their thumbnails are our own bytes.
    // on the view model, so a font-size rebuild keeps them as rememberSaveable keeps the text (ChatViewModel.draftImages)
    var picked by vm.draftImagesState
    var imageNote by vm.draftImageNoteState
    /** Photos on their way in (ChatViewModel.draftPending): a placeholder tile each; send waits (the maintainer 2026-11-14). */
    val pending by vm.draftPendingState
    // a share's draft is the strip's FIRST value (see the share block below): set before the thumbnails are read
    remember(sharedDraft) { sharedDraft?.let { d -> picked = d.images; imageNote = d.note; vm.pendingStarted(d.pending) } }
    /** The viewer (IMAGES.md §5): a message's images and the one tapped. */
    var viewing by remember { mutableStateOf<Pair<List<ChatImage>, Int>?>(null) }
    val pickScope = rememberCoroutineScope()
    val reverifyScope = rememberCoroutineScope()
    // one bitmap per picked image, kept while it stays picked: the composer keys its thumbnails by them, so only a new one
    // arrives with the motion and a removed one leaves with it (IMAGES.md §4d)
    val thumbCache = remember { java.util.IdentityHashMap<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded, androidx.compose.ui.graphics.ImageBitmap>() }
    val thumbs = remember(picked) {
        thumbCache.keys.retainAll { k -> picked.any { it === k } }
        picked.mapNotNull { f -> thumbCache[f] ?: android.graphics.BitmapFactory.decodeByteArray(f.thumbnail, 0, f.thumbnail.size)?.asImageBitmap()?.also { thumbCache[f] = it } }
    }
    /** 10 to a cloud model, 4 on this phone until the probe (ImageCaps.perSend; iOS ChatComposer.imageLimit). */
    val imageLimit = ai.teemoon.app.imaging.ImageCaps.perSend(onDevice = whereLocality == WhereLocality.PHONE)
    /** Null when images can be added now; otherwise what the attach control says instead (the limit; §4d keeps it). */
    fun attachBlocked(): String? = if (picked.size + pending >= imageLimit) ai.teemoon.app.imaging.ImageCopy.upTo(imageLimit) else null
    fun cantSeeNow(): Boolean = !vm.imagesAllowed()
    /**
     * What an attach brings lands in ONE write with its tiles going (ChatViewModel.pendingLanded); a read that never
     * finishes (this screen went away mid-read) takes its tiles away rather than leaving send off for good.
     */
    suspend fun land(pendingN: Int, frames: suspend () -> List<ai.teemoon.app.imaging.ImageDecodeFrame>, note: (List<ai.teemoon.app.imaging.ImageDecodeFrame>) -> String? = { null }) {
        var landed = false
        try { val got = frames(); vm.pendingLanded(pendingN, got, imageLimit, note(got)); landed = true } finally { if (!landed) vm.pendingDropped(pendingN) }
    }
    // a share lands here as the draft: the text in the field, the photos in the strip, any note; nothing is sent. What it
    // already holds is the strip's FIRST value, as the text is the field's (set in a LaunchedEffect it came a frame after
    // the first draw, and the chat played a photo's arrival; MotionCheckShareTest). Photos still being read are a tile
    // each from that frame and land as they are read, with the share's own note (the maintainer 2026-11-14: the chat opens at
    // once, not after the decode).
    val shareScope = rememberCoroutineScope()
    LaunchedEffect(sharedDraft) {
        val d = sharedDraft ?: return@LaunchedEffect
        prompt = d.text
        d.later?.let { later -> shareScope.launch { land(d.pending, { later.await() }) { got -> ai.teemoon.app.imaging.ShareIn.draft(d.text, got).note } } }
        onSharedDraftTaken()
    }
    var offerImageDownload by remember { mutableStateOf(false) }
    /** The machine's state: whether images are attached. */
    fun imagesState(): ai.teemoon.app.imaging.ComposerImagesMachine.State = if (picked.isEmpty()) ai.teemoon.app.imaging.ComposerImagesMachine.State.EMPTY else ai.teemoon.app.imaging.ComposerImagesMachine.State.ATTACHED
    val attachPresenter = LocalAttachPresenter.current
    val attachFocus = LocalFocusManager.current
    // fire() as the latest composition has it, for what comes back later — a pick — and for the attach sheet's buttons,
    // which fire() itself presents (local functions can't call ahead)
    val latestFire = remember { arrayOfNulls<(ai.teemoon.app.imaging.ComposerImagesMachine.Event, suspend () -> List<ai.teemoon.app.imaging.ImageDecodeFrame>, Int) -> Unit>(1) }
    /** The camera or the photo picker; what comes back is the machine's "picked". */
    fun pickThen(take: suspend (chosen: (Int) -> Unit) -> List<ai.teemoon.app.imaging.ImageDecodeFrame>) {
        attachBlocked()?.let { imageNote = it; return }
        pickScope.launch {
            // the tiles go up the moment the count is known (the picker closed, the shutter fired), before any read
            var n = 0
            val got = try { take { c -> n = c; vm.pendingStarted(c) } } catch (e: Throwable) { vm.pendingDropped(n); throw e }
            latestFire[0]?.invoke(ai.teemoon.app.imaging.ComposerImagesMachine.Event.PICKED, { got }, n) ?: vm.pendingDropped(n)
        }
    }
    fun attach() { val pick = pickImages ?: return; pickThen { chosen -> pick(ai.teemoon.app.imaging.ComposerImages.room(imageLimit, picked.size, pending), chosen) } }
    fun capture() { val take = takePhoto ?: return; pickThen { chosen -> listOfNotNull(take { chosen(1) }) } }
    /**
     * The attach sheet (IMAGES.md §4d): take photo, choose photos; full, it says so there. On a model that can't see
     * images it says so at its top, with "choose an image-capable model". The keyboard goes first: the sheet rises over
     * the composer, not the keyboard. With no root to present it (a test), the picker as before.
     */
    fun presentAttachSheet(cantSee: Boolean) {
        if (pickImages == null) return
        val present = attachPresenter ?: return attach()
        attachFocus.clearFocus()
        present(AttachSheetRequest(ai.teemoon.app.imaging.ImageCopy.attachSheet(hasCamera = takePhoto != null), attachBlocked(),
            act = { if (it == ai.teemoon.app.imaging.ImageCopy.TAKE_PHOTO) capture() else attach() },
            // a phone model this phone can never fit can see images; the phone can't hold them (IMAGES.md §4b)
            cantSee = if (!cantSee) null else if (phoneImagesNever) ai.teemoon.app.imaging.CantSeeRoute.permanent(whereModel) else ai.teemoon.app.imaging.CantSeeRoute.sheetLine(whereModel),
            chooseModel = { latestFire[0]?.invoke(ai.teemoon.app.imaging.ComposerImagesMachine.Event.ATTACH_SHEET_CHOOSE_MODEL, { emptyList() }, 0) }))
    }
    /**
     * One event through the machine (ComposerImagesMachine, IMAGES.md §4d): the table gives the next state and the effects,
     * and the composer runs them in that order — it decides nothing about images itself. [frames] reads what an attach
     * brings; [removeAt] is the image a remove names; [returned] is a returned question's images, all kept.
     */
    fun fire(event: ai.teemoon.app.imaging.ComposerImagesMachine.Event, frames: suspend () -> List<ai.teemoon.app.imaging.ImageDecodeFrame> = { emptyList() },
             removeAt: Int = -1, returned: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>? = null, pendingN: Int = 0) {
        val step = ai.teemoon.app.imaging.ComposerImagesMachine.next(imagesState(), event)
        if (step.effects.any(ai.teemoon.app.imaging.SheetFocus::dropsKeyboard)) attachFocus.clearFocus()
        for (effect in step.effects) when (effect) {
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.OPEN_ATTACH_SHEET -> presentAttachSheet(cantSee = false)
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.OPEN_ATTACH_SHEET_CANT_SEE -> presentAttachSheet(cantSee = true)
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.CLOSE_ATTACH_SHEET -> {}   // the sheet's own button closes it before it fires (AttachSheet)
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.OPEN_WHERE -> { imageNote = null; onChooseImageModel() }
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.OPEN_WHERE_PLAIN -> onOpenWhere?.invoke()
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.ATTACH -> if (returned != null) picked = returned else pickScope.launch { land(pendingN, frames) }
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.REMOVE -> { picked = picked.filterIndexed { k, _ -> k != removeAt }; imageNote = null }
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.REMOVE_ALL -> { picked = emptyList(); imageNote = null }
            // the send itself goes through the send gate as every send does: the caller sends after the event
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.SEND_IMAGES, ai.teemoon.app.imaging.ComposerImagesMachine.Effect.SEND_TEXT -> {}
            // the alert drawn below (ChatViewModel.imageRefusal); the images stay in the composer behind it
            ai.teemoon.app.imaging.ComposerImagesMachine.Effect.SHOW_REFUSED_ALERT -> vm.refuseImages()
        }
    }
    latestFire[0] = { e, frames, n -> fire(e, frames, pendingN = n) }
    /** The "+": always the attach sheet; on a model that can't see images it says so at the sheet's top. */
    fun openAttach() {
        if (pickImages == null) return
        if (imageDownloadOffer) { offerImageDownload = true; return }
        fire(if (cantSeeNow()) ai.teemoon.app.imaging.ComposerImagesMachine.Event.TAP_PLUS_BLIND else ai.teemoon.app.imaging.ComposerImagesMachine.Event.TAP_PLUS_SEES)
    }
    /** A pasted image enters as a picked one (the shared paste rule), on any model: send decides, not the paste. */
    fun pasted(mimeTypes: List<String>, items: List<ai.teemoon.app.imaging.ComposerPaste.Item>, dataUrl: String?) {
        val read = pasteImages ?: return
        val room = ai.teemoon.app.imaging.ComposerImages.room(imageLimit, picked.size, pending)
        val take = ai.teemoon.app.imaging.ComposerPaste.imageUris(mimeTypes, items, room)
        if (take.isEmpty()) return
        if (imageDownloadOffer) { offerImageDownload = true; return }
        attachBlocked()?.let { imageNote = it; return }
        // the image items, then the data: URL beside them, in one read so they land in item order; the URL is read only
        // while there is room (one past it already says "up to n images per message")
        val readUrl = pasteDataUrl?.takeIf { dataUrl != null && take.size <= room }
        val n = take.size + (if (readUrl != null) 1 else 0)
        vm.pendingStarted(n)
        fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.PASTE, frames = { read(take.map(android.net.Uri::parse)) + listOfNotNull(readUrl?.invoke(dataUrl!!)) }, pendingN = n)
    }
    /** A pasted data: URL is a picked photo too (IMAGES.md §4c). */
    fun pastedDataUrl(url: String) {
        val read = pasteDataUrl ?: return
        if (imageDownloadOffer) { offerImageDownload = true; return }
        attachBlocked()?.let { imageNote = it; return }
        vm.pendingStarted(1)
        fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.PASTE, frames = { listOf(read(url)) }, pendingN = 1)
    }
    var gate by remember { mutableStateOf<SendPrep?>(null) }
    // The gates the user raises, and what a Re-verify does when its wait ends — only for the gate it was tapped on.
    val reverifyGate = remember { ReverifyGate() }
    /** The gate above was raised by the RETRY glyph, not the composer: its bypass re-asks the question. */
    var gatedRetry by remember { mutableStateOf(false) }
    /** The gated retry is a Think Harder: the effort survives the confirm (iOS ChatView.pendingRetryEffort). */
    var gatedThinkHarder by remember { mutableStateOf(false) }
    /** The composer's message the gate above was raised for: its bypass sends this, not a draft typed since (GatedSend). */
    var gatedSend by remember { mutableStateOf<GatedSend<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>?>(null) }
    // a retry from the error card or the search offer found no place: the same alert the composer shows
    LaunchedEffect(state.needsPlace) { if (state.needsPlace) { gatedRetry = true; gate = SendPrep.BLOCKED_NO_PROVIDER; vm.placeRequestShown() } }
    // Still checking at the settle timeout, the seal in hand: the still-checking question over the question just asked;
    // "send anyway" asks it again (near-send-gate-rule, iOS hardwareConfirmRequested).
    // A declined still-checking question comes back (reply-machine.json composerReturn): the question first, a blank line,
    // then whatever was typed since; every image kept — over the limit, the note shows and send waits.
    LaunchedEffect(state.returnedQuestion) {
        val q = state.returnedQuestion ?: return@LaunchedEffect
        val back = ai.teemoon.chat.ComposerReturn.afterReturn(q.text, q.images, prompt, picked, imageLimit) { it.image }
        prompt = back.text
        fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.QUESTION_RETURNED, returned = back.images)
        vm.returnedQuestionTaken()
    }
    LaunchedEffect(state.hardwareConfirmRequested) { if (state.hardwareConfirmRequested) { gatedRetry = true; gatedThinkHarder = false; gate = SendPrep.CONFIRM_E2EE } }
    val focus = LocalFocusManager.current
    // Weights still arriving for the equipped place — iOS's ChatView.arrival, which it passes to
    // prepareSend at BOTH call sites as `arrival != nil`. A fraction means a pull is in flight; no
    // fraction with needsDownload means the file is not on the device at all. Copy is data: the
    // alert below renders Arrival.alertMessage verbatim and composes nothing.
    val arrival: Arrival? = whereModel?.let { name ->
        when {
            whereProgress != null -> Arrival(name, whereProgress, Arrival.Kind.PHONE)
            whereNeedsDownload -> Arrival(name, null, Arrival.Kind.PHONE)
            else -> null
        }
    }
    // a confirm already given for this record and reason doesn't ask again (near-send-gate-rule)
    val sendPolicy = trust?.composerPolicy ?: TrustSendPolicy.ALLOW

    // Every entry point that can start a generation consults the session's ONE send policy.
    var imageRecheckRunning by remember { mutableStateOf(false) }
    fun attemptSend() {
        // over the place's image limit nothing is sent and nothing dropped: the note says how many (pm, 2026-11-06)
        if (ai.teemoon.app.imaging.ComposerImages.overLimitNote(picked.size, imageLimit) != null) return
        // images to a model that can't see them: the machine keeps them and raises the alert; nothing is cleared or sent
        val imageEvent = ai.teemoon.app.imaging.ComposerSend.event(picked.isNotEmpty(), cantSeeNow())
        // before the alert, a home model's own machine is asked once (iOS 6218fbf5): a yes sends; a second refusal while
        // it is asked goes straight to the alert, never a loop. The composer machine is unchanged.
        if (imageEvent == ai.teemoon.app.imaging.ComposerImagesMachine.Event.SEND_BLIND && !imageRecheckRunning) {
            imageRecheckRunning = true
            pickScope.launch { val sees = vm.recheckImages(); imageRecheckRunning = false; if (sees) attemptSend() else fire(imageEvent) }
            return
        }
        imageEvent?.let { e -> fire(e); if (e == ai.teemoon.app.imaging.ComposerImagesMachine.Event.SEND_BLIND) return }
        // A message of images alone is not an empty prompt: every other gate still applies to it.
        val prep = SendPrep.prepare(prompt.ifBlank { if (picked.isNotEmpty()) "image" else prompt }, hasProvider = hasProvider, isDownloading = arrival != null, trust = sendPolicy, hasKey = hasKey)
        // The field clears on send (ChatInputBar); the draft survives a gate so a cancelled send loses nothing.
        // ChatView.performGenerate: `isPromptFocused = false` after the send — the keyboard leaves and the reply has the screen
        // a fresh send retires any older question's kept retry (iOS 88eb704e): a later "send anyway" never resends it
        if (prep == SendPrep.READY) { gatedRetry = false; gatedSend = null; vm.send(prompt, images = picked); prompt = ""; picked = emptyList(); imageNote = null; focus.clearFocus(); if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.Confirm) } else if (prep != SendPrep.BLOCKED_EMPTY_PROMPT) { gatedRetry = false; gatedSend = GatedSend(prompt, picked); gate = prep; reverifyGate.raised() }
    }
    /** "send anyway" on a composer send: the gated message (GatedSend), with its images; a draft typed since stays. */
    fun sendGated() {
        val m = GatedSend.orComposer(gatedSend, prompt, picked) ?: return
        gatedSend = null
        vm.send(m.prompt, acceptedDegrade = true, images = m.images)
        prompt = m.promptAfter(prompt); picked = m.imagesAfter(picked); if (picked.isEmpty()) imageNote = null; focus.clearFocus()
    }

    /**
     * A RETRY IS AN ENTRY POINT. iOS gates it identically (ChatView.swift:799-802 calls
     * prepareSend on the retry path), and SendPrep's own doc comment says the message row
     * "must not be a hole that resends the whole thread into a compromised or unverified
     * enclave". It was exactly that hole here: retry called the view model straight through,
     * so with a hard integrity break the composer refused and the retry glyph did not.
     * `requirePrompt = false` — the question is already in the transcript; nothing else differs.
     */
    fun attemptRetry() {
        val prep = SendPrep.prepare("", hasProvider = hasProvider, isDownloading = arrival != null, trust = sendPolicy, requirePrompt = false, hasKey = hasKey)
        gatedThinkHarder = false   // a retry the no-place gate catches (needsPlace) re-runs as a plain one
        if (prep == SendPrep.READY) vm.retryLastTurn() else { gatedRetry = true; gate = prep; reverifyGate.raised() }
    }
    /** Think Harder is a retry, gated identically (ChatView.thinkHarder → retry(from:effort:)). */
    fun attemptThinkHarder(replyId: String) {
        if (!vm.canThinkHarder(replyId)) return
        val prep = SendPrep.prepare("", hasProvider = hasProvider, isDownloading = arrival != null, trust = sendPolicy, requirePrompt = false, hasKey = hasKey)
        gatedThinkHarder = true
        if (prep == SendPrep.READY) vm.thinkHarder(replyId) else { gatedRetry = true; gate = prep; reverifyGate.raised() }
    }
    val listState = rememberLazyListState()

    // The follow contract (TranscriptFollow, docs/UIKIT_TRANSCRIPT.md constraint 2): phase A anchors the last user
    // message near the top and lets the reply grow into reserved slack — the offset does not move while the reply fits;
    // phase B pins the end, throttled to the 40 ms floor. A real drag away stops it for the turn; the next send re-arms it;
    // a reader who left is HELD at their offset through the hand-off.
    val follow = remember { TranscriptFollow { System.nanoTime() / 1e9 } }
    // FollowGlide: the glide is a display-link write on iOS, OWNED by the transcript, not by the update that started it.
    // Here it runs in its own scope: the pin effect restarts on every chunk, and a glide launched inside it was cancelled
    // a few ms in, then the 40 ms floor held the next pin — each pin covered a tenth of the gap and the follow fell ~500dp
    // behind a 17 s stream (FollowContractTest, 2026-09-07). A burst more than 28pt behind retargets it; a smaller one
    // lets it finish; sub-8pt deltas and every settle pin snap.
    val glideScope = rememberCoroutineScope()
    var glide by remember { mutableStateOf<Job?>(null) }
    var landed by remember { mutableStateOf(false) }
    var placeholderPx by remember { mutableStateOf(0) }
    var anchoredPrompt by remember { mutableStateOf(-1) }   // the user message phase A has anchored this turn (see pin)
    // The activity chip's clock: once a second while a turn is in flight (iOS `TimelineView(.periodic(by: 1))`), so
    // "thinking... 12s" ticks through a silent reasoning phase — a chunk-driven recomposition alone froze it.
    var chipClockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isStreaming) { while (state.isStreaming) { chipClockMs = System.currentTimeMillis(); delay(1_000) } }
    var followInterrupted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { follow.onInterruptionChanged = { followInterrupted = it } }
    val userDriving by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(state.isStreaming) { follow.update(generating = state.isStreaming); TranscriptTrace.event(if (state.isStreaming) "turn-start" else "turn-end") }
    // Drag tracking: the delegate callbacks, from the list's own scroll state.
    val density = LocalDensity.current
    // UIScrollViewDelegate's drag phases: the FINGER, not any scroll. `isScrollInProgress` is also true under the follow's own
    // glide, and a swipe during a glide then never registered as a drag; the list's interaction source says when a finger is
    // down, and the deceleration after it lifts still counts as the user's until the list stops.
    var fingerDown by remember { mutableStateOf(false) }
    var decelerating by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> { fingerDown = true; decelerating = false }
                is DragInteraction.Stop, is DragInteraction.Cancel -> { fingerDown = false; decelerating = true }
            }
        }
    }
    LaunchedEffect(listState) {
        var wasDriving = false
        // The offset as a continuous pixel value: LazyListState only knows the first visible item and its offset, so the
        // displacement is carried across items by tracking one visible item and re-anchoring when it leaves. (index ×
        // 10 000 + offset was NOT continuous: a 30dp nudge across an item boundary read as a page and tripped the 50dp rule.)
        var trackIndex = -1; var trackBase = 0.0; var y = 0.0
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }.collect { (_, _, scrolling) ->
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val tracked = visible.firstOrNull { it.index == trackIndex }
            if (tracked != null) y = trackBase - tracked.offset
            else visible.getOrNull(visible.size / 2)?.let { mid -> trackIndex = mid.index; trackBase = y + mid.offset }
            val last = visible.lastOrNull()
            // in dp, not px: the 50 pt interruption rule is a distance on the screen, and 50 px is a 19 dp nudge on a 2.6× phone
            val distanceFromEnd = if (last != null && last.index == info.totalItemsCount - 1) ((last.offset + last.size + info.afterContentPadding - info.viewportEndOffset) / density.density).toDouble().coerceAtLeast(0.0) else Double.MAX_VALUE
            if (!scrolling) decelerating = false
            val driving = fingerDown || decelerating
            val yDp = y / density.density
            if (driving && !wasDriving) follow.onDragBegan(yDp)
            follow.onScrolled(yDp, distanceFromEnd, driving)
            if (!driving && wasDriving) follow.onDragEnded()
            wasDriving = driving
            if (ScrollTraceEnabled.value) {
                // the [scrolltrace] line, in dp. contentH is what iOS reports as contentSize + contentInset.bottom: the end of the
                // scrollable range — the phase-A slack included, as the reserved inset is on iOS — while it is laid out, else
                // the viewport's edge (a lazy list has no content size to read; past = 0 until the end is on screen)
                val visibleH = (info.viewportEndOffset - info.viewportStartOffset) / density.density.toDouble()
                val endRow = visible.firstOrNull { it.index == info.totalItemsCount - 1 } ?: visible.firstOrNull { it.index == info.totalItemsCount - 2 }?.takeIf { placeholderPx == 0 }
                val contentH = if (endRow != null) yDp + (endRow.offset + endRow.size + info.afterContentPadding) / density.density else yDp + visibleH
                TranscriptTrace.geometry(yDp, contentH, visibleH, visible.size, state.isStreaming, follow.interrupted, fingerDown, follow.intent() is TranscriptViewport.PinToEnd)
            }
        }
    }
    // A thread opens on its newest message (TranscriptView: the thread CHANGE arms the settle window that lands a cold open at
    // the end; "settle and thread-open still snap"). The list state is this screen's own, so a fresh thread starts at 0.
    //
    // A WINDOW, NOT ONE SCROLL: a single scrollToItem lands against ESTIMATED row heights — the rows above the end have
    // not been measured yet — and as they measure the end moves while the list holds its index. A 60-turn thread opened
    // from the list after a turn elsewhere landed nine replies short (UserJourneysTest, 2026-10-05). So for a second the
    // last row's bottom is re-pinned to the viewport's end on every layout, as iOS's settle window does; the reader's
    // first drag ends it.
    TraceWhile("chat:loading", !state.loaded)
    val autoScrolls = LocalAutoScrollsTranscript.current
    LaunchedEffect(state.loaded) {
        if (!autoScrolls || !state.loaded || state.messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(state.messages.lastIndex)
        if (scrollToMessageId != null) return@LaunchedEffect   // a search hit lands on its match (the pin effect), not the end
        // NavTrace: the window, and how many times it moved the transcript (each move is one a reader can see)
        val landing = NavTrace.begin("chat:landing"); var moves = 1L
        var dragged = false
        val watch = launch { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        val until = System.nanoTime() + 1_000_000_000L
        while (!dragged && System.nanoTime() < until) {
            val info = listState.layoutInfo
            val endIndex = state.messages.lastIndex   // the last message row, never the slack spacer after it
            val last = info.visibleItemsInfo.firstOrNull { it.index == endIndex }
            if (last == null) { listState.scrollToItem(endIndex); moves++ }
            else {
                val delta = (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
                if (delta > 0.5f) { listState.scrollBy(delta); moves++ }
            }
            delay(50)
        }
        watch.cancel()
        NavTrace.count("chat.landing.moves", moves); NavTrace.end("chat:landing", landing)
    }
    // iOS re-reads anchorGeometry() on every layout pass. The keyed effect below sees the reply's text change, not the
    // layout under it: the developer card that appears after the hand-off, a row inside it opening, the sources chip
    // landing. Each grows the ink below the prompt and must shrink the reserve, or the band it reserved stands under the
    // card as a screen of nothing (Pixel 10 Pro, developer mode, 2026-09-17). Only the reserve is re-read here; the pin
    // is the keyed effect's, once per key change, as before.
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.let { info -> Triple(info.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) }, info.totalItemsCount, info.viewportEndOffset - info.viewportStartOffset) } }
            .distinctUntilChanged()
            .collect { val s = currentState; val next = transcriptReserve(listState.layoutInfo, s.messages, s.isStreaming, placeholderPx); if (next != placeholderPx) placeholderPx = next }
    }
    // Phase A slack: reserve below the last user message so the reply grows without moving the offset (TranscriptAnchorGeometry).
    // The error card is a key too: it is appended under the prompt at the turn's end (ConversationView.items), and with
    // no reply row left (settle drops the empty placeholder) the reserve is 0 and the settle pin lands on the card.
    LaunchedEffect(state.isStreaming, state.messages.size, state.messages.lastOrNull()?.text?.length, state.lastError != null) {
        if (!autoScrolls || state.messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val userIndex = state.messages.indexOfLast { it.role == "user" }
        val userItem = info.visibleItemsInfo.firstOrNull { it.index == userIndex }
        // SLACK IS MEASURED FROM INK, NEVER FROM "A TURN IS LIVE" (TRANSCRIPT_ANCHOR.md, "the reserve must survive the
        // hand-off"): gating it on the streaming flag collapses the band at exactly the seam — the scrollable end falls
        // by the placeholder and the clamp shoves the anchored prompt back down the screen by that much, the hand-off
        // jump rebuilt out of the fix for it, invisible to every overscroll gate because offset and content move
        // together. Ink (the streaming reply, then the persisted row of the same height) is continuous across the seam
        // by construction. What keeps this from being the blank band is the CAP, not a flag: it cannot exceed the band
        // under the prompt, sits directly under drawn ink, and the reader is anchored at the top of it. It is re-sized
        // on the next send. (2026-09-09: 2bs's collapse at turn end was that jump — 575px on the anchor test.)
        placeholderPx = transcriptReserve(info, state.messages, state.isStreaming, placeholderPx)
        val target = scrollToMessageId?.takeIf { !landed }?.let { id -> state.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        if (target != null) { landed = true; listState.animateScrollToItem(target); return@LaunchedEffect }
        // One pin per key change while streaming; after the turn a followed reader gets the 1 s settle (TranscriptViewport:
        // PinToEnd(IMMEDIATE)) re-landed a few times across the hand-off's re-measures, and a reader who left is HELD.
        suspend fun pin(mode: PinMode) {
            // Phase A: the prompt near the top, set ONCE per turn. The reserve keeps the offset stable by construction (the
            // reply eats the placeholder), so nothing needs re-pinning per chunk — and re-pinning per chunk fought the
            // finger: a swipe was snapped back within 12 ms, before the 50dp interruption could latch (traced 2026-09-09,
            // intr=0 through a whole turn). Through the hand-off the reserve is still there, so the anchor holds untouched.
            //
            // The anchor GLIDES, as on iOS: there phase A is the pin to an end that includes the reserved slack, and a live
            // turn's pin over 8pt is a FollowGlide (0.12 s linear) — so the prompt travels up to the top. scrollToItem
            // teleported it: "when you submit a message, shouldn't the entire screen scroll up? it seems abrupt" (Pixel,
            // 2026-10-04). In the glide scope, so the next chunk's restart of this effect does not cancel it; a prompt not
            // laid out (the reader was far up the thread) still snaps, because there is no offset to glide by.
            if (placeholderPx > 0 && userIndex >= 0) {
                if (anchoredPrompt != userIndex) {
                    anchoredPrompt = userIndex
                    val prompt = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == userIndex }
                    val travel = prompt?.offset?.toFloat() ?: 0f
                    glide?.cancel()
                    if (prompt != null && mode == PinMode.THROTTLED && FollowGlide.shouldStart(travel / density.density.toDouble(), mode))
                        glide = glideScope.launch { listState.animateScrollBy(travel, if (FollowGlide.isJump(travel / density.density.toDouble())) Motion.jump() else Motion.follow()) }
                    else listState.scrollToItem(userIndex)
                }
                return
            }
            // phase B: the end. A wrap grows the content ~22pt, and snapping there is the "one line at a time" generation
            // scroll: wrap-sized deltas on the live stream glide over 0.12 s linear (FollowGlide).
            val info2 = listState.layoutInfo
            // THE END IS THE LAST ROW, NEVER THE SLACK SPACER BEHIND IT. Pinning to the list's last item landed the settle pin
            // on the phase-A spacer after a turn — a viewport full of nothing, the reader held inside a blank band (the
            // contract's NEVER BLANK, paid for again here on 2026-09-08: both chat captures came back empty).
            val endIndex = info2.totalItemsCount - 2
            if (endIndex < 0) return
            val last = info2.visibleItemsInfo.firstOrNull { it.index == endIndex }
            if (last == null) { glide?.cancel(); listState.scrollToItem(endIndex); return }   // bring the last row in; the next pass aligns its bottom
            val deltaPx = (last.offset + last.size + info2.afterContentPadding - info2.viewportEndOffset).toFloat()
            if (deltaPx <= 0f) return
            val deltaDp = deltaPx / density.density.toDouble()
            if (FollowGlide.shouldStart(deltaDp, mode)) {
                if (glide?.isActive == true && !FollowGlide.shouldRetarget(deltaDp)) return   // let the one in flight finish
                glide?.cancel()
                glide = glideScope.launch { listState.animateScrollBy(deltaPx, Motion.follow()) }
            } else { glide?.cancel(); listState.scrollBy(deltaPx) }
        }
        var passes = 0
        while (true) {
            when (val intent = follow.intent()) {
                is TranscriptViewport.Hold -> return@LaunchedEffect         // a reader who left is not moved by the hand-off
                is TranscriptViewport.PinToEnd -> if (follow.mayPin(intent.mode)) pin(intent.mode)
                TranscriptViewport.Free -> return@LaunchedEffect
            }
            if (state.isStreaming || ++passes >= 5) return@LaunchedEffect   // streaming: the next chunk restarts this; settled: a few re-lands, then rest
            delay(200)
        }
    }

    // The keyboard goes away as on iOS: a tap outside the field, or a scroll of the transcript (keyboardDismissMode = .interactive).
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() }
    // The title block's height and the composer inset's, measured, never assumed — one-line titles and the two-line
    // e2ee header are different heights.
    var titleHeightPx by remember { mutableStateOf(0) }
    var insetPx by remember { mutableStateOf(0) }
    var titleBottomRootPx by remember { mutableStateOf(0) }
    var panelTopRootPx by remember { mutableStateOf(0) }
    // the title bar's divider: only while the transcript has content scrolled up under it
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
        .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }) {
        // The empty transcript: today's moon, and nothing else (ConversationView). Not before the thread is read — the
        // moon flashing over a thread that is about to fill is not a state iOS has (SwiftData reads synchronously).
        if (!state.loaded) Unit
        else if (state.messages.isEmpty() && state.lastError == null && state.error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MoonGlyph() }
        else
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("chat.transcript"),   // XCUIApplication.transcript
            // The title block and the composer inset are measured and become the list's content padding: at rest the
            // transcript sits between them, and what scrolls past either edge goes under its solid ground.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = with(density) { titleHeightPx.toDp() } + 8.dp, bottom = with(density) { insetPx.toDp() } + ComposerEdge.LIST_GAP),
            verticalArrangement = Arrangement.spacedBy(16.dp),   // a user bubble adds 20dp more below itself (iOS: 36pt to the reply)
        ) {
            val freshStarts = FreshStart.indices(state.messages.map { it.role }, singleTurn)
            val lastUserIndex = state.messages.indexOfLast { it.role == "user" }
            itemsIndexed(state.messages) { index, m ->
                if (index in freshStarts) Text("— ${FreshStart.LABEL} —", style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                when {
                    // Tool traffic is DEVELOPER-MODE ONLY, as on iOS: the transcript shows the chip and the reply; the calls and
                    // their raw results live in the developer card (RequestDebugView). A user saw Brave's whole XML context in the
                    // thread (2026-09-08).
                    m.toolCall != null && !developerMode -> Unit
                    m.toolCall != null -> Panel {
                        Text("tool · ${m.toolCall}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                        Text(m.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp))
                    }
                    // The user's bubble: right-aligned, secondary background, 48pt of leading air.
                    // MessageView: the LAST USER message grows a retry glyph when its turn failed (ConversationView.volatileItems).
                    // the newest question named for the motion check: the send's scroll is judged by where it travels
                    m.role == "user" -> Box(Modifier.then(if (index == lastUserIndex) Modifier.testTag("chat.lastQuestion") else Modifier).padding(bottom = 20.dp)) { UserBubble(m.text, onRetry = if (index == lastUserIndex && (state.lastError != null || state.error != null) && !state.isStreaming) ({ attemptRetry() }) else null, onCopy = onCopy,
                        images = m.images, onOpenImage = { i -> viewing = m.images to i }, imageActions = ImageActions(onCopyImage, onSaveImage, onShareImage, haptics = hapticsEnabled)) }
                    m.role == "system" -> Text(m.text, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                    // The reply: full width, no bubble, no label — the transcript IS the reply (MessageView, assistant role).
                    else -> {
                        // The stall watch (StallChip): every change of the text is an arrival, and a clock decides when a
                        // pause has gone on long enough to say so and when the chip may go. A running tool resets it, since
                        // the chip already shows for that.
                        var stall by remember(m.id) { mutableStateOf(StallChip()) }
                        var lastTextAt by remember(m.id) { mutableLongStateOf(SystemClock.uptimeMillis()) }
                        // reveal lag, screen side (StreamLagMetric): this text is in the frame being built
                        if (m.streaming) SideEffect { NavTrace.count("stream.shown", m.text.length.toLong()) }
                        if (m.streaming) LaunchedEffect(m.id, m.text) {
                            val now = SystemClock.uptimeMillis()
                            lastTextAt = now; stall = stall.onText(now)
                        }
                        if (m.streaming) LaunchedEffect(m.id, state.isExecutingTools) {
                            if (state.isExecutingTools) { stall = StallChip(); return@LaunchedEffect }
                            lastTextAt = SystemClock.uptimeMillis()
                            while (true) { stall = stall.tick(SystemClock.uptimeMillis(), lastTextAt); delay(StallChip.TICK_MS) }
                        }
                        val stalled = stall.visible
                        val chip: @Composable () -> Unit = { ActivityChip(ActivityState.of(state.isExecutingTools, state.sources.size, state.providerWait, chipClockMs, thinkingHarder = state.superseding.isActive, gateWaiting = state.waitingForVerification,
                            loadLine = if (state.turnOnDevice) EngineLoadObserver.shared.line(state.turnStartedAtMs, chipClockMs) else null), state.turnStartedAtMs?.let { (chipClockMs - it) / 1000.0 }) }
                        // Think Harder's swap (TranscriptCollectionView): at the new reply's first text the old one leaves
                        // and the new one takes its row, so this slot's message changes — a 150 ms crossfade, top edge
                        // anchored, nothing below moving. Any other change of a slot's message snaps, as it always has.
                        // With animations off (the system's duration scale, Android's Reduce Motion) both snap.
                        AnimatedContent(targetState = m, contentKey = { it.id }, contentAlignment = Alignment.TopStart, label = "reply-swap",
                            transitionSpec = {
                                if (state.superseding.isActive) (fadeIn(Motion.swap()) togetherWith fadeOut(Motion.swap())).using(null)
                                else (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            }) { m ->
                        // SupersededReplyDimming: the old reply at 45% while the new one has no text, 200 ms each way —
                        // opacity only, so the row keeps its size and nothing below it moves.
                        val dim by animateFloatAsState(if (state.superseding.isDimmed(m.id)) 0.45f else 1f, Motion.change(), label = "superseded-dim")
                        // the reply and its marker lines in ONE column: AnimatedContent lays its content in a Box, and as siblings
                        // here the marker drew over the reply's first line (the maintainer's Pixel, 2026-11-07)
                        ReplyWithMarkers(reply = {
                        Box(Modifier.graphicsLayer { alpha = dim }) { AssistantMessageView(messageId = m.id, content = m.text, generatingTimeMs = m.generatingTimeMs, isLLMRunning = m.streaming, streaming = m.streaming, sources = m.sources,
                            thinkingElapsedMs = state.turnStartedAtMs?.takeIf { m.streaming }?.let { chipClockMs - it },
                            onDevice = m.streaming && state.turnOnDevice,
                            // The activity chip stands in for a reply that has no text yet (StreamingMessageView).
                            placeholder = chip,
                            trailing = chip,
                            showTrailing = TrailingActivityChip.visible(hasText = m.text.isNotEmpty(), stalled = stalled,
                                executingTools = state.isExecutingTools, awaitingModel = state.awaitingModel),
                            onCopy = onCopy,
                            onThinkHarder = if (index == state.messages.lastIndex && !state.isStreaming && !m.streaming) ({ attemptThinkHarder(m.id) }) else null,
                            thinkHarderOffered = { vm.canThinkHarder(m.id) }) }
                        },
                            // sent while near.ai's hardware was still being checked: the mark until a pass clears it; red once it failed
                            sentMark = if (m.streaming) null else ai.teemoon.chat.SentBeforeConfirmed.mark(m.sentBeforeConfirmed)?.let { it.rawValue to (it == ai.teemoon.chat.SentBeforeConfirmed.Mark.CHECK_FAILED_AFTER_SEND) },
                            // how the turn ended, when it didn't finish cleanly: "didn't finish" offers retry on the newest reply
                            marker = if (m.streaming) null else m.marker,
                            onRetry = m.marker?.takeIf { ReplyMarkerCopy.offersRetry(it) && index == state.messages.lastIndex && !state.isStreaming }?.let { { attemptRetry() } })
                        }
                    }
                }
                // The developer card is earned by the LAST reply only, never while an error stands (ConversationView.showsDebugCard).
                if (developerMode && index == state.messages.lastIndex && !m.streaming && state.lastError == null) AppearFade(state.lastRequestDebugInfo, "chat.debugCard") { RequestDebugCard(it, onCopy = onCopy, modifier = Modifier.padding(top = 32.dp)) }   // iOS: the reply cell and the debug cell each carry .padding() — 32pt from the text to the card, not a row's 8
                // The answer stands; the card under it explains what the model wanted the web for — until a key exists,
                // whichever way it arrived (settings, the web chip, this card): then no offer survives (iOS 008cc19).
                AppearFade(state.offers[m.id]?.takeIf { TranscriptItemsPolicy.showsOffer(webSearchState.searchConfigured, offered = true) }, "chat.offer") { query ->
                    WebSearchOffer(query = query, onDismiss = vm::dismissOffer, onKeyAccepted = vm::acceptSearchKey, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // ConversationView.items: the error card is the last item, under the transcript it belongs to.
            state.lastError?.let { err ->
                item(key = "error") {
                    val toolLikely400 = err.httpStatus == 400 && groundingAttached
                    AppearFade(err, "chat.errorCard", appearOnFirst = true) { ErrorCard(it, consoleRecovery(it), onDisableWebSearch = if (toolLikely400) onDisableWebSearchAndRetry else null, onCopy = onCopy) }
                }
            } ?: state.error?.let { item(key = "error") { Panel { Text("error: $it", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.red) } } }
            // The reserved slack under the anchored prompt (phase A). Capped by the geometry; never a blank band the reader is held inside.
            item(key = "slack") { Spacer(Modifier.height(with(LocalDensity.current) { placeholderPx.toDp() })) }
        }


        // The bottom edge (docs/ANDROID_NATIVE.md, "Transcript edges"): solid ground from the panel down and on both sides
        // of it, and a short fade above it that is whole at the panel's top. iOS frosts both edges; a dim with no blur
        // under it left the text readable beside the panel and under the title (2026-09-08, again 2026-11-13).
        BottomEdge(insetPx, Modifier.align(Alignment.BottomCenter).fillMaxWidth())

        // The title block, over the transcript: list.bullet / title + the trust caption / gear. The caption is the chip's own copy (§9.5).
        // E2EETitleBlock: the locality glyph, the thread's title (or "chat"), the chevron, and the trust caption as the
        // subtitle — the chip's own copy (§9.5). ONE button: the whole block opens the trust ladder, in every state
        // (the chevron is "the tap-affordance for the attestation sheet"); the where sheet is the chip's, below.
        // The title bar: Material's default (the maintainer 2026-11-14, "try the default android") — solid, the page's own black,
        // no hairline and no tint on scroll; its ground reaches 8 dp below the round buttons, so scrolled text goes under
        // cleanly instead of running into them.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("chat.titleBar").background(MaterialTheme.colorScheme.background)
            .padding(bottom = TITLE_GROUND_BELOW_BUTTONS)
            .onGloballyPositioned { titleHeightPx = it.size.height; titleBottomRootPx = (it.positionInRoot().y + it.size.height).toInt(); onEdges?.invoke(titleBottomRootPx, panelTopRootPx) }) {
        NavBar(
            title = title,
            // AttestationState.captionColor: secondary unless degraded (orange); a hard block or mismatch reads red.
            // Verified is NOT green here — the chip carries the word, the caption is quiet.
            subtitle = trust?.chip?.label,
            subtitleColor = when (trust?.chip?.tone) { TrustTone.ADVISORY -> TeemoonColors.orange; TrustTone.FAILED -> TeemoonColors.red; else -> TeemoonColors.secondaryLabel },
            // lockView: iphone on device, lock.laptopcomputer self-hosted, otherwise the (verifying) lock — one glyph, never two.
            titleLeading = when (whereLocality) {
                WhereLocality.PHONE -> ({ LocalityGlyph(WhereLocality.PHONE, tint = TeemoonColors.label, sizeDp = 16.dp) })
                WhereLocality.HOME -> ({ LaptopLockGlyph(TeemoonColors.label, sizeDp = 18.dp) })
                WhereLocality.CLOUD -> when (trust?.chip?.tone) {
                    TrustTone.OK, TrustTone.VERIFYING -> ({ VerifyingLock(verifying = trust.chip.tone == TrustTone.VERIFYING) })
                    TrustTone.FAILED -> ({ Icon(Icons.Filled.Lock, null, tint = TeemoonColors.red, modifier = Modifier.size(16.dp)) })
                    // sealed but the hardware not confirmed: the closed lock, in orange (trust-caption-sealed); else lock.open.fill
                    TrustTone.ADVISORY -> if (trust.chip.sealed) ({ Icon(Icons.Filled.Lock, null, tint = TeemoonColors.orange, modifier = Modifier.size(16.dp)) })
                        else ({ LockGlyph(open = true, tint = TeemoonColors.label, sizeDp = 16.dp) })
                    else -> null
                }
                null -> null
            },
            titleChevron = true, onTitle = onOpenTrust,
            leading = { CircleButton(Icons.AutoMirrored.Filled.List, "chats", onBack) },
            trailing = { onOpenSettings?.let { CircleButton(Icons.Filled.Settings, "settings", it) } },
        )

        }

        gate?.let { prep ->
            when (prep) {
                // ONE WAY IN, and it is the same one the chip offers — no "open
                // settings" button, which is the route this redesign demotes. It
                // deep-links into Where rather than dropping the user on a list
                // that is empty by definition.
                //
                // PHONE, COMPUTER, CLOUD — the app's one ordering for the three
                // tiers. The order is an argument, not a list: it runs
                // cheapest-and-most-private first. The phone needs no key and no
                // other machine; a computer needs a machine but still no account;
                // the cloud needs both a key and trusting someone else with the
                // text. Android said "pick a place in settings first." with an ok
                // button, which taught neither the order nor the way in.
                SendPrep.BLOCKED_NO_PROVIDER -> IosAlert(
                    "no provider configured",
                    "download a model to this phone, connect a computer, or add a cloud key — then send again.",
                    listOfNotNull(
                        onOpenWhere?.let { open -> "add a provider" to { gate = null; open() } },
                        "cancel" to { gate = null },
                    ),
                    onDismiss = { gate = null })
                // The weights are still on their way. Arrival owns the sentence (copy is data); the draft stays
                // in the field so the question can be sent the moment the file lands.
                // "no api key". Straight to the key field of THIS setup — not the
                // settings root, not the places list two taps away.
                //
                // Blocked HERE rather than at the wire: an unauthenticated request
                // surfaces as an authentication failure, which is a worse place to
                // learn it and gives the user nothing to act on.
                SendPrep.BLOCKED_NO_KEY -> IosAlert(
                    "no api key",
                    "${wherePlace?.lowercase() ?: "this provider"} needs an api key, and none is saved on this phone. " +
                        "add one under setups, then send again.",
                    listOfNotNull(
                        onOpenWhere?.let { open -> "add a key" to { gate = null; open() } },
                        "cancel" to { gate = null },
                    ),
                    onDismiss = { gate = null })
                // "still downloading". Restarting is offered HERE and not only in
                // the Where sheet: an interrupted download is discovered at the
                // moment of sending, and making the user navigate away to fix it
                // is a detour through a screen that would tell them the same thing.
                //
                // RESTART IS NOT WIRED YET. iOS shows it when the download has no
                // fraction — stalled rather than progressing — and calling it needs
                // a downloader handle this screen does not have; threading one
                // through MainActivity is its own change. The button is
                // deliberately absent rather than present and dead.
                SendPrep.BLOCKED_DOWNLOADING -> arrival?.let { a ->
                    IosAlert(
                        "still downloading", a.alertMessage,
                        listOfNotNull(
                            onOpenWhere?.let { open -> "choose another" to { gate = null; open() } },
                            "wait" to { gate = null },
                        ),
                        onDismiss = { gate = null })
                } ?: run { gate = null }
                // HARD integrity break: no bypass — re-verify or don't send. The send option appears ONLY for soft degrades
                // (E2EESendChoice.choices). "don't send" is a named row, not only a tap outside: it closes the question,
                // keeps the draft and sends nothing (iOS f600e4e).
                SendPrep.CONFIRM_E2EE, SendPrep.BLOCKED_E2EE -> if (trust != null) {
                    val pendingAsk = state.hardwareConfirmRequested
                    // read live: a hard verdict that lands while the question is up turns it into the hard choices (iOS)
                    val blockedNow = trust.composerPolicy == TrustSendPolicy.BLOCK
                    val asking = asksStillChecking(pendingAsk, trust.composerPolicy)
                    // the settle timeout's question names what was still pending (S30): provenance alone asks its own
                    val ask = if (asking) E2EEAsk.make(hardFailure = false, pendingAsk = state.pendingAskKind, provenanceUnchecked = false,
                        softButSealed = true, provenanceBlocked = false, cause = null) else E2EEAsk(trust.alertTitle, trust.alertMessage, trust.sendChoices)
                    val title = ask.title
                    val message = ask.message
                    val choices = ask.choices
                    IosAlert(title, message,
                    choices.mapNotNull<E2EESendChoice, Pair<String, () -> Unit>> { choice ->
                        when (choice) {
                            // iOS retryE2EEAndGenerate (5638261c; the maintainer 2026-11-07, "1.1 should match"): a held turn is
                            // declined first, so a re-verify never leaves it live (F3) — a fresh send's question goes back to the
                            // composer, a retry or think harder stays pending. After the re-verify the alert comes back (blocked,
                            // or still asking) and its "send anyway" resends what was kept; a clean state runs it.
                            E2EESendChoice.RetryVerification -> choice.label to {
                                gate = null
                                val wasHard = blockedNow
                                if (pendingAsk) {
                                    val kept = vm.reverifyHeldTurn()
                                    gatedRetry = kept != null
                                    if (kept != null) gatedThinkHarder = kept == ReasoningEffort.THINK_HARDER
                                    // a fresh send's question went back to the composer: that is what a later "send anyway" sends
                                    else gatedSend = null
                                }
                                val captured = vm.reverifyCapture()
                                val ticket = reverifyGate.tapped()
                                onRetryVerification()
                                reverifyScope.launch {
                                    when (reverifyGate.then(vm.afterReverify(wasHard, captured), ticket, gatedRetry)) {
                                        ReverifyGate.Then.SHOW_BLOCKED -> gate = SendPrep.BLOCKED_E2EE
                                        ReverifyGate.Then.SHOW_ASK -> gate = SendPrep.CONFIRM_E2EE
                                        ReverifyGate.Then.RESEND_RETRY -> vm.retryLastTurn(effort = if (gatedThinkHarder) ReasoningEffort.THINK_HARDER else ReasoningEffort.CHAT_DEFAULT)
                                        ReverifyGate.Then.SEND_COMPOSER -> attemptSend()
                                        ReverifyGate.Then.NOTHING -> Unit
                                    }
                                }
                            }
                            // "send anyway" is the user accepting THIS degrade: the view model may honour a CONFIRM that
                            // is still a CONFIRM after prepareTurn settles, never a BLOCK. A gated RETRY re-asks the
                            // question (iOS generateBypassing's pendingRetryMessage) — sending the empty composer did nothing.
                            is E2EESendChoice.SendAnyway -> if (prep == SendPrep.CONFIRM_E2EE && !blockedNow) choice.label to {
                                gate = null
                                // the still-checking question is answered on the turn that is waiting for it (the kit's confirm)
                                if (pendingAsk) vm.answerStillChecking(sendIt = true)
                                else if (gatedRetry) vm.retryLastTurn(acceptedDegrade = true, effort = if (gatedThinkHarder) ReasoningEffort.THINK_HARDER else ReasoningEffort.CHAT_DEFAULT) else sendGated()
                            } else null
                            // "don't send" to the still-checking question is the kit's decline: the waiting reply ends stopped
                            E2EESendChoice.DontSend -> choice.label to { gate = null; gatedSend = null; if (pendingAsk) vm.answerStillChecking(sendIt = false) }
                            // the seal holds; the send asks once per record and reason (the view model remembers it)
                            E2EESendChoice.SendSealedUnconfirmed -> if (prep == SendPrep.CONFIRM_E2EE && !blockedNow) choice.label to {
                                gate = null
                                if (pendingAsk) vm.answerStillChecking(sendIt = true)
                                else {
                                    // a re-shown or composer still-checking alert: this answer covers the pending ask too
                                    vm.confirmStillChecking()
                                    if (gatedRetry) vm.retryLastTurn(acceptedDegrade = true, effort = if (gatedThinkHarder) ReasoningEffort.THINK_HARDER else ReasoningEffort.CHAT_DEFAULT) else sendGated()
                                }
                            } else null
                            E2EESendChoice.Wait -> choice.label to { gate = null; gatedSend = null; if (pendingAsk) vm.answerStillChecking(sendIt = false) else vm.hardwareConfirmShown() }
                        }
                    },
                    // a question not sent is not kept for a later one: the draft stays in the composer, the gate forgets it
                    onDismiss = { gate = null; gatedSend = null; if (pendingAsk) vm.answerStillChecking(sendIt = false) else vm.hardwareConfirmShown() },
                    destructive = choices.filter { it.isDestructive }.map { it.label }.toSet())
                }
                else -> gate = null
            }
        }

        // The composer block: one panel (ChatInputBar).
        val composerTraceId = remember { java.util.UUID.randomUUID().hashCode() }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onGloballyPositioned { insetPx = it.size.height; ComposerTrace.geometry(composerTraceId, it.positionInRoot().y, it.size.height, picked.size) }
            .padding(start = 16.dp, end = 16.dp, top = ComposerEdge.BLOCK_TOP, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // One panel (COMPOSER_PANEL.md @ 85c544c6): the where chip and the web chip are its bottom row's, beside "+"
            // and send; no first-run line above it (the maintainer 2026-11-08, "remove it"): "choose where" is the prompt.
            var chipMenu by remember { mutableStateOf(false) }
            val whereChip: @Composable (Boolean) -> Unit = { iconOnly -> Box {
                WhereChip(model = whereModel, place = wherePlace, locality = whereLocality, onClick = onOpenWhere?.let { { fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.TAP_WHERE_CHIP) } }, modifier = Modifier.testTag("chat.whereChip"), progress = whereProgress, progressLabel = whereProgressLabel, needsDownload = whereNeedsDownload,
                    onLongClick = if (onModelInfo != null) ({ chipMenu = true }) else null, iconOnly = iconOnly,
                    // a Think Harder turn shows the level it sent, then the pick again (only where a level shows at all)
                    level = shownChipLevel(whereLevel, state.thinkHarderLevel))
                // Two items deliberately (iOS: a lone entry renders as an oversized pill, and is better as a direct action).
                // The keyboard goes before the page covers the composer, as the where sheet's does (iOS 23d5fac7): a menu on the page brought it back over it.
                if (onModelInfo != null) ChipMenu(expanded = chipMenu, onDismiss = { chipMenu = false }, onModelInfo = { attachFocus.clearFocus(); onModelInfo() }, onCopyModelID = { onCopyModelID?.invoke() })
            } }
            // hidden when the selected model can't take tools; the row closes the gap (new on both apps)
            val webChip: (@Composable (Boolean) -> Unit)? = if (ComposerPanel.Item.WEB in ComposerPanel.bottomRow(modelTakesTools)) ({ iconOnly -> WebSearchChip(webSearchState, onClick = onWebSearchChip, iconOnly = iconOnly) }) else null
            // ContentView: `if currentThread?.messages.isEmpty != false { isPromptFocused = true }` — an empty thread opens with the prompt focused.
            val composerFocus = remember { FocusRequester() }
            LaunchedEffect(state.loaded, state.messages.isEmpty(), focusEpoch) { if (state.loaded && state.messages.isEmpty() && focusOnEmpty) runCatching { composerFocus.requestFocus() } }   // not before the thread is read: an unread thread is not an empty one
            // ChatComposer's imageNote: a caption above the field, left-aligned, never an alert (IMAGES.md §4 "the
            // paperclip is disabled and says why"; a modal here read as a refusal of the whole chat, the maintainer 2026-10-28).
            Composer(modifier = Modifier.testTag("chat.panel").onGloballyPositioned { c -> panelTopRootPx = c.positionInRoot().y.toInt(); onEdges?.invoke(titleBottomRootPx, panelTopRootPx) },
                value = prompt, onValueChange = { prompt = it }, streaming = state.isStreaming, onSend = { attemptSend() }, onStop = vm::stop,
                // over the place's image limit, send waits until enough are removed (never dropped)
                canSend = ai.teemoon.app.imaging.ComposerImages.canSend(prompt.isBlank(), picked.size, pending, imageLimit), pendingImages = pending, focusRequester = composerFocus,   // ChatComposer: .disabled(isPromptEmpty) — with or without a place
                onAttach = if (pickImages != null) ::openAttach else null,   // tint on every model (§4d): it always opens the sheet
                images = thumbs, onRemoveImage = { i -> fire(if (picked.size == 1) ai.teemoon.app.imaging.ComposerImagesMachine.Event.REMOVE_LAST else ai.teemoon.app.imaging.ComposerImagesMachine.Event.REMOVE_ONE, removeAt = i) },
                onPasteImages = if (pasteImages != null) ::pasted else null, onPasteDataUrl = if (pasteDataUrl != null) ::pastedDataUrl else null,
                note = ai.teemoon.app.imaging.ComposerImages.overLimitNote(picked.size, imageLimit) ?: imageNote, whereChip = whereChip, webChip = webChip,
                // a status on the where chip: web goes icon-only to give it room (COMPOSER_PANEL.md @ cbacf674)
                // web goes glyph-only for a status, and for the level so the model's name stays whole (the maintainer 2026-11-13)
                whereStatus = whereProgress != null || whereNeedsDownload || whereLevel != null)
            // A choice, so an alert (the reasons above are captions): download, or not now.
            // 1.0.6's card, as the mobile-data and E2EE choices beside it (the maintainer 2026-10-31, "Revert all to 1.0.6"; pm).
            if (offerImageDownload) IosAlert(ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD_TITLE, ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD_BODY,
                listOf(ai.teemoon.app.imaging.ImageCopy.OFFER_DOWNLOAD to { offerImageDownload = false; onImageDownload() },
                    ai.teemoon.app.imaging.ImageCopy.OFFER_NOT_NOW to { offerImageDownload = false }),
                onDismiss = { offerImageDownload = false })
            viewing?.let { (images, start) -> ImageViewer(images, start, vm::imageBytesFor, onShareImage, onDone = { viewing = null }, onCopy = onCopyImage, onSave = onSaveImage, hapticsEnabled = hapticsEnabled) }
            // The send refused (IMAGES.md §4d): the alert stays; its button opens the where sheet filtered to models that
            // read images, and the images stay attached so the person sends again.
            // "send without images" (DRAFT, ab914c3e) only when there is text to send.
            // short, all DRAFT (the maintainer 2026-11-12); a phone model this phone can never fit says so instead (IMAGES.md §4b)
            state.imageRefusal?.let { if (phoneImagesNever) IosAlert(ai.teemoon.app.imaging.CantSeeRoute.permanent(whereModel), "",
                    listOfNotNull(smallerPhoneModel?.let { (name, use) -> ai.teemoon.app.imaging.CantSeeRoute.useSmaller(name) to { vm.imageRefusalShown(); use() } },
                        ai.teemoon.app.imaging.CantSeeRoute.OK to vm::imageRefusalShown), onDismiss = vm::imageRefusalShown)
                else IosAlert(ai.teemoon.app.imaging.CantSeeRoute.alertTitle(whereModel), ai.teemoon.app.imaging.CantSeeRoute.alertMessage(hasText = prompt.isNotBlank()),
                listOfNotNull(ai.teemoon.app.imaging.CantSeeRoute.ALERT_CHOOSE to { vm.imageRefusalShown(); fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.ALERT_CHOOSE_MODEL) },
                    (ai.teemoon.app.imaging.CantSeeRoute.ALERT_SEND_TEXT to { vm.imageRefusalShown(); fire(ai.teemoon.app.imaging.ComposerImagesMachine.Event.SEND_WITHOUT_IMAGES); attemptSend() }).takeIf { prompt.isNotBlank() },
                    ai.teemoon.app.imaging.CantSeeRoute.CANCEL to vm::imageRefusalShown), onDismiss = vm::imageRefusalShown) }
        }
    }
}

/**
 * The ground under the composer: solid from [ComposerEdge.FADE] above the panel to the bottom of the screen, across the
 * full width, so nothing of the transcript shows beside or under the panel. The fade is whole at the panel's top and
 * fits inside the gap the list keeps above the composer at rest (its content padding), so it never dims a resting line.
 */
@Composable
private fun BottomEdge(insetPx: Int, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    // the composer block's 4 dp top padding sits between the inset's top and the panel's
    val height = with(density) { insetPx.toDp() } - ComposerEdge.BLOCK_TOP + ComposerEdge.FADE
    if (height <= ComposerEdge.FADE) return
    Canvas(modifier.height(height)) {
        val fade = ComposerEdge.FADE.toPx() / size.height
        drawRect(Brush.verticalGradient(0f to bg.copy(alpha = 0f), fade to bg, 1f to bg))
    }
}

/** How far the title bar's solid ground reaches below its 44 dp round buttons (the maintainer 2026-11-14). */
internal val TITLE_GROUND_BELOW_BUTTONS = 8.dp

/** The bottom edge's lengths. A reply pinned to the end stops [LIST_GAP] above the composer block, [BLOCK_TOP] above the panel. */
internal object ComposerEdge {
    val BLOCK_TOP = 4.dp
    val LIST_GAP = 8.dp
    /** At most 16 dp (the design), and exactly the resting gap, so the last line of a pinned reply is never in it. */
    val FADE = BLOCK_TOP + LIST_GAP
}

/**
 * The anchor reserve for this layout (TranscriptAnchorGeometry.placeholder), with iOS's `inkBelow`: everything drawn
 * below the prompt (TranscriptInk). The four cases, as before: a reply below a visible prompt measures; the send
 * (last row the user's) is 0 — re-sized when the reply's ink exists; the prompt off screen at the turn's end is 0 —
 * the reader left, or phase B pinned a reply taller than the band, and a lazy list cannot measure off-screen ink so it
 * must not keep the last size it saw; the prompt off screen mid-turn keeps the last size. The viewport spans the
 * chrome (content padding); the usable band is what is under the title and above the composer — with 0/0 a short
 * reply and its sources chip grew under the composer (2026-09-09).
 */
private fun transcriptReserve(info: LazyListLayoutInfo, messages: List<ChatMessage>, isStreaming: Boolean, current: Int): Int {
    if (messages.isEmpty()) return current
    if (messages.last().role == "user") return 0
    val userIndex = messages.indexOfLast { it.role == "user" }
    val userItem = info.visibleItemsInfo.firstOrNull { it.index == userIndex } ?: return if (isStreaming) current else 0
    val slackIndex = info.totalItemsCount - 1
    val lastTail = info.visibleItemsInfo.filter { it.index > userIndex && it.index < slackIndex }.maxByOrNull { it.index }
    val ink = TranscriptInk.below(
        promptBottom = (userItem.offset + userItem.size).toDouble(),
        lastVisibleTailEnd = lastTail?.let { (it.offset + it.size).toDouble() },
        tailContinuesPastViewport = lastTail != null && lastTail.index < slackIndex - 1,
    )
    return TranscriptAnchorGeometry(userHeight = userItem.size.toDouble(), visibleHeight = (info.viewportEndOffset - info.viewportStartOffset).toDouble(),
        topFade = info.beforeContentPadding.toDouble(), chrome = info.afterContentPadding.toDouble(), inkBelow = ink).placeholder.toInt()
}

/**
 * E2EETitleBlock.VerifyingLock (iOS): while verifying the lock breathes, 1 to 0.45 and back over 0.85 s each way, in the
 * secondary colour; verified, it eases to solid in the label colour rather than cutting (motion-pass item 4: "the app's
 * core trust moment" was a hard cut). The breath is a loop that ends when verification does — never an infinite
 * animation left running behind a verified chat — and with animations off there is no breath at all: the lock is solid.
 */
/** The verifying lock's breath, until cancelled: to 0.45 and back, 0.85 s each way. */
internal suspend fun lockBreath(pulse: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>): Nothing {
    while (true) { pulse.animateTo(0.45f, Motion.breathHalf()); pulse.animateTo(1f, Motion.breathHalf()) }
}

@Composable
internal fun VerifyingLock(verifying: Boolean) {
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    val weight by androidx.compose.animation.core.animateFloatAsState(if (verifying) 1f else 0f, Motion.change(), label = "verifying")
    LaunchedEffect(verifying) {
        val still = (coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f) == 0f
        // an endless loop goes through the InfiniteAnimationPolicy, as an infinite transition does, so a UI test can idle
        if (verifying && !still) coroutineContext[androidx.compose.ui.platform.InfiniteAnimationPolicy]?.onInfiniteOperation { lockBreath(pulse) } ?: lockBreath(pulse)
        else pulse.animateTo(1f, Motion.change())
    }
    val alpha = pulse.value
    val tint = androidx.compose.ui.graphics.lerp(TeemoonColors.label, TeemoonColors.secondaryLabel, weight)
    Icon(Icons.Filled.Lock, null, tint = tint,
        modifier = Modifier.size(16.dp).graphicsLayer { this.alpha = alpha }.testTag("title.lock").semantics { motionAlpha = alpha })
}
