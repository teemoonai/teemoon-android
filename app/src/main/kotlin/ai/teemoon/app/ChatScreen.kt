package ai.teemoon.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

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
    webSearchState: WebSearchChipState = WebSearchChipState.DISABLED,
    onWebSearchChip: () -> Unit = {},
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
) {
    val haptic = LocalHapticFeedback.current
    // On the MAIN dispatcher, explicitly: the stream writes the view model's state from the transport's thread, and a
    // collector resumed there recomposes off the main thread. The app's own frame clock always dispatched to main; the
    // v1 Compose test rule's unconfined dispatcher did not, and crashed the app twice a suite on the Pixel
    // ("performMeasureAndLayout called during measure layout", 2026-10-05).
    val state by vm.state.collectAsStateWithLifecycle(context = Dispatchers.Main)
    // The draft survives leaving the chat for the trust screen or a sheet (iOS keeps it in the view's state; a
    // Compose route change would otherwise recreate this screen and drop it).
    var prompt by rememberSaveable { mutableStateOf("") }
    var gate by remember { mutableStateOf<SendPrep?>(null) }
    /** The gate above was raised by the RETRY glyph, not the composer: its bypass re-asks the question. */
    var gatedRetry by remember { mutableStateOf(false) }
    // a retry from the error card or the search offer found no place: the same alert the composer shows
    LaunchedEffect(state.needsPlace) { if (state.needsPlace) { gatedRetry = true; gate = SendPrep.BLOCKED_NO_PROVIDER; vm.placeRequestShown() } }
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
    val sendPolicy = trust?.verdict?.sendPolicy ?: TrustSendPolicy.ALLOW

    // Every entry point that can start a generation consults the session's ONE send policy.
    fun attemptSend() {
        val prep = SendPrep.prepare(prompt, hasProvider = hasProvider, isDownloading = arrival != null, trust = sendPolicy, hasKey = hasKey)
        // The field clears on send (ChatInputBar); the draft survives a gate so a cancelled send loses nothing.
        // ChatView.performGenerate: `isPromptFocused = false` after the send — the keyboard leaves and the reply has the screen
        if (prep == SendPrep.READY) { vm.send(prompt); prompt = ""; focus.clearFocus(); if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.Confirm) } else if (prep != SendPrep.BLOCKED_EMPTY_PROMPT) { gatedRetry = false; gate = prep }
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
        if (prep == SendPrep.READY) vm.retryLastTurn() else { gatedRetry = true; gate = prep }
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
    LaunchedEffect(state.loaded) {
        if (!state.loaded || state.messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(state.messages.lastIndex)
        if (scrollToMessageId != null) return@LaunchedEffect   // a search hit lands on its match (the pin effect), not the end
        var dragged = false
        val watch = launch { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        val until = System.nanoTime() + 1_000_000_000L
        while (!dragged && System.nanoTime() < until) {
            val info = listState.layoutInfo
            val endIndex = state.messages.lastIndex   // the last message row, never the slack spacer after it
            val last = info.visibleItemsInfo.firstOrNull { it.index == endIndex }
            if (last == null) listState.scrollToItem(endIndex)
            else {
                val delta = (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
                if (delta > 0.5f) listState.scrollBy(delta)
            }
            delay(50)
        }
        watch.cancel()
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
        if (state.messages.isEmpty()) return@LaunchedEffect
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
                        glide = glideScope.launch { listState.animateScrollBy(travel, tween((FollowGlide.FOLLOW_GLIDE_DURATION * 1000).toInt(), easing = LinearEasing)) }
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
                glide = glideScope.launch { listState.animateScrollBy(deltaPx, tween((FollowGlide.FOLLOW_GLIDE_DURATION * 1000).toInt(), easing = LinearEasing)) }
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
    // ChatChrome (ChatFadeBand.kt): the title block's height and the composer inset's geometry, measured, never assumed —
    // one-line titles and the two-line e2ee header are different heights.
    var titleHeightPx by remember { mutableStateOf(0) }
    var insetPx by remember { mutableStateOf(0) }
    var chipTopDp by remember { mutableStateOf(16.0) }
    var chipBottomDp by remember { mutableStateOf(60.0) }
    val band = ChatFadeBand(chipTopDp, chipBottomDp, with(density) { insetPx.toDp() }.value.toDouble())
    val chrome = ChatChrome(bottom = band)
    val topChromePx = chrome.topChromeHeight(titleHeightPx.toDouble()).toInt()
    val topOverlayDp = chrome.topOverlayHeight(with(density) { titleHeightPx.toDp() }.value.toDouble())
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
        .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }) {
        // The empty transcript: today's moon, and nothing else (ConversationView). Not before the thread is read — the
        // moon flashing over a thread that is about to fill is not a state iOS has (SwiftData reads synchronously).
        if (!state.loaded) Unit
        else if (state.messages.isEmpty() && state.lastError == null && state.error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MoonGlyph() }
        else
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("chat.transcript"),   // XCUIApplication.transcript
            // ChatChrome: the canvas is full-bleed; the title block and the composer inset are measured and become the
            // list's content padding, so the transcript runs BEHIND both and the glass edges dissolve it there.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = with(density) { topChromePx.toDp() } + 8.dp, bottom = with(density) { insetPx.toDp() } + 8.dp),
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
                    m.role == "user" -> Box(Modifier.padding(bottom = 20.dp)) { UserBubble(m.text, onRetry = if (index == lastUserIndex && (state.lastError != null || state.error != null) && !state.isStreaming) ({ attemptRetry() }) else null, onCopy = onCopy) }
                    m.role == "system" -> Text(m.text, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                    // The reply: full width, no bubble, no label — the transcript IS the reply (MessageView, assistant role).
                    else -> {
                        // The stall watch (StreamingMessageView.restartStallTimer): every change of the text re-arms it; a
                        // running tool holds it off, since the chip already shows for that.
                        var stalled by remember(m.id) { mutableStateOf(false) }
                        if (m.streaming) LaunchedEffect(m.id, m.text, state.isExecutingTools) {
                            stalled = false
                            if (!state.isExecutingTools) { delay(TrailingActivityChip.STALL_MS); stalled = true }
                        }
                        val chip: @Composable () -> Unit = { ActivityChip(ActivityState.of(state.isExecutingTools, state.sources.size), state.turnStartedAtMs?.let { (chipClockMs - it) / 1000.0 }) }
                        AssistantMessageView(messageId = m.id, content = m.text, generatingTimeMs = m.generatingTimeMs, isLLMRunning = m.streaming, streaming = m.streaming, sources = m.sources,
                            // The activity chip stands in for a reply that has no text yet (StreamingMessageView).
                            placeholder = chip,
                            trailing = chip,
                            showTrailing = TrailingActivityChip.visible(hasText = m.text.isNotEmpty(), stalled = stalled,
                                executingTools = state.isExecutingTools, awaitingModel = state.awaitingModel))
                    }
                }
                // The developer card is earned by the LAST reply only, never while an error stands (ConversationView.showsDebugCard).
                if (developerMode && index == state.messages.lastIndex && !m.streaming && state.lastError == null) state.lastRequestDebugInfo?.let { RequestDebugCard(it, onCopy = onCopy, modifier = Modifier.padding(top = 32.dp)) }   // iOS: the reply cell and the debug cell each carry .padding() — 32pt from the text to the card, not a row's 8
                // The answer stands; the card under it explains what the model wanted the web for — until a key exists,
                // whichever way it arrived (settings, the web chip, this card): then no offer survives (iOS 008cc19).
                state.offers[m.id]?.takeIf { TranscriptItemsPolicy.showsOffer(webSearchState.searchConfigured, offered = true) }?.let { query ->
                    WebSearchOffer(query = query, onDismiss = vm::dismissOffer, onKeyAccepted = vm::acceptSearchKey, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // ConversationView.items: the error card is the last item, under the transcript it belongs to.
            state.lastError?.let { err ->
                item(key = "error") {
                    val toolLikely400 = err.httpStatus == 400 && groundingAttached
                    ErrorCard(err, consoleRecovery(err), onDisableWebSearch = if (toolLikely400) onDisableWebSearchAndRetry else null, onCopy = onCopy)
                }
            } ?: state.error?.let { item(key = "error") { Panel { Text("error: $it", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.red) } } }
            // The reserved slack under the anchored prompt (phase A). Capped by the geometry; never a blank band the reader is held inside.
            item(key = "slack") { Spacer(Modifier.height(with(LocalDensity.current) { placeholderPx.toDp() })) }
        }


        // The glass edges (GlassEdgeFadeView): viewport-fixed dissolves at both edges, the system edge effects hidden. Compose
        // has no backdrop blur to give an overlay, so the dim carries the fade alone (docs/ANDROID_NATIVE.md).
        if (topOverlayDp > 1) GlassEdge(ChatGlassFade.topStops(with(density) { titleHeightPx.toDp() }.value.toDouble()), Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topOverlayDp.dp))
        GlassEdge(ChatGlassFade.bottomStops(band.ramp, band.hidden, 0.0), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(band.overlayHeight(0.0).dp))

        // The title block, over the transcript: list.bullet / title + the trust caption / gear. The caption is the chip's own copy (§9.5).
        // E2EETitleBlock: the locality glyph, the thread's title (or "chat"), the chevron, and the trust caption as the
        // subtitle — the chip's own copy (§9.5). ONE button: the whole block opens the trust ladder, in every state
        // (the chevron is "the tap-affordance for the attestation sheet"); the where sheet is the chip's, below.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onGloballyPositioned { titleHeightPx = it.size.height }) {
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
                    TrustTone.OK, TrustTone.VERIFYING -> ({ Icon(Icons.Filled.Lock, null, tint = if (trust.chip.tone == TrustTone.VERIFYING) TeemoonColors.secondaryLabel else TeemoonColors.label, modifier = Modifier.size(16.dp)) })
                    TrustTone.FAILED -> ({ Icon(Icons.Filled.Lock, null, tint = TeemoonColors.red, modifier = Modifier.size(16.dp)) })
                    TrustTone.ADVISORY -> ({ LockGlyph(open = true, tint = TeemoonColors.label, sizeDp = 16.dp) })   // lock.open.fill: degraded, not sealed
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
                // HARD integrity break: no bypass — re-verify or cancel. The send option appears ONLY for soft degrades.
                SendPrep.CONFIRM_E2EE, SendPrep.BLOCKED_E2EE -> if (trust != null) IosAlert(
                    trust.alertTitle, trust.alertMessage,
                    listOfNotNull<Pair<String, () -> Unit>>(
                        "retry verification" to { gate = null; onRetryVerification() },
                        // "send anyway" is the user accepting THIS degrade: the view model may honour a CONFIRM that
                        // is still a CONFIRM after prepareTurn settles, never a BLOCK. A gated RETRY re-asks the
                        // question (iOS generateBypassing's pendingRetryMessage) — sending the empty composer did nothing.
                        if (prep == SendPrep.CONFIRM_E2EE) trust.bypassLabel?.let { label -> label to {
                            gate = null
                            if (gatedRetry) vm.retryLastTurn(acceptedDegrade = true) else { vm.send(prompt, acceptedDegrade = true); prompt = ""; focus.clearFocus() }
                        } } else null,
                        "cancel" to { gate = null },
                    ),
                    onDismiss = { gate = null })
                else -> gate = null
            }
        }

        // The composer block: the where chip and the web chip above the pill (ChatInputBar).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onGloballyPositioned { insetPx = it.size.height }
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.onGloballyPositioned { c -> val top = (c.positionInParent().y / density.density).toDouble(); chipTopDp = top; chipBottomDp = top + c.size.height / density.density },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var chipMenu by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f, fill = false)) {
                    WhereChip(model = whereModel, place = wherePlace, locality = whereLocality, onClick = onOpenWhere, modifier = Modifier.testTag("chat.whereChip"), progress = whereProgress, progressLabel = whereProgressLabel, needsDownload = whereNeedsDownload,
                        onLongClick = if (onModelInfo != null) ({ chipMenu = true }) else null)
                    // Two items deliberately (iOS: a lone entry renders as an oversized pill, and is better as a direct action).
                    if (onModelInfo != null) ChipMenu(expanded = chipMenu, onDismiss = { chipMenu = false }, onModelInfo = onModelInfo, onCopyModelID = { onCopyModelID?.invoke() })
                }
                WebSearchChip(webSearchState, onClick = onWebSearchChip)
            }
            // ContentView: `if currentThread?.messages.isEmpty != false { isPromptFocused = true }` — an empty thread opens with the prompt focused.
            val composerFocus = remember { FocusRequester() }
            LaunchedEffect(state.loaded, state.messages.isEmpty(), focusEpoch) { if (state.loaded && state.messages.isEmpty() && focusOnEmpty) runCatching { composerFocus.requestFocus() } }   // not before the thread is read: an unread thread is not an empty one
            Composer(value = prompt, onValueChange = { prompt = it }, streaming = state.isStreaming, onSend = { attemptSend() }, onStop = vm::stop,
                canSend = prompt.isNotBlank(), focusRequester = composerFocus)   // ChatComposer: .disabled(isPromptEmpty) — with or without a place
        }
    }
}

/**
 * One glass edge, drawn from GlassEdgeFadeView's stops over the transcript in the system background. iOS frosts the
 * band with a blur UNDER this dim, and the two together make the header read as chrome; Compose cannot blur what is
 * behind an overlay, so on the dim alone the text under the title stayed legible — the user read it as "chat text
 * underneath the header" (2026-09-08). The blur weight is folded into the dim here: alpha = 1 − (1 − dim)(1 − 0.8·blur),
 * which is a solid ground where iOS is fully frosted and the same soft edge where the frost ends.
 */
@Composable
private fun GlassEdge(stops: List<ChatGlassFade.Stop>, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(colorStops = stops.map { it.location.toFloat() to bg.copy(alpha = (1 - (1 - it.dim) * (1 - 0.8 * it.blur)).toFloat().coerceIn(0f, 1f)) }.toTypedArray()))
    }
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
