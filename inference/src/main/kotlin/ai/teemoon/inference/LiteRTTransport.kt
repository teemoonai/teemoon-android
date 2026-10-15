// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 — the transport over the LocalEngineFactory seam (the
//        LiteRT-LM binding itself is :ondevice's LiteRtLmEngineFactory); EngineCache.building: one build per key,
//        shared; the engine is discarded when the turn ends, never under it
//
// On-device inference: the on-device GenerationTransport. Tool calling is wired
// through LiteRTToolBridge; the shared GenerationEngine loop sits above.
package ai.teemoon.inference

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Engines are expensive to build (gigabytes, seconds), so one per model path is
 * both correct and necessary. Keyed by path AND context size: contextTokens
 * sizes the KV cache at construction, so a different context must not
 * silently get the engine built for the old one.
 */
class LocalEngineCache(
    private val factory: LocalEngineFactory,
    private val residency: LocalEngineResidency = LocalEngineResidency.shared,
    /** The one-generation lock: an eviction asked for while a turn holds it waits for [afterTurn]. */
    private val gate: LocalGenerationGate = LocalGenerationGate.shared,
    /** The pre-load memory gate: throws LocalInferenceError.InsufficientMemory. The app wires ActivityManager; tests pass nothing. */
    private val memoryCheck: (needMB: Int) -> Unit = {},
    private val fileExists: (String) -> Boolean = { File(it).exists() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    private data class Key(val path: String, val contextTokens: Int)
    private val engines = LinkedHashMap<Key, LocalEngineHandle>()
    /**
     * Builds in flight. A warm-up and a turn asking for the same engine share
     * one build: the open suspends for seconds, and without this the second
     * caller found no engine and built another — two multi-gigabyte engines.
     */
    private val building = HashMap<Key, Deferred<LocalEngineHandle>>()
    private var evictionPending = false
    val residentCount: Int get() = synchronized(this) { engines.size }

    /**
     * Releases every cached engine — on memory pressure and when the app is backgrounded.
     *
     * NEVER UNDER A RUNNING GENERATION. Backgrounding sends TRIM_MEMORY_UI_HIDDEN, and closing the engine a decode is
     * streaming from pulls the native session out from under it. iOS hit the same shape from the other side (5525c8a:
     * Metal revoked mid-decode, a turn "thinking" forever) and its rule carries over: the engine is discarded when the
     * turn is done, before the gate releases. While a turn holds the gate the eviction is remembered and [afterTurn]
     * performs it.
     */
    fun evictAll() {
        if (gate.isBusy) { synchronized(this) { if (engines.isNotEmpty()) { evictionPending = true; log("[litert] eviction deferred until the turn ends") } }; return }
        evictNow()
    }

    /** Called by the transport at the end of every turn, still inside the gate: performs an eviction deferred during it. */
    fun afterTurn() {
        val pending = synchronized(this) { evictionPending.also { evictionPending = false } }
        if (pending) evictNow()
    }

    private fun evictNow() { synchronized(this) { if (engines.isEmpty()) return; log("[litert] releasing ${engines.size} engine(s)"); engines.values.forEach { runCatching { it.close() } }; engines.clear(); publish() } }

    private fun publish() { residency.update(engines.keys.map { it.path }.toSet()) }

    suspend fun engine(path: String, contextTokens: Int, sizeMB: Int, headroomMB: Int, requireNpu: Boolean = false): LocalEngineHandle {
        val key = Key(path, contextTokens)
        val (job, owner) = synchronized(this) {
            engines[key]?.let { return it }
            building[key]?.let { return@synchronized it to false }
            CompletableDeferred<LocalEngineHandle>().also { building[key] = it } to true
        }
        if (!owner) return job.await()
        val done = job as CompletableDeferred<LocalEngineHandle>
        try {
            val handle = build(key, path, contextTokens, sizeMB, headroomMB, requireNpu)
            done.complete(handle)
            return handle
        } catch (t: Throwable) {
            done.completeExceptionally(t)
            throw t
        } finally {
            synchronized(this) { building.remove(key) }
        }
    }

    private suspend fun build(key: Key, path: String, contextTokens: Int, sizeMB: Int, headroomMB: Int, requireNpu: Boolean): LocalEngineHandle {
        if (!fileExists(path)) throw LocalInferenceError.ModelNotDownloaded(File(path).name)
        // ONE ENGINE RESIDENT AT A TIME, evicted BEFORE the memory gate runs — otherwise the gate measures memory an
        // engine nobody is using still holds. Published before the gate, which can throw: nothing is warm at this point.
        synchronized(this) {
            for (other in engines.keys.toList()) if (other != key) { log("[litert] evicting ${File(other.path).name} to make room"); engines.remove(other)?.let { runCatching { it.close() } } }
            publish()
        }
        memoryCheck(sizeMB + headroomMB)
        val start = nowMs()
        val handle = factory.open(path, contextTokens, requireNpu)
        val took = nowMs() - start
        log("[litert] engine ready in ${took}ms")
        synchronized(this) { engines[key] = handle; residency.recordLoad(path, took); publish() }
        return handle
    }
}

class LiteRTTransport(
    /** Path to the `.litertlm` bundle — one packaged file. */
    private val modelPath: String,
    private val estimatedSizeMB: Int,
    /**
     * KV-cache size: the ceiling on INPUT + OUTPUT tokens together — NOT a
     * generation cap. Sized for a grounded prompt in a conversation: persona
     * ~200 + history ~500–1500 + 5 sources ~4200 + answer ~500 ≈ 6400, so 8192
     * with headroom. Not the model's 32k maximum because this IS the KV cache
     * and costs memory; trimmedHistory bounds the part that grows.
     */
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    private val cache: LocalEngineCache,
    private val bridge: LiteRTToolBridge = LiteRTToolBridge(),
    private val gate: LocalGenerationGate = LocalGenerationGate.shared,
    /** This transport reports tool rounds itself: LiteRT runs the loop, so the engine never sees the calls. */
    private val events: StreamCallbacks? = null,
    private val headroomMB: Int = 512,
    private val maxTokens: Int = LocalDefaults.MAX_TOKENS,
    private val enableThinking: Boolean = LocalDefaults.ENABLE_THINKING,
    private val temperature: Float = LocalDefaults.TEMPERATURE,
    private val backendLabel: String = "gpu",
    /** This bundle is NPU-compiled and runs on the NPU alone; see LocalEngineFactory.open. */
    private val requireNpu: Boolean = false,
    private val log: (String) -> Unit = {},
) : GenerationTransport {

    override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult = gate.withTurn {
        try { turn(messages, includeTools, tools, onPartial) } finally { cache.afterTurn() }
    }

    private suspend fun turn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
        val engine = cache.engine(modelPath, contextTokens, estimatedSizeMB, headroomMB, requireNpu)
        // SEED the transcript, do not REPLAY it. sendMessage GENERATES a full response to each message — replaying the
        // history produced a complete generation per prior turn (minutes vs ~15 s, "still thinking, 60s and rising").
        // The system message and the initial messages populate the history WITHOUT generating.
        val systemText = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String
        val systemMessage = systemText?.takeIf { it.isNotEmpty() }
        val lastText = (messages.lastOrNull()?.get("content") as? String) ?: ""
        val prior = messages.dropLast(1).filter { it["role"] != "system" }
        // The answer reserve was sized for an 8192 window; an NPU bundle's window is 4096, where 4800 would leave
        // the history budget negative and drop every prior turn. Reserve a third of a small window instead.
        val answerReserve = minOf(ANSWER_RESERVE_TOKENS, contextTokens / 3)
        val history = trimmedHistory(prior, budgetTokens = contextTokens - estimatedTokens(systemMessage ?: "") - estimatedTokens(lastText) - answerReserve, log = log)
            .mapNotNull { m -> (m["content"] as? String)?.takeIf { it.isNotEmpty() }?.let { role(m["role"] as? String) to it } }
        val executed = ArrayList<ToolCallRecord>()
        val shims = if (includeTools && tools.isNotEmpty()) bridge.bridged(tools,
            onStarted = { _, argsJson ->
                // Flip the chip to "searching" while the search is actually running, as GenerationEngine does for remote providers.
                val query = runCatching { (Json.parseToJsonElement(argsJson) as? JsonObject)?.get("query") as? JsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
                if (!query.isNullOrEmpty()) events?.onQueriesFound(listOf(query))
            },
            onExecuted = { name, args, result ->
                synchronized(executed) { executed += ToolCallRecord(name, args, result) }
                // Per ROUND, not once at the end: the rail populates as sources arrive, and the chip is told the search finished.
                val sources = BraveWebSearchTool.parseSources(from = result)
                if (sources.isNotEmpty()) events?.onSourcesFound(sources)
                events?.onToolExecutionEnded()
            }, warn = log) else emptyList()
        // FAIL LOUD before the runtime does. The fixed part of the prompt — system message, tool descriptions, the
        // message — cannot be trimmed, and the runtime's refusal ("Input token ids are too long") names no cause. On a
        // 4096 window the web tool alone is most of the budget; the user needs to be told THAT, not shown a JNI code.
        val toolTokens = shims.sumOf { estimatedTokens(it.descriptionJson) }
        val fixedTokens = estimatedTokens(systemMessage ?: "") + estimatedTokens(lastText) + toolTokens
        if (fixedTokens + MIN_ANSWER_TOKENS > contextTokens) {
            log("[litert] prompt of ~$fixedTokens tokens (tools ~$toolTokens) cannot fit a $contextTokens window")
            throw LocalInferenceError.PromptTooLarge(fixedTokens, contextTokens, toolTokens)
        }
        val spec = LocalConversationSpec(systemMessage, history, shims, lastText, maxTokens, enableThinking, temperature)
        val answer = StringBuilder()
        // Tool-call markup must never stream to the user — same machine as the SSE path. Always enabled: a small local
        // model may emit tool syntax whether or not tools were offered this turn. Stream the ELIDED accumulation, never
        // the raw one: a cumulative-with-marker check LATCHED and froze partials for the rest of the turn.
        val elision = StreamingMarkupElision(enabled = true)
        // THE ANSWER ONLY reaches the screen, as with every cloud model: their reasoning arrives in its own field and
        // the transcript shows the "thinking…" chip, never the tokens. Gemma writes its thinking INTO the text
        // (`<think>` or the Tensor bundle's `<|channel>thought`), so it streamed as body text in an open disclosure —
        // the one model that did (user, 2026-10-02: "just use the thinking chip rather than showing the thinking
        // tokens to be consistent"). While only thinking has arrived nothing is emitted and the chip stays up; the
        // reasoning is kept in TurnResult.reasoning, where the cloud path keeps it.
        var lastShown = ""
        fun emitAnswerOnly() {
            val visible = elision.visibleContent
            if (ThinkingContentParser.mayBeginOpener(visible)) return   // an opener still arriving in pieces is not answer
            val shown = ThinkingContentParser.parse(visible).answer ?: return
            if (shown != lastShown) { lastShown = shown; onPartial(shown) }
        }
        engine.stream(spec) { piece ->
            if (piece.isEmpty()) return@stream
            answer.append(piece)
            if (elision.append(piece).isNotEmpty()) emitAnswerOnly()
            if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
        }
        // End of stream: a suffix held back as a possible marker prefix is now known to be prose.
        if (elision.finish().isNotEmpty()) emitAnswerOnly()
        val bench = engine.benchmark()
        bench?.let { log("[litert] ttft=${it.timeToFirstTokenSeconds}s prefill=${it.prefillTokens}tok (${it.prefillTokensPerSecond.toInt()}tok/s) · decode=${it.decodeTokens}tok (${it.decodeTokensPerSecond.toInt()}tok/s)") }
        val records = synchronized(executed) { executed.toList() }
        if (records.isNotEmpty()) log("[litert] executed ${records.size} tool call(s)")
        val parsed = ThinkingContentParser.parse(answer.toString())
        return TurnResult(
            content = parsed.answer ?: "",
            reasoning = parsed.thinking ?: "",
            // Deliberately empty: the calls already ran inside LiteRT's loop; handing them back would run each a second time.
            toolCalls = emptyMap(),
            isTextBasedToolCall = false,
            completionTokens = bench?.decodeTokens,
            // The developer panel's equivalent of a request for a turn that never made one: url stays null, the messages
            // actually sent and the runtime settings ARE the request.
            report = TurnReport(url = null,
                requestHeaders = mapOf("runtime" to "LiteRT-LM", "model" to File(modelPath).name, "backend" to backendLabel, "context-tokens" to contextTokens.toString(), "tools-offered" to (if (includeTools) tools.size.toString() else "0")),
                requestBodyJson = prettyMessages(messages), isE2eeActive = false, executedToolCalls = records, verify = null,
                localStats = bench?.let { LocalTurnStats(engine.backend ?: backendLabel, engine.speculative, it.prefillTokens, it.prefillTokensPerSecond) }),
        )
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 8192
        /** Room for the model's own answer and the grounding payload a tool round injects; generous because the failure it prevents is a hard INVALID_ARGUMENT, seen as a one-token non-answer. */
        const val ANSWER_RESERVE_TOKENS = 4800
        /** The least room an answer is worth starting with. Below this the turn is refused up front rather than cut off mid-sentence. */
        const val MIN_ANSWER_TOKENS = 256

        /** Deliberately PESSIMISTIC: Gemma 4 averages ~6.3 characters per token, so dividing by 4 over-estimates, which keeps the prompt under a hard ceiling. */
        fun estimatedTokens(text: String): Int = maxOf(1, text.length / 4)

        /** Keeps the most RECENT turns that fit: a follow-up depends on the message before it, not the first one. */
        fun trimmedHistory(messages: List<Map<String, Any>>, budgetTokens: Int, log: (String) -> Unit = {}): List<Map<String, Any>> {
            if (budgetTokens <= 0) return emptyList()
            val kept = ArrayList<Map<String, Any>>(); var used = 0
            for (message in messages.asReversed()) {
                val cost = estimatedTokens((message["content"] as? String) ?: "")
                if (used + cost > budgetTokens) break
                used += cost; kept.add(0, message)
            }
            if (kept.size < messages.size) log("[litert] trimmed history to ${kept.size}/${messages.size} turns to fit the context")
            return kept
        }

        /** LiteRT follows Gemini's vocabulary: the assistant is MODEL. Getting this wrong quietly poisons multi-turn context. */
        fun role(wireRole: String?): LocalRole = when (wireRole) { "assistant" -> LocalRole.MODEL; "system" -> LocalRole.SYSTEM; "tool" -> LocalRole.TOOL; else -> LocalRole.USER }

        private val pretty = Json { prettyPrint = true }
        fun prettyMessages(messages: List<Map<String, Any>>): String? = runCatching { pretty.encodeToString(JsonElement.serializer(), JsonArray(messages.map { toJson(it) })) }.getOrNull()
        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull; is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.sortedBy { it.key.toString() }.associate { it.key.toString() to toJson(it.value) })
            is List<*> -> JsonArray(v.map { toJson(it) }); else -> JsonPrimitive(v.toString())
        }
    }
}
