// android-only DIAGNOSTIC: no Swift counterpart, and not a gate. It answers one question the app cannot — is the
// decode rate the RUNTIME's, or ours? Measured 7 tok/s on a Pixel 10 Pro's GPU against Google's published 52 on a
// Snapdragon, while our GPU prefill was 4.7x our CPU prefill. A working GPU decode is not slower than the same
// device's CPU decode, so the shape said "something in our loop", not "this hardware".
package ai.teemoon.app

import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * Decodes with NOTHING downstream — no elision, no view model, no Compose — and reports the runtime's own benchmark.
 *
 * Run it against the app's real bundle:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.OnDeviceDecodeBenchTest
 * Skips itself where no model is installed, so it is safe in any suite.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class OnDeviceDecodeBenchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    /** The directory the APP hands Backend.NPU (NpuDispatch): the Qualcomm dispatcher is reached only through it. */
    private val npuDir by lazy { NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir, ::log) }
    private fun log(line: String) = Log.w("teemoon", "[bench] $line")

    private fun modelPath(): String? {
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        return LocalModelCatalog.all.firstOrNull(storage::isInstalled)?.let { storage.file(it).path }
    }

    /**
     * One run. [sink] is what each piece is handed to: the point of the test is comparing a no-op sink against one
     * that does what the app does, so a slow consumer shows up as a slow "decode rate" exactly as it does in the app.
     */
    private fun run(label: String, path: String, constrained: Boolean, speculative: Boolean?, tokens: Int, context: Int = 4096, temperature: Float = 0.8f, sink: suspend (String) -> Unit) = runBlocking {
        ExperimentalFlags.enableConversationConstrainedDecoding = constrained
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        val factory = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log, nativeLibraryDir = npuDir,
            socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty())
        val engine = factory.open(path, context)
        try {
            val spec = LocalConversationSpec(systemMessage = null, history = emptyList(), tools = emptyList(),
                userText = "Write a short paragraph about the planet Jupiter.", maxOutputTokens = tokens,
                enableThinking = false, temperature = temperature)
            val wall = System.currentTimeMillis()
            engine.stream(spec, sink)
            val elapsed = System.currentTimeMillis() - wall
            val b = engine.benchmark()
            log("$label [backend=${factory.backendLabel} ctx=$context temp=$temperature constrained=$constrained speculative=$speculative] " +
                "ttft=${b?.timeToFirstTokenSeconds}s prefill=${b?.prefillTokens}tok (${b?.prefillTokensPerSecond?.toInt()}tok/s) " +
                "decode=${b?.decodeTokens}tok (${b?.decodeTokensPerSecond?.toInt()}tok/s) wall=${elapsed}ms")
        } finally { engine.close() }
    }

    /** The baseline the app's number should be compared against: the runtime alone, consumer doing nothing. */
    @Test fun decodeWithNothingDownstream() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        run("bare", path!!, constrained = true, speculative = null, tokens = 128) { }
    }

    /** Constrained decoding is on globally in the app. If it costs per token, it shows here and nowhere else. */
    @Test fun decodeWithConstrainedDecodingOff() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        run("no-constrained", path!!, constrained = false, speculative = null, tokens = 128) { }
    }

    /** ExperimentalFlags exposes speculative decoding and the app never sets it; the native library carries an MTP drafter. */
    @Test fun decodeWithSpeculativeDecodingOn() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        run("speculative", path!!, constrained = true, speculative = true, tokens = 128) { }
    }

    /**
     * The KV cache is sized by the context we ask for, and the app asks for 8192 by default. If attention is paid over
     * the ALLOCATION rather than the tokens in use, every turn on this app is four times slower than a benchmark run
     * at 2048 — and that would be ours to fix, not Google's.
     */
    @Test fun decodeAcrossContextSizes() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        for (ctx in listOf(1024, 2048, 4096, 8192)) run("ctx-$ctx", path!!, constrained = true, speculative = null, tokens = 96, context = ctx) { }
    }

    /**
     * RAW runtime, our wrapper entirely out of the way, varying only the sampler.
     *
     * `ConversationConfig` takes a `samplerConfig` and we never pass one, so the runtime uses its default. Sampling is
     * the per-token cost that fell back to the CPU when `libLiteRtTopKOpenClSampler.so` could not be loaded, and a
     * default that considers the whole 262k vocabulary on the CPU every token would explain the entire gap.
     */
    @Test fun rawDecodeWithAndWithoutAnExplicitSampler() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        ExperimentalFlags.enableBenchmark = true
        for ((label, sampler) in listOf<Pair<String, SamplerConfig?>>(
            "raw-default-sampler" to null,
            "raw-topk40" to SamplerConfig(40, 0.95, 0.8, 0),
            "raw-topk1" to SamplerConfig(1, 1.0, 1.0, 0),
        )) {
            val engine = Engine(EngineConfig(
                modelPath = path!!, backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
            try {
                engine.initialize()
                val conv = engine.createConversation(ConversationConfig(
                    samplerConfig = sampler, maxOutputToken = 96,
                    thinkingConfig = ThinkingConfig(false)))
                try {
                    val wall = System.currentTimeMillis()
                    conv.sendMessage("Write a short paragraph about the planet Jupiter.")
                    val b = conv.getBenchmarkInfo()
                    log("$label decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) " +
                        "prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms")
                } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
    }

    /**
     * RAW runtime, CPU backend, thread count swept. `Backend.CPU()` leaves the count to the runtime's default and the
     * app never sets it; Tensor G5 has eight cores. The CPU path is the fastest DECODE on this phone (13 tok/s against
     * the GPU's 7), so if it scales with threads it is the cheapest win available.
     */
    @Test fun rawCpuThreadSweep() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        ExperimentalFlags.enableBenchmark = true
        for (threads in listOf<Int?>(null, 4, 6, 8)) {
            val backend = if (threads == null) Backend.CPU() else Backend.CPU(threads, threads)
            val engine = Engine(EngineConfig(
                modelPath = path!!, backend = backend, maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
            try {
                engine.initialize()
                val conv = engine.createConversation(ConversationConfig(
                    maxOutputToken = 96, thinkingConfig = ThinkingConfig(false)))
                try {
                    val wall = System.currentTimeMillis()
                    conv.sendMessage("Write a short paragraph about the planet Jupiter.")
                    val b = conv.getBenchmarkInfo()
                    log("cpu-threads=${threads ?: "default"} decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) " +
                        "prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms")
                } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
    }

    /**
     * RAW runtime, GPU and CPU, speculative decoding off and on: the 2x2 that decides whether the factory's GPU-only
     * default (LiteRtLmEngineFactory) is right on THIS chip. Adreno 840 measured 24 → 52 with it; the Tensor NPU 16 → 14.
     */
    @Test fun rawSpeculativeSweep() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        ExperimentalFlags.enableBenchmark = true
        for ((label, backend) in listOf("gpu" to Backend.GPU(), "cpu4" to Backend.CPU(4, 4))) {
            for (spec in listOf(false, true)) {
                ExperimentalFlags.enableSpeculativeDecoding = spec
                val engine = Engine(EngineConfig(
                    modelPath = path!!, backend = backend, maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
                try {
                    val t0 = System.currentTimeMillis(); engine.initialize(); val load = System.currentTimeMillis() - t0
                    val conv = engine.createConversation(ConversationConfig(
                        maxOutputToken = 128, thinkingConfig = ThinkingConfig(false)))
                    try {
                        val wall = System.currentTimeMillis()
                        val reply = conv.sendMessage("Write a short paragraph about the planet Jupiter.")
                        val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        val b = conv.getBenchmarkInfo()
                        log("speculative-sweep $label speculative=$spec load=${load}ms ttft=${b.timeToFirstTokenInSecond}s " +
                            "prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) " +
                            "decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms")
                        log("speculative-sweep $label speculative=$spec text: " + text.take(120).replace('\n', ' '))
                    } finally { runCatching { conv.close() } }
                } finally { runCatching { engine.close() } }
            }
        }
        ExperimentalFlags.enableSpeculativeDecoding = null
    }

    /**
     * The stop path with a drafter in the loop, which iOS asked about: speculative decoding ON, the stream cancelled
     * after its first pieces the way ChatViewModel's stop does (CancellationException → `cancelProcess()`), then a
     * fresh conversation on the same engine must answer in full. Forced on whatever GPU this is: the drafter code path
     * is the runtime's, not the chip's, so PowerVR is evidence for Adreno here.
     */
    @Test fun rawSpeculativeStopMidStreamThenAnswerAgain() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = true
        val engine = Engine(EngineConfig(
            modelPath = path!!, backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
        try {
            engine.initialize()
            val first = engine.createConversation(ConversationConfig(
                maxOutputToken = 400, thinkingConfig = ThinkingConfig(false)))
            var pieces = 0
            val t0 = System.currentTimeMillis()
            val stopped = runBlocking {
                val job = launch(Dispatchers.IO) {
                    try {
                        first.sendMessageAsync(Message.user("Write a 400 word essay about the planet Jupiter.")).collect { pieces++ }
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        runCatching { first.cancelProcess() }; throw t
                    }
                }
                while (pieces < 8 && System.currentTimeMillis() - t0 < 30_000) delay(50)
                job.cancel(); job.join(); pieces >= 8
            }
            val stopAt = System.currentTimeMillis() - t0
            runCatching { first.close() }
            log("speculative-stop stopped=$stopped after $pieces pieces at ${stopAt}ms")
            assumeTrue("the stream never produced 8 pieces in 30 s", stopped)
            val second = engine.createConversation(ConversationConfig(
                maxOutputToken = 64, thinkingConfig = ThinkingConfig(false)))
            try {
                val wall = System.currentTimeMillis()
                val reply = second.sendMessage("Name the largest moon of Jupiter in one sentence.")
                val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val b = second.getBenchmarkInfo()
                log("speculative-stop next turn: decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms text: " + text.take(120).replace('\n', ' '))
                org.junit.Assert.assertTrue("the turn after a stop came back empty", text.isNotBlank())
            } finally { runCatching { second.close() } }
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = null
            runCatching { engine.close() }
        }
    }

    /**
     * The second turn's cost, three ways, thinking ON (the app's default, so turn 1 leaves a thought block behind):
     *   fresh     — the app's shape today: a new Conversation per turn, `initialMessages` = the visible history
     *   kept      — one Conversation across both turns, runtime default
     *   kept+filt — the same, with `filterChannelContentFromKvCache` on: the thought block leaves the cache
     * What matters is turn 2's prefill token count and time to first token. Prefers the chip's NPU bundle.
     */
    @Test fun rawMultiTurnKvCache() {
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val gemma = LocalModelCatalog.all.first()
        val npuBundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        val npuPath = npuBundle?.let { storage.npuFile(gemma, it).path }?.takeIf { File(it).isFile }
        val general = storage.file(gemma).path.takeIf { File(it).isFile }
        val (path, backend) = when {
            npuPath != null -> npuPath to Backend.NPU(npuDir)
            general != null -> general to Backend.GPU()
            else -> { assumeTrue("no bundle staged", false); return }
        }
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = null
        val q1 = "Write a short paragraph about the planet Jupiter."; val q2 = "Now do the same for Saturn, in two sentences."
        fun text(m: Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        fun cfg() = ConversationConfig(maxOutputToken = 512, thinkingConfig = ThinkingConfig(true))
        for (variant in listOf("fresh", "kept", "kept+filter")) {
            ExperimentalFlags.filterChannelContentFromKvCache = if (variant == "kept+filter") true else null
            val engine = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
            try {
                engine.initialize()
                val c1 = engine.createConversation(cfg())
                val r1 = c1.sendMessage(q1); val b1 = c1.getBenchmarkInfo()
                val a1 = text(r1)
                log("kv $variant turn1: prefill=${b1.lastPrefillTokenCount}tok decode=${b1.lastDecodeTokenCount}tok (${b1.lastDecodeTokensPerSecond.toInt()}tok/s) visible=${a1.length}ch")
                val c2 = if (variant == "fresh") {
                    runCatching { c1.close() }
                    engine.createConversation(ConversationConfig(initialMessages = listOf(Message.user(q1), Message.model(a1)),
                        maxOutputToken = 512, thinkingConfig = ThinkingConfig(true)))
                } else c1
                try {
                    val wall = System.currentTimeMillis()
                    val r2 = c2.sendMessage(q2); val b2 = c2.getBenchmarkInfo()
                    log("kv $variant turn2: ttft=${b2.timeToFirstTokenInSecond}s prefill=${b2.lastPrefillTokenCount}tok (${b2.lastPrefillTokensPerSecond.toInt()}tok/s) " +
                        "decode=${b2.lastDecodeTokenCount}tok (${b2.lastDecodeTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms text: " + text(r2).take(100).replace('\n', ' '))
                } finally { runCatching { c2.close() } }
            } finally { runCatching { engine.close() } }
        }
        ExperimentalFlags.filterChannelContentFromKvCache = null
    }

    /**
     * The NPU refusing is reported once, against the file, before the throw — the marker LocalModelStorage keeps so
     * the next message runs the general file. Forced here: the NPU-only ladder on the GENERAL bundle, which no NPU can
     * load, and a native library directory with no dispatcher in it. The message names the way out.
     */
    @Test fun anNpuRefusalIsReportedAgainstTheFileAndNamesTheWayOut() = runBlocking {
        // The TENSOR file, not the general one: the NPU rung on a general bundle does not refuse, it loads and decodes on
        // something that is not the NPU (the disguised fallback of BackendLadder's note — measured again 2026-09-18).
        // A Tensor file with no dispatcher library to find is the real refusal: "No dispatch library found".
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val gemma = LocalModelCatalog.all.first()
        val bundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty()); assumeTrue("no NPU bundle for this chip", bundle != null)
        val path: String? = storage.npuFile(gemma, bundle!!).path.takeIf { File(it).isFile }; assumeTrue("Tensor bundle not staged", path != null)
        var reported: Pair<String, String>? = null
        val factory = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log, nativeLibraryDir = ctx.cacheDir.path /* no dispatcher here */,
            socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty(), probeTimeoutMs = 60_000,
            onNpuUnusable = { p, reason -> reported = p to reason })
        val failure = runCatching { factory.open(path!!, 4096, requireNpu = true) }.exceptionOrNull()
        log("npu-refusal: reported=$reported failure=${failure?.message?.take(200)}")
        org.junit.Assert.assertNotNull("the NPU-only open must fail without a dispatcher", failure)
        org.junit.Assert.assertEquals(path, reported?.first)
        org.junit.Assert.assertTrue("the message names the general bundle: ${failure?.message}", failure?.message?.contains("general bundle") == true)
    }

    /**
     * RAW runtime on the chip's NPU bundle: `Backend.NPU(nativeLibraryDir)` and a prompt that fits its 4096-token
     * context. This is the number the phone was bought for. Skips itself where no Tensor bundle is staged.
     */
    @Test fun rawNpuDecode() {
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val gemma = LocalModelCatalog.all.first()
        val bundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        assumeTrue("no NPU bundle for this chip", bundle != null)
        val path = storage.npuFile(gemma, bundle!!).path
        assumeTrue("Tensor bundle not staged", File(path).isFile)
        ExperimentalFlags.enableBenchmark = true
        for ((label, spec) in listOf("npu" to BenchSpec.SPECULATIVE, "npu+speculative" to true)) {
            ExperimentalFlags.enableSpeculativeDecoding = spec
            val engine = Engine(EngineConfig(
                modelPath = path, backend = Backend.NPU(npuDir),
                maxNumTokens = BenchSpec.MAX_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                val t0 = System.currentTimeMillis(); engine.initialize(); val load = System.currentTimeMillis() - t0
                val conv = engine.createConversation(ConversationConfig(
                    maxOutputToken = BenchSpec.SHORT_MAX_OUTPUT, thinkingConfig = ThinkingConfig(false)))
                try {
                    val wall = System.currentTimeMillis()
                    val reply = conv.sendMessage(BenchSpec.SHORT_PROMPT)
                    val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val b = conv.getBenchmarkInfo()
                    log("$label load=${load}ms ttft=${b.timeToFirstTokenInSecond}s prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) " +
                        "decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms")
                    log("$label text: " + text.take(160).replace('\n', ' '))
                } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
    }

    /**
     * PREFILL, which decode benchmarks cannot see: a ~2000-token prompt on each backend, 32 output tokens. Every chat
     * turn re-prefills a system prompt carrying the tools, so time-to-first-token is decided here, not by decode.
     */
    @Test fun rawPrefillCompare() {
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val gemma = LocalModelCatalog.all.first()
        val general = storage.file(gemma).path; assumeTrue("general bundle not staged", File(general).isFile)
        val npuBundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        val npuPath = npuBundle?.let { storage.npuFile(gemma, it).path }?.takeIf { File(it).isFile }
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = BenchSpec.SPECULATIVE
        val longPrompt = BenchSpec.LONG_PROMPT
        val runs = buildList {
            npuPath?.let { add(Triple("npu", it, Backend.NPU(npuDir))) }
            add(Triple("cpu4", general, Backend.CPU(4, 4)))
            add(Triple("gpu", general, Backend.GPU()))
        }
        for ((label, path, backend) in runs) {
            val engine = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = BenchSpec.MAX_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                val t0 = System.currentTimeMillis(); engine.initialize(); val load = System.currentTimeMillis() - t0
                val conv = engine.createConversation(ConversationConfig(maxOutputToken = BenchSpec.LONG_MAX_OUTPUT, thinkingConfig = ThinkingConfig(false)))
                try {
                    val wall = System.currentTimeMillis()
                    conv.sendMessage(longPrompt)
                    val b = conv.getBenchmarkInfo()
                    log("prefill $label load=${load}ms ttft=${"%.2f".format(b.timeToFirstTokenInSecond)}s prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) wall=${System.currentTimeMillis() - wall}ms")
                } catch (t: Throwable) { log("prefill $label FAILED: ${t.message?.take(200)}") } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
    }

    /**
     * The verifier check iOS asked for: greedy (top-k 1, temperature 0, seed 0), drafter off then on, twice each, on
     * this GPU with the flag FORCED. Exact verification must reproduce argmax decoding byte for byte; a divergence
     * that also appears here is the runtime's verifier, one that appears only on Metal is Apple's kernels or wrapper.
     */
    @Test fun rawGreedySpeculativeCompare() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        ExperimentalFlags.enableBenchmark = true
        val prompt = "Explain in one paragraph how a bicycle stays upright."
        val texts = mutableMapOf<String, String>()
        for (spec in listOf(false, true, false, true)) {
            ExperimentalFlags.enableSpeculativeDecoding = spec
            val engine = Engine(EngineConfig(
                modelPath = path!!, backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = ctx.cacheDir.path))
            try {
                engine.initialize()
                val conv = engine.createConversation(ConversationConfig(
                    samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                    maxOutputToken = 200, thinkingConfig = ThinkingConfig(false)))
                try {
                    val reply = conv.sendMessage(prompt)
                    val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val b = conv.getBenchmarkInfo()
                    val key = "speculative=$spec"; val run = if (texts.containsKey(key)) 2 else 1
                    if (run == 1) texts[key] = text
                    log("greedy-compare $key run=$run decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s) chars=${text.length} " +
                        "sameAsRun1=${texts[key] == text}")
                    text.chunked(900).forEachIndexed { i, c -> log("greedy-compare $key run=$run text[$i]: " + c.replace('\n', ' ')) }
                } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
        val off = texts["speculative=false"]!!; val on = texts["speculative=true"]!!
        val common = off.commonPrefixWith(on)
        log("greedy-compare verdict: identical=${off == on} commonPrefixChars=${common.length} of off=${off.length}/on=${on.length}; divergence after: \"" + common.takeLast(60).replace('\n', ' ') + "\"")
        ExperimentalFlags.enableSpeculativeDecoding = null
    }

    /** Sampling is the per-token cost that fell back to the CPU. Greedy skips the distribution entirely. */
    @Test fun decodeGreedy() {
        val path = modelPath(); assumeTrue("no on-device model installed", path != null)
        run("greedy", path!!, constrained = true, speculative = null, tokens = 96, temperature = 0f) { }
    }
}
