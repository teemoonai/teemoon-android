// ports: teemoon/Inference/LocalRuntime.swift @ v1.0.4 (LocalInferenceError, LocalGenerationGate, LocalEngineResidency;
//        LocalMemory lives in :providers, LocalMemoryPressure is the app's onTrimMemory hook),
//        teemoon/Inference/LocalLanguageModel.swift @ v1.0.1 (LocalModelRef, the defaults)
//
// The parts of on-device inference that are not specific to one runtime: the
// error type, the one-generation-at-a-time lock, the residency mirror, and the
// seam a runtime plugs into. The memory gate and the serialization lock are
// properties of the PHONE, not of whichever library loads the weights.
package ai.teemoon.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** GB to one decimal, both platforms (wherefilter.json phoneImages.refusals.now): MB / 1024. */
internal fun gb(mb: Int) = String.format(java.util.Locale.US, "%.1f", mb / 1024.0)

sealed class LocalInferenceError(message: String) : Exception(message) {
    class ModelNotDownloaded(val id: String) : LocalInferenceError("$id isn't downloaded yet. download it before starting a chat.")
    class InsufficientMemory(val needMB: Int, val availableMB: Int) : LocalInferenceError("not enough free memory to load this model (needs about $needMB mb, $availableMB mb free). close some apps, or pick a smaller model.")
    class LoadFailed(val detail: String) : LocalInferenceError("the model couldn't be loaded: $detail")
    /**
     * IMAGES.md §4b (the maintainer 2026-11-12, run time): an image turn short of memory right now, on a phone that could
     * have the room ("now"; never on a phone that can't — that is refused before the turn, permanently). [needMB] is what
     * the gate asked for, [availableMB] what it read. DRAFT (wherefilter.json phoneImages.refusals.now).
     */
    class ImagesShortOfMemoryNow(val modelName: String, val needMB: Int, val availableMB: Int) : LocalInferenceError(
        "$modelName needs about ${gb(needMB)} GB free for images; this phone has ${gb(availableMB)} GB free right now. close some apps and try again.")
    /**
     * The fixed part of the prompt — system message, tool descriptions, the message itself — is bigger than the
     * model's context window, so no amount of history trimming can make the turn fit. Raised BEFORE the runtime is
     * asked, with the cause named: the runtime's own "Input token ids are too long" says nothing about why.
     */
    class PromptTooLarge(val promptTokens: Int, val windowTokens: Int, val toolTokens: Int) : LocalInferenceError(
        "this message needs about $promptTokens tokens and this model's window is $windowTokens." +
            (if (toolTokens > windowTokens / 4) " web search adds about $toolTokens of those — turn it off for this model, or pick a place with a larger window." else " try a shorter message."))
}


/**
 * Lets exactly one on-device generation run at a time. A retry, a second send
 * before the first finishes, or a tool round overlapping a cancelled
 * generation all put two generations in flight; queueing costs a little
 * latency, not queueing means two multi-gigabyte engines resident at once.
 * A coroutine cancelled while queued here unwinds (Mutex.lock is cancellable)
 * — iOS learned that a non-cancellable wait wedged the composer for good.
 */
class LocalGenerationGate {
    private val mutex = Mutex()
    suspend fun <T> withTurn(block: suspend () -> T): T = mutex.withLock { block() }
    val isBusy: Boolean get() = mutex.isLocked
    companion object { val shared = LocalGenerationGate() }
}

/**
 * Which downloaded model currently has an engine built — so the picker can say
 * whether a phone row answers now or pays a load first. A MIRROR of the engine
 * cache, pushed after every mutation it makes; a SET even though the cache
 * keeps one engine, so a later two-engine policy cannot silently lie here.
 */
class LocalEngineResidency {
    data class Load(val file: String, val tookMs: Long)
    private val _resident = MutableStateFlow<Set<String>>(emptySet())
    val resident: StateFlow<Set<String>> get() = _resident
    @Volatile var lastLoad: Load? = null; private set
    fun isResident(file: String) = file in _resident.value
    fun update(files: Set<String>) { _resident.value = files }
    fun recordLoad(file: String, tookMs: Long) { lastLoad = Load(file, tookMs) }
    companion object { val shared = LocalEngineResidency() }
}

/**
 * The on-device defaults, AS iOS ACTUALLY RUNS LiteRT — not as its `LocalLanguageModel` declares them.
 *
 * iOS declares `defaultEnableThinking = true` and `defaultMaxTokens = 1024` with a measured rationale (thinking
 * made a 0.6B MLX model call tools 3/3 instead of 0/3). Neither value reaches LiteRT: `LiteRTTransport.swift`
 * builds its `ConversationConfig` without a `ThinkingConfig` (the runtime's default is thinking OFF) and passes no
 * `maxOutputTokens`, bounding the answer itself at `answerReserveTokens` = 4800 estimated tokens. The first port
 * read the declarations, so Android thought and iOS did not — Gemma 4 thought once before a search and once before
 * the answer, the parser folded the first block and the second landed in the bubble as text (Pixel 10 Pro, Play
 * 1.0.4 (3910), 2026-10-12). Gemma 4 E2B calls tools 12/12 on iOS with thinking off (LocalModelCatalog.swift), so
 * off loses nothing measured. The flags stay so a test can pin them and a later iOS change has one place to land.
 *
 * No temperature: nothing passes a SamplerConfig, so LiteRT runs its own sampler, as on iOS. The 0.7 that sat here
 * never reached the engine and is gone (the maintainer 2026-10-28, the Gallery-gap accepts). A greedy run builds its own
 * SamplerConfig (rawGreedySpeculativeCompare); a spec field carried the old decodeGreedy's 0 nowhere.
 */
object LocalDefaults {
    /** iOS `answerReserveTokens`: the runtime stops at the KV ceiling first on a small window. */
    const val MAX_TOKENS = 4800
    const val ENABLE_THINKING = false
    const val MAX_TOOL_ROUNDS = 2
}

// MARK: - The runtime seam

/** A tool as LiteRT's OpenAPI-style interface wants it: a description document (teemoon's envelope) and a JSON-in/string-out executor. */
class BridgedTool(val name: String, val descriptionJson: String, val execute: suspend (argumentsJson: String) -> String)

/** What a turn asks the runtime for. History is SEEDED here, never replayed through sendMessage (see LiteRTTransport). */
data class LocalConversationSpec(
    val systemMessage: String?,
    val history: List<Pair<LocalRole, String>>,
    val tools: List<BridgedTool>,
    val userText: String,
    val maxOutputTokens: Int,
    val enableThinking: Boolean,
    /**
     * IMAGES.md §4b: the turn's images, in the order attached, up to [MAX_IMAGES_PER_TURN] — teemoon's own JPEGs,
     * re-encoded and at most 768 px, never bytes a user or an export supplied. Only an engine that
     * [LocalEngineHandle.sees] is given any.
     */
    val userImages: List<ByteArray> = emptyList(),
    /**
     * IMAGES.md §4b @ 00f72739: each [history] turn's images, same order and length as [history] (empty for a turn with
     * none) — the chat's earlier images, seeded with their turns so the model still has them (it kept text only, and a
     * question about two earlier photos found neither: the maintainer's Pixel, 2026-11-12).
     */
    val historyImages: List<List<ByteArray>> = emptyList(),
) {
    /** Every image this request carries: the question's and the earlier ones (the memory check and reuse count them all). */
    val requestImages: Int get() = userImages.size + historyImages.sumOf { it.size }

    companion object {
        /**
         * Up to 10 images in one phone message (the maintainer 2026-11-12: pass the memory check → 10, fail → images off).
         * The vision engine is built for this many (maxNumImages), so any count up to it runs on one engine. Pixel 10
         * Pro, 2026-10-30 (854cae21): 2–10 images all named right, peak PSS 3.29–3.47 GB, flat from 4 to 10.
         */
        const val MAX_IMAGES_PER_TURN = 10
    }
}

/** OpenAI wire roles → LiteRT's Gemini vocabulary: the assistant is MODEL, not assistant. */
enum class LocalRole { SYSTEM, USER, MODEL, TOOL }

data class LocalBenchmark(val timeToFirstTokenSeconds: Double, val prefillTokens: Int, val decodeTokens: Int, val prefillTokensPerSecond: Double, val decodeTokensPerSecond: Double)

/** One built engine. */
interface LocalEngineHandle {
    /** Streams the reply's text pieces for one turn; tool calls run inside the runtime's own loop through [BridgedTool.execute]. */
    suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit)
    fun benchmark(): LocalBenchmark?
    fun close()
    /** Where the engine runs ("npu", "gpu", "cpu") and whether its drafter is on. */
    val backend: String? get() = null
    val speculative: Boolean get() = false
    /** Whether a runtime conversation (its KV cache) is still open between turns: only a kept one may be (iOS parity). */
    val holdsConversation: Boolean get() = false
    /** Drops a kept conversation (its KV and its transcript copy); the engine and its weights stay. See LocalEngineCache.forgetKeptConversations. */
    fun dropKept() {}
    /** How the last turn started, for the developer card: "reused (N turns)" or "rebuilt" (iOS fcfdfb0); null before any turn. */
    val lastConversation: String? get() = null
    /** Whether this engine was built with its vision backend and can take [LocalConversationSpec.userImages]. */
    val sees: Boolean get() = false
    /** The KV window this engine was built with, when the factory chose a smaller one than asked (the CPU rung's); null = as asked. */
    val contextTokens: Int? get() = null
}

/** Builds engines. The LiteRT-LM binding lives in :ondevice; tests use a scripted one. */
interface LocalEngineFactory {
    /**
     * Throws LocalInferenceError.LoadFailed when the runtime refuses the file.
     *
     * @param requireNpu this bundle is compiled for a vendor NPU and carries no CPU or GPU graph, so the usual
     *   ladder is wrong: every other rung would fail, slowly, with an error about tensors rather than accelerators.
     *   The engine tries the NPU and nothing else, and says so when it cannot.
     */
    suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean = false): LocalEngineHandle
    /** IMAGES.md §4b: [vision] builds the engine with the vision executor, for a turn with an image; it then serves text too. */
    suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean, vision: Boolean): LocalEngineHandle =
        open(modelPath, contextTokens, requireNpu)
    /** The window [open] would build for [requested] on the rung it would try first — what the search payload is sized to. */
    fun contextTokensFor(modelPath: String, requested: Int, requireNpu: Boolean): Int = requested
    val backendLabel: String
}
