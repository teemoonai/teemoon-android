// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 — the transport over the LocalEngineFactory seam (the
//        LiteRT-LM binding itself is :ondevice's LiteRtLmEngineFactory); EngineCache.building: one build per key,
//        shared; the engine is discarded when the turn ends, never under it
// ports: teemoon/Inference/LiteRTTransport.swift @ afc4327 (residentServes; the vision executor only for an image turn, IMAGES.md §4b)
// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.6 (thinking, thinkingReserveTokens, strippingInlineThought, the
//        "thinking" header, wasTruncated at the cap; 7cd8c10's ThinkingConfig for Think Harder)
//
// On-device inference: the on-device GenerationTransport. Tool calling is wired
// through LiteRTToolBridge; the shared GenerationEngine loop sits above.
// history images: teemoon/Inference/LiteRTTransport.swift @ 29d48870 (IMAGES.md §4b @ 00f72739).
package ai.teemoon.inference

import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Duration
import java.time.Instant
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
    /**
     * One build at a time (A5, pm sweep 2026-11-06). Each build evicts what is resident before it loads, but two at
     * once (a warm-up of one bundle, a turn on another) each evicted only what was there before either started, and
     * both kept their engine. Serialised, the second evicts the first. It also keeps the runtime's process-global
     * speculative-decoding flag, set per open, from being set by one build under the other.
     */
    private val buildLock = kotlinx.coroutines.sync.Mutex()
    /** Bumped by every eviction asked for: a build that finishes after one knows its engine was evicted while it loaded. */
    private var evictions = 0L
    private var forgetPending = false
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
        // A build in flight has nothing resident yet: counted, so the engine it finishes with is not kept against this.
        synchronized(this) { evictions += 1 }
        if (gate.isBusy) { synchronized(this) { if (engines.isNotEmpty() || building.isNotEmpty()) { evictionPending = true; log("[litert] eviction deferred until the turn ends") } }; return }
        evictNow()
    }

    /**
     * A deleted chat's text must not outlive it in a kept conversation (iOS fc09a91; the maintainer, 2026-10-19: "yes clear on
     * delete"). The kept state is per engine, not per thread, so all of it goes and the next on-device turn seeds
     * again; the engines and their weights stay loaded. Like an eviction, never under a running turn: it waits for
     * [afterTurn].
     */
    fun forgetKeptConversations() {
        if (gate.isBusy) { synchronized(this) { forgetPending = true }; log("[litert] dropping kept conversations after the turn"); return }
        forgetNow()
    }

    private fun forgetNow() { val all = synchronized(this) { engines.values.toList() }; all.forEach { runCatching { it.dropKept() } }; if (all.isNotEmpty()) log("[litert] kept conversations dropped") }

    /** Called by the transport at the end of every turn, still inside the gate: performs an eviction or a forget deferred during it. */
    fun afterTurn() {
        val (evict, forget) = synchronized(this) { (evictionPending to forgetPending).also { evictionPending = false; forgetPending = false } }
        if (evict) evictNow() else if (forget) forgetNow()
    }

    private fun evictNow() { synchronized(this) { if (engines.isEmpty()) return; log("[litert] releasing ${engines.size} engine(s)"); engines.values.forEach { runCatching { it.close() } }; engines.clear(); publish() } }

    private fun publish() { residency.update(engines.keys.map { it.path }.toSet()) }

    suspend fun engine(path: String, contextTokens: Int, sizeMB: Int, headroomMB: Int, requireNpu: Boolean = false, vision: Boolean = false): LocalEngineHandle {
        val key = Key(path, contextTokens)
        val (job, owner) = synchronized(this) {
            engines[key]?.takeIf { residentServes(it.sees, vision) }?.let { return it }
            building[key]?.let { return@synchronized it to false }
            CompletableDeferred<LocalEngineHandle>().also { building[key] = it } to true
        }
        // A shared build that came out text-only cannot run an image turn: build again, with vision.
        if (!owner) return job.await().takeIf { residentServes(it.sees, vision) } ?: engine(path, contextTokens, sizeMB, headroomMB, requireNpu, vision)
        val done = job as CompletableDeferred<LocalEngineHandle>
        try {
            val handle = buildLock.withLock { build(key, path, contextTokens, sizeMB, headroomMB, requireNpu, vision) }
            done.complete(handle)
            return handle
        } catch (t: Throwable) {
            done.completeExceptionally(t)
            throw t
        } finally {
            synchronized(this) { building.remove(key) }
        }
    }

    private suspend fun build(key: Key, path: String, contextTokens: Int, sizeMB: Int, headroomMB: Int, requireNpu: Boolean, vision: Boolean): LocalEngineHandle {
        if (!fileExists(path)) throw LocalInferenceError.ModelNotDownloaded(File(path).name)
        // ONE ENGINE RESIDENT AT A TIME, evicted BEFORE the memory gate runs — otherwise the gate measures memory an
        // engine nobody is using still holds. Published before the gate, which can throw: nothing is warm at this point.
        // Includes this key's own text-only engine when an image turn asks for vision (iOS afc4327).
        synchronized(this) {
            for (other in engines.keys.toList()) { log("[litert] evicting ${File(other.path).name}${if (other == key) " (text-only; this turn has an image)" else ""} to make room"); engines.remove(other)?.let { runCatching { it.close() } } }
            publish()
        }
        memoryCheck(sizeMB + headroomMB)
        val epoch = synchronized(this) { evictions }
        val start = nowMs()
        val handle = factory.open(path, contextTokens, requireNpu, vision)
        val took = nowMs() - start
        log("[litert] engine ready in ${took}ms")
        val evictedWhileLoading = synchronized(this) {
            val evicted = evictions != epoch
            // Under a turn (it holds the gate while its engine builds) the turn needs it: kept, and evicted when the
            // turn ends. With no turn — a warm-up — nobody is using it: not kept against the eviction.
            if (evicted && !gate.isBusy) return@synchronized true
            if (evicted) evictionPending = true
            engines[key] = handle; residency.recordLoad(path, took); publish()
            false
        }
        if (evictedWhileLoading) {
            runCatching { handle.close() }
            log("[litert] evicted while loading — not kept")
            throw kotlinx.coroutines.CancellationException("the engine was evicted while it loaded")
        }
        return handle
    }
}

/**
 * Whether a resident engine can run this turn: a vision engine serves text too, so only an image turn on a text-only
 * engine rebuilds. The engine decides vision at creation (iOS LiteRTTransport.residentServes @ afc4327).
 */
fun residentServes(residentVision: Boolean, wantsVision: Boolean): Boolean = residentVision || !wantsVision

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
    /**
     * Think Harder: LiteRT's ThinkingConfig on (iOS 7cd8c10). Off for every other turn, as before 1.0.6. The thought is
     * handled like cloud reasoning — never shown, never saved (a6ce6e8): the GPU bundle sends it in its own channel,
     * which the factory never reads; the NPU bundle writes it inline, held back while it streams and stripped below.
     */
    private val thinking: Boolean = LocalDefaults.ENABLE_THINKING,
    private val backendLabel: String = "gpu",
    /** This bundle is NPU-compiled and runs on the NPU alone; see LocalEngineFactory.open. */
    private val requireNpu: Boolean = false,
    /**
     * the maintainer 2026-10-26 (a): the general file an NPU bundle's image turns run on (it carries the vision encoder; the NPU
     * bundle does not). Null when [modelPath] is the general file itself, or the general file is not downloaded.
     */
    private val imageModelPath: String? = null,
    private val imageSizeMB: Int = estimatedSizeMB,
    /**
     * IMAGES.md §4b, option (c): what a vision load and its image turns take from availMem (LocalModel.visionLoadMB). An image
     * turn's load asks the memory gate for this + headroom instead of the file's size; short, the turn is refused with
     * [LocalInferenceError.ImagesShortOfMemoryNow]. Null: the file's size, as a text load.
     */
    private val visionLoadMB: Int? = null,
    /** The model's name as the refusal says it ("gemma 4 e2b"). */
    private val modelName: String = "this model",
    /** An image turn begins (true) and ends (false): PhoneImageLedger's in-flight mark, so a kill mid-turn is found at launch. */
    private val onImageTurn: (begins: Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
) : GenerationTransport {

    override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult = gate.withTurn {
        try { turn(messages, includeTools, tools, onPartial) } finally { cache.afterTurn() }
    }

    private suspend fun turn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
        // SEED the transcript, do not REPLAY it. sendMessage GENERATES a full response to each message — replaying the
        // history produced a complete generation per prior turn (minutes vs ~15 s, "still thinking, 60s and rising").
        // The system message and the initial messages populate the history WITHOUT generating.
        val systemText = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String
        val systemMessage = systemText?.takeIf { it.isNotEmpty() }
        // A turn with images arrives as a content array (wireContent): its text parts, and every image's bytes in order.
        val lastContent = messages.lastOrNull()?.get("content")
        val lastText = textOf(lastContent)
        val lastImages = imagesOf(lastContent)
        // IMAGES.md §4b @ 00f72739: earlier turns carry their images too (ImageHistory.applyOnDevice chose them), and any
        // image in the request, the question's or an earlier one, needs the vision engine, the memory check and the mark.
        val prior = messages.dropLast(1).filter { it["role"] != "system" }
        val anyImages = lastImages.isNotEmpty() || prior.any { imagesOf(it["content"]).isNotEmpty() }
        // IMAGES.md §4b: only a turn with an image asks for the vision executor. On a Tensor phone that turn runs the
        // general file instead of the NPU bundle (one engine resident at a time: the NPU engine is closed for it).
        val imageOnGeneral = anyImages && imageModelPath != null
        val requested = if (imageOnGeneral) DEFAULT_CONTEXT_TOKENS else contextTokens
        if (anyImages) onImageTurn(true)
        try {
        val engine = try {
            if (imageOnGeneral) cache.engine(imageModelPath, requested, visionLoadMB ?: imageSizeMB, headroomMB, requireNpu = false, vision = true)
            else cache.engine(modelPath, requested, if (anyImages) visionLoadMB ?: estimatedSizeMB else estimatedSizeMB, headroomMB, requireNpu, vision = anyImages)
        } catch (e: LocalInferenceError.InsufficientMemory) {
            // the memory check (option c): an image turn short of the vision load says so, not "close some apps"
            if (!anyImages) throw e
            log("[litert] image turn refused: needs ${e.needMB} MB, ${e.availableMB} MB available")
            throw LocalInferenceError.ImagesShortOfMemoryNow(modelName, e.needMB, e.availableMB)
        }
        // The window the engine was BUILT with: the CPU rung takes 4096 whatever was asked (BackendLadder.contextTokens).
        // Budgeting by the request would hand a 4096 engine an 8192 prompt, and the runtime's refusal names no cause.
        val contextTokens = engine.contextTokens ?: requested
        // The answer reserve was sized for an 8192 window; an NPU bundle's window is 4096, where 4800 would leave
        // the history budget negative and drop every prior turn. Reserve a third of a small window instead.
        val answerReserve = minOf(ANSWER_RESERVE_TOKENS, contextTokens / 3)
        // a turn with images was a content array: its text and its images both seed (§4b @ 00f72739; it was dropped whole)
        val seeded = trimmedHistory(prior, budgetTokens = contextTokens - estimatedTokens(systemMessage ?: "") - estimatedTokens(lastText) - lastImages.size * VISION_TOKENS_PER_IMAGE - answerReserve, log = log)
            .map { m -> Triple(role(m["role"] as? String), textOf(m["content"]), imagesOf(m["content"])) }
            .filter { (_, text, images) -> text.isNotEmpty() || images.isNotEmpty() }
        val history = seeded.map { (role, text, _) -> role to text }
        val historyImages = seeded.map { it.third }
        val executed = ArrayList<ToolCallRecord>()
        // The developer card's phase line (TurnPhase): the runtime runs the tool loop, so this transport times it.
        val phases = ArrayList<TurnPhase>()
        var toolStartedAt: Instant? = null
        val shims = if (includeTools && tools.isNotEmpty()) bridge.bridged(tools,
            onStarted = { _, argsJson ->
                toolStartedAt = Instant.now()
                // Flip the chip to "searching" while the search is actually running, as GenerationEngine does for remote providers.
                val query = runCatching { (Json.parseToJsonElement(argsJson) as? JsonObject)?.get("query") as? JsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
                if (!query.isNullOrEmpty()) events?.onQueriesFound(listOf(query))
            },
            onExecuted = { name, args, result ->
                synchronized(executed) { executed += ToolCallRecord(name, args, result) }
                val end = Instant.now()
                synchronized(phases) { phases += TurnPhase(TurnPhase.Kind.TOOL, end, ms = toolStartedAt?.let { Duration.between(it, end).toMillis() },
                    bytes = result.toByteArray(Charsets.UTF_8).size, tokens = TurnPhase.tokenEstimate(result.toByteArray(Charsets.UTF_8).size)) }
                // Per ROUND, not once at the end: the rail populates as sources arrive, and the chip is told the search finished.
                val sources = BraveWebSearchTool.parseSources(from = result)
                if (sources.isNotEmpty()) events?.onSourcesFound(sources)
                events?.onToolExecutionEnded()
            }, warn = log) else emptyList()
        // FAIL LOUD before the runtime does. The fixed part of the prompt — system message, tool descriptions, the
        // message — cannot be trimmed, and the runtime's refusal ("Input token ids are too long") names no cause. On a
        // 4096 window the web tool alone is most of the budget; the user needs to be told THAT, not shown a JNI code.
        val toolTokens = shims.sumOf { estimatedTokens(it.descriptionJson) }
        val fixedTokens = estimatedTokens(systemMessage ?: "") + estimatedTokens(lastText) + toolTokens + lastImages.size * VISION_TOKENS_PER_IMAGE
        if (fixedTokens + MIN_ANSWER_TOKENS > contextTokens) {
            log("[litert] prompt of ~$fixedTokens tokens (tools ~$toolTokens) cannot fit a $contextTokens window")
            throw LocalInferenceError.PromptTooLarge(fixedTokens, contextTokens, toolTokens)
        }
        // A thought counts against the cap too (~400 tokens a question, iOS measured): room for one on top of the answer.
        val cap = if (thinking) maxTokens + THINKING_RESERVE_TOKENS else maxTokens
        val spec = LocalConversationSpec(systemMessage, history, shims, lastText, cap, thinking, userImages = lastImages, historyImages = historyImages)
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
        var firstTokenAt: Instant? = null
        var firstVisibleAt: Instant? = null
        fun emitAnswerOnly() {
            val visible = elision.visibleContent
            if (ThinkingContentParser.mayBeginOpener(visible)) return   // an opener still arriving in pieces is not answer
            val shown = ThinkingContentParser.parse(visible).answer ?: return
            if (shown.isNotEmpty() && firstVisibleAt == null) firstVisibleAt = Instant.now()
            if (shown != lastShown) { lastShown = shown; onPartial(shown) }
        }
        // An inline thought is held back while it streams, so it never flashes on screen (iOS a6ce6e8).
        val inlineThought = InlineThoughtFilter()
        engine.stream(spec) { text ->
            if (text.isEmpty()) return@stream
            if (firstTokenAt == null) firstTokenAt = Instant.now()
            answer.append(text)
            val piece = inlineThought.append(text)
            if (piece.isNotEmpty() && elision.append(piece).isNotEmpty()) emitAnswerOnly()
            if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
        }
        // End of stream: a suffix held back as a possible marker prefix is now known to be prose.
        inlineThought.finish().takeIf { it.isNotEmpty() }?.let { elision.append(it) }
        if (elision.finish().isNotEmpty()) emitAnswerOnly()
        val bench = engine.benchmark()
        // No ttft: getBenchmarkInfo's is the CONVERSATION's first turn and never updates on a reused one (Pixel 10 Pro,
        // 2026-10-26: 1.29 s on all five turns of a reused CPU chat), while its prefill and decode figures do. iOS
        // dropped it from its line the same way (6777498). The card's first-token time is this transport's own firstTokenAt.
        bench?.let { log("[litert] prefill=${it.prefillTokens}tok (${it.prefillTokensPerSecond.toInt()}tok/s) · decode=${it.decodeTokens}tok (${it.decodeTokensPerSecond.toInt()}tok/s)") }
        val records = synchronized(executed) { executed.toList() }
        if (records.isNotEmpty()) log("[litert] executed ${records.size} tool call(s)")
        // The inline thought goes, all of it when the cap cut it off: never the reply, never reasoning for the
        // reasoning-only fallback to promote. A `<think>` block still parses as before.
        val parsed = ThinkingContentParser.parse(strippingInlineThought(answer.toString()))
        // The runtime stopped at the cap: with no answer that is the "no reply" error, never the thought.
        val hitCap = bench?.decodeTokens?.let { it >= cap } ?: false
        if (hitCap) log("[litert] the answer reached the $cap-token cap")
        return TurnResult(
            content = parsed.answer ?: "",
            reasoning = parsed.thinking ?: "",
            // Deliberately empty: the calls already ran inside LiteRT's loop; handing them back would run each a second time.
            toolCalls = emptyMap(),
            isTextBasedToolCall = false,
            completionTokens = bench?.decodeTokens,
            wasTruncated = hitCap,
            // The developer panel's equivalent of a request for a turn that never made one: url stays null, the messages
            // actually sent and the runtime settings ARE the request.
            report = TurnReport(url = null,
                requestHeaders = mapOf("runtime" to "LiteRT-LM", "model" to File(if (imageOnGeneral) imageModelPath else modelPath).name, "backend" to backendLabel, "context-tokens" to contextTokens.toString(), "tools-offered" to (if (includeTools) tools.size.toString() else "0"),
                    "thinking" to if (thinking) "on" else "off") + listOfNotNull(engine.lastConversation?.let { "conversation" to it }),
                requestBodyJson = prettyRequest(messages, if (includeTools) tools else emptyList()), isE2eeActive = false, executedToolCalls = records, verify = null,
                firstTokenAt = firstTokenAt, firstVisibleTokenAt = firstVisibleAt, phases = synchronized(phases) { phases.toList() },
                localStats = bench?.let { LocalTurnStats(engine.backend ?: backendLabel, engine.speculative, it.prefillTokens, it.prefillTokensPerSecond) }),
        )
        } finally { if (anyImages) onImageTurn(false) }
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 8192
        /** Room for the model's own answer and the grounding payload a tool round injects; generous because the failure it prevents is a hard INVALID_ARGUMENT, seen as a one-token non-answer. */
        const val ANSWER_RESERVE_TOKENS = 4800
        /** Room for a Think Harder thought on top of the answer cap (iOS a6ce6e8): 4800 + 2048. */
        const val THINKING_RESERVE_TOKENS = 2048

        private val INLINE_THOUGHT = Regex("""<\|channel>thought[\s\S]*?(?:<channel\|>|$)""")

        /**
         * Some bundles put the thought inline as `<|channel>thought…<channel|>` (the Tensor NPU build). Like the channel,
         * it is discarded: never shown, never the saved reply. Cut off by the cap, all of it goes.
         */
        fun strippingInlineThought(raw: String): String = raw.replace(INLINE_THOUGHT, "")
        /** The least room an answer is worth starting with. Below this the turn is refused up front rather than cut off mid-sentence. */
        const val MIN_ANSWER_TOKENS = 256
        /** IMAGES.md §4b: the vision tokens an image costs in the prompt (iOS's budget; to be tuned by measurement). */
        const val VISION_TOKENS_PER_IMAGE = 280

        /** Deliberately PESSIMISTIC: Gemma 4 averages ~6.3 characters per token, so dividing by 4 over-estimates, which keeps the prompt under a hard ceiling. */
        fun estimatedTokens(text: String): Int = maxOf(1, text.length / 4)

        /** A message's text: the string, or a content array's text parts (wireContent). */
        fun textOf(content: Any?): String = when (content) {
            is String -> content
            is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.takeIf { p -> p["type"] == "text" }?.get("text") as? String }.joinToString("\n\n")
            else -> ""
        }

        /** A content array's images, in order: each data URL's bytes. */
        fun imagesOf(content: Any?): List<ByteArray> = (content as? List<*>).orEmpty()
            .mapNotNull { ((it as? Map<*, *>)?.get("image_url") as? Map<*, *>)?.get("url") as? String }
            .mapNotNull { url -> url.substringAfter(";base64,", "").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } }

        /** Keeps the most RECENT turns that fit: a follow-up depends on the message before it, not the first one. */
        fun trimmedHistory(messages: List<Map<String, Any>>, budgetTokens: Int, log: (String) -> Unit = {}): List<Map<String, Any>> {
            if (budgetTokens <= 0) return emptyList()
            val kept = ArrayList<Map<String, Any>>(); var used = 0
            for (message in messages.asReversed()) {
                // an earlier turn's images count as the vision tokens they prefill (§4b @ 00f72739)
                val cost = estimatedTokens(textOf(message["content"])) + imagesOf(message["content"]).size * VISION_TOKENS_PER_IMAGE
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

        /**
         * The card's request body (iOS ca3d155; the maintainer, 2026-10-22: Gemma's card said "tools-offered: 1" and showed no
         * tool): the messages and the tools the model was given, in the cloud card's `tools` shape from the same mapping
         * the wire uses ([OpenAiTransport.wireTools]), keys sorted. No tools offered, no `tools` key: the body is as it
         * was. Display only — built from the list handed to the runtime this turn; nothing the model gets changes.
         */
        fun prettyRequest(messages: List<Map<String, Any>>, tools: List<Tool>): String? {
            if (tools.isEmpty()) return prettyMessages(messages)
            return runCatching {
                pretty.encodeToString(JsonElement.serializer(), sorted(JsonObject(mapOf("messages" to JsonArray(messages.map { toJson(it) }), "tools" to OpenAiTransport.wireTools(tools)))))
            }.getOrNull()
        }
        private fun sorted(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { (k, v) -> k to sorted(v) })
            is JsonArray -> JsonArray(e.map { sorted(it) })
            else -> e
        }
        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull; is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.sortedBy { it.key.toString() }.associate { it.key.toString() to toJson(it.value) })
            is List<*> -> JsonArray(v.map { toJson(it) }); else -> JsonPrimitive(v.toString())
        }
    }
}
