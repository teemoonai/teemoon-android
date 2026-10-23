// ports: teemoon/Inference/ChatGeneration.swift @ v1.0.4 (the per-turn state; generate()'s prologue and epilogue),
//        teemoon/Inference/ChatGeneration+SearchOffer.swift @ v1.0.1,
//        teemoon/Inference/ChatGeneration+Stream.swift @ v1.0.1 (the callback folds; the model factory is GenerationEngine's transport seam),
//        teemoon/Inference/ChatGeneration+Transcript.swift @ 224ec87,
//        teemoon/Inference/LastRequestDebugInfo.swift @ v1.0.1 (LastRequestDebugInfo; ToolCallRecord is in :inference)
//        teemoon/Inference/GenerationTransport.swift @ v1.0.1 — PromptBudget lives in :inference (GenerationReport.kt)
//
// The generation STATE, pure. iOS keeps the stream loop in this class; here
// the loop is GenerationEngine (so there is one loop to keep in step with the
// Swift, not two) and ChatViewModel drives it, folding every callback through
// the methods below. Nothing here touches the network or the UI.
package ai.teemoon.app

import ai.teemoon.inference.ProviderWait
import ai.teemoon.confidential.ResponseVerification
import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.inference.ConversationReuse
import ai.teemoon.inference.GroundingBudget
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.PromptBudget
import ai.teemoon.inference.RequestResult
import ai.teemoon.inference.Tool
import ai.teemoon.inference.ToolCallRecord
import ai.teemoon.inference.TurnPhase
import ai.teemoon.inference.WireMessage
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the developer card shows for the last request. Seeded when the request goes out, refined as results land. */
data class LastRequestDebugInfo(
    val providerName: String,
    val modelID: String = "",
    val url: String?,
    val requestHeaders: Map<String, String>?,
    val requestBodyJson: String?,
    val sealedBodyJson: String? = null,
    val responseBody: String?,
    val toolCalls: List<ToolCallRecord>,
    val threadID: String,
    val totalDuration: Double?,
    val timeToFirstToken: Double?,
    val thinkingTime: Double? = null,
    val outputTokens: Int?,
    val promptBudget: PromptBudget? = null,
    val isE2EEActive: Boolean,
    val teeVerification: ResponseVerification?,
    /** On-device turns only: the engine's own account (backend, drafter, load, prefill). See [localStatsLine]. */
    val localStats: ai.teemoon.inference.LocalTurnStats? = null,
    /** Where the send's time went, offsets from the send: gate, headers, first token, first answer, each tool, done. See [TurnPhase.line]. */
    val phasesLine: String? = null,
    /** The card's cache figure (TurnPhase.cacheLine); the phases line itself stays in the copied text only (iOS ef5c272). */
    val cacheLine: String? = null,
) {
    val generationTime: Double?
        get() { val total = totalDuration ?: return null; val ttft = timeToFirstToken ?: return null; return maxOf(0.0, total - ttft) }
    /** Tokens over the window the model was actually producing: thinking plus generation, never the network wait. */
    val tokensPerSecond: Double?
        get() { val tokens = outputTokens ?: return null; val window = (thinkingTime ?: 0.0) + (generationTime ?: 0.0); return if (window > 0) tokens / window else null }

    /**
     * On-device turns only, the card's FIRST line: the speeds, decode first (what the reader feels — how fast the answer
     * streams), then prefill, then where they came from — "decode 96tok/s · prefill 2139tok/s · gpu + speculative". The
     * timing chain moves under it and drops its tok/s, so decode appears once. No load time: the engine loads when
     * Gemma is chosen in the where chip (the warm-up), not when a message is sent, so it is not this turn's. Cloud turns
     * have no speed line: there, prefill cannot be told apart from the network.
     */
    val localStatsLine: String?
        get() = localStats?.let { s ->
            listOfNotNull(tokensPerSecond?.let { String.format(java.util.Locale.US, "decode %.0ftok/s", it) },
                String.format(java.util.Locale.US, "prefill %.0ftok/s", s.prefillTokensPerSecond),
                s.backend + if (s.speculative) " + speculative" else "").joinToString(" · ")
        }

    /** The first line's tok/s, unless the second line carries it (on-device). */
    val firstLineTokensPerSecond: Double? get() = if (localStats != null) null else tokensPerSecond

    /** The timing chain, card and copied text alike: `6.9s · ttft 1.4s · gen 5.5s · 412tok · 75tok/s`. */
    val timingLine: String?
        get() = listOfNotNull(totalDuration?.let { secs(it) }, timeToFirstToken?.let { "ttft ${secs(it)}" }, thinkingTime?.let { "think ${secs(it)}" },
            generationTime?.let { "gen ${secs(it)}" }, outputTokens?.let { "${it}tok" }, firstLineTokensPerSecond?.let { String.format(Locale.US, "%.0ftok/s", it) })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** The turn's reasoning level, "effort high" or "thinking on": the body that went out, or on device its header (iOS 7cd8c10). */
    val effortLevel: String? get() = ai.teemoon.inference.ReasoningEffort.sentLevel(requestBodyJson) ?: requestHeaders?.get("thinking")?.let { "thinking $it" }

    /** The card's row (the maintainer): the timing chain, then `effort low · cache 92%`. The copied text keeps its own lines. */
    val cardTimingLine: String?
        get() = listOfNotNull(timingLine, effortLevel, cacheLine).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun secs(d: Double) = String.format(Locale.US, "%.1fs", d)
}

class ChatGeneration {
    @Volatile var running = false
    @Volatile var cancelled = false
    var output = ""
    var lastError: LlmError? = null
    var lastErrorThreadID: String? = null
    @Volatile var lastRequestDebugInfo: LastRequestDebugInfo? = null
    val groundingSources = ArrayList<GroundingSource>()
    val searchQueries = ArrayList<String>()
    @Volatile var isExecutingTools = false
    /** The turn currently waiting on the model, or null. Monotonic: a late "awaiting" for an older turn cannot resurrect the spinner. */
    @Volatile var awaitingTurn: Int? = null
    val isAwaitingModel: Boolean get() = awaitingTurn != null
    /** The request in flight with no response headers yet, or null. */
    @Volatile var providerWait: ProviderWait? = null
    /**
     * Think Harder's old reply while the new one has no text (ReplySupersession). Set and cleared by ChatViewModel,
     * which also leaves it out of the request and notes the swap — the loop is GenerationEngine's, not this class's.
     */
    @Volatile var superseding = ReplySupersession()

    /** The query the model asked for while keyless, until it attaches to the reply's message. */
    var unconfiguredSearchQuery: String? = null
    var unconfiguredSearchThreadID: String? = null
    /** Offer cards outlive the turn that raised them: the answer stands; the card explains it. */
    val offerByMessageID = LinkedHashMap<String, String>()
    val declinedOfferThreadIDs = HashSet<String>()

    data class PendingSearchRetry(val threadID: String, val userMessageID: String)
    /** Set when the user taps set-it-up; consumed (even keyless) the moment the key screen closes. A generation reset does not clear it. */
    var pendingSearchRetry: PendingSearchRetry? = null

    var startTimeMs: Long? = null
    var firstTokenTimeMs: Long? = null
    /** When the attestation gate let the send go (seedDebugInfo runs right after prepareTurn): the card's "gate". */
    var gateAtMs: Long? = null
    /** This turn is the chat's first message: nothing of it can be in a provider's cache yet (the card says so). */
    var turnIsFirstMessage = false
    fun elapsedSeconds(nowMs: Long): Double? = startTimeMs?.let { (nowMs - it) / 1000.0 }

    fun stop() { cancelled = true }

    /** generate()'s prologue: everything per-turn is cleared. The offer map, the declined threads and the pending retry are NOT — they outlive a turn. */
    @Synchronized fun beginTurn(nowMs: Long): Boolean {
        if (running) return false
        running = true; cancelled = false; output = ""
        groundingSources.clear(); searchQueries.clear(); isExecutingTools = false; awaitingTurn = null; providerWait = null
        unconfiguredSearchQuery = null; unconfiguredSearchThreadID = null
        lastError = null; lastErrorThreadID = null; lastRequestDebugInfo = null
        startTimeMs = nowMs; firstTokenTimeMs = null; gateAtMs = null; lastPhases = emptyList()
        return true
    }

    @Synchronized fun endTurn() { running = false; awaitingTurn = null; providerWait = null }

    /** True the first time a turn produces output — the "recently used" moment. */
    @Synchronized fun markFirstToken(nowMs: Long): Boolean {
        if (firstTokenTimeMs != null) return false
        firstTokenTimeMs = nowMs; return true
    }

    @Synchronized fun noteAwaiting(turn: Int, awaiting: Boolean) {
        if (awaiting) { if ((awaitingTurn ?: Int.MIN_VALUE) < turn) awaitingTurn = turn }
        else if (awaitingTurn == turn) awaitingTurn = null
    }
    /** ChatGeneration+Stream's onAwaitingResponse fold: a newer request's wait replaces an older one; only its own headers clear it. */
    @Synchronized fun noteResponseWait(turn: Int, sentAtMs: Long?, providerName: String) {
        if (sentAtMs != null) { if ((providerWait?.turn ?: Int.MIN_VALUE) < turn) providerWait = ProviderWait(turn, sentAtMs, providerName) }
        else if (providerWait?.turn == turn) providerWait = null
    }
    @Synchronized fun noteSources(sources: List<GroundingSource>) { groundingSources.addAll(sources) }
    @Synchronized fun noteQueries(queries: List<String>) { searchQueries.addAll(queries); isExecutingTools = true }
    @Synchronized fun noteToolExecutionEnded() { isExecutingTools = false }

    // MARK: - Search offer (ChatGeneration+SearchOffer)

    @Synchronized fun attachPendingOffer(messageID: String) {
        val query = unconfiguredSearchQuery ?: return
        offerByMessageID[messageID] = query
        unconfiguredSearchQuery = null; unconfiguredSearchThreadID = null
    }

    @Synchronized fun declineOffer(threadID: String, messageIDs: Collection<String>) {
        declinedOfferThreadIDs.add(threadID)
        for (id in messageIDs) offerByMessageID.remove(id)
        unconfiguredSearchQuery = null; unconfiguredSearchThreadID = null
    }

    /**
     * Keyless still attaches the tool, so the model can SAY it wanted the web —
     * that is the only honest trigger for the offer card. A thread whose offer
     * was declined stops paying for the schema while keyless; once a key
     * exists it attaches again. Built-in grounding never attaches it.
     */
    fun makeGroundingTools(provider: Provider, groundingAPIKey: String?, threadID: String, localContextTokens: Int? = null): List<Tool> {
        val offerDeclined = synchronized(this) { threadID in declinedOfferThreadIDs }
        val keyless = groundingAPIKey.isNullOrEmpty()
        if (ProviderCapability.BUILT_IN_GROUNDING in provider.capabilities || !provider.modelSupportsTools || (keyless && offerDeclined)) return emptyList()
        // android-only: the payload is sized to the local bundle's window (an NPU bundle's is 4096, compiled in); docs/ANDROID_NATIVE.md.
        val tool = BraveWebSearchTool(apiKey = groundingAPIKey ?: "", onDevice = provider.isLocal,
            contextTokens = localContextTokens ?: GroundingBudget.measuredContextTokens)
        tool.onUnconfiguredSearch = { query ->
            synchronized(this) {
                if (unconfiguredSearchQuery == null) { unconfiguredSearchQuery = query; unconfiguredSearchThreadID = threadID }
            }
        }
        return listOf(tool)
    }

    // MARK: - Debug info (ChatGeneration+Stream)

    @Synchronized fun seedDebugInfo(providerName: String, modelID: String, url: String?, threadID: String, expectingE2EE: Boolean, nowMs: Long = System.currentTimeMillis()) {
        gateAtMs = nowMs
        lastRequestDebugInfo = LastRequestDebugInfo(providerName = providerName, modelID = modelID, url = url, requestHeaders = null, requestBodyJson = null,
            responseBody = null, toolCalls = emptyList(), threadID = threadID, totalDuration = null, timeToFirstToken = null, outputTokens = null,
            isE2EEActive = expectingE2EE, teeVerification = null)
    }

    /** onSuccess: fold what the transport reported over what the seed already knows. */
    @Synchronized fun recordSuccess(result: RequestResult, providerName: String, modelID: String, threadID: String) {
        val existing = lastRequestDebugInfo
        val preservedResponse = result.responseBody ?: existing?.responseBody
        val thought: Double? = run {
            val first = result.firstTokenAt ?: return@run null
            val visible = result.firstVisibleTokenAt ?: return@run null
            val gap = Duration.between(first, visible).toNanos() / 1e9
            if (gap > 0.05) gap else null     // below that it is jitter
        }
        lastRequestDebugInfo = LastRequestDebugInfo(
            providerName = providerName, modelID = modelID, url = result.url, requestHeaders = result.requestHeaders,
            requestBodyJson = result.requestBodyJson, sealedBodyJson = result.sealedBodyJson, responseBody = preservedResponse,
            toolCalls = result.toolCalls, threadID = threadID, totalDuration = existing?.totalDuration, timeToFirstToken = existing?.timeToFirstToken,
            thinkingTime = thought ?: existing?.thinkingTime, outputTokens = result.outputTokens ?: existing?.outputTokens,
            promptBudget = result.promptBudget ?: existing?.promptBudget,
            isE2EEActive = result.isE2eeActive, teeVerification = result.teeVerification ?: existing?.teeVerification,
            localStats = result.localStats ?: existing?.localStats,
            phasesLine = phasesLine(result.phases) ?: existing?.phasesLine,
            cacheLine = TurnPhase.cacheLine(result.phases, turnIsFirstMessage, TurnPhase.cacheReporting(
                host = result.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }, onDevice = result.localStats != null,
                isE2EE = result.isE2eeActive)) ?: existing?.cacheLine)
        if (result.phases.isNotEmpty()) lastPhases = result.phases
    }

    /** The phases the card's line was built from, so a late verdict can add its mark. */
    private var lastPhases: List<TurnPhase> = emptyList()

    /**
     * The reply-signature verdict, after the turn closed (iOS cef6ad8). Only the card of the same send takes it — the
     * same thread, and the same turn ([turnStartMs]); a verdict for an older send must not relabel a newer card.
     */
    @Synchronized fun applyLateVerification(verification: ResponseVerification, threadID: String, turnStartMs: Long?, nowMs: Long = System.currentTimeMillis()) {
        val info = lastRequestDebugInfo ?: return
        if (info.threadID != threadID || startTimeMs != turnStartMs) return
        lastPhases = lastPhases + TurnPhase(TurnPhase.Kind.VERIFIED, Instant.ofEpochMilli(nowMs))
        lastRequestDebugInfo = info.copy(teeVerification = verification, phasesLine = phasesLine(lastPhases) ?: info.phasesLine)
    }

    private fun phasesLine(phases: List<TurnPhase>): String? {
        val start = startTimeMs ?: return null
        if (phases.isEmpty()) return null
        val gate = gateAtMs?.let { TurnPhase(TurnPhase.Kind.GATE, Instant.ofEpochMilli(it)) }
        return TurnPhase.line(Instant.ofEpochMilli(start), listOfNotNull(gate) + phases)
    }

    /**
     * generate()'s epilogue. Totals land on the debug card, and a turn that ended
     * with nothing to show — no error, not cancelled, blank output — becomes an
     * error the user can act on, worded by whether searches ran.
     */
    @Synchronized fun finishGeneration(providerName: String, baseURL: String?, threadID: String, toolCalls: List<ToolCallRecord>, landed: RequestResult?, nowMs: Long): LlmError? {
        val existing = lastRequestDebugInfo
        if (lastError == null && existing != null) {
            val total = elapsedSeconds(nowMs)
            val ttft = firstTokenTimeMs?.let { ft -> startTimeMs?.let { (ft - it) / 1000.0 } }
            lastRequestDebugInfo = existing.copy(
                responseBody = output.ifEmpty { null },
                toolCalls = if (toolCalls.isEmpty()) existing.toolCalls else toolCalls,
                totalDuration = total, timeToFirstToken = ttft,
                outputTokens = existing.outputTokens ?: (if (output.isEmpty()) null else output.length / 4),
                isE2EEActive = landed?.isE2eeActive ?: existing.isE2EEActive,
                teeVerification = landed?.teeVerification ?: existing.teeVerification)
        }
        if (lastError == null && !cancelled && output.trim().isEmpty()) {
            val searches = toolCalls.size
            lastError = LlmError(LlmError.Source.Provider(providerName),
                if (searches > 0) "$providerName ran $searches search${if (searches == 1) "" else "es"} but didn't write an answer. Ask again to retry."
                else "$providerName returned an empty response. Ask again to retry.",
                url = baseURL)
            lastErrorThreadID = threadID
        }
        return lastError
    }

    companion object {
        /** The transcript as the wire sees it: system prompt (unless the provider forbids one), then the prior turns, capped from the END so the newest survive. */
        fun buildWireMessages(messages: List<WireMessage>, systemPrompt: String, maxMessages: Int?, omitSystemPrompt: Boolean = false): List<WireMessage> {
            val wire = ArrayList<WireMessage>()
            if (!omitSystemPrompt) wire += WireMessage("system", systemPrompt)
            val prior = messages.dropLast(1).filter { it.role != "system" }
            val priorToInclude = if (maxMessages != null) prior.takeLast(maxOf(0, maxMessages - 1)) else prior
            for (message in priorToInclude) when (message.role) {
                "user" -> wire += WireMessage("user", message.content)
                "assistant" -> wire += WireMessage("assistant", message.content)
            }
            return wire
        }

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

        /**
         * `{{date}}` (and the older `{{datetime}}`, same meaning) resolves to the DAY, never the minute: the system prompt
         * opens every request, and a prefix that changes each minute misses every provider's prompt cache (pm,
         * 2026-10-19: Fireworks cached 0 → 3718 of 3847 tokens on a repeat, and 0 again when only the minute changed).
         * The minute rides on each user turn instead ([timeNote]). On device it is also what lets a kept Conversation
         * serve the next turn (ConversationReuse).
         */
        fun resolvePromptTemplates(prompt: String, now: ZonedDateTime = ZonedDateTime.now()): String {
            val day = DATE_FORMAT.format(now)
            return prompt.replace("{{datetime}}", day).replace("{{date}}", day)
        }

        /** Whether the persona asked for the date, and so each user turn gets its time. */
        fun wantsTimeNotes(systemPrompt: String, omitSystemPrompt: Boolean): Boolean =
            !omitSystemPrompt && ("{{date}}" in systemPrompt || "{{datetime}}" in systemPrompt)

        /**
         * Appended to a user turn on the wire, never stored: the message's OWN timestamp is the source, so the same turn
         * renders the same bytes on every request (a cached prefix and a kept on-device conversation both depend on it).
         */
        fun timeNote(sentAtMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
            "\n\n[Sent at ${TIME_FORMAT.format(java.time.Instant.ofEpochMilli(sentAtMs).atZone(zone))}]"
    }
}
