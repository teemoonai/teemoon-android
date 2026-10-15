// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 (EngineCache.engine's EngineConfig/initialize, enableExperimentalFeatures,
//        the createConversation calls, the sendMessageStream text extraction, getBenchmarkInfo) — REBUILD over
//        com.google.ai.edge.litertlm:litertlm-android 0.18.0. Everything runtime-neutral is :inference's LiteRTTransport.
// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.4 (constrained decoding off, set per conversation)
package ai.teemoon.ondevice

import ai.teemoon.inference.BridgedTool
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
import kotlinx.coroutines.Dispatchers
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
    /**
     * The NPU-only bundle at [modelPath] could not start on this phone: told once per failure, before the throw. The
     * app records it against the file so the storage stops preferring it (LocalModelStorage.markNpuUnusable) — the
     * general file runs instead, or its download is offered. Without this a Tensor phone whose NPU refuses (a chip
     * the bundle was never run on) keeps failing every turn with nowhere to go.
     */
    private val onNpuUnusable: (modelPath: String, reason: String) -> Unit = { _, _ -> },
) : LocalEngineFactory {
    /** What the last successful open ran on. "cpu" until something better is PROVEN — never a hopeful claim. */
    @Volatile override var backendLabel: String = label(BackendKind.CPU); private set

    override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = withContext(Dispatchers.IO) {
        ExperimentalFlags.enableBenchmark = true
        // Constrained decoding is NOT set here: it is per conversation, see Handle.stream.
        // An NPU bundle has one rung by construction. Walking the rest would spend a model load per rung to learn
        // what the file already says, and report "Input tensor not found" instead of "this device has no NPU".
        val ladder = if (requireNpu) listOf(BackendKind.NPU) else BackendLadder.order(socModel, hardware)
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
            val engine = Engine(EngineConfig(modelPath = modelPath, backend = backendFor(kind), maxNumTokens = contextTokens, cacheDir = cacheDir))
            val started = System.currentTimeMillis()
            try {
                // withTimeoutOrNull cannot interrupt a blocking JNI call — it lets US stop waiting on one that has
                // wedged, and the abandoned native thread dies with the process. Better than an open that never returns.
                val ok = withTimeoutOrNull(probeTimeoutMs) {
                    engine.initialize()
                    probeDecodes(engine)
                }
                if (ok != true) throw LocalInferenceError.LoadFailed(
                    if (ok == null) "the ${label(kind)} backend did not answer within ${probeTimeoutMs / 1000}s" else "the ${label(kind)} backend loaded but produced nothing",
                )
                memo.remember(key, kind)
                backendLabel = label(kind)
                log("[litert] engine on ${label(kind)} (ready in ${System.currentTimeMillis() - started}ms)")
                return@withContext Handle(engine, log, label(kind), speculative)
            } catch (t: Throwable) {
                lastFailure = t; runCatching { engine.close() }
                // The reason matters more than the failure: this line is how the next device's story gets read.
                log("[litert] ${label(kind)} refused after ${System.currentTimeMillis() - started}ms: ${t.message}")
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

    private fun backendFor(kind: BackendKind): Backend = when (kind) {
        BackendKind.GOOGLE_TENSOR -> Backend.GOOGLE_TENSOR()
        // Non-null by construction: the rung is only in the ladder when a dispatch directory exists.
        BackendKind.NPU -> Backend.NPU(nativeLibraryDir!!)
        BackendKind.GPU -> Backend.GPU()
        // Four threads, MEASURED (Pixel 10 Pro, Gemma 4 E2B, raw runtime, 2026-09-16): the runtime's default gave
        // 14 tok/s decode, 4 threads 17, 6 threads 16, 8 threads 14. The big cluster is the useful part; spreading
        // decode across the small cores costs more in synchronisation than it gains. One device's number, so a
        // conservative one — four is also the common size of a big cluster on other SoCs.
        BackendKind.CPU -> Backend.CPU(CPU_THREADS, CPU_THREADS)
    }

    private companion object {
        const val CPU_THREADS = 4

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
    ) : LocalEngineHandle {
        private var conversation: Conversation? = null

        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {
            conversation?.let { runCatching { it.close() } }
            val config = ConversationConfig(
                systemInstruction = spec.systemMessage?.let { Contents.of(it) },
                initialMessages = spec.history.map { (role, text) -> liteRtMessage(role, text) },
                tools = spec.tools.map { tool(OpenApiShim(it)) },
                maxOutputToken = spec.maxOutputTokens,
                thinkingConfig = ThinkingConfig(spec.enableThinking),
            )
            // Constrained decoding, per conversation — OFF (6222a57). On iOS's runtime it neutralised the drafter, and
            // every on-device turn carries the search tool, so "only when tools are attached" would be every turn. Tool
            // calls still parse with it off. Re-measure before turning it back on. The flag is global but read at
            // createConversation, which is why it is set right before each one.
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            log("[litert] conversation: toolsAttached=${spec.tools.isNotEmpty()} constrained=false")
            val conv = engine.createConversation(config)
            conversation = conv
            withContext(Dispatchers.IO) {
                try {
                    conv.sendMessageAsync(Message.user(spec.userText)).collect { message ->
                        val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (text.isNotEmpty()) onPiece(text)
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    // The user stopped the turn. Tell the runtime, or the accelerator keeps decoding a reply nobody
                    // will see until the next turn's conversation replaces this one.
                    runCatching { conv.cancelProcess() }
                    log("[litert] stream cancelled by the caller"); throw t
                } catch (t: Throwable) {
                    // The binding's own failures arrive without a stack in the error card; the log keeps it.
                    log("[litert] stream failed: " + t.stackTraceToString().take(2000)); throw t
                }
            }
        }

        override fun benchmark(): LocalBenchmark? = runCatching {
            val b = conversation?.getBenchmarkInfo() ?: return null
            LocalBenchmark(b.timeToFirstTokenInSecond, b.lastPrefillTokenCount, b.lastDecodeTokenCount, b.lastPrefillTokensPerSecond, b.lastDecodeTokensPerSecond)
        }.getOrNull()

        override fun close() { runCatching { conversation?.close() }; conversation = null; runCatching { engine.close() } }
    }

    /**
     * The bridge LiteRT actually calls. teemoon's flattened schema in the
     * `{"type":"function","function":{…}}` envelope; the model's arguments
     * arrive as a raw JSON string and go straight to teemoon's coercion —
     * never a typed decode that could fail before repair can run.
     */
    private class OpenApiShim(private val bridged: BridgedTool) : OpenApiTool {
        // The binding wants the flat function object; see LiteRTToolBridge.flatDescription for why.
        override fun getToolDescriptionJsonString(): String = LiteRTToolBridge.flatDescription(bridged.descriptionJson)
        // Synchronous by LiteRT's contract, called from its loop; the tool does network, so block an IO thread, not the caller's.
        override fun execute(paramsJsonString: String): String = runBlocking(Dispatchers.IO) { bridged.execute(paramsJsonString) }
    }

}

/** Message's constructor is internal; the companion builders are the API. The assistant is MODEL. */
private fun liteRtMessage(role: LocalRole, text: String): Message = when (role) {
    LocalRole.SYSTEM -> Message.system(text); LocalRole.USER -> Message.user(text); LocalRole.MODEL -> Message.model(text); LocalRole.TOOL -> Message.tool(Contents.of(text))
}
