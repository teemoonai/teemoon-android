package ai.teemoon.app

// android-only: no Swift counterpart. The teemoon half of the on-device performance gate against Google AI Edge Gallery
// (pm/features/ondevice-perf-parity.md; the maintainer 2026-10-26, standing: every handoff that changes on-device inference).
// Same runtime, same Gemma 4 E2B file, the GPU rung, the app's own LiteRtLmEngineFactory and engine handle — the path a
// teemoon turn takes, minus the UI. One instrumentation run is one fresh process (run it 5 times for the medians):
//   -e mode cold    the LiteRT caches deleted first: cold text load, short + ~1,000-token turns, cold vision load, image turn
//   -e mode cached  the same on the caches the previous run left
//   -e mode heat    a cached text engine, 10 ~1,000-token turns, battery temperature and thermal status after each
//   -e mode coldvision  the caches deleted, then a vision engine straight away and the image turn: a truly cold image
//                   load (in `cold` the vision open reuses the text weights the text open just cached)
//   -e mode npucold / npu  the Tensor NPU bundle (what teemoon equips on a Tensor G5), caches deleted or warm: load,
//                   short and ~1,000-token turns. The NPU bundle carries no vision encoder.
//   -e mode cpucold / cpu  the general file on the CPU rung (Gallery's only choice for it on a Pixel 10): text load,
//                   short and ~1,000-token turns, a vision load on the CPU and the image turn
//   -e mode default / defaultcold  what a Pixel 10 Pro user gets: text on the NPU bundle, then the first image turn —
//                   the NPU engine closed and the general file's vision engine loaded (the switch), timed as one
// Two engine opens per process at most: a third crashed the GPU runtime before (ContextAndBundleBenchTest). Gallery's
// half is read from its own stats panel; Gallery itself is never instrumented.

import ai.teemoon.app.imaging.OnDeviceImage
import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.inference.LocalEngineHandle
import ai.teemoon.ondevice.EngineCache
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class GalleryParityBenchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private var mode = InstrumentationRegistry.getArguments().getString("mode") ?: "cached"
    /** -e bench off: LiteRT's benchmark instrumentation off for the turns (the factory turns it on at open); wall time only. */
    private val benchOff = InstrumentationRegistry.getArguments().getString("bench") == "off"
    private fun log(s: String) {
        Log.w("teemoon", "[parity] $s")
        inst.sendStatus(2, android.os.Bundle().apply { putString("parity", s) })
    }

    private val gemma = LocalModelCatalog.all.first()
    private val storage get() = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
    // The ladder as the app builds it on this phone (GPU, then CPU, for the general file): the NPU is not in play.
    // The CPU modes pass an emulator's hardware name, which BackendLadder answers with the CPU rung alone — the test's way
    // to pin the rung without a switch in the app.
    private fun factory() = LiteRtLmEngineFactory(ctx.cacheDir.path, {}, socModel = Build.SOC_MODEL.orEmpty(),
        hardware = if (mode.startsWith("cpu")) "ranchu" else Build.HARDWARE.orEmpty(), cpuThreadsOverride = threads,
        ladderOverride = if (gpuPinned) listOf(ai.teemoon.ondevice.BackendKind.GPU) else null, diskCache = diskCache, reuseConversations = reuse)
    /** (ii): no cacheDir, as Gallery. (iii): the general file's context. (v): keep last turn's Conversation (ConversationReuse). */
    private var diskCache = true
    private var contextTokens = 8192
    private var reuse = true
    /** t1/t2: the general file on the GPU whatever the ladder now says — the stock-vs-GrapheneOS program-cache check. */
    private var gpuPinned = false
    /** The CPU rung's threads for the sweep (null = BackendLadder's value for this chip, 0 = the runtime's default). */
    private var threads: Int? = InstrumentationRegistry.getArguments().getString("threads")?.toIntOrNull()

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun batteryC(): Double = shell("dumpsys battery").lines().first { it.trim().startsWith("temperature:") }.filter(Char::isDigit).toInt() / 10.0
    private val power by lazy { ctx.getSystemService(PowerManager::class.java) }
    private fun heat() = "battery ${batteryC()}°C thermal ${power.currentThermalStatus} headroom ${"%.2f".format(power.getThermalHeadroom(0))}"

    private fun <T> peak(block: () -> T): Pair<T, Long> {
        var p = 0L; var on = true
        val sampler = Thread { while (on) { p = maxOf(p, Debug.getPss() / 1024); Thread.sleep(200) } }.apply { start() }
        try { return block() to maxOf(p, Debug.getPss() / 1024) } finally { on = false; sampler.join() }
    }

    private val shortPrompt = "Write one sentence about the sea."
    /** ~1,000 tokens of plain prose (4 characters a token), then a question about it. Fixed, so every run prefills the same. */
    private val longPrompt = buildString {
        val para = "The harbour town woke slowly. Fishing boats came back with the tide, gulls followed them in, and the market " +
            "opened its shutters one by one. A baker carried trays of bread across the square while the ferry sounded its horn. "
        while (length < 4000) append(para)
        append("\n\nIn two sentences, what happens in this passage?")
    }
    private val imagePrompt = "What shape is in this picture, and what colour is it? Answer in one short sentence."
    private fun picture(): ByteArray {
        val bmp = Bitmap.createBitmap(1536, 2048, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); drawCircle(768f, 1024f, 500f, Paint().apply { color = Color.RED; isAntiAlias = true }) }
        return OnDeviceImage.prepare(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray())!!
    }

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private suspend fun turn(label: String, engine: LocalEngineHandle, prompt: String, image: ByteArray? = null) {
        if (benchOff) com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = false
        val text = StringBuilder()
        val t0 = System.nanoTime()
        val (_, pss) = peak { runBlocking { engine.stream(LocalConversationSpec(null, emptyList(), emptyList(), prompt, 256, false, userImages = listOfNotNull(image))) { text.append(it) } } }
        val wall = (System.nanoTime() - t0) / 1e9
        val b = engine.benchmark()
        log("ROW $mode${if (benchOff) "/bench-off" else ""}${threads?.let { "/threads=$it" } ?: ""} $label · window ${engine.contextTokens ?: "as asked"} · ttft ${"%.2f".format(b?.timeToFirstTokenSeconds)}s · prefill ${b?.prefillTokens} tok @ ${b?.prefillTokensPerSecond?.toInt()} tok/s · " +
            "decode ${b?.decodeTokens} tok @ ${"%.1f".format(b?.decodeTokensPerSecond)} tok/s · wall ${"%.1f".format(wall)}s · peak pss ${pss}MB · ${heat()} · <${text.take(60).toString().replace("\n", " ")}>")
    }

    /**
     * The 10 Pro (PowerVR) decoded at half speed whenever LiteRT's GPU program cache was loaded (2026-10-26: 10–13 tok/s
     * on a fresh compile, 5–6 on the cache). For Test Lab, where one matrix cannot pass a mode: a cold run then a cached
     * one, each in its own process under the orchestrator, in this order, on the same install.
     */
    @Test fun t1_cold() { mode = "cold"; gpuPinned = true; parity() }
    @Test fun t2_cached() { mode = "cached"; gpuPinned = true; parity() }

    /**
     * gemma-4-E2B-it-gpu.litertlm (litert-community b3ca0d2f, 2026-08-31; on main): 1.87 GB, ONE section,
     * tf_lite_artisan_text_decoder — no vision encoder, no audio, no MTP drafter (read from its header, 2026-10-26). A
     * GPU-targeted text build neither teemoon nor Gallery uses; measured text-only, cold then cached, after t1/t2.
     */
    @Test fun t3_gpuFileCold() { mode = "cold"; bundle = GPU_FILE; parity() }
    @Test fun t4_gpuFileCached() { mode = "cached"; bundle = GPU_FILE; parity() }

    /**
     * (B), the maintainer 2026-10-26: the CPU rung's thread count, prefill against decode. A cold CPU load first (builds the
     * XNNPack cache), then cached runs at 4, 6 and 8 threads and the runtime's default (0), text and an image each.
     */
    @Test fun t5_cpuColdBuild() { mode = "cpucold"; threads = 4; parity() }
    @Test fun t6_cpuThreads4() { mode = "cpu"; threads = 4; parity() }
    @Test fun t7_cpuThreads6() { mode = "cpu"; threads = 6; parity() }
    @Test fun t8_cpuThreads8() { mode = "cpu"; threads = 8; parity() }
    @Test fun t9_cpuThreadsDefault() { mode = "cpu"; threads = 0; parity() }

    /** (ii) and (iii) on the CPU rung (run each 3x as separate instrumentation runs; the engine is opened fresh each time). */
    @Test fun t10_cpuNoDiskCache() { mode = "cpu"; diskCache = false; parity() }
    @Test fun t11_cpuContext4096() { mode = "cpu"; contextTokens = 4096; parity() }

    /**
     * (v): a 5-turn chat on one engine, first token per turn. With reuse the kept Conversation sends only the new
     * message; without, every turn re-reads the system prompt and the whole history — what any rebuild costs (a search
     * or image turn in between, a trimmed window, an eviction). -e bundle npu runs the Tensor bundle, else the general
     * file on this chip's ladder.
     */
    @Test fun t12_multiturnReuse() { reuse = true; multiturn() }
    @Test fun t13_multiturnNoReuse() { reuse = false; multiturn() }

    private fun multiturn() = runBlocking {
        val npuArg = InstrumentationRegistry.getArguments().getString("bundle") == "npu"
        val nb = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        val (file, factory, window) = if (npuArg && nb != null) {
            val f = BundleFetch.fetch(gemma.id, nb.revision, nb.fileName, nb.sha256, storage.npuFile(gemma, nb), ::log)
            Triple(f, LiteRtLmEngineFactory(ctx.cacheDir.path, {}, socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty(), reuseConversations = reuse,
                nativeLibraryDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir, {})), nb.maxTokens)
        } else Triple(BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log), factory(), 8192)
        val engine = factory.open(file.path, window, requireNpu = npuArg && nb != null)
        log("MT reuse=$reuse backend=${engine.backend} file=${file.name}")
        val system = "You are teemoon, a concise assistant. Answer in two or three sentences."
        val questions = listOf("What makes a harbour town lively in the morning?", "Why do gulls follow fishing boats?",
            "What does a ferry horn signal?", "How do bakers time their bread for a market?", "Summarise everything we discussed in one sentence.")
        val history = ArrayList<Pair<ai.teemoon.inference.LocalRole, String>>()
        try {
            for ((i, q) in questions.withIndex()) {
                val text = StringBuilder()
                val t0 = System.nanoTime()
                var first = 0L
                engine.stream(LocalConversationSpec(system, history.toList(), emptyList(), q, 160, false)) { if (first == 0L && it.isNotEmpty()) first = System.nanoTime(); text.append(it) }
                val b = engine.benchmark()
                // ttft is the runtime's (stale on a reused conversation); first is this turn's own first piece
                log("MTROW reuse=$reuse turn ${i + 1} history=${history.size} · window ${engine.contextTokens ?: "as asked"} · ttft ${"%.2f".format(b?.timeToFirstTokenSeconds)}s · first ${"%.2f".format((first - t0) / 1e9)}s · prefill ${b?.prefillTokens} tok @ ${b?.prefillTokensPerSecond?.toInt()} tok/s · decode ${b?.decodeTokens} @ ${"%.1f".format(b?.decodeTokensPerSecond)} · wall ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s · conversation ${engine.lastConversation}")
                history += ai.teemoon.inference.LocalRole.USER to q
                history += ai.teemoon.inference.LocalRole.MODEL to text.toString()
            }
        } finally { engine.close() }
    }

    private var bundle: Triple<String, String, String>? = null   // fileName, revision, sha256; null = the catalog's general file
    private companion object {
        val GPU_FILE = Triple("gemma-4-E2B-it-gpu.litertlm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5")
    }

    @Test fun parity() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} mode=$mode · ${heat()}")
        if (mode == "npu" || mode == "npucold" || mode == "default" || mode == "defaultcold") return@runBlocking npu()
        val file = bundle?.let { (name, rev, sha) -> BundleFetch.fetch(gemma.id, rev, name, sha, File(storage.directory(gemma.id), name), ::log) }
            ?: BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        if (bundle != null) log("bundle ${file.name} ${file.length()} bytes")
        if (mode == "cold" || mode == "coldvision" || mode == "cpucold") EngineCache.directoryFor(file.path).deleteRecursively()
        if (mode == "coldvision") return@runBlocking visionTurn(file)
        val t0 = System.nanoTime()
        val (text, loadPss) = peak { runBlocking { factory().open(file.path, contextTokens, requireNpu = false, vision = false) } }
        log("ROW $mode text load ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s backend=${text.backend} speculative=${text.speculative} · peak pss ${loadPss}MB · ${heat()}")
        try {
            if (mode == "heat") {
                repeat(10) { turn("heat turn ${it + 1}", text, longPrompt) }
                return@runBlocking
            }
            turn("short text", text, shortPrompt)
            turn("long text", text, longPrompt)
        } finally { text.close() }
        if (bundle == null) visionTurn(file)   // the -gpu file has no vision encoder
    }

    private suspend fun visionTurn(file: File) {
        val t1 = System.nanoTime()
        val (vision, visionPss) = peak { runBlocking { factory().open(file.path, 8192, requireNpu = false, vision = true) } }
        log("ROW $mode vision load ${"%.1f".format((System.nanoTime() - t1) / 1e9)}s backend=${vision.backend} sees=${vision.sees} speculative=${vision.speculative} · peak pss ${visionPss}MB · ${heat()}")
        try { turn("image", vision, imagePrompt, picture()) } finally { vision.close() }
    }

    private suspend fun npu() {
        val bundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty()) ?: return log("no NPU bundle for ${Build.SOC_MODEL}")
        val file = BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, storage.npuFile(gemma, bundle), ::log)
        val general = if (mode.startsWith("default")) BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log) else null
        if (mode == "npucold" || mode == "defaultcold") EngineCache.directoryFor(file.path).deleteRecursively()
        val npuFactory = LiteRtLmEngineFactory(ctx.cacheDir.path, {}, socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty(),
            nativeLibraryDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir, {}))
        val t0 = System.nanoTime()
        val (engine, pss) = peak { runBlocking { npuFactory.open(file.path, bundle.maxTokens, requireNpu = true) } }
        log("ROW $mode text load ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s backend=${engine.backend} speculative=${engine.speculative} · peak pss ${pss}MB · ${heat()}")
        try { turn("short text", engine, shortPrompt); turn("long text", engine, longPrompt) } finally {
            if (general == null) engine.close()
        }
        general ?: return
        // The first image turn: the NPU engine goes (one resident at a time, LocalEngineCache) and the general file's
        // vision engine loads — the user waits for both.
        val t1 = System.nanoTime()
        engine.close()
        val (vision, visionPss) = peak { runBlocking { factory().open(general.path, 8192, requireNpu = false, vision = true) } }
        log("ROW $mode switch to images ${"%.1f".format((System.nanoTime() - t1) / 1e9)}s (NPU closed + vision load) backend=${vision.backend} sees=${vision.sees} speculative=${vision.speculative} · peak pss ${visionPss}MB · ${heat()}")
        try { turn("image after switch", vision, imagePrompt, picture()) } finally { vision.close() }
    }
}
