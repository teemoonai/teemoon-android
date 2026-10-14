// ports: teemoon/Inference/ChatGeneration.swift @ v1.0.4 (the per-turn state; generate()'s prologue and epilogue),
//        teemoon/Inference/ChatGeneration+SearchOffer.swift @ v1.0.1,
//        teemoon/Inference/ChatGeneration+Stream.swift @ v1.0.1 (the callback folds; the model factory is GenerationEngine's transport seam),
//        teemoon/Inference/ChatGeneration+Transcript.swift @ v1.0.1,
//        teemoon/Inference/LastRequestDebugInfo.swift @ v1.0.1 (LastRequestDebugInfo; ToolCallRecord is in :inference)
//        teemoon/Inference/GenerationTransport.swift @ v1.0.1 — PromptBudget lives in :inference (GenerationReport.kt)
//
// The generation STATE, pure. iOS keeps the stream loop in this class; here
// the loop is GenerationEngine (so there is one loop to keep in step with the
// Swift, not two) and ChatViewModel drives it, folding every callback through
// the methods below. Nothing here touches the network or the UI.
package ai.teemoon.app

import ai.teemoon.confidential.ResponseVerification
import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.inference.GroundingBudget
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.PromptBudget
import ai.teemoon.inference.RequestResult
import ai.teemoon.inference.Tool
import ai.teemoon.inference.ToolCallRecord
import ai.teemoon.inference.WireMessage
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import java.time.Duration
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
) {
    val generationTime: Double?
        get() { val total = totalDuration ?: return null; val ttft = timeToFirstToken ?: return null; return maxOf(0.0, total - ttft) }
    /** Tokens over the window the model was actually producing: thinking plus generation, never the network wait. */
    val tokensPerSecond: Double?
        get() { val tokens = outputTokens ?: return null; val window = (thinkingTime ?: 0.0) + (generationTime ?: 0.0); return if (window > 0) tokens / window else null }
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
    fun elapsedSeconds(nowMs: Long): Double? = startTimeMs?.let { (nowMs - it) / 1000.0 }

    fun stop() { cancelled = true }

    /** generate()'s prologue: everything per-turn is cleared. The offer map, the declined threads and the pending retry are NOT — they outlive a turn. */
    @Synchronized fun beginTurn(nowMs: Long): Boolean {
        if (running) return false
        running = true; cancelled = false; output = ""
        groundingSources.clear(); searchQueries.clear(); isExecutingTools = false; awaitingTurn = null
        unconfiguredSearchQuery = null; unconfiguredSearchThreadID = null
        lastError = null; lastErrorThreadID = null; lastRequestDebugInfo = null
        startTimeMs = nowMs; firstTokenTimeMs = null
        return true
    }

    @Synchronized fun endTurn() { running = false; awaitingTurn = null }

    /** True the first time a turn produces output — the "recently used" moment. */
    @Synchronized fun markFirstToken(nowMs: Long): Boolean {
        if (firstTokenTimeMs != null) return false
        firstTokenTimeMs = nowMs; return true
    }

    @Synchronized fun noteAwaiting(turn: Int, awaiting: Boolean) {
        if (awaiting) { if ((awaitingTurn ?: Int.MIN_VALUE) < turn) awaitingTurn = turn }
        else if (awaitingTurn == turn) awaitingTurn = null
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

    @Synchronized fun seedDebugInfo(providerName: String, modelID: String, url: String?, threadID: String, expectingE2EE: Boolean) {
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
            isE2EEActive = result.isE2eeActive, teeVerification = result.teeVerification ?: existing?.teeVerification)
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

        private val DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())

        fun resolvePromptTemplates(prompt: String, now: ZonedDateTime = ZonedDateTime.now()): String =
            prompt.replace("{{datetime}}", DATETIME_FORMAT.format(now))
    }
}
