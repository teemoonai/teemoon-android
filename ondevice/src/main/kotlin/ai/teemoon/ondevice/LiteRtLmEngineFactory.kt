// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 (EngineCache.engine's EngineConfig/initialize, enableExperimentalFeatures,
//        the createConversation calls, the sendMessageStream text extraction, getBenchmarkInfo) — REBUILD over
//        com.google.ai.edge.litertlm:litertlm-android 0.18.0. Everything runtime-neutral is :inference's LiteRTTransport.
// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 (constrained decoding off, set per conversation)
// ports: teemoon/Inference/LiteRTTransport.swift @ afc4327 (EngineCache.build's load events and the 250 ms cache watch during initialize())
package ai.teemoon.ondevice

import ai.teemoon.inference.LiteRTLiveTools
import ai.teemoon.inference.ConversationReuse
import ai.teemoon.inference.KeptConversation
import ai.teemoon.inference.LiteRTToolBridge
import ai.teemoon.inference.LocalBenchmark
import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.inference.LocalEngineFactory
import ai.teemoon.inference.LocalEngineHandle
import ai.teemoon.inference.LocalInferenceError
import ai.teemoon.inference.LocalRole
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds LiteRT-LM engines on the best accelerator this device can actually run, and falls down [BackendLadder] to
 * the CPU when it cannot.
 *
 * **A backend is accepted only once it has decoded a token.** `initialize()` succeeding proves nothing: it is a graph
 * compile, and both failures seen so far got past it.
 *   - Emulator (API 35 arm64): the GPU initializes on the host's software adapter, then `WaitForCompletion timeout`
 *     ×N → status 13 from llm_litert_compiled_model_executor.cc.
 *   - Pixel 10 Pro (Tensor G5, Android 17, 2026-09-15): the GPU initializes in 18 s, every node claimed by the
 *     LITERT_WEBGPU delegate, and the first decode throws `Can not find OpenCL library on this device`.
 * With the old initialize-only probe both of those left the user a model that loads and then cannot answer, because
 * the fallback had already been skipped. The probe below runs a one-token generation, so "this backend works" means
 * what it says. THE MANIFEST IS HALF OF THIS FIX: since API 31 a vendor library must be named in a
 * `<uses-native-library>` tag or the dlopen fails and the runtime reports it absent — which is what the Pixel's
 * OpenCL message really meant.
 *
 * Flags carried from the Swift side: benchmark (the prefill/decode split is the whole point of comparing runtimes).
 * Constrained decoding was carried too, and is now off per conversation with iOS (6222a57) — see Handle.stream. The tool-call-streaming AND-gate that bit
 * the Swift wrapper does not exist in the Kotlin API: `Conversation` runs tool calls itself on both the sync and the
 * Flow path.
 *
 * @param nativeLibraryDir the directory `Backend.NPU` lists for a vendor dispatch library: the APK's own `.so`
 *   directory on a Tensor phone, a directory of links on a Snapdragon 8 Elite — whatever [NpuDispatch.directory]
 *   says. Null when the caller has none to offer, and the NPU rung is then left out of the ladder rather than attempted with nowhere to look.
 * @param socModel `Build.SOC_MODEL`, @param hardware `Build.HARDWARE` — passed in, never read here, so the ladder
 *   stays testable off-device.
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmEngineFactory(
    private val cacheDir: String,
    private val log: (String) -> Unit = {},
    private val nativeLibraryDir: String? = null,
    private val socModel: String = "",
    private val hardware: String = "",
    private val memo: BackendMemo = BackendMemo(File(cacheDir, "backend-verdict.tsv")),
    /** How long one rung gets to load AND decode its probe token before it is abandoned. */
    private val probeTimeoutMs: Long = 180_000,
    /** Keep a turn's Conversation for the next when it already holds what that turn would seed (ConversationReuse). Off only to measure the difference. */
    private val reuseConversations: Boolean = true,
    /**
     * The NPU-only bundle at [modelPath] could not start on this phone: told once per failure, before the throw. The
     * app records it against the file so the storage stops preferring it (LocalModelStorage.markNpuUnusable) — the
     * general file runs instead, or its download is offered. Without this a Tensor phone whose NPU refuses (a chip
     * the bundle was never run on) keeps failing every turn with nowhere to go.
     */
    private val onNpuUnusable: (modelPath: String, reason: String) -> Unit = { _, _ -> },
    /** Each load's start, end, and the first cache file written during it: the chip's load line (EngineLoadLine). */
    private val loadEvents: EngineLoadEvents = EngineLoadEvents.None,
    /** The CPU rung's threads; null takes [BackendLadder.cpuThreads] for this chip. Benches sweep it; 0 is the runtime's default. */
    private val cpuThreadsOverride: Int? = null,
    /** Benches only: the rungs to walk instead of [BackendLadder.order] (e.g. the GPU on a Pixel 10, to measure what (A) moved away from). */
    private val ladderOverride: List<BackendKind>? = null,
    /** Benches only: false opens the engine with no cacheDir, as Google AI Edge Gallery does for downloaded models. */
    private val diskCache: Boolean = true,
) : LocalEngineFactory {
    /** What the last successful open ran on. "cpu" until something better is PROVEN — never a hopeful claim. */
    @Volatile override var backendLabel: String = label(BackendKind.CPU); private set

    override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = open(modelPath, contextTokens, requireNpu, vision = false)

    /** The first rung this file would try (the memo's proven one, else the ladder's head) decides the window. */
    override fun contextTokensFor(modelPath: String, requested: Int, requireNpu: Boolean): Int {
        if (requireNpu) return requested
        val ladder = ladderOverride ?: BackendLadder.order(socModel, hardware)
        val first = memo.reorder(ladder, BackendMemo.key(modelPath, ladder)).firstOrNull() ?: return requested
        return BackendLadder.contextTokens(first, requested)
    }

    override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean, vision: Boolean): LocalEngineHandle = withContext(Dispatchers.IO) {
        ExperimentalFlags.enableBenchmark = true
        // Constrained decoding is NOT set here: it is per conversation, see Handle.stream.
        // An NPU bundle has one rung by construction. Walking the rest would spend a model load per rung to learn
        // what the file already says, and report "Input tensor not found" instead of "this device has no NPU".
        val full = if (requireNpu) listOf(BackendKind.NPU) else ladderOverride
            ?: if (vision) BackendLadder.visionOrder(socModel, hardware) else BackendLadder.order(socModel, hardware)
        // IMAGES.md §4b: the vision executor is created only for a turn with an image — text-only turns keep today's
        // memory (iOS afc4327; Android loaded it on every open from 70cfc1f until 2026-10-26, pm's decision to align).
        // Such a turn tries only the rungs that run a vision encoder, and never falls back to a text-only engine: the
        // turn would then go without its image, which a message never does.
        val ladder = if (vision) BackendLadder.visionLadder(full, requireNpu) else full
        if (vision && ladder.isEmpty()) throw LocalInferenceError.LoadFailed("this model can't see images on this phone")
        if (requireNpu && nativeLibraryDir == null) throw LocalInferenceError.LoadFailed("this model needs the NPU and no native library directory was given")
        val key = BackendMemo.key(modelPath, ladder)
        val order = memo.reorder(ladder, key)
        log("[litert] backends to try: " + order.joinToString(", ") { label(it) })
        var lastFailure: Throwable? = null
        for (kind in order) {
            // Speculative decoding is a per-engine flag read at construction. Where it pays and where it costs is
            // measured in BackendLadder.wantsSpeculativeDecoding (Adreno 26 → 69 tok/s, and on the Snapdragon 8 Elite's NPU;
            // PowerVR, CPU and the Tensor NPU all slower with it). `null` leaves the runtime's own default alone. And only when the file carries
            // a drafter: `Capabilities` reads the bundle's header, the same check iOS has.
            val speculative = BackendLadder.wantsSpeculativeDecoding(kind, socModel, hardware) && hasDrafter(modelPath)
            ExperimentalFlags.enableSpeculativeDecoding = if (speculative) true else null
            // The CPU rung's window is 4096 (BackendLadder.contextTokens); the handle reports it, the transport budgets by it.
            val window = if (requireNpu) contextTokens else BackendLadder.contextTokens(kind, contextTokens)
            run {
                val engine = Engine(EngineConfig(modelPath = modelPath, backend = backendFor(kind),
                    visionBackend = if (vision) visionBackendFor(kind) else null, maxNumTokens = window,
                    // one engine for any count up to the message limit; was 1, which refused a second image
                    maxNumImages = if (vision) LocalConversationSpec.MAX_IMAGES_PER_TURN else null,
                    // beside the model, deleted with it (EngineCache); the factory's own [cacheDir] keeps only the backend memo
                    cacheDir = if (diskCache) EngineCache.directoryFor(modelPath).apply { mkdirs() }.path else null))
                val started = System.currentTimeMillis()
                // The chip's load line reads what the load does on disk, never a timer (EngineLoadLine): whether this
                // bundle's cache was there, and the first file LiteRT writes while it initializes. Only during
                // initialize(): on iOS the program cache grew after a load had ended, which a longer watch would
                // misread as a rebuild. Every rung and the vision-less retry are loads of their own.
                val bundle = File(modelPath)
                val before = EngineCache.snapshot(bundle)
                loadEvents.began(started, imageCapable = vision, cachePresent = EngineCache.present(bundle, vision))
                val watch = launch {
                    while (isActive) {
                        delay(CACHE_WATCH_MS)
                        if (EngineCache.snapshot(bundle) != before) { loadEvents.rebuildSeen(System.currentTimeMillis()); break }
                    }
                }
                // Nothing interrupts a blocking JNI load. withTimeoutOrNull around it could not even stop the waiting —
                // a call that never suspends holds the coroutine until it returns (A7, pm sweep 2026-11-06). The load
                // runs on its own thread: we stop waiting at the deadline, and an engine that finishes after it is closed
                // then, not left loaded beside the next rung's. Until then nothing here may close it under the load.
                var abandonedLoad = false
                var initialized = false
                try {
                    val loaded = BlockingDeadline.run(probeTimeoutMs, block = { try { engine.initialize() } finally { watch.cancel() } },
                        abandoned = { r -> runCatching { engine.close() }; log("[litert] ${label(kind)} load finished after its deadline or a stop (${if (r.isSuccess) "loaded" else "failed"}) — closed") })
                    if (loaded == null) { abandonedLoad = true; throw LocalInferenceError.LoadFailed("the ${label(kind)} backend did not answer within ${probeTimeoutMs / 1000}s") }
                    initialized = true
                    val ok = withTimeoutOrNull(probeTimeoutMs) { probeDecodes(engine) }
                    if (ok != true) throw LocalInferenceError.LoadFailed(
                        if (ok == null) "the ${label(kind)} backend did not answer within ${probeTimeoutMs / 1000}s" else "the ${label(kind)} backend loaded but produced nothing",
                    )
                    memo.remember(key, kind)
                    backendLabel = label(kind)
                    log("[litert] engine on ${label(kind)}${if (vision) " with vision" else ""}, window $window (ready in ${System.currentTimeMillis() - started}ms)")
                    return@withContext Handle(engine, log, label(kind), speculative, reuseConversations, sees = vision,
                        contextTokens = window.takeIf { it != contextTokens })
                } catch (c: CancellationException) {
                    // A stop, not a refusal: no next rung, and never the NPU-unusable marker below. An engine still
                    // initializing is BlockingDeadline's to close when its load ends; closing it here would be under the load.
                    if (initialized) runCatching { engine.close() }
                    log("[litert] ${label(kind)} load stopped after ${System.currentTimeMillis() - started}ms")
                    throw c
                } catch (t: Throwable) {
                    lastFailure = t; if (!abandonedLoad) runCatching { engine.close() }
                    // The reason matters more than the failure: this line is how the next device's story gets read.
                    log("[litert] ${label(kind)}${if (vision) " with vision" else ""} refused after ${System.currentTimeMillis() - started}ms: ${t.message}")
                } finally {
                    watch.cancel(); loadEvents.ended(System.currentTimeMillis())
                }
            }
        }
        val reason = lastFailure?.message ?: "no backend could load the model"
        if (requireNpu) {
            onNpuUnusable(modelPath, reason)
            throw LocalInferenceError.LoadFailed("the Tensor bundle could not start on this phone's NPU ($reason). The general bundle runs instead from the next message — download gemma 4 e2b from where if it is not on this phone.")
        }
        throw LocalInferenceError.LoadFailed(reason)
    }

    /**
     * The honest half of the probe: one token, generated for real, on a throwaway conversation.
     *
     * Cheap next to the load that precedes it, and the only thing that distinguishes a backend that works from one
     * that merely compiled. Returns false rather than throwing when the runtime yields nothing at all, so a silent
     * backend is rejected on the same footing as a loud one.
     */
    private fun hasDrafter(modelPath: String): Boolean = runCatching {
        ModelInfo.from(modelPath).use { (it as? LlmCapability)?.hasSpeculativeDecodingSupport() == true }
    }.onFailure { log("[litert] capabilities unreadable, speculative decoding stays off: ${it.message}") }.getOrDefault(false)
        .also { log("[litert] bundle drafter: $it") }

    private fun probeDecodes(engine: Engine): Boolean {
        val conversation = engine.createConversation(ConversationConfig(maxOutputToken = 1, thinkingConfig = ThinkingConfig(false)))
        try {
            val reply = conversation.sendMessage("hi")
            return reply.contents.contents.filterIsInstance<Content.Text>().isNotEmpty()
        } finally { runCatching { conversation.close() } }
    }

    /**
     * The vision encoder runs on the GPU beside a GPU text model, and on the CPU beside a CPU one — EXCEPT on a PowerVR
     * (Pixel 10), where the text model is on the CPU (BackendLadder) but the encoder stays on the GPU: Google AI Edge
     * Gallery's own Gemma 4 E2B config does the same ("accelerators": "gpu,cpu" with the GPU removed on a Pixel 10,
     * "visionAccelerator": "gpu", allowlist 1_0_20), and teemoon's CPU encoder cost ~5 s of a 9.4 s image turn there
     * (2026-10-26). To be measured on the phone before 1.1.0's head-to-head.
     */
    private fun visionBackendFor(kind: BackendKind): Backend =
        // an NPU bundle's encoder runs on the GPU beside it (the S25 Ultra, 2026-11-12)
        if (kind == BackendKind.GPU || kind == BackendKind.NPU || (kind == BackendKind.CPU && BackendLadder.isPowerVr(socModel))) Backend.GPU() else backendFor(BackendKind.CPU)

    private fun backendFor(kind: BackendKind): Backend = when (kind) {
        BackendKind.GOOGLE_TENSOR -> Backend.GOOGLE_TENSOR()
        // Non-null by construction: the rung is only in the ladder when a dispatch directory exists.
        BackendKind.NPU -> Backend.NPU(nativeLibraryDir!!)
        BackendKind.GPU -> Backend.GPU()
        // The count is BackendLadder.cpuThreads (4 below). Four threads, MEASURED (Pixel 10 Pro, Gemma 4 E2B, raw runtime, 2026-09-16): the runtime's default gave
        // 14 tok/s decode, 4 threads 17, 6 threads 16, 8 threads 14. The big cluster is the useful part; spreading
        // decode across the small cores costs more in synchronisation than it gains. One device's number, so a
        // conservative one — four is also the common size of a big cluster on other SoCs.
        BackendKind.CPU -> (cpuThreadsOverride ?: BackendLadder.cpuThreads(socModel)).let { n ->
            if (n == null || n == 0) Backend.CPU() else Backend.CPU(n, n) }
    }

    private companion object {
        /** How often the load watches its cache directory (iOS: 250 ms). */
        const val CACHE_WATCH_MS = 250L

        /** Lowercase, as every string this app shows is; it rides the developer-mode request headers as `backend`. */
        fun label(kind: BackendKind): String = when (kind) {
            BackendKind.GOOGLE_TENSOR -> "google tensor"
            BackendKind.NPU -> "npu"
            BackendKind.GPU -> "gpu"
            BackendKind.CPU -> "cpu"
        }
    }

    private class Handle(
        private val engine: Engine, private val log: (String) -> Unit,
        override val backend: String, override val speculative: Boolean,
        private val reuse: Boolean,
        override val sees: Boolean = false,
        override val contextTokens: Int? = null,
    ) : LocalEngineHandle {
        private var conversation: Conversation? = null
        /** What [conversation] holds, when it may serve the next turn; null = rebuild. See ConversationReuse. */
        private var kept: KeptConversation? = null
        /** The last turn's numbers, read before a conversation that is not kept is closed (the developer card's stats). */
        private var lastBenchmark: LocalBenchmark? = null
        override val holdsConversation: Boolean get() = conversation != null
        override var lastConversation: String? = null; private set
        /**
         * This turn's tools, by name. A kept Conversation's ToolManager holds the shims it was built with, so the shims
         * look the tool up here at call time — or a reused conversation would run LAST turn's bridge callbacks (its
         * chip, its sources, its executed list) for this turn's search. Only while a turn runs, and only from its own
         * conversation (G7): a stopped decode that calls late runs nothing, never the next turn's search.
         */
        private val liveTools = LiteRTLiveTools()
        private val toolRuns = AtomicInteger(0)

        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {
            toolRuns.set(0)
            // A request with any image, the question's or an earlier one, starts its own conversation and is not kept: the
            // reuse key is the text transcript, which an image is not part of (IMAGES.md §4b @ 00f72739).
            val reused = reuse && ConversationReuse.reusable(spec.requestImages) && conversation != null && ConversationReuse.canReuse(kept, spec)
            kept = null
            lastConversation = if (reused) "reused (${spec.history.size} turns)" else "rebuilt"
            val conv = if (reused) {
                log("[litert] conversation: reused (${spec.history.size} turns already in the cache)")
                conversation!!
            } else {
                conversation?.let { runCatching { it.close() } }
                val id = liveTools.newConversation()
                val config = ConversationConfig(
                    systemInstruction = spec.systemMessage?.let { Contents.of(it) },
                    // earlier turns go in with their images, the images first as in the question (§4b @ 00f72739)
                    initialMessages = spec.history.mapIndexed { i, (role, text) ->
                        val images = spec.historyImages.getOrNull(i).orEmpty().takeIf { sees && role == LocalRole.USER }.orEmpty()
                        if (images.isEmpty()) liteRtMessage(role, text)
                        else Message.user(Contents.of(images.map { Content.ImageBytes(it) } + listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { Content.Text(it) })))
                    },
                    tools = spec.tools.map { tool(OpenApiShim(it.name, it.descriptionJson, { n, args -> liveTools.run(id, n, args) }, toolRuns)) },
                    maxOutputToken = spec.maxOutputTokens,
                    thinkingConfig = ThinkingConfig(spec.enableThinking),
                )
                // Constrained decoding, per conversation — OFF (6222a57). On iOS's runtime it neutralised the drafter, and
                // every on-device turn carries the search tool, so "only when tools are attached" would be every turn. Tool
                // calls still parse with it off. Re-measure before turning it back on. The flag is global but read at
                // createConversation, which is why it is set right before each one.
                ExperimentalFlags.enableConversationConstrainedDecoding = false
                log("[litert] conversation: toolsAttached=${spec.tools.isNotEmpty()} constrained=false")
                engine.createConversation(config).also { conversation = it }
            }
            val raw = StringBuilder()
            lastBenchmark = null
            liveTools.begin(spec.tools)
            withContext(Dispatchers.IO) {
                try {
                    // IMAGES.md §4b: the images first, in the order attached, then the question, in one user message.
                    val ask = spec.userImages.takeIf { sees && it.isNotEmpty() }?.let { images -> Message.user(Contents.of(images.map { Content.ImageBytes(it) } + Content.Text(spec.userText))) }
                        ?: Message.user(spec.userText)
                    conv.sendMessageAsync(ask).collect { message ->
                        val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (text.isNotEmpty()) { raw.append(text); onPiece(text) }
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    // The user stopped the turn. Tell the runtime, or the accelerator keeps decoding a reply nobody
                    // will see until the next turn's conversation replaces this one.
                    runCatching { conv.cancelProcess() }
                    log("[litert] stream cancelled by the caller"); releaseUnkept(); throw t
                } catch (t: Throwable) {
                    // The binding's own failures arrive without a stack in the error card; the log keeps it.
                    log("[litert] stream failed: " + t.stackTraceToString().take(2000)); releaseUnkept(); throw t
                } finally {
                    // Before the gate opens for the next turn: a decode still winding down calls nothing from here on.
                    liveTools.end()
                }
            }
            // Reached only on a completed stream: a cancelled or failed one threw above with [kept] still null.
            if (reuse && ConversationReuse.reusable(spec.requestImages)) kept = ConversationReuse.afterTurn(spec, raw.toString(), toolsRan = toolRuns.get(), completed = true)
            if (kept == null) releaseUnkept()
        }

        /**
         * A turn that is not kept frees its conversation when it ends, as iOS's does (each turn takes the kept entry at
         * its start, so a stopped or failed turn's partial KV goes with it). It used to stay open until the next
         * rebuild or the handle's close. Its numbers are read first, for the developer card.
         */
        private fun releaseUnkept() {
            lastBenchmark = readBenchmark()
            conversation?.let { runCatching { it.close() } }; conversation = null
        }

        private fun readBenchmark(): LocalBenchmark? = runCatching {
            val b = conversation?.getBenchmarkInfo() ?: return null
            LocalBenchmark(b.timeToFirstTokenInSecond, b.lastPrefillTokenCount, b.lastDecodeTokenCount, b.lastPrefillTokensPerSecond, b.lastDecodeTokensPerSecond)
        }.getOrNull()

        override fun benchmark(): LocalBenchmark? = lastBenchmark ?: readBenchmark()

        /** A deleted chat's conversation goes: the KV with its tokens and the plaintext transcript copy (iOS fc09a91). */
        override fun dropKept() { kept = null; conversation?.let { runCatching { it.close() } }; conversation = null }

        override fun close() { runCatching { conversation?.close() }; conversation = null; runCatching { engine.close() } }
    }

    /**
     * The bridge LiteRT actually calls. teemoon's flattened schema in the
     * `{"type":"function","function":{…}}` envelope; the model's arguments
     * arrive as a raw JSON string and go straight to teemoon's coercion —
     * never a typed decode that could fail before repair can run.
     */
    private class OpenApiShim(
        private val name: String, private val descriptionJson: String,
        /** Runs the CURRENT turn's tool of this name, if this shim's conversation is the running one — see Handle.liveTools. */
        private val call: suspend (name: String, argumentsJson: String) -> String, private val runs: AtomicInteger,
    ) : OpenApiTool {
        // The binding wants the flat function object; see LiteRTToolBridge.flatDescription for why.
        override fun getToolDescriptionJsonString(): String = LiteRTToolBridge.flatDescription(descriptionJson)
        // Synchronous by LiteRT's contract, called from its loop; the tool does network, so block an IO thread, not the caller's.
        override fun execute(paramsJsonString: String): String {
            runs.incrementAndGet()
            return runBlocking(Dispatchers.IO) { call(name, paramsJsonString) }
        }
    }

}

/** Message's constructor is internal; the companion builders are the API. The assistant is MODEL. */
private fun liteRtMessage(role: LocalRole, text: String): Message = when (role) {
    LocalRole.SYSTEM -> Message.system(text); LocalRole.USER -> Message.user(text); LocalRole.MODEL -> Message.model(text); LocalRole.TOOL -> Message.tool(Contents.of(text))
}
