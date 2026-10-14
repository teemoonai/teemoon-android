// ports: teemoon/Inference/ChatGeneration.swift @ v1.0.4 (generate() — the drive; state lives in ChatGeneration),
//        teemoon/Views/Chat/ChatView.swift @ v1.0.4 (runPendingSearchRetryIfReady, retry(from:) — the search-offer re-ask only)
//        teemoon/Chat/ChatViewModel.swift @ v1.0.4 (postPrepareRefusal, acceptedDegrade; a refusal before the wire is abandoned, not booked)
//        teemoon/Chat/ChatViewModel.swift @ v1.0.4 (the saved reply's sources: GroundingSource.citedOrAll)
package ai.teemoon.app

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
import ai.teemoon.inference.StreamCallbacks
import ai.teemoon.inference.ToolCallState
import ai.teemoon.inference.ToolRoundPolicy
import ai.teemoon.inference.WireMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ai.teemoon.chat.MessageEntity
import ai.teemoon.confidential.TeeContext
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.LocalDefaults
import ai.teemoon.inference.RequestResult
import ai.teemoon.providers.ProviderCapability

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
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val isStreaming: Boolean = false,
    val awaitingModel: Boolean = false,
    /** A retry found no place: the screen shows the composer's "no provider configured" alert, then calls [ChatViewModel.placeRequestShown]. */
    val needsPlace: Boolean = false,
    val warnings: List<String> = emptyList(),
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
    private val makeTransport: ((endpoint: String, model: String, context: TeeContext?, events: StreamCallbacks) -> GenerationTransport)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val generation = ChatGeneration()

    private var job: Job? = null


    /** Replays a persisted thread into the transcript. */
    fun restore(messages: List<MessageEntity>) {
        if (_state.value.messages.isNotEmpty() || messages.isEmpty()) { _state.update { it.copy(loaded = true) }; return }
        _state.update { s -> s.copy(loaded = true,
            messages = messages.map { ChatMessage(it.role, it.content, id = it.id, createdAtMs = it.timestamp, sources = GroundingSource.decodeList(it.sourcesJson), isE2EE = it.isE2EE, generatingTimeMs = it.generatingTimeMs) }) }
    }

    /**
     * [acceptedDegrade] is the user having chosen "send anyway" on the soft alert, which a CONFIRM after
     * prepareTurn settles may honour; a BLOCK never is (iOS ChatViewModel.generate, 9f4aa84).
     */
    fun send(prompt: String, acceptedDegrade: Boolean = false) {
        if (prompt.isBlank() || _state.value.isStreaming) return
        // No place, no turn. This is the last gate, not the first — the composer's SendPrep stops a send with no place
        // (BLOCKED_NO_PROVIDER) — but the error card's retries call send() directly. There used to be a compiled
        // default endpoint (the emulator's fake server, in clear) for them to fall back to; flagged by the local audit.
        val place = provider() ?: return
        val startMs = now()
        if (!generation.beginTurn(startMs)) return
        val userMessageId = "m-$startMs"
        val assistantId = "a-$startMs"
        val tid = threadId ?: ""

        // The wire transcript: system prompt (unless the provider forbids one), then the prior turns capped from the end.
        val all = _state.value.messages.filter { it.toolCall == null }.map { WireMessage(it.role, it.text) } + WireMessage("user", prompt)
        val history = ChatGeneration.buildWireMessages(all, ChatGeneration.resolvePromptTemplates(systemPrompt()),
            maxMessages = place.effectiveMaxMessages, omitSystemPrompt = place.effectiveOmitSystemPrompt)

        _state.update { it.copy(
            messages = it.messages +
                ChatMessage("user", prompt, id = userMessageId, createdAtMs = startMs) +
                ChatMessage("assistant", "", streaming = true, id = assistantId, createdAtMs = startMs + 1),
            error = null, lastError = null, isStreaming = true, warnings = emptyList(),
            sources = emptyList(), isExecutingTools = false, awaitingModel = false, turnStartedAtMs = startMs,
        ) }

        val endpoint = place.chatCompletionsUrl
        val model = place.model
        val providerName = place.name

        viewModelScope.launch {
            repo?.let { r ->
                threadId?.let { t ->
                    r.addMessage(userMessageId, t, "user", prompt, startMs)
                    r.titleFromFirstMessage(t, prompt)
                }
            }
        }

        val tools = generation.makeGroundingTools(place, groundingKey(), tid, localContextTokens(place))

        // Opened per turn below: the transport is built after prepareTurn so it carries this turn's context.
        var lastResult: RequestResult? = null
        var persisted = false
        fun engine(context: TeeContext?): GenerationEngine {
            val callbacks = object : StreamCallbacks {
                override fun onText(text: String) {
                    if (text.isNotEmpty() && generation.markFirstToken(now())) onFirstToken(place)
                    generation.output = text
                    replaceStreamingText(text)
                }
                override fun onAwaitingModel(turn: Int, awaiting: Boolean) {
                    generation.noteAwaiting(turn, awaiting)
                    _state.update { it.copy(awaitingModel = generation.isAwaitingModel) }
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
                    generation.noteSources(sources)
                    _state.update { it.copy(sources = generation.groundingSources.toList()) }
                }
                override fun onQueriesFound(queries: List<String>) {
                    generation.noteQueries(queries)
                    _state.update { it.copy(isExecutingTools = true) }
                }
                override fun onToolExecutionEnded() {
                    generation.noteToolExecutionEnded()
                    _state.update { it.copy(isExecutingTools = false) }
                }
                override fun onWarning(message: String) {
                    _state.update { it.copy(warnings = it.warnings + message) }
                }
                override fun onSuccess(result: RequestResult) {
                    lastResult = result
                    generation.recordSuccess(result, providerName, model, tid)
                    _state.update { it.copy(lastRequestDebugInfo = generation.lastRequestDebugInfo) }
                }
                // Persist the reply as soon as its text is final — before the signature check, which can take seconds.
                override fun onTurnText(text: String) {
                    if (persisted) return; persisted = true
                    // What the reply CITES, as iOS saves it (GroundingSource.citedOrAll): its own links, with the search
                    // snippet where one matched — the whole search haul only when it cites nothing
                    val sources = GroundingSource.citedOrAll(text, generation.groundingSources.toList())
                    val sealed = lastResult?.isE2eeActive ?: (context?.e2eePeer != null)
                    val elapsed = _state.value.turnStartedAtMs?.let { now() - it }
                    repo?.let { r -> threadId?.let { t -> viewModelScope.launch { r.addMessage(assistantId, t, "assistant", text, now(),
                        sourcesJson = sources.takeIf { it.isNotEmpty() }?.let(GroundingSource::encodeList), isE2EE = sealed, generatingTimeMs = elapsed) } } }
                }
            }
            return GenerationEngine(
                transport = makeTransport?.invoke(endpoint, model, context, callbacks) ?: OpenAiTransport(endpoint, model, apiKey = credential(place), authHeaderName = place.authHeaderName,
                    teeContext = context, extraParams = place.extraParams, providerName = providerName),
                tools = tools,
                events = callbacks,
                // The ceiling is the MODEL's, not the app's, and iOS sets it in two places:
                // GenerationEngine.defaultMaxToolRounds = 4 for anything dialled over the wire,
                // and LocalLanguageModel.swift:136 passes 2 — each extra round on the phone is a
                // full re-prefill plus a serial decode, and two still allows search-then-answer.
                // This passed 2 for every provider, so a hosted model researched two rounds
                // shallower than the same model on iOS (2026-09 port review, B3).
                maxToolRounds = if (place.isLocal) LocalDefaults.MAX_TOOL_ROUNDS else ToolRoundPolicy.DEFAULT_MAX_ROUNDS,
            )
        }

        // The host wires this to TurnService so a turn survives the user leaving (TurnNotification.kt). Announced
        // HERE, at send, because process priority has to be raised before the foreground is lost — there is no
        // window between `onStop` and the kill in which to do it. Null in tests and previews.
        onTurn?.invoke(true, null, "")
        job = viewModelScope.launch {
            try {
                // prepareTurn refreshes a stale record, waits for the model key, and opens the turn.
                val context = session?.prepareTurn()
                // The gate, re-read AFTER prepareTurn settled the refresh it may have started. The composer's check
                // ran on the state before that refresh; a verdict that changed underneath it must not ride the
                // earlier answer (iOS 9f4aa84: every other message to a host whose TLS check failed went out).
                session?.let { s -> postPrepareRefusal(s.sendPolicy, acceptedDegrade, s.verdictsPending) }?.let { refusal ->
                    session.abandonTurn(refusal)
                    throw RefusedBeforeTheWire(refusal)
                }
                if (mustRefuseUnsealedSend(place, context)) {
                    // FAIL CLOSED. An attested provider with no E2EE peer would reach the transport with a nil codec
                    // and send PLAINTEXT under an E2EE promise. Surface it like a generation error and send NOTHING.
                    // Booked as abandoned, not as an unsealed request: that read as plaintext-went-out once the key
                    // arrived — a hard block only a provider switch cleared.
                    session?.abandonTurn(UNSEALED_SEND_REFUSED)
                    throw RefusedBeforeTheWire(UNSEALED_SEND_REFUSED)
                }
                generation.seedDebugInfo(providerName, model, endpoint, tid, expectingE2EE = context?.e2eePeer != null)
                val answer = engine(context).run(history, prompt)
                generation.output = answer
                // The session records what actually went out: sealed, or plaintext to a non-attesting provider.
                session?.finishTurn(isE2EEActive = lastResult?.isE2eeActive ?: (if (context != null) false else null), error = null, verification = lastResult?.teeVerification)
                // Normally persisted by onTurnText already; this is the fallback for a transport that never reported final text.
                val cited = GroundingSource.citedOrAll(answer, generation.groundingSources.toList())
                if (!persisted) repo?.let { r -> threadId?.let { t -> r.addMessage(assistantId, t, "assistant", answer, now(),
                    sourcesJson = cited.takeIf { it.isNotEmpty() }?.let(GroundingSource::encodeList), isE2EE = lastResult?.isE2eeActive ?: false) } }
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
            } catch (e: Throwable) {
                if (e !is RefusedBeforeTheWire) session?.finishTurn(isE2EEActive = null, error = ConfidentialSession.RequestError((e as? LlmError)?.httpStatus, e.message ?: e.toString()), verification = null)
                // An unexpected error is wrapped as iOS does, so the card can name the provider and carry the URL.
                // A refusal before the wire is not unexpected: its card reads the refusal itself, as iOS's does.
                val llm = e as? LlmError ?: LlmError(LlmError.Source.Provider(providerName),
                    if (e is RefusedBeforeTheWire) e.refusal else "An unexpected error occurred with $providerName. ${e.message ?: e.toString()}", url = endpoint, underlyingError = e)
                generation.lastError = llm; generation.lastErrorThreadID = tid
                _state.update { it.copy(error = llm.userMessage, lastError = llm) }
                onTurn?.invoke(false, TurnOutcome.FAILED, "")
            } finally {
                generation.endTurn()
                settle()
            }
        }
    }

    /**
     * Turn lifecycle for the host: (inFlight, outcome, reply). Set by MainActivity, which has the Context the
     * service needs; the view model stays free of Android imports.
     */
    var onTurn: ((Boolean, TurnOutcome?, String) -> Unit)? = null

    fun stop() { generation.stop(); job?.cancel(); settle() }

    /**
     * The error card's "this model can't search the web — turn off & retry". Grounding is off by the time this runs,
     * and the thread is marked DECLINED as well: keyless, makeGroundingTools still attaches web_search (it is the
     * offer's trigger), so turning search off alone re-sent the same schema and the model refused it with the same
     * 400 — the retry could never answer (the B4 journey, 2026-10-04; iOS ConversationView has the same gap).
     */
    fun disableWebSearchAndRetry() {
        generation.declineOffer(threadId ?: "", _state.value.offers.keys)
        _state.update { it.copy(offers = emptyMap()) }
        retryLastTurn()
    }

    /** Asks the last question again (the error card's retry, and the search-off retry above). */
    fun retryLastTurn(acceptedDegrade: Boolean = false) {
        val last = _state.value.messages.lastOrNull { it.role == "user" } ?: return
        retry(from = last, acceptedDegrade = acceptedDegrade)
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

    /** iOS ChatView.retry(from:): drop the question and everything after it, then ask it again. */
    private fun retry(from: ChatMessage, acceptedDegrade: Boolean = false) {
        if (_state.value.isStreaming) return
        // No place: keep the question and say why. Dropping it first and letting send() find no place deleted it from
        // the transcript and the store with nothing sent and nothing shown (the local audit's re-run, 2026-10-13).
        if (provider() == null) { _state.update { it.copy(needsPlace = true) }; return }
        val index = _state.value.messages.indexOfFirst { it.id == from.id }
        if (index < 0) return
        val kept = _state.value.messages.take(index)
        val dropped = _state.value.messages.drop(index).map { it.id }.toSet()
        generation.offerByMessageID.keys.removeAll(dropped)
        _state.update { it.copy(messages = kept, offers = generation.offerByMessageID.toMap(), error = null) }
        viewModelScope.launch {
            repo?.let { r -> threadId?.let { t -> r.deleteMessagesSince(t, from.createdAtMs) } }
            send(from.text, acceptedDegrade)
        }
    }

    /**
     * Every write of [_state] is an atomic update over the value it replaces. The stream calls back from the transport's
     * thread (OpenAiTransport's IO loop, the on-device engine's own) while the main thread writes the same state — stop,
     * retry, an edit; a `_state.value = _state.value.copy(…)` read on one thread and written after the other's write
     * dropped that write. iOS's ChatViewModel is @MainActor and has no such interleaving.
     */
    private fun replaceStreamingText(text: String) = _state.update { s ->
        val last = s.messages.lastOrNull() ?: return@update s
        s.copy(messages = s.messages.dropLast(1) + last.copy(text = text))
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
            if (msgs.lastOrNull()?.let { it.role == "assistant" && it.text.isBlank() } == true) msgs.removeAt(msgs.lastIndex)
            msgs.lastOrNull()?.let { msgs[msgs.lastIndex] = it.copy(streaming = false, generatingTimeMs = it.generatingTimeMs ?: elapsed) }
            s.copy(messages = msgs, isStreaming = false, awaitingModel = false, isExecutingTools = false, turnStartedAtMs = null)
        }
    }
}

/** iOS: the refusal's LLMError userMessage. */
internal const val UNSEALED_SEND_REFUSED = "End-to-end encryption could not be established, so nothing was sent. Try again — if this keeps happening, re-verify the connection from the lock icon."

/** Nothing went out: the turn was refused before the wire, and [refusal] is the card's whole message. */
internal class RefusedBeforeTheWire(val refusal: String) : Exception(refusal)

/**
 * The gate, re-read AFTER `prepareTurn` settled the refresh it may have started (iOS ChatViewModel.postPrepareRefusal,
 * 9f4aa84). A hard block never proceeds; a soft degrade proceeds only if the user already chose "send anyway"
 * ([acceptedDegrade]); a verdict still pending is not a pass. Returns the refusal to show, or null to proceed.
 */
fun postPrepareRefusal(policy: TrustSendPolicy, acceptedDegrade: Boolean, verdictsPending: Boolean): String? = when {
    policy == TrustSendPolicy.BLOCK -> "Verification failed while preparing this message, so nothing was sent. Re-verify from the lock icon."
    policy == TrustSendPolicy.CONFIRM && !acceptedDegrade -> "Verification changed while preparing this message, so nothing was sent. Send again to review it."
    verdictsPending -> "Verification is still running, so nothing was sent. Try again in a moment."
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
