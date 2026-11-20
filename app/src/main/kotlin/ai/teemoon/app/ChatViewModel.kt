// ports: teemoon/Inference/ChatGeneration.swift @ v1.0.4 (generate() — the drive; state lives in ChatGeneration),
//        teemoon/Views/Chat/ChatView.swift @ v1.0.4 (runPendingSearchRetryIfReady, retry(from:) — the search-offer re-ask only)
//        teemoon/Chat/ChatViewModel.swift @ v1.0.4 (postPrepareRefusal, acceptedDegrade; a refusal before the wire is abandoned, not booked;
//        searchTurnedOff passed to the grounding tools @ 8494cdb9)
//        teemoon/Chat/ChatViewModel.swift @ v1.0.4 (the saved reply's sources: GroundingSource.citedOrAll)
//        teemoon/Chat/ChatViewModel.swift @ 14b2974 (Think Harder keeps the old reply until the new one lands: ReplySupersession)
//        teemoon/Chat/ChatViewModel.swift @ cf57d0c (a re-ask of the first question is not a first message: turnIsReask)
//        teemoon/Chat/ChatViewModel.swift @ 8d33cae1 (confirm re-reads the gate: a refused held-back turn is the kit's gateRefused)
//        teemoon/Chat/ChatViewModel.swift @ 6b99b42a (F4: a confirmation counts only for its record and reason)
//        teemoon/Chat/ChatViewModel.swift @ a13c1ca2 (F3 residual: Re-verify declines the held-back turn — ChatScreen)
//        teemoon/Chat/ChatViewModel.swift @ 5638261c (Re-verify keeps a held retry pending; the alert comes back after it)
//        teemoon/Chat/ChatViewModel.swift @ f6d44c1b (a turn that started attestable never leaves unsealed: gateWouldGoPlain)
package ai.teemoon.app

import ai.teemoon.inference.NetworkFailure
import ai.teemoon.inference.ProviderWait
import androidx.lifecycle.ViewModel
import ai.teemoon.confidential.TrustSendPolicy
import androidx.lifecycle.viewModelScope
import ai.teemoon.chat.ChatRepository
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.providers.Provider
import ai.teemoon.providers.effectiveOmitSystemPrompt
import ai.teemoon.inference.GenerationEngine
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.OpenAiTransport
import ai.teemoon.inference.ReasoningControl
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.SessionAffinity
import ai.teemoon.inference.StreamCallbacks
import ai.teemoon.inference.ToolCallState
import ai.teemoon.inference.ToolRoundPolicy
import ai.teemoon.inference.WireMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ai.teemoon.chat.MessageEntity
import ai.teemoon.confidential.ResponseVerification
import ai.teemoon.confidential.TeeContext
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.LocalDefaults
import ai.teemoon.inference.RequestResult
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred

data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String = "",
    val streaming: Boolean = false,
    /** Rendered as a tool card rather than prose. */
    val toolCall: String? = null,
    /** The persisted id, so an offer card and a retry can name a message. */
    val id: String = "",
    val createdAtMs: Long = 0,
    /** The sources the reply cited — persisted on the message, shown as the rail under it. */
    val sources: List<GroundingSource> = emptyList(),
    val isE2EE: Boolean = false,
    /** How long the reply took (Message.generatingTime) — the thinking disclosure's "thought for 4s". */
    val generatingTimeMs: Long? = null,
    /** Its images (IMAGES.md §5): re-encoded, with a thumbnail for the bubble. */
    val images: List<ChatImage> = emptyList(),
    /**
     * The reply's marker id (conformance/reply-machine.json): shown under it in the session when its turn ended stopped,
     * cut short, undecryptable or unfinished, and read back from the saved status on reopen (ReplyStatus.reloadMarker).
     * The words are ReplyMarkerCopy's.
     */
    val marker: String? = null,
    /**
     * The reply's sentBeforeConfirmed value ("pending|<recordKey>" / "failed|<recordKey>"), as stored: its mark line
     * (SentBeforeConfirmed.mark; the words are ReplyMarkerCopy's) shows until a pass clears it.
     */
    val sentBeforeConfirmed: String? = null,
    /** A reply's saved status (messages.status; null = a row from before the column, read as complete). */
    val status: String? = null,
)

/** One image of a message: an attachments row ([path] empty until its file is stored). */
data class ChatImage(val id: String, val mime: String, val width: Int, val height: Int, val path: String, val thumbnail: ByteArray) {
    override fun equals(other: Any?) = other is ChatImage && id == other.id && path == other.path && width == other.width && height == other.height
    override fun hashCode() = id.hashCode()
}

/** A declined question on its way back to the composer: its text and its images, as they were picked. */
data class ReturnedQuestion(val text: String, val images: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /**
     * While a Think Harder turn generates, the level its request sent ("high", or the pick when that is higher), from
     * the same fields the transport puts in the body; the chip shows it in place of the pick (developer mode, the maintainer
     * 2026-11-14: "for the duration of a think harder, the composer should show the actual reasoning level"). Null on
     * every other turn, and as the turn ends, stops or fails, or the model refuses the level.
     */
    val thinkHarderLevel: String? = null,
    val error: String? = null,
    val isStreaming: Boolean = false,
    /**
     * prepareTurn is holding this turn's send (S32; iOS ChatGeneration.waitingForVerification @ 59b9280e): the reply
     * placeholder reads "waiting for verification…", never thinking. Set at the tap only for a place whose gate waits
     * (ATTESTATION), so a plain-TLS or on-device turn never flashes it (formal/ WaitingOnlyWhenTheGateCanHold).
     */
    val waitingForVerification: Boolean = false,
    val awaitingModel: Boolean = false,
    /** The request in flight with no response headers yet, or null (ProviderWait; the chip shows it from 5 s). */
    val providerWait: ProviderWait? = null,
    /** Think Harder's old reply, dimmed until the new one shows text (ChatGeneration.superseding, mirrored for the screen). */
    val superseding: ReplySupersession = ReplySupersession(),
    /** A retry found no place: the screen shows the composer's "no provider configured" alert, then calls [ChatViewModel.placeRequestShown]. */
    val needsPlace: Boolean = false,
    /**
     * Verdicts were still pending at the settle timeout with the seal in hand: the screen asks the still-checking
     * question (near-send-gate-rule), then calls [ChatViewModel.hardwareConfirmShown].
     */
    val hardwareConfirmRequested: Boolean = false,
    /** What was still pending when the settle timeout asked (S30): picks the question; read only while [hardwareConfirmRequested]. */
    val pendingAskKind: ai.teemoon.confidential.ConfidentialSession.PendingKind = ai.teemoon.confidential.ConfidentialSession.PendingKind.HARDWARE,
    /**
     * A question the still-checking ask returned to the composer (reply-machine.json "returned": declined on a fresh
     * send). The chat screen merges it with whatever is in the composer (ComposerReturn) and clears this.
     */
    val returnedQuestion: ReturnedQuestion? = null,
    /** A send with images to a model that cannot see them was refused (IMAGES.md §4); the screen says why. */
    val imageRefusal: String? = null,
    /** This turn's images that were not sent (older than the newest 4 / 8 MiB, or a model that cannot see images). */
    val imagesLeftOut: String? = null,
    /** Sources found this turn, for the rail. */
    val sources: List<GroundingSource> = emptyList(),
    val isExecutingTools: Boolean = false,
    /** Web-search offer cards, by the assistant message they sit under. */
    val offers: Map<String, String> = emptyMap(),
    /** The developer card's payload for the last request. */
    val lastRequestDebugInfo: LastRequestDebugInfo? = null,
    /** The error card's payload; `error` is its one-line form. */
    val lastError: LlmError? = null,
    /** When the turn in flight started, for the activity chip's elapsed seconds. */
    val turnStartedAtMs: Long? = null,
    /** The turn in flight runs on the phone, so an engine load can stand in for "thinking" (EngineLoadLine). */
    val turnOnDevice: Boolean = false,
    /** The persisted thread has been read (restore ran, even for an empty thread): before that the transcript shows nothing, not the moon. */
    val loaded: Boolean = false,
)




/**
 * A COLLECTOR, not an engine. The tool-round loop, the budget, the exhausted
 * notice, the reasoning fallback and containment all live in GenerationEngine —
 * where iOS keeps them — so there is one loop to keep in step with the Swift,
 * not two. Per-turn state is [ChatGeneration]; this class drives it.
 */
class ChatViewModel(
    private val repo: ChatRepository? = null,
    private val threadId: String? = null,
    private val provider: () -> Provider? = { null },
    /** The stored API key for a provider — the Keystore, read at send time so a rotation takes effect on the next turn. */
    private val credential: (Provider) -> String = { "" },
    /** Brave Search key, or null when grounding is off. Keyless still attaches the tool (see ChatGeneration.makeGroundingTools). */
    private val groundingKey: () -> String? = { null },
    /** A key is stored and the web chip is off: on device that sends no tool (ChatGeneration.makeGroundingTools). */
    private val searchKeyTurnedOff: () -> Boolean = { false },
    /** android-only: the equipped local model's KV window, which sizes the search payload (docs/ANDROID_NATIVE.md); null for a cloud model. */
    private val localContextTokens: (Provider) -> Int? = { null },
    /** The attestation session: it opens and closes every turn's E2EE bookkeeping; this class never writes those fields. */
    private val session: ConfidentialSession? = null,
    /** "Recently used" means used — fired when a model first produces output, not when it is picked (iOS: ChatGeneration.onFirstToken). */
    private val onFirstToken: (Provider) -> Unit = {},
    /** The user's system prompt, resolved per turn ({{datetime}}). */
    private val systemPrompt: () -> String = { "" },
    /** A key accepted through the offer card: stored, and grounding switched on (AppState.setBraveKey). */
    private val onBraveKey: (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    /** iOS ChatGeneration.makeLanguageModel: a test's scripted transport in place of the network one. */
    private val makeTransport: ((endpoint: String, model: String, context: TeeContext?, events: StreamCallbacks, effort: ReasoningEffort?, affinityID: String?) -> GenerationTransport)? = null,
    /** How hard the provider's model can be asked to think (AppState.reasoningControl); a test passes its own. */
    private val reasoningControl: (Provider) -> ReasoningControl = { ReasoningControl.resolve(it, null, null) },
    /** Awaited before a turn is resolved: a self-hosted server's kind, learned on its first send (AppState.firstSendKind). */
    private val beforeSend: suspend (Provider) -> Unit = {},
    /** IMAGES.md §4: whether the place's model takes images, from its catalogue row; unknown is no. */
    private val canSeeImages: (Provider) -> Boolean = { false },
    /** Before a photo is refused: a home model's own machine asked once (AppState.recheckHomeImages, iOS 6218fbf5). */
    private val recheckHomeImages: suspend (Provider) -> Boolean = { false },
    /** Whether the phone has a network: Android's offline phone fails DNS like an unreachable host, so the card asks (NetworkFailure). */
    private val isOnline: () -> Boolean = { true },
    /** A stored image's bytes (its file), or null when it cannot be read — it is then left out like an old one. */
    private val imageBytes: (ChatImage) -> ByteArray? = { null },
    /** Stores a sent message's images (rows and files); returns them as stored. */
    private val saveImages: suspend (messageId: String, images: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>) -> List<ChatImage> = { _, _ -> emptyList() },
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val generation = ChatGeneration()
    private val writes = kotlinx.coroutines.sync.Mutex()

    // MARK: - The reply machine (conformance/reply-machine.json, ReplyMachine.kt): the only writer of a reply's status.
    // Every turn event goes through [fire]; the effects it returns are applied here, and a callback for a generation
    // that is not the live one changes nothing (G1/G2/G6). The machine itself never touches the store or the screen.

    private val replies = ai.teemoon.chat.ReplyMachine()
    private var lastGeneration = 0

    /** What a generation's row is written from: its id and thread, and what the turn has gathered so far. */
    private class ReplyRowContext(val rowId: String, val threadId: String, val createdAtMs: Long) {
        var text = ""; var sourcesJson: String? = null; var sealed = false; var elapsedMs: Long? = null
        /** The question this reply answers, kept so a declined fresh send can go back to the composer whole. */
        var questionId: String? = null; var questionText = ""; var questionImages: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded> = emptyList()
        /** Think Harder's save: the old reply deleted, the question restamped and this one saved, in one transaction. */
        var supersedeSave: ((MessageEntity) -> Unit)? = null
        /** A retry's question write (the old turn deleted, the question saved again): held until the machine commits it. */
        var heldQuestion: (() -> Unit)? = null
        /** A retry ended before its request went out with a reply to restore: the chat as it was before the retry. */
        var undo: (() -> Unit)? = null
        /** A retry or think harder: its effort, so a Re-verify that declines it can keep it for a later "send anyway". */
        var retryEffort: ReasoningEffort? = null
    }
    private val replyRows = HashMap<ai.teemoon.chat.ReplyGeneration, ReplyRowContext>()

    private fun nextGeneration() = ai.teemoon.chat.ReplyGeneration(++lastGeneration)

    /** A row the machine names (a reply this view model did not start, e.g. read from the store): a generation of its own. */
    private fun generationOfRow(rowId: String): ai.teemoon.chat.ReplyGeneration =
        replyRows.entries.firstOrNull { it.value.rowId == rowId }?.key
            ?: nextGeneration().also { replyRows[it] = ReplyRowContext(rowId, threadId ?: "", 0) }

    private fun isLive(g: ai.teemoon.chat.ReplyGeneration) = replies.live == g

    /**
     * The machine and [replyRows] have two writers: the turn and stop() on the main thread, and the stream's onText on
     * the transport's IO thread (OpenAiTransport.runTurn calls back from inside its withContext(Dispatchers.IO)). On iOS
     * this view model is @MainActor and they never meet; here a stop could land between a token's live check and its
     * fire. One lock, held by [fire] and by onText's check-then-fire. Reentrant (fire's effects may fire), and nothing
     * held under it blocks: the effects launch their writes and update state.
     */
    private val machineLock = Any()

    /** Sends [event] to the machine and applies what it returns. */
    private fun fire(event: ai.teemoon.chat.ReplyEvent): List<ai.teemoon.chat.ReplyEffect> = synchronized(machineLock) { fireLocked(event) }

    private fun fireLocked(event: ai.teemoon.chat.ReplyEvent): List<ai.teemoon.chat.ReplyEffect> {
        val before = replies.live
        val effects = replies.handle(event)
        var savedBySupersede = false
        var lastRow: String? = null
        for (e in effects) when (e) {
            is ai.teemoon.chat.ReplyEffect.PersistRow -> {
                // an unsent retry commits (its request goes out, or it ends with nothing to restore): its question first
                replyRows[e.generation]?.let { ctx -> ctx.heldQuestion?.let { ctx.heldQuestion = null; it() } }
                lastRow = replyRows[e.generation]?.rowId; savedBySupersede = persistRow(e.generation, e.row)
            }
            is ai.teemoon.chat.ReplyEffect.DeleteRows -> if (!savedBySupersede) {
                val ids = e.generations.mapNotNull { replyRows[it]?.rowId }
                store { r, _ -> r.deleteReplies(ids) }
            }
            is ai.teemoon.chat.ReplyEffect.PersistSentBeforeConfirmed -> Unit   // written with the row it belongs to
            // declined on a fresh send: the question leaves the chat and the store and goes back to the composer; the
            // deleteRows that follows takes its reply row. Nothing is persisted and no marker is shown.
            is ai.teemoon.chat.ReplyEffect.ReturnQuestionToComposer -> returnToComposer(e.generation)
            // the question is written by send() itself, before the machine starts (so a retry's delete can't take it)
            is ai.teemoon.chat.ReplyEffect.PersistQuestion -> Unit
            is ai.teemoon.chat.ReplyEffect.ShowMarker -> lastRow?.let { id ->
                _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) it.copy(marker = e.marker.rawValue) else it }) } }
            // the rest are driven by the turn's own code, in the order the machine allows them (startGate → the gate
            // after prepareTurn, askUser → hardwareConfirmRequested, sendRequest → the engine, showLastRequestCard → the
            // error card, cancelEngine → the job's cancel, swapSupersededDisplay → ReplySupersession's swap)
            else -> Unit
        }
        // A retry that ended before its request went out, with a reply the user had (0.6.2 "never destroy a reply the
        // user had"): nothing was written, and the chat goes back to how it was. A Think Harder restores on its own.
        if (before != null && replies.live == null && replies.state == ai.teemoon.chat.ReplyState.RESTORED)
            replyRows[before]?.let { ctx -> ctx.heldQuestion = null; ctx.undo?.let { ctx.undo = null; it() } }
        return effects
    }

    /** persistRow: the generation's row with the status the machine decided. True when Think Harder's transaction saved it. */
    private fun persistRow(g: ai.teemoon.chat.ReplyGeneration, row: ai.teemoon.chat.ReplyRow): Boolean {
        val ctx = replyRows[g] ?: return false
        val keepsText = row.content.isNotEmpty()
        val ended = row.status != ai.teemoon.chat.ReplyStatus.INTERRUPTED
        val reply = MessageEntity(id = ctx.rowId, threadId = ctx.threadId, role = "assistant",
            content = if (keepsText) ctx.text.ifEmpty { row.content } else "",
            timestamp = if (ended) now() else ctx.createdAtMs,
            sourcesJson = if (keepsText) ctx.sourcesJson else null, isE2EE = ctx.sealed,
            generatingTimeMs = if (ended) ctx.elapsedMs else null,
            status = row.status.rawValue, sentBeforeConfirmed = row.sentBeforeConfirmed)
        val produced = row.status == ai.teemoon.chat.ReplyStatus.COMPLETE || row.status == ai.teemoon.chat.ReplyStatus.CUT_OFF
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == ctx.rowId) it.copy(status = reply.status, sentBeforeConfirmed = reply.sentBeforeConfirmed ?: it.sentBeforeConfirmed) else it }) }
        ctx.supersedeSave?.takeIf { produced }?.let { it(reply); return true }
        store { r, _ -> r.addMessage(reply.id, reply.threadId, reply.role, reply.content, reply.timestamp, sourcesJson = reply.sourcesJson,
            isE2EE = reply.isE2EE, generatingTimeMs = reply.generatingTimeMs, status = reply.status, sentBeforeConfirmed = reply.sentBeforeConfirmed) }
        return false
    }

    private fun returnToComposer(g: ai.teemoon.chat.ReplyGeneration) {
        val ctx = replyRows[g] ?: return
        val questionId = ctx.questionId ?: return
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == questionId || it.id == ctx.rowId },
            returnedQuestion = ReturnedQuestion(ctx.questionText, ctx.questionImages)) }
        store { r, _ -> r.deleteReplies(listOf(questionId)) }
    }

    /** A record's verdict settled (SentBeforeConfirmed): this chat's replies on it lose or change their mark. */
    fun resolveSentMarks(recordKey: String, outcome: ai.teemoon.chat.SentBeforeConfirmed.Outcome) = _state.update { s ->
        s.copy(messages = s.messages.map { it.copy(sentBeforeConfirmed = ai.teemoon.chat.SentBeforeConfirmed.resolved(it.sentBeforeConfirmed, recordKey, outcome)) })
    }

    /** The chat screen has put the returned question in the composer. */
    fun returnedQuestionTaken() = _state.update { it.copy(returnedQuestion = null) }

    /**
     * How the reply ended, as the kit reads it (G4): finish_reason length or content_filter, or the on-device cap, is cut
     * short; a stream that closed with neither [DONE] nor a finish_reason was cut short too; finish(stop) without [DONE]
     * is complete.
     */
    private fun replyEnd(g: ai.teemoon.chat.ReplyGeneration, last: ai.teemoon.inference.TurnResult?): ai.teemoon.chat.ReplyEvent {
        val reason = when {
            last?.finishReason == "length" || last?.wasTruncated == true -> ai.teemoon.chat.FinishReason.LENGTH
            last?.finishReason == "content_filter" -> ai.teemoon.chat.FinishReason.CONTENT_FILTER
            else -> ai.teemoon.chat.FinishReason.STOP
        }
        if (last != null && last.endedWithoutDone && last.finishReason == null && reason == ai.teemoon.chat.FinishReason.STOP)
            return ai.teemoon.chat.ReplyEvent.StreamEndedWithoutDone(g)
        return ai.teemoon.chat.ReplyEvent.Finish(g, reason)
    }

    /**
     * The "still checking" question was dismissed without "send anyway" (the kit's decline): the waiting reply ends
     * stopped, so the chat is free for the next message.
     */
    fun declineConfirm() = answerStillChecking(sendIt = false)

    /** The turn waiting on the still-checking question, if one is. */
    private var stillChecking: CompletableDeferred<Boolean>? = null

    /**
     * The still-checking question's answer, for the turn that is waiting on it: "send anyway" sends THAT reply (the kit's
     * confirm on the same generation, so a pending sent-before-confirmed mark is written); anything else is the decline.
     */
    fun answerStillChecking(sendIt: Boolean) {
        if (!sendIt) replies.live?.let { g -> if (replies.state == ai.teemoon.chat.ReplyState.AWAITING_CONFIRM) fire(ai.teemoon.chat.ReplyEvent.Decline(g)) }
        stillChecking?.complete(sendIt)
        _state.update { it.copy(hardwareConfirmRequested = false) }
    }

    private var job: Job? = null


    /** Replays a persisted thread into the transcript, each message with its images. */
    fun restore(messages: List<MessageEntity>, attachments: List<ai.teemoon.chat.AttachmentEntity> = emptyList()) {
        if (_state.value.messages.isNotEmpty() || messages.isEmpty()) { _state.update { it.copy(loaded = true) }; return }
        val byMessage = attachments.groupBy { it.messageId }
        _state.update { s -> s.copy(loaded = true,
            messages = messages.map { ChatMessage(it.role, it.content, id = it.id, createdAtMs = it.timestamp, sources = GroundingSource.decodeList(it.sourcesJson), isE2EE = it.isE2EE, generatingTimeMs = it.generatingTimeMs,
                images = byMessage[it.id].orEmpty().sortedBy { a -> a.idx }.map { a -> ChatImage(a.id, a.mime, a.width, a.height, a.path, a.thumbnail) },
                // launch recovery (the kit): the saved status says how the turn ended; a row still "interrupted" reads
                // didn't finish + retry. Nothing is written and nothing is asked again.
                marker = if (it.role == "assistant") ai.teemoon.chat.ReplyStatus.reloadMarker(ai.teemoon.chat.ReplyStatus.fromRaw(it.status))?.rawValue else null,
                sentBeforeConfirmed = it.sentBeforeConfirmed, status = it.status) }) }
    }

    /** A thread's images, as they arrive from the store: each message gets its own, in order. */
    fun applyAttachments(attachments: List<ai.teemoon.chat.AttachmentEntity>) {
        val byMessage = attachments.groupBy { it.messageId }
        _state.update { s -> s.copy(messages = s.messages.map { m ->
            byMessage[m.id]?.let { list -> m.copy(images = list.sortedBy { it.idx }.map { a -> ChatImage(a.id, a.mime, a.width, a.height, a.path, a.thumbnail) }) } ?: m
        }) }
    }

    /** A stored image's bytes, for the viewer. */
    fun imageBytesFor(image: ChatImage): ByteArray? = image.path.takeIf { it.isNotEmpty() }?.let { imageBytes(image) }

    /** The composer's send/blind (ComposerSend): the refused alert, the images still in the composer. */
    fun refuseImages() = _state.update { it.copy(imageRefusal = ai.teemoon.app.imaging.CantSeeRoute.REFUSED) }

    /** The screen showed [ChatUiState.imageRefusal]. */
    fun imageRefusalShown() = _state.update { it.copy(imageRefusal = null) }

    /** Whether the current place can take images: the composer's paperclip asks before it opens the picker. */
    fun imagesAllowed(): Boolean = provider()?.let(canSeeImages) == true

    /**
     * The composer's attached photos and their note, held here rather than in the screen: a font-size change rebuilds
     * the activity (it handles rotation and dark mode itself, not font scale), and a plain remember lost them while the
     * text survived (the maintainer's Pixel, 2026-11-12). Compose state, so the composer recomposes on a change.
     */
    val draftImagesState = androidx.compose.runtime.mutableStateOf(listOf<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>())
    val draftImageNoteState = androidx.compose.runtime.mutableStateOf<String?>(null)
    var draftImages: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>
        get() = draftImagesState.value
        set(v) { draftImagesState.value = v }
    var draftImageNote: String?
        get() = draftImageNoteState.value
        set(v) { draftImageNoteState.value = v }
    /** Photos chosen but not yet read and decoded: a placeholder tile each, and send waits (ComposerImages.canSend). */
    val draftPendingState = androidx.compose.runtime.mutableStateOf(0)
    val draftPending: Int get() = draftPendingState.value
    /** [n] photos are on their way (the picker returned them, a paste or a share brought them, the camera took one). */
    fun pendingStarted(n: Int) { if (n > 0) draftPendingState.value += n }
    /** [n] pending photos have been read: the ones that decoded join the draft and the tiles go, in ONE write. */
    fun pendingLanded(n: Int, frames: List<ai.teemoon.app.imaging.ImageDecodeFrame>, limit: Int, note: String? = null) {
        // one snapshot: the strip never draws a frame with neither the tile nor its photo
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            val added = ai.teemoon.app.imaging.ComposerImages.add(draftImages, frames, limit)
            draftImagesState.value = added.kept
            (note ?: added.note)?.let { draftImageNoteState.value = it }
            draftPendingState.value = (draftPending - n).coerceAtLeast(0)
        }
    }
    /** [n] pending photos will never land (the read was cancelled): their tiles go. */
    fun pendingDropped(n: Int) { draftPendingState.value = (draftPending - n).coerceAtLeast(0) }

    /**
     * Before the "can't see images" alert (iOS 6218fbf5; the maintainer 2026-11-12): a home Ollama model whose ability
     * is unknown or stale has its machine asked about that one model; true when it reads images (and that is kept).
     */
    suspend fun recheckImages(): Boolean = provider()?.let { recheckHomeImages(it) } == true

    /**
     * [acceptedDegrade] is the user having chosen "send anyway" on the soft alert, which a CONFIRM after
     * prepareTurn settles may honour; a BLOCK never is (iOS ChatViewModel.generate, 9f4aa84).
     */
    /**
     * [effort]: chat sends low unless the person asks for more (iOS ad960c7, the maintainer 2026-10-19); null is the provider's own
     * default. [askedAtMs]: the question's time, kept by a plain retry (iOS restampIfReasked); null is now — a new
     * question, or Think Harder asking again. [reasking]: Think Harder's question, already in the transcript and the
     * store, asked again in place of [superseded] — the replies that stay, dimmed, until the new one lands.
     */
    fun send(prompt: String, acceptedDegrade: Boolean = false, effort: ReasoningEffort? = ReasoningEffort.CHAT_DEFAULT, askedAtMs: Long? = null,
             reasking: ChatMessage? = null, superseded: Set<String> = emptySet(), retried: Boolean = false,
             /** Picked images, already decoded and re-encoded in the isolated process (IMAGES.md §4). */
             images: List<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded> = emptyList(),
             /** A retry's stored turns from this time on: deleted in the same ordered write that saves the question again. */
             clearStoredFromMs: Long? = null,
             /** A retry's turn as it was: what the chat goes back to if the retry ends before its request goes out. */
             replacing: RetriedTurn? = null) {
        if ((prompt.isBlank() && images.isEmpty()) || _state.value.isStreaming) return
        // The stop window (pm sweep 2026-11-06, A2): stop() settles the screen at once, but the stopped turn's job runs
        // until its read returns, and the engine takes one turn at a time. A send here passed the check above, the
        // composer cleared, and the turn refused to begin — the message was lost. It waits for the stopped turn.
        job?.takeIf { it.isCancelled && !it.isCompleted }?.let { stopping ->
            viewModelScope.launch { stopping.join(); send(prompt, acceptedDegrade, effort, askedAtMs, reasking, superseded, retried, images, clearStoredFromMs, replacing) }
            return
        }
        // No place, no turn. This is the last gate, not the first — the composer's SendPrep stops a send with no place
        // (BLOCKED_NO_PROVIDER) — but the error card's retries call send() directly. There used to be a compiled
        // default endpoint (the emulator's fake server, in clear) for them to fall back to; flagged by the local audit.
        val place = provider() ?: return
        // Whether this turn started attestable (an attested or unknown near.ai tier): such a turn never leaves unsealed.
        val startedAttestable = ProviderCapability.ATTESTATION in place.capabilities
        // "send anyway" to a soft state: remembered for this record and reason, so it isn't asked again (near-send-gate-rule)
        if (acceptedDegrade) { session?.let { it.rememberConfirmation(pending = _state.value.hardwareConfirmRequested || it.sendPending) }; _state.update { it.copy(hardwareConfirmRequested = false) } }
        // IMAGES.md §4: images go only to a model whose catalogue says it takes them. A refused send sends nothing.
        val vision = canSeeImages(place)
        if (images.isNotEmpty() && !vision) { _state.update { it.copy(imageRefusal = ai.teemoon.app.imaging.CantSeeRoute.REFUSED) }; return }
        // A new message instead of an answer to the "still checking" question: that question is declined (the kit's
        // decline), so the chat is free for this one. "send anyway" comes back as a retry and replaces it instead.
        if (!retried && reasking == null && replies.state == ai.teemoon.chat.ReplyState.AWAITING_CONFIRM) replies.live?.let { fire(ai.teemoon.chat.ReplyEvent.Decline(it)) }
        val startMs = now()
        if (!generation.beginTurn(startMs)) return
        // The old reply stays until the new one lands (ReplySupersession); the model is never sent it, nor the
        // question a second time.
        if (reasking != null) setSuperseding(ReplySupersession(superseded))
        val prior = generation.superseding.history(_state.value.messages).let { m -> if (reasking != null) m.takeWhile { it.id != reasking.id } else m }
        // "cache — first message" is a chat's first SEND only. Asking the first question again — a retry, Think Harder —
        // reads its figure, "cache 0%" on a miss (the maintainer, 2026-10-21, cache-first-message-retry).
        generation.turnIsFirstMessage = reasking == null && !retried && prior.none { it.role == "user" }
        val userMessageId = reasking?.id ?: "m-$startMs"
        val askedAt = askedAtMs ?: startMs
        val assistantId = "a-$startMs"
        val tid = threadId ?: ""

        // The wire transcript: system prompt (unless the provider forbids one), then the prior turns capped from the end.
        // Every user turn carries the time IT was sent, from its stored timestamp (iOS 3f21459/0802a0f): the same turn
        // spells the same bytes on every request, so a cached prefix and a kept on-device conversation hold.
        val timeNotes = ChatGeneration.wantsTimeNotes(systemPrompt(), omitSystemPrompt = place.effectiveOmitSystemPrompt)
        // What this request may say about the person, decided beside the place it goes to (docs/DISCLOSURE.md, iOS
        // 868edec6). Sealed is the place's end-to-end promise, not the live seal iOS reads: this transcript is built
        // before the turn's TEE context, and an attestable turn never leaves unsealed (startedAttestable above).
        val disclosure = ai.teemoon.providers.DisclosurePolicy.of(ai.teemoon.providers.WhereLocality.of(place), sealed = place.claimsEndToEndEncryption)
        fun noted(text: String, atMs: Long) = if (timeNotes) text + ChatGeneration.timeNote(atMs, disclosure) else text
        fun wired(m: ChatMessage) = m.images.mapNotNull { ci -> imageBytes(ci)?.let { ai.teemoon.inference.WireImage(ci.mime, it) } }
        // an empty reply row (a turn that failed, or was stopped before its first word) never goes on the wire
        val all = prior.filter { it.toolCall == null && !(it.role == "assistant" && it.text.isBlank()) }.map { WireMessage(it.role, if (it.role == "user") noted(it.text, it.createdAtMs) else it.text, wired(it)) } +
            WireMessage("user", prompt)
        // The question's images: picked now, or — Think Harder asking again — the ones it already carries.
        val askedImages = images.map { ai.teemoon.inference.WireImage(it.mime, it.image) } + (reasking?.let(::wired) ?: emptyList())
        // IMAGES.md §4: the newest 10 images / 8 MiB travel (on the phone: up to 10, the question's then earlier ones, §4b @ 00f72739); older ones, or all of them to a model that cannot see images,
        // become a fixed line for this request only.
        val wire = ChatGeneration.buildWireMessages(all, ChatGeneration.resolvePromptTemplates(systemPrompt(), disclosure = disclosure),
            maxMessages = place.effectiveMaxMessages, omitSystemPrompt = place.effectiveOmitSystemPrompt)
        val capped = if (place.isLocal && vision) ai.teemoon.app.imaging.ImageHistory.applyOnDevice(wire, askedImages, prepare = ai.teemoon.app.imaging.OnDeviceImage::prepare)
            else ai.teemoon.app.imaging.ImageHistory.apply(wire, askedImages, vision)
        val history = capped.history
        val promptImages = capped.promptImages
        val leftOutNote = capped.leftOut.takeIf { it > 0 }?.let { n ->
            if (vision) ai.teemoon.app.imaging.ImageCopy.leftOutForSize(n) else ai.teemoon.app.imaging.ImageCopy.leftOutCantSee(n) }
        val shownImages = images.mapIndexed { i, d -> ChatImage("$userMessageId#$i", d.mime, d.width, d.height, "", d.thumbnail) }

        _state.update { it.copy(
            messages = it.messages +
                listOfNotNull(ChatMessage("user", prompt, id = userMessageId, createdAtMs = askedAt, images = shownImages).takeIf { reasking == null }) +
                ChatMessage("assistant", "", streaming = true, id = assistantId, createdAtMs = startMs + 1),
            error = null, lastError = null, isStreaming = true,
            thinkHarderLevel = if (effort == ReasoningEffort.THINK_HARDER) ReasoningEffort.sentValue(ReasoningEffort.For(effort, place).bodyFields()) else null,
            // in the tap's own frame, or the placeholder reads thinking before the gate's wait shows
            waitingForVerification = ProviderCapability.ATTESTATION in place.capabilities,
            sources = emptyList(), isExecutingTools = false, awaitingModel = false, providerWait = null, turnStartedAtMs = startMs, turnOnDevice = place.isLocal,
            imagesLeftOut = leftOutNote,
        ) }

        val endpoint = place.chatCompletionsUrl
        val model = place.model
        val providerName = place.name

        // A retry's delete, then the question again, in that order — and only when the machine commits the retry (its
        // request goes out, or it ends with no reply to restore): until then the store keeps the turn the user had.
        val writeQuestion = { store { r, t ->
            run {
                run {
                    clearStoredFromMs?.let { r.deleteMessagesSince(t, it) }
                    r.addMessage(userMessageId, t, "user", prompt, askedAt)
                    // A question of images alone is titled "image" / "N images" (IMAGES.md §5).
                    r.titleFromFirstMessage(t, prompt.ifBlank { if (images.isNotEmpty()) ai.teemoon.chat.ChatRepository.imagesTitle(images.size) else prompt })
                    if (images.isNotEmpty()) {
                        val stored = saveImages(userMessageId, images)
                        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == userMessageId) it.copy(images = stored) else it }) }
                    }
                }
            }
        } }
        if (reasking == null && !retried) writeQuestion()

        // The reply's generation (the kit's newGeneration), started after the question's own write so a retry's delete
        // never takes the new row: a plain turn writes its row "interrupted" now, Think Harder writes nothing yet.
        val g = nextGeneration()
        val ctx = ReplyRowContext(assistantId, tid, startMs + 1).also { replyRows[g] = it }
        if (reasking == null) { ctx.questionId = userMessageId; ctx.questionText = prompt; ctx.questionImages = images }
        if (reasking == null && retried) ctx.heldQuestion = writeQuestion
        if (reasking != null || retried) ctx.retryEffort = effort ?: ReasoningEffort.CHAT_DEFAULT
        replacing?.let { was -> ctx.undo = {
            generation.offerByMessageID.putAll(was.offers)
            _state.update { it.copy(messages = was.messages, offers = generation.offerByMessageID.toMap()) }
        } }
        val replyPlace = if (place.isLocal) ai.teemoon.chat.ReplyPlace.LOCAL else ai.teemoon.chat.ReplyPlace.REMOTE
        if (reasking != null) ctx.supersedeSave = { reply ->
            when (val outcome = generation.superseding.outcome(failed = false, cancelled = false, output = reply.content)) {
                ReplySupersession.Outcome.Restore -> Unit
                is ReplySupersession.Outcome.Replace -> {
                    val question = MessageEntity(id = userMessageId, threadId = tid, role = "user", content = prompt, timestamp = askedAt)
                    land(outcome.replyIDs, userMessageId, askedAt)
                    store { r, _ -> r.supersede(outcome.replyIDs.toList(), question, askedAt, reply) }
                }
                null -> store { r, _ -> r.addMessage(reply.id, reply.threadId, reply.role, reply.content, reply.timestamp, sourcesJson = reply.sourcesJson,
                    isE2EE = reply.isE2EE, generatingTimeMs = reply.generatingTimeMs, status = reply.status, sentBeforeConfirmed = reply.sentBeforeConfirmed) }
            }
        }
        fire(when {
            reasking != null -> ai.teemoon.chat.ReplyEvent.ThinkHarder(g, replyPlace, superseded.map(::generationOfRow))
            retried -> ai.teemoon.chat.ReplyEvent.Retry(g, replyPlace, (replyRows.filter { (k, v) -> k != g && clearStoredFromMs != null && v.createdAtMs >= clearStoredFromMs }.keys
                + listOfNotNull(replies.live) + replacing?.replyIds.orEmpty().map(::generationOfRow)).distinct(),
                restorable = replacing?.restorable == true)
            else -> ai.teemoon.chat.ReplyEvent.Send(g, replyPlace, images = images.size)
        })

        val tools = generation.makeGroundingTools(place, groundingKey(), tid, localContextTokens(place), today = disclosure.toolDay(java.time.Instant.now()),
            searchTurnedOff = searchKeyTurnedOff())

        // Opened per turn below: the transport is built after prepareTurn so it carries this turn's context.
        var lastResult: RequestResult? = null
        var persisted = false
        // This turn's job: once stopped, its stream writes nothing. A transport whose read outlives the stop (a
        // blocked read, an on-device engine finishing its step) used to write into whatever row was then last — after
        // a stopped Think Harder, the restored old reply; after a stop before any text, the question's own bubble.
        var turnJob: Job? = null
        fun stopped() = turnJob?.isCancelled == true
        // The reply's one save. Under Think Harder it is iOS's `.replace`: the old reply deleted, the question
        // restamped and the new reply saved in one transaction, and the transcript swapped in the same update; a
        // blank reply is `.restore` — nothing saved, and the turn's end puts the old one back.
        // What the reply's row is written from: the machine decides when and with which status (persistRow, above).
        fun persistReply(text: String, sources: List<GroundingSource>, sealed: Boolean, elapsed: Long?) {
            ctx.text = text; ctx.sourcesJson = sources.takeIf { it.isNotEmpty() }?.let(GroundingSource::encodeList); ctx.sealed = sealed; ctx.elapsedMs = elapsed
        }
        fun engine(context: TeeContext?): GenerationEngine {
            val callbacks = object : StreamCallbacks {
                // Called on the transport's IO thread: the live check and the token's fire are one step under the
                // machine's lock, so a stop on the main thread lands before it or after it, never between. The first
                // token's recent-use write (a file) happens after the lock is let go.
                override fun onText(text: String) {
                    val first = synchronized(machineLock) {
                        if (stopped() || !isLive(g)) return
                        if (text.isNotEmpty()) { ctx.text = text; fire(ai.teemoon.chat.ReplyEvent.Token(g, text)) }
                        val first = text.isNotEmpty() && generation.markFirstToken(now())
                        generation.output = text
                        // The first text of Think Harder's new reply takes the old one out, in the same update.
                        replaceStreamingText(text, generation.superseding.takeIf { it.isActive && !it.swapped && text.isNotEmpty() }?.noteText(text))
                        first
                    }
                    if (first) onFirstToken(place)
                }
                // Every callback below is the stopped turn's no more (G1, pm sweep 2026-11-06): a stop, then a send, and
                // the old search's sources joined the new reply, its spinners and its success landed on the new turn.
                override fun onAwaitingModel(turn: Int, awaiting: Boolean) {
                    if (stopped()) return
                    generation.noteAwaiting(turn, awaiting)
                    _state.update { it.copy(awaitingModel = generation.isAwaitingModel) }
                }
                override fun onAwaitingResponse(turn: Int, sentAt: java.time.Instant?) {
                    if (stopped()) return
                    generation.noteResponseWait(turn, sentAt?.toEpochMilli(), providerName)
                    // a refused level is sent again without it: the chip stops claiming one
                    _state.update { it.copy(providerWait = generation.providerWait, thinkHarderLevel = it.thinkHarderLevel?.takeUnless { ReasoningEffort.wasRefused(place) }) }
                }
                // ONE assistant message per turn, as on iOS: the engine emits CUMULATIVE text across rounds, so
                // opening a second bubble here re-rendered round one's text inside it.
                //
                // Nothing is kept here any more. This used to append to a `toolRecords` list that ChatScreen drew as
                // always-open panels above the reply — an Android-only surface that showed, uncollapsed, what the
                // debug card already shows properly: `RequestDebugCard` renders `info.toolCalls` through
                // `ToolCallRow`, collapsed behind a chevron, which is exactly iOS's CollapsibleDebugRow. Two
                // surfaces for one fact, and the extra one put 29,527 characters of search XML over the answer
                // (2026-09-22). Live feedback that a tool is running is the ActivityChip's job and always was.
                override fun onToolRound(call: ToolCallState, result: String) {}
                override fun onSourcesFound(sources: List<GroundingSource>) {
                    if (stopped()) return
                    generation.noteSources(sources)
                    _state.update { it.copy(sources = generation.groundingSources.toList()) }
                }
                override fun onQueriesFound(queries: List<String>) {
                    if (stopped()) return
                    generation.noteQueries(queries)
                    _state.update { it.copy(isExecutingTools = true) }
                }
                override fun onToolExecutionEnded() {
                    if (stopped()) return
                    generation.noteToolExecutionEnded()
                    _state.update { it.copy(isExecutingTools = false) }
                }
                override fun onWarning(message: String) = TurnWarnings.log(message)
                override fun onSuccess(result: RequestResult) {
                    if (stopped()) return
                    lastResult = result
                    generation.recordSuccess(result, providerName, model, tid)
                    _state.update { it.copy(lastRequestDebugInfo = generation.lastRequestDebugInfo) }
                }
                // The signature check no longer holds the turn (iOS cef6ad8: it added ≥2 s after the last token): its verdict
                // lands here afterwards. The session counts it only while the same provider is selected — a verdict that
                // lands after a switch must not count for the new one; the card takes it only for the same send.
                override val verifiesAfterTurn: Boolean get() = session != null
                override fun onVerified(verification: ResponseVerification) {
                    viewModelScope.launch {
                        generation.applyLateVerification(verification, tid, startMs)
                        if (session?.ownsTurn(place) == true) session.recordVerification(verification)
                        _state.update { it.copy(lastRequestDebugInfo = generation.lastRequestDebugInfo) }
                    }
                }
                // Persist the reply as soon as its text is final — before the signature check, which can take seconds.
                override fun onTurnText(text: String) {
                    if (persisted || stopped()) return; persisted = true
                    // What the reply CITES, as iOS saves it (GroundingSource.citedOrAll): its own links, with the search
                    // snippet where one matched — the whole search haul only when it cites nothing
                    val sources = GroundingSource.citedOrAll(text, generation.groundingSources.toList())
                    val sealed = lastResult?.isE2eeActive ?: (context?.e2eePeer != null)
                    // the thinking time when the reply thought (stamped at `</think>`), else the whole turn
                    val elapsed = _state.value.messages.lastOrNull { it.id == assistantId }?.generatingTimeMs ?: _state.value.turnStartedAtMs?.let { now() - it }
                    persistReply(text, sources, sealed, elapsed)
                }
            }
            // Resolved here, where the store is; the transport reads it per request (iOS 7cd8c10).
            ReasoningControl.note(reasoningControl(place), place)
            return GenerationEngine(
                transport = makeTransport?.invoke(endpoint, model, context, callbacks, effort, SessionAffinity.id(tid)) ?: OpenAiTransport(endpoint, model, apiKey = credential(place), authHeaderName = place.authHeaderName,
                    teeContext = context, extraParams = place.extraParams, providerName = providerName, effort = effort?.let { ReasoningEffort.For(it, place) }, affinityID = SessionAffinity.id(tid)),
                tools = tools,
                events = callbacks,
                // The ceiling is the MODEL's, not the app's, and iOS sets it in two places:
                // GenerationEngine.defaultMaxToolRounds = 4 for anything dialled over the wire,
                // and LocalLanguageModel.swift:136 passes 2 — each extra round on the phone is a
                // full re-prefill plus a serial decode, and two still allows search-then-answer.
                // This passed 2 for every provider, so a hosted model researched two rounds
                // shallower than the same model on iOS (2026-09 port review, B3).
                maxToolRounds = if (place.isLocal) LocalDefaults.MAX_TOOL_ROUNDS else ToolRoundPolicy.DEFAULT_MAX_ROUNDS,
                // The late signature check outlives the turn (StreamCallbacks.verifiesAfterTurn).
                lateScope = viewModelScope,
            )
        }

        // The host wires this to TurnService so a turn survives the user leaving (TurnNotification.kt). Announced
        // HERE, at send, because process priority has to be raised before the foreground is lost — there is no
        // window between `onStop` and the kill in which to do it. Null in tests and previews.
        onTurn?.invoke(true, null, "")
        job = viewModelScope.launch {
            turnJob = coroutineContext[Job]
            try {
                // prepareTurn refreshes a stale record, waits for the model key, and opens the turn.
                val context = session?.prepareTurn(place)
                // the gate's wait is over: what follows is the gate's answer, then the request (formal/ ThinkingOnlyAfterSend)
                _state.update { it.copy(waitingForVerification = false) }
                // A turn that started attestable never leaves unsealed, asked or not (iOS f6d44c1b; reply-machine.json 0.6.6
                // gateWouldGoPlain, windows A and B): the tier resolved to third-party meanwhile, no peer remains, or its place
                // is no longer the one selected (iOS re-enters prepareTurn, whose ownsTurn opens nothing). Nothing is sent;
                // it ends like a decline — a fresh question back in the composer, a retry's reply kept — and the next send
                // meets the normal flow.
                fun refuseIfItWouldGoPlain() {
                    if (!startedAttestable) return
                    if (context?.e2eePeer != null && session.ownsTurn(place) && ProviderCapability.ATTESTATION in place.capabilities) return
                    session?.abandonTurn(null)
                    fire(ai.teemoon.chat.ReplyEvent.GateWouldGoPlain(g))
                    throw DeclinedBeforeTheWire("")
                }
                // The gate, re-read AFTER prepareTurn settled the refresh it may have started. The composer's check
                // ran on the state before that refresh; a verdict that changed underneath it must not ride the
                // earlier answer (iOS 9f4aa84: every other message to a host whose TLS check failed went out).
                session?.let { s ->
                    // S30: everything pending holds the send — a verdict, a stale refetch, provenance, the tier
                    val pending = s.sendPending
                    // Only a confirmation for THIS record and reason (F4, iOS 6b99b42a): "send anyway" is remembered by the session.
                    when (val gate = postPrepare(s.sendPolicy, s.hasConfirmed(pending), pending, sealed = context?.e2eePeer != null)) {
                        PostPrepare.Proceed -> Unit
                        // Still checking at the settle timeout, the seal in hand: ask, don't refuse outright. The question
                        // stays with the "still running" card; "send anyway" asks it again.
                        // Still checking at the settle timeout, the seal in hand: the turn WAITS for the answer on this
                        // same reply (the kit's awaitingConfirm → confirm): "send anyway" sends it, marked sent before
                        // confirmed; "don't send" (or wait, or a dismiss) is the decline and ends it stopped.
                        PostPrepare.ConfirmPending -> {
                            val refusal = postPrepareRefusal(s.sendPolicy, false, pending) ?: ""
                            val sealed = context?.e2eePeer != null
                            fire(ai.teemoon.chat.ReplyEvent.GateNeedsConfirm(g))
                            var asked = s.confirmationKey(pending = true)
                            while (true) {
                                val answer = CompletableDeferred<Boolean>().also { stillChecking = it }
                                val kind = s.pendingKind ?: ai.teemoon.confidential.ConfidentialSession.PendingKind.HARDWARE
                                _state.update { it.copy(hardwareConfirmRequested = true, pendingAskKind = kind) }
                                val sendIt = try { answer.await() } finally { stillChecking = null }
                                if (!sendIt) { s.abandonTurn(refusal); throw DeclinedBeforeTheWire(refusal) }
                                // the setup or model was switched, or the tier moved, while the question was up: not this turn's to send
                                refuseIfItWouldGoPlain()
                                // The answer confirms the record it was asked about, never another (F4, sendgate.json confirmScope)...
                                if (s.confirmationKey(pending = true) == asked) s.rememberConfirmation(pending = true)
                                // ...and "send" re-reads the gate NOW (reply-machine.json 0.6.4): a verdict that landed while the
                                // question was up decides. A hard one, or a soft reason nobody answered, is the kit's gateRefused —
                                // nothing is sent, the question goes back to the composer and the existing refusal shows. It
                                // used to go out unmarked (the verdict was no longer pending) to a host that had just failed.
                                val nowPending = s.sendPending
                                when (val again = postPrepare(s.sendPolicy, s.hasConfirmed(nowPending), nowPending, sealed)) {
                                    PostPrepare.Proceed -> { refuseIfItWouldGoPlain(); break }
                                    PostPrepare.ConfirmPending -> asked = s.confirmationKey(pending = true)   // another record: asked again
                                    is PostPrepare.Refuse -> {
                                        fire(ai.teemoon.chat.ReplyEvent.GateRefused(g))
                                        s.abandonTurn(again.refusal); throw RefusedBeforeTheWire(again.refusal)
                                    }
                                }
                            }
                            fire(ai.teemoon.chat.ReplyEvent.Confirm(g, s.sentBeforeConfirmedMark(sealed)))
                        }
                        is PostPrepare.Refuse -> { fire(ai.teemoon.chat.ReplyEvent.GateRefused(g)); s.abandonTurn(gate.refusal); throw RefusedBeforeTheWire(gate.refusal) }
                    }
                }
                if (mustRefuseUnsealedSend(place, context)) {
                    // FAIL CLOSED. An attested provider with no E2EE peer would reach the transport with a nil codec
                    // and send PLAINTEXT under an E2EE promise. Surface it like a generation error and send NOTHING.
                    // Booked as abandoned, not as an unsealed request: that read as plaintext-went-out once the key
                    // arrived — a hard block only a provider switch cleared.
                    fire(ai.teemoon.chat.ReplyEvent.GateRefused(g))
                    session?.abandonTurn(UNSEALED_SEND_REFUSED)
                    throw RefusedBeforeTheWire(UNSEALED_SEND_REFUSED)
                }
                refuseIfItWouldGoPlain()
                // through the gate: the request goes out (the kit's gatePassed → requesting); a confirm already moved it there
                if (replies.state == ai.teemoon.chat.ReplyState.GATING)
                    fire(ai.teemoon.chat.ReplyEvent.GatePassed(g, sealed = context?.e2eePeer != null, sentBeforeConfirmed = session?.sentBeforeConfirmedMark(context?.e2eePeer != null)))
                generation.seedDebugInfo(providerName, model, endpoint, tid, expectingE2EE = context?.e2eePeer != null)
                // Before engine() notes the ReasoningControl, so an Ollama box's first turn already gets its field.
                beforeSend(place)
                // The question's own time rides on the wire only (ChatGeneration.timeNote); the stored message stays as typed.
                val turnEngine = engine(context)
                val answer = turnEngine.run(history, noted(prompt, askedAt), promptImages)
                // Stopped while the engine was finishing a read that outlived the stop: the turn ends as a stop, and
                // the answer it brought back is neither saved nor shown.
                coroutineContext.ensureActive()
                generation.output = answer
                // The session records what actually went out: sealed, or plaintext to a non-attesting provider.
                session?.finishTurn(isE2EEActive = lastResult?.isE2eeActive ?: (if (context != null) false else null), error = null, verification = lastResult?.teeVerification, place = place)
                // Normally persisted by onTurnText already; this is the fallback for a transport that never reported final text.
                val cited = GroundingSource.citedOrAll(answer, generation.groundingSources.toList())
                if (!persisted) { persisted = true; persistReply(answer, cited, lastResult?.isE2eeActive ?: false, null) }
                // a transport that never streamed: its answer is the reply's text
                if (answer.isNotBlank() && ctx.text.isEmpty()) { ctx.text = answer; fire(ai.teemoon.chat.ReplyEvent.Token(g, answer)) }
                // the turn ends: the machine writes the row once — complete, cut short (G4), or failed when nothing came back
                fire(replyEnd(g, turnEngine.lastTurn))
                // The rail stays with the message it belongs to, as the persisted row does.
                stampLastAssistant(sources = cited, isE2EE = lastResult?.isE2eeActive ?: false)
                val emptyReply = generation.finishGeneration(providerName, endpoint, tid, lastResult?.toolCalls ?: emptyList(), lastResult, now())
                if (emptyReply != null) _state.update { it.copy(error = emptyReply.userMessage, lastError = emptyReply) }
                // The offer attaches to the message its turn produced; a later turn does not erase it.
                generation.attachPendingOffer(assistantId)
                _state.update { it.copy(offers = generation.offerByMessageID.toMap(), lastRequestDebugInfo = generation.lastRequestDebugInfo) }
                onTurn?.invoke(false, TurnOutcome.SUCCEEDED, answer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                onTurn?.invoke(false, TurnOutcome.STOPPED, "")
                // A STOP, not a failure. stop() cancels this job; the cancellation unwinds through the transport's
                // collector and, before this line, was caught below as "An unexpected error occurred …
                // StandaloneCoroutine was cancelled" — an error card for the user's own tap (Pixel 10 Pro,
                // 2026-09-16). What streamed stays; nothing is recorded against the session; the finally settles.
                throw e
            } catch (e: DeclinedBeforeTheWire) {
                // The user's own "don't send" (or wait, or a dismiss): the machine already ended the turn — returned,
                // restored or stopped — and no card repeats their choice (iOS returnGatedQuestion). A refusal is gateRefused's.
                onTurn?.invoke(false, TurnOutcome.STOPPED, "")
            } catch (e: Throwable) {
                // the machine ends the turn: undecryptable is its own status (G5); anything else failed (its row empty)
                if (e !is RefusedBeforeTheWire) fire(if ((e as? LlmError)?.underlyingError === ai.teemoon.confidential.E2EEError.DecryptionFailed)
                    ai.teemoon.chat.ReplyEvent.DecryptFailed(g, "content") else ai.teemoon.chat.ReplyEvent.Error(g, e::class.simpleName ?: "error"))
                if (e !is RefusedBeforeTheWire) session?.finishTurn(isE2EEActive = null, error = ConfidentialSession.RequestError((e as? LlmError)?.httpStatus, e.message ?: e.toString()), verification = null, place = place)
                // An unexpected error is wrapped as iOS does, so the card can name the provider and carry the URL.
                // A refusal before the wire is not unexpected: its card reads the refusal itself, as iOS's does. Anything
                // else gets iOS's cardMessage line (conformance/errorcopy.json); the exception itself stays on the card's
                // details, never in the line.
                val llm = e as? LlmError ?: LlmError(LlmError.Source.Provider(providerName),
                    if (e is RefusedBeforeTheWire) e.refusal
                    else NetworkFailure.cardMessage(e, providerName, answerStarted = generation.output.isNotEmpty(), selfHosted = place.isSelfHosted, online = isOnline()),
                    url = endpoint, underlyingError = e)
                generation.lastError = llm; generation.lastErrorThreadID = tid
                _state.update { it.copy(error = llm.userMessage, lastError = llm) }
                onTurn?.invoke(false, TurnOutcome.FAILED, "")
            } finally {
                generation.endTurn()
                // A failed, stopped or empty Think Harder: the question keeps its old answer, and no partial reply.
                restoreSuperseded()
                settle()
            }
        }
    }

    /**
     * Turn lifecycle for the host: (inFlight, outcome, reply). Set by MainActivity, which has the Context the
     * service needs; the view model stays free of Android imports.
     */
    var onTurn: ((Boolean, TurnOutcome?, String) -> Unit)? = null

    fun stop() { fire(ai.teemoon.chat.ReplyEvent.UserStop); generation.stop(); job?.cancel(); restoreSuperseded(); settle() }

    /**
     * The chat list deleted this thread (A4, pm sweep 2026-11-06). Its turn stops, and nothing more is written to it: the
     * reply's save hit the thread's CASCADE foreign key in a bare launch — an uncaught throw that crashed the app.
     */
    fun threadDeleted() { fire(ai.teemoon.chat.ReplyEvent.DeleteChat); threadGone = true; stop() }

    /** Set by the host to take this view model out of its registry (AppState.openChats). */
    var onCleared: (() -> Unit)? = null
    override fun onCleared() { onCleared?.invoke(); super.onCleared() }
    @Volatile private var threadGone = false

    /** One store write for this thread, skipped once it is deleted; a write already in flight when it went is dropped. */
    private fun store(write: suspend (ai.teemoon.chat.ChatRepository, String) -> Unit) {
        val r = repo ?: return; val t = threadId ?: return
        if (threadGone) return
        viewModelScope.launch {
            // one write at a time, in the order asked (Mutex is FIFO): a reply's "interrupted" row must land before its end
            try { writes.withLock { if (!threadGone) write(r, t) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // Fail loud unless the thread is gone: then the write raced the delete, and there is nothing to keep it in.
                if (!threadGone) throw e
                TurnWarnings.log("write to deleted thread dropped: $e")
            }
        }
    }

    /**
     * The error card's "this model can't search the web — turn off & retry". Grounding is off by the time this runs,
     * and the thread is marked DECLINED as well: keyless, makeGroundingTools still attaches web_search (it is the
     * offer's trigger), so turning search off alone re-sent the same schema and the model refused it with the same
     * 400 — the retry could never answer (the B4 journey, 2026-10-04). iOS closed the same gap that day (0a4df3c,
     * ConversationView declines the thread before the retry).
     */
    fun disableWebSearchAndRetry() {
        generation.declineOffer(threadId ?: "", _state.value.offers.keys)
        _state.update { it.copy(offers = emptyMap()) }
        retryLastTurn()
    }

    /** Asks the last question again (the error card's retry, and the search-off retry above). [effort]: Think Harder's gated path. */
    fun retryLastTurn(acceptedDegrade: Boolean = false, effort: ReasoningEffort = ReasoningEffort.CHAT_DEFAULT) {
        val last = _state.value.messages.lastOrNull { it.role == "user" } ?: return
        retry(from = last, acceptedDegrade = acceptedDegrade, effort = effort)
    }

    /**
     * Think Harder (iOS 42b12d8/0802a0f, the maintainer 2026-10-19): the newest finished reply's question again, same provider and
     * model, at high effort with room to think, in place of that reply — and asked NOW, so its time note says so.
     * Only the newest finished reply qualifies: on an earlier one the retry would delete every later turn.
     */
    fun thinkHarder(replyId: String, acceptedDegrade: Boolean = false) {
        if (!canThinkHarder(replyId)) return
        val question = question(answeredBy = replyId) ?: return
        retry(from = question, acceptedDegrade = acceptedDegrade, effort = ReasoningEffort.THINK_HARDER)
    }

    /** The menu's gate (ConversationView.thinkHarderAction): an assistant reply that is the thread's last message, nothing generating. */
    fun canThinkHarder(replyId: String): Boolean {
        val s = _state.value
        val last = s.messages.lastOrNull() ?: return false
        if (s.isStreaming || last.id != replyId || last.role != "assistant") return false
        // Offered only where the selected provider has a higher level to ask for (iOS ChatView.canThinkHarder, 7cd8c10).
        val place = provider() ?: return false
        return ReasoningEffort.canThinkHarder(reasoningControl(place), place)
    }

    /** The question a reply answered: the user message just before it (iOS ChatViewModel.question(answeredBy:)). */
    fun question(answeredBy: String): ChatMessage? {
        val msgs = _state.value.messages
        val i = msgs.indexOfFirst { it.id == answeredBy }.takeIf { it > 0 } ?: return null
        return msgs.subList(0, i).lastOrNull { it.role == "user" }
    }

    private fun stampLastAssistant(sources: List<GroundingSource>, isE2EE: Boolean) {
        val msgs = _state.value.messages.toMutableList()
        val i = msgs.indexOfLast { it.role == "assistant" }; if (i < 0) return
        msgs[i] = msgs[i].copy(sources = sources, isE2EE = isE2EE)
        _state.update { it.copy(messages = msgs) }
    }

    // MARK: - Web-search offer

    /** Declining clears every card in the thread and marks it declined: keyless, the tool stops attaching here. */
    fun dismissOffer() {
        generation.declineOffer(threadId ?: "", _state.value.offers.keys)
        _state.update { it.copy(offers = emptyMap()) }
    }

    /** A key Brave accepted through the card: store it, then re-ask the question the offer was raised for. */
    fun acceptSearchKey(key: String) {
        onBraveKey(key)
        val userMessage = _state.value.messages.lastOrNull { it.role == "user" } ?: return
        generation.pendingSearchRetry = ChatGeneration.PendingSearchRetry(threadId ?: "", userMessage.id)
        runPendingSearchRetryIfReady()
    }

    /** Consumed even when no key was entered — a retry must never fire on a later, unrelated visit. */
    private fun runPendingSearchRetryIfReady() {
        val pending = generation.pendingSearchRetry ?: return
        generation.pendingSearchRetry = null
        if (groundingKey().isNullOrEmpty()) return
        if (pending.threadID != (threadId ?: "")) return
        val message = _state.value.messages.firstOrNull { it.id == pending.userMessageID } ?: return
        retry(from = message)
    }

    fun placeRequestShown() = _state.update { it.copy(needsPlace = false) }
    /** The still-checking question was answered "wait" or dismissed. */
    fun hardwareConfirmShown() = _state.update { it.copy(hardwareConfirmRequested = false) }


    /**
     * Re-verify in the alert (iOS reverifyHeldTurn @ 5638261c; reply-machine.json 0.6.5): it answers a still-checking
     * question by declining the held turn first (F3). A fresh send's question goes back to the composer; a retry or a
     * think harder is kept — its effort is returned — for the re-shown alert's "send anyway" to resend, and the reply it
     * would replace stands until that request goes out.
     */
    fun reverifyHeldTurn(): ReasoningEffort? {
        val kept = replies.live?.takeIf { replies.state == ai.teemoon.chat.ReplyState.AWAITING_CONFIRM }?.let { replyRows[it]?.retryEffort }
        answerStillChecking(sendIt = false)
        return kept
    }

    /** The chat's newest turn when a re-verify starts (iOS reverifyCapture): what follows runs only if it is still the newest (G6). */
    fun reverifyCapture(): Int = lastGeneration

    enum class AfterReverify { ASK, BLOCKED, GO }

    /**
     * iOS retryE2EEAndGenerate, after the refresh: wait for its outcome (10 s), then the alert again — blocked, or the
     * question if the state still asks or was a hard block (C2: never auto-send after a hard break "clears", even once it
     * reads allow; the user re-initiates against the settled state) — or GO: run what was gated. Null: a newer turn
     * started meanwhile (G6).
     */
    suspend fun afterReverify(wasHardBlock: Boolean, captured: Int): AfterReverify? {
        val s = session ?: return null
        s.attestationOutcome(waitingUpToMs = 10_000)
        if (lastGeneration != captured) return null
        return when {
            s.sendPolicy == TrustSendPolicy.BLOCK -> AfterReverify.BLOCKED
            wasHardBlock || s.requiresE2EEConfirmation -> AfterReverify.ASK
            else -> AfterReverify.GO
        }
    }

    /**
     * "send sealed unconfirmed" on a still-checking alert that is not a held turn's (a re-shown or a composer one): the
     * answer is for (this record, pending) while a verdict is out, so the send's own gate does not ask it again.
     */
    fun confirmStillChecking() { session?.takeIf { it.sendPending }?.rememberConfirmation(pending = true) }

    /** iOS ChatView.retry(from:): drop the question and everything after it, then ask it again. */
    private fun retry(from: ChatMessage, acceptedDegrade: Boolean = false, effort: ReasoningEffort = ReasoningEffort.CHAT_DEFAULT) {
        if (_state.value.isStreaming) return
        // No place: keep the question and say why. Dropping it first and letting send() find no place deleted it from
        // the transcript and the store with nothing sent and nothing shown (the local audit's re-run, 2026-10-13).
        if (provider() == null) { _state.update { it.copy(needsPlace = true) }; return }
        val index = _state.value.messages.indexOfFirst { it.id == from.id }
        if (index < 0) return
        if (effort == ReasoningEffort.THINK_HARDER) {
            // The old reply stays until the new one lands (ReplySupersession), and its question with it: asked again
            // now, restamped only when the new reply is saved. Build 50 deleted both first, so a failure or a stop
            // left the question with no answer.
            _state.update { it.copy(error = null, lastError = null) }
            send(from.text, acceptedDegrade, effort = effort, reasking = from, superseded = _state.value.messages.drop(index + 1).map { it.id }.toSet())
            return
        }
        // Delete all messages from the question on, and ask it again.
        val before = _state.value.messages
        val kept = before.take(index)
        val dropped = before.drop(index).map { it.id }.toSet()
        val replacing = RetriedTurn.of(before, before.drop(index), generation.offerByMessageID.filterKeys { it in dropped })
        generation.offerByMessageID.keys.removeAll(dropped)
        _state.update { it.copy(messages = kept, offers = generation.offerByMessageID.toMap(), error = null) }
        // One step, not check-then-act (A2): the send starts now (or waits out a stopping turn) and its own store write
        // deletes the old turns before saving the question again. Deleting first in a coroutine of its own let a send
        // that then refused to begin leave the question gone.
        // A plain retry keeps the time the question was first sent; Think Harder asks again now (restampIfReasked).
        send(from.text, acceptedDegrade, effort = effort, askedAtMs = if (effort == ReasoningEffort.THINK_HARDER) null else from.createdAtMs, retried = true,
            clearStoredFromMs = from.createdAtMs, replacing = replacing)
    }

    /**
     * Every write of [_state] is an atomic update over the value it replaces. The stream calls back from the transport's
     * thread (OpenAiTransport's IO loop, the on-device engine's own) while the main thread writes the same state — stop,
     * retry, an edit; a `_state.value = _state.value.copy(…)` read on one thread and written after the other's write
     * dropped that write. iOS's ChatViewModel is @MainActor and has no such interleaving.
     */
    private fun replaceStreamingText(text: String, swapped: ReplySupersession? = null) {
        if (swapped != null) generation.superseding = swapped
        val at = now()
        _state.update { s ->
            NavTrace.count("stream.received", text.length.toLong())   // reveal lag, source side (StreamLagMetric)
            val last = s.messages.lastOrNull() ?: return@update s
            // "thought for Ns" is stamped when the answer begins — turn start → `</think>`, the start the activity chip
            // counts from — so the disclosure and the chip read one clock. It read Message.generatingTime, null until
            // the turn ended: "thinking... 0s" beside a chip at 2s, then "thought for 0s", then the WHOLE turn at the
            // hand-off (ThinkingTimeTest).
            val thought = last.generatingTimeMs ?: s.turnStartedAtMs?.takeIf { last.streaming && text.contains("</think>") }?.let { at - it }
            s.copy(messages = s.messages.dropLast(1) + last.copy(text = text, generatingTimeMs = thought), superseding = swapped ?: s.superseding)
        }
    }

    private fun setSuperseding(value: ReplySupersession) {
        generation.superseding = value
        _state.update { it.copy(superseding = value) }
    }

    /** `.replace`: the old reply leaves the transcript and the question takes the time it was asked again — cleared only now, or the old reply shows for a frame. */
    private fun land(oldIds: Set<String>, questionId: String, askedAt: Long) {
        generation.superseding = ReplySupersession()
        generation.offerByMessageID.keys.removeAll(oldIds)
        _state.update { s -> s.copy(
            messages = s.messages.filter { it.id !in oldIds }.map { if (it.id == questionId) it.copy(createdAtMs = askedAt) else it },
            superseding = ReplySupersession(), offers = generation.offerByMessageID.toMap()) }
    }

    /** `.restore`: the old reply as it was; the new one's row, partial or empty, goes. */
    private fun restoreSuperseded() {
        val s = generation.superseding
        if (!s.isActive) return
        generation.superseding = ReplySupersession()
        _state.update { st ->
            val lastOld = st.messages.indexOfLast { it.id in s.replyIDs }
            st.copy(messages = if (lastOld >= 0) st.messages.take(lastOld + 1) else st.messages, superseding = ReplySupersession())
        }
    }

    private fun settle() {
        val now = now()
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            val elapsed = s.turnStartedAtMs?.let { now - it }
            // iOS has no reply row until there is output (ChatViewModel.swift: the message is appended only when `output`
            // is non-empty); the placeholder here exists so the activity chip has a row to live in. A turn that ends with
            // nothing in it — a failure before the first token, an empty reply — must take the row with it: left behind,
            // it is a blank line's worth of padding between the prompt and the error card, it counts as "a reply" for the
            // transcript's phase-A reserve so the settle never pins the card into view, and it was never persisted anyway
            // (Pixel 10 Pro, 2026-09-17: the Brave 422 card a screen-height under its prompt).
            // A row that carries a marker stays: its marker is what the turn left (reply-markers).
            if (msgs.lastOrNull()?.let { it.role == "assistant" && it.text.isBlank() && it.marker == null } == true) msgs.removeAt(msgs.lastIndex)
            // Only the turn's own row takes the elapsed time: after a Think Harder restore the last row is the old reply.
            msgs.lastOrNull()?.let { msgs[msgs.lastIndex] = it.copy(streaming = false, generatingTimeMs = if (it.streaming) it.generatingTimeMs ?: elapsed else it.generatingTimeMs) }
            s.copy(messages = msgs, isStreaming = false, thinkHarderLevel = null, waitingForVerification = false, awaitingModel = false, providerWait = null, isExecutingTools = false, turnStartedAtMs = null, turnOnDevice = false)
        }
    }
}

/**
 * A retry's turn as it was (reply-machine.json 0.6.2): the chat to go back to, the replies it would replace, and whether
 * one of them is worth keeping — the kit's `restorable`: it has content and its status is not interrupted, failed or
 * decryptFailed.
 */
class RetriedTurn(val messages: List<ChatMessage>, val offers: Map<String, String>, val replyIds: List<String>, val restorable: Boolean) {
    companion object {
        private val NOT_RESTORABLE = setOf(ai.teemoon.chat.ReplyStatus.INTERRUPTED, ai.teemoon.chat.ReplyStatus.FAILED, ai.teemoon.chat.ReplyStatus.DECRYPT_FAILED)
        fun of(chat: List<ChatMessage>, replaced: List<ChatMessage>, offers: Map<String, String>): RetriedTurn {
            val replies = replaced.filter { it.role == "assistant" && it.toolCall == null }
            return RetriedTurn(chat, offers, replies.map { it.id }, replies.any { m ->
                m.text.isNotBlank() && (ai.teemoon.chat.ReplyStatus.fromRaw(m.status) ?: ai.teemoon.chat.ReplyStatus.COMPLETE) !in NOT_RESTORABLE })
        }
    }
}

/** iOS: the refusal's LLMError userMessage. */
internal const val UNSEALED_SEND_REFUSED = "end-to-end encryption couldn't be set up, so nothing was sent. try again. if it keeps happening, re-verify from the lock icon."

/** Nothing went out: the turn was refused before the wire, and [refusal] is the card's whole message. */
internal open class RefusedBeforeTheWire(val refusal: String) : Exception(refusal)

/** Nothing went out because the user declined the still-checking question: no card (the kit shows one only on gateRefused). */
internal class DeclinedBeforeTheWire(refusal: String) : RefusedBeforeTheWire(refusal)

/**
 * The gate, re-read AFTER `prepareTurn` settled the refresh it may have started (iOS ChatViewModel.postPrepareRefusal,
 * 9f4aa84). A hard block never proceeds; a soft degrade proceeds only if the user already chose "send anyway"
 * ([acceptedDegrade]); a verdict still pending is not a pass. Returns the refusal to show, or null to proceed.
 */
/** The chat's gate after prepareTurn (iOS ChatViewModel.PostPrepare). */
sealed interface PostPrepare {
    data object Proceed : PostPrepare
    data object ConfirmPending : PostPrepare
    data class Refuse(val refusal: String) : PostPrepare
}

/**
 * Verdicts still pending at the settle timeout ask instead of refusing, but only when the message would go out sealed
 * (near-send-gate-rule, the maintainer 2026-11-06; iOS postPrepare); evidence still blocks, and with no peer it refuses as before.
 */
fun postPrepare(policy: TrustSendPolicy, acceptedDegrade: Boolean, verdictsPending: Boolean, sealed: Boolean): PostPrepare {
    val refusal = postPrepareRefusal(policy, acceptedDegrade, verdictsPending) ?: return PostPrepare.Proceed
    if (verdictsPending && policy != TrustSendPolicy.BLOCK && sealed) return if (acceptedDegrade) PostPrepare.Proceed else PostPrepare.ConfirmPending
    return PostPrepare.Refuse(refusal)
}

/**
 * Whether the screen asks the still-checking question: only while the policy is not BLOCK. A hard verdict that lands
 * while it is up turns it into the hard choices (iOS's alert), read live, not from the gate the screen captured.
 */
fun asksStillChecking(pendingAsk: Boolean, policy: TrustSendPolicy): Boolean = pendingAsk && policy != TrustSendPolicy.BLOCK

fun postPrepareRefusal(policy: TrustSendPolicy, acceptedDegrade: Boolean, verdictsPending: Boolean): String? = when {
    policy == TrustSendPolicy.BLOCK -> "verification failed while preparing this message, so nothing was sent. re-verify from the lock icon."
    policy == TrustSendPolicy.CONFIRM && !acceptedDegrade -> "verification changed while preparing this message, so nothing was sent. send again to review it."
    verdictsPending -> "verification is still running, so nothing was sent. try again in a moment."
    else -> null
}

/**
 * Whether this turn must be refused because the provider promised attestation +
 * E2EE but no peer could be established — the transport would run with a nil
 * codec and send PLAINTEXT under that promise. Pure so the regression tests
 * can pin the decision. Non-attested providers (peer legitimately absent) are
 * unaffected. (ChatViewModel.mustRefuseUnsealedSend on iOS.)
 */
fun mustRefuseUnsealedSend(provider: Provider, teeContext: TeeContext?): Boolean =
    ProviderCapability.ATTESTATION in provider.capabilities && teeContext?.e2eePeer == null
