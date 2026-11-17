package ai.teemoon.app

// android-only: no Swift counterpart. IMAGES.md §4b's measurement before on-device vision is offered (1.1.0): the
// general bundle opened with its vision backend, one picture per turn, reported through sendStatus, not asserted —
// load time, whether the handle sees and on what backend, first token and prefill for an image turn against a text
// turn, memory (PSS, graphics, peak RSS) and the thermal status over three image turns. The picture is drawn here (a
// red circle on white), so the answer says whether the model saw it. Keyless and tool-free: nothing is sent anywhere
// but the bundle download, which Test Lab pre-stages in /data/local/tmp.

import ai.teemoon.app.imaging.ImageHistory
import ai.teemoon.app.imaging.OnDeviceImage
import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.inference.LocalDefaults
import ai.teemoon.inference.LocalEngineHandle
import ai.teemoon.inference.LocalRole
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
class OnDeviceVisionBenchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) {
        Log.w("teemoon", "[bench] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("bench", s) })
    }

    // `-e model e4b` (the maintainer 2026-10-27, "Yes add", via pm): the same probe on E4B, which stays image-off on device until
    // it passes. A name that matches no catalogue entry fails the run rather than measuring E2B under E4B's label.
    private val gemma = InstrumentationRegistry.getArguments().getString("model")
        ?.let { m -> LocalModelCatalog.all.first { it.id.contains("gemma-4-${m.uppercase()}-") } } ?: LocalModelCatalog.all.first()
    private val storage get() = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
    private fun factory() = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log,
        nativeLibraryDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir, ::log),
        socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty())

    /**
     * The system's figures the memory-fit rule reads (iOS f3773d9 / 6017baf): totalMem for the "may not fit" floor,
     * availMem for the load gate, and lmkd's threshold the gate used to subtract — logged so a Test Lab readout settles
     * the floor (6 GB vs 8 GB) and replaces the Pixel 8a stand-in in WhereGetPolicyTest.
     */
    private fun sysMem(): String {
        val m = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val mb = { b: Long -> b / (1024 * 1024) }
        return "sys total ${mb(m.totalMem)}MB (${ai.teemoon.providers.LocalMemory.marketedRAMGB(m.totalMem)} GB) avail ${mb(m.availMem)}MB threshold ${mb(m.threshold)}MB low=${m.lowMemory}"
    }

    private fun memory(): String {
        val m = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.firstOrNull { it.startsWith(key) }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
        return "pss ${m.totalPss / 1024}MB (graphics ${m.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)}MB) rss ${kb("VmRSS")}MB peakRss ${kb("VmHWM")}MB"
    }

    /** A phone-photo-sized picture (1536×2048) the app would have stored, then what OnDeviceImage gives the model. */
    private fun picture(): ByteArray {
        val bmp = Bitmap.createBitmap(1536, 2048, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); drawCircle(768f, 1024f, 500f, Paint().apply { color = Color.RED; isAntiAlias = true }) }
        val stored = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return OnDeviceImage.prepare(stored)!!
    }

    private suspend fun turn(engine: LocalEngineHandle, history: List<Pair<LocalRole, String>>, prompt: String, image: ByteArray?): String {
        val text = StringBuilder()
        engine.stream(LocalConversationSpec(null, history, emptyList(), prompt, 96, LocalDefaults.ENABLE_THINKING, userImages = listOfNotNull(image))) { text.append(it) }
        return text.toString()
    }

    @Test fun visionTurns() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB")
        log("SUMMARY sysmem start · ${sysMem()}")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        log("SUMMARY sysmem before load · need ${ai.teemoon.providers.LocalMemory.loadNeedMB(gemma.sizeMB, gemma.peakFootprintMB)}MB · ${sysMem()}")
        log("before load · ${memory()}")
        val started = System.nanoTime()
        val engine = factory().open(file.path, 8192, requireNpu = false, vision = true)
        val loadS = (System.nanoTime() - started) / 1e9
        log("SUMMARY vision load ${"%.1f".format(loadS)}s backend=${engine.backend} sees=${engine.sees} · ${memory()} · ${sysMem()}")
        val power = ctx.getSystemService(PowerManager::class.java)
        try {
            val image = picture()
            log("prompt image ${image.size / 1024} KB")
            val textAnswer = turn(engine, emptyList(), "Say hello in five words.", null)
            val t = engine.benchmark()
            log("SUMMARY vision text turn: ttft ${"%.2f".format(t?.timeToFirstTokenSeconds)}s prefill ${t?.prefillTokens} @ ${t?.prefillTokensPerSecond?.toInt()}tok/s · <${textAnswer.take(80)}>")
            val history = ArrayList<Pair<LocalRole, String>>()
            val prompts = listOf("What shape is in this picture, and what colour is it? Answer in one short sentence.",
                "Describe this picture in one short sentence.", "Is there anything besides the shape in this picture? One sentence.")
            for ((i, p) in prompts.withIndex()) {
                val answer = turn(engine, history, p, image)
                val b = engine.benchmark()
                log("SUMMARY vision image turn ${i + 1}: ttft ${"%.2f".format(b?.timeToFirstTokenSeconds)}s prefill ${b?.prefillTokens} @ ${b?.prefillTokensPerSecond?.toInt()}tok/s " +
                    "decode ${b?.decodeTokensPerSecond?.toInt()}tok/s · thermal ${power.currentThermalStatus} headroom ${"%.2f".format(power.getThermalHeadroom(0))} · ${memory()} · ${sysMem()} · <${answer.take(120)}>")
                // Earlier image turns go back as text, as ImageHistory.applyOnDevice sends them.
                history += LocalRole.USER to "$p\n${ImageHistory.EARLIER}"
                history += LocalRole.MODEL to answer
            }
        } finally { engine.close() }
        log("after close · ${memory()}")
    }

    /**
     * pm 2026-10-26: the first vision load took 58.8 s on the 8a with nothing cached. The same file loaded twice in one
     * install — cold, closed, then again on LiteRT's cache — with what the cache directory holds between. Two loads, not
     * three: a third engine open in one process crashed the GPU runtime before (ContextAndBundleBenchTest).
     */
    @Test fun visionLoadColdThenCached() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        // Cold means LiteRT's cache beside the model is gone, not only this process: another test in the same install
        // (Test Lab keeps the data between them) would have built it, and "cold" would time a cached load.
        val cacheDir = ai.teemoon.ondevice.EngineCache.directoryFor(file.path)
        cacheDir.deleteRecursively()
        for (pass in listOf("cold", "cached")) {
            val started = System.nanoTime()
            val engine = factory().open(file.path, 8192, requireNpu = false, vision = true)
            val loadS = (System.nanoTime() - started) / 1e9
            val cache = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            try {
                val answer = turn(engine, emptyList(), "What colour is the shape? One word.", picture())
                log("SUMMARY vision load $pass ${"%.1f".format(loadS)}s backend=${engine.backend} sees=${engine.sees} · cache ${cache}MB · ttft ${"%.2f".format(engine.benchmark()?.timeToFirstTokenSeconds)}s · <${answer.take(40)}>")
            } finally { engine.close() }
        }
    }

    /**
     * The audit's evidence for the vision cache (pm, 2026-10-26): (1) every cache file is written by the FIRST load, before
     * any image turn, and its mtime does not move over the turns; (2) its hash is unchanged after a three-image chat;
     * (3) it sits beside the model and deleting the model removes it; and the names, for the disk gate's first-load
     * exception (ContextAndBundleBenchTest), so a cache appearing mid-chat still fails there.
     */
    @Test fun visionCacheDiskGate() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val cacheDir = ai.teemoon.ondevice.EngineCache.directoryFor(file.path)
        fun roots() = listOfNotNull(ctx.dataDir, ctx.externalCacheDir)
        fun snapshot() = roots().flatMap { r -> r.walkTopDown().filter { it.isFile }.toList() }.associate { it.path to (it.length() to it.lastModified()) }
        fun hashes() = cacheDir.walkTopDown().filter { it.isFile }.associate { f ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            f.name to md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }
        cacheDir.deleteRecursively()
        val before = snapshot()
        val engine = factory().open(file.path, 8192, requireNpu = false, vision = true)
        val afterLoad = snapshot()
        val loadNew = afterLoad.keys - before.keys
        log("SUMMARY gate vision: backend=${engine.backend} sees=${engine.sees} · new at first load: " + loadNew.joinToString { "${it.substringAfterLast('/')} ${afterLoad.getValue(it).first / (1024 * 1024)}MB" })
        val inCache = loadNew.filter { it.startsWith(cacheDir.path) }
        val hashAfterLoad = hashes()
        log("SUMMARY gate vision: cache beside the model at ${cacheDir.path}: ${hashAfterLoad.entries.joinToString { "${it.key} ${it.value}" }}")
        try {
            val image = picture()
            for (q in listOf("What colour is the shape?", "What shape is it?", "Is the background white?")) turn(engine, emptyList(), q, image)
        } finally { engine.close() }
        val afterTurns = snapshot()
        val movedCache = inCache.filter { afterTurns[it]?.second != afterLoad[it]?.second }
        val newInTurns = (afterTurns.keys - afterLoad.keys).filter { it.startsWith(cacheDir.path) }
        val hashAfterTurns = hashes()
        log("SUMMARY gate vision: after 3 image turns · cache mtimes moved: ${movedCache.size} · new cache files: ${newInTurns.size} · hashes unchanged: ${hashAfterTurns == hashAfterLoad}")
        storage.delete(gemma)
        log("SUMMARY gate vision: after deleting the model · cache dir exists: ${cacheDir.exists()} · model dir exists: ${storage.directory(gemma.id).exists()}")
        org.junit.Assert.assertTrue("the first load wrote no cache beside the model", inCache.isNotEmpty())
        org.junit.Assert.assertTrue("a cache file changed during the image turns: $movedCache $newInTurns", movedCache.isEmpty() && newInTurns.isEmpty() && hashAfterTurns == hashAfterLoad)
        org.junit.Assert.assertFalse("deleting the model left its cache", cacheDir.exists())
    }

    /** PSS sampled every 200 ms while [block] runs: the turn's own peak, which VmHWM (the process's, since start) is not. */
    private fun <T> peakPssMB(block: () -> T): Pair<T, Long> {
        var peak = 0L; var on = true
        val sampler = Thread { while (on) { peak = maxOf(peak, Debug.getPss() / 1024); Thread.sleep(200) } }.apply { start() }
        try { return block() to maxOf(peak, Debug.getPss() / 1024) } finally { on = false; sampler.join() }
    }

    private fun availMB(): Long {
        val info = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        return (info.availMem - info.threshold) / (1024 * 1024)
    }

    /**
     * pm 2026-10-26, for the findings file: what the vision executor costs a TEXT turn — the reason IMAGES.md §4b keeps
     * it out of text-only turns. The same text turn on a text-only engine, then on a vision engine (two opens, the most
     * one process takes). The first open also builds the text caches, so its peak carries that; the resident PSS after
     * the turn is the comparable number.
     */
    @Test fun visionCostOnATextTurn() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB · avail ${availMB()}MB")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        for (vision in listOf(false, true)) {
            val (engine, loadPeak) = peakPssMB { runBlocking { factory().open(file.path, 8192, requireNpu = false, vision = vision) } }
            try {
                val afterLoad = Debug.getPss() / 1024
                val (answer, turnPeak) = peakPssMB { runBlocking { turn(engine, emptyList(), "Say hello in five words.", null) } }
                val b = engine.benchmark()
                log("SUMMARY text turn on a ${if (vision) "VISION" else "text-only"} engine: backend=${engine.backend} sees=${engine.sees} · pss after load ${afterLoad}MB (load peak ${loadPeak}MB) · " +
                    "turn peak ${turnPeak}MB · after turn ${Debug.getPss() / 1024}MB · ttft ${"%.2f".format(b?.timeToFirstTokenSeconds)}s · avail ${availMB()}MB · ${memory()} · <${answer.take(40)}>")
            } finally { engine.close() }
        }
    }

    /**
     * The app's own path on a file with no NPU bundle (E4B everywhere, pm 2026-10-27): a text turn on a text-only engine,
     * then the image arrives, the text engine closes and the vision engine opens (the switch), the image turn, and a text
     * turn after it on the vision engine. Peak PSS is sampled across the whole switch, close through the image turn, so
     * a transient while the text engine is still being freed (2.7 GB on the iPhone 16 Pro) is in the number.
     */
    @Test fun switchTextToVision() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB · avail ${availMB()}MB · model ${gemma.id}")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val t0 = System.nanoTime()
        val text = factory().open(file.path, 8192, requireNpu = false)
        log("SUMMARY switch text load ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s backend=${text.backend} · ${memory()}")
        val (_, textPeak) = peakPssMB { runBlocking { turn(text, emptyList(), "Say hello in five words.", null) } }
        log("SUMMARY switch text turn: ttft ${"%.2f".format(text.benchmark()?.timeToFirstTokenSeconds)}s · peak pss ${textPeak}MB · ${memory()}")
        val image = picture()
        val t1 = System.nanoTime()
        val (vision, switchPeak) = peakPssMB { runBlocking {
            text.close()
            factory().open(file.path, 8192, requireNpu = false, vision = true).also { turn(it, emptyList(), "What shape is in this picture, and what colour is it? One sentence.", image) }
        } }
        try {
            log("SUMMARY switch to images + image turn ${"%.1f".format((System.nanoTime() - t1) / 1e9)}s backend=${vision.backend} sees=${vision.sees} · ttft ${"%.2f".format(vision.benchmark()?.timeToFirstTokenSeconds)}s · peak pss ${switchPeak}MB · avail ${availMB()}MB · ${memory()}")
            val (after, afterPeak) = peakPssMB { runBlocking { turn(vision, emptyList(), "Say hello in five words.", null) } }
            log("SUMMARY switch text after vision: ttft ${"%.2f".format(vision.benchmark()?.timeToFirstTokenSeconds)}s · peak pss ${afterPeak}MB · ${memory()} · <${after.take(60)}>")
        } finally { vision.close() }
    }

    /** N different pictures, as OnDeviceImage gives them: a shape of its own colour on white. */
    // Ten colours for up to ten pictures; the four shapes repeat, so the answer still says which picture was which.
    private fun pictures(n: Int): List<ByteArray> = listOf(Color.RED, Color.BLUE, Color.rgb(0, 160, 0), Color.rgb(230, 160, 0), Color.BLACK,
        Color.rgb(128, 0, 128), Color.rgb(255, 105, 180), Color.rgb(0, 160, 160), Color.rgb(139, 69, 19), Color.GRAY).take(n).mapIndexed { idx, colour -> val i = idx % 4
        val bmp = Bitmap.createBitmap(1536, 2048, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = colour; isAntiAlias = true }
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            when (i) {
                0 -> drawCircle(768f, 1024f, 500f, paint)
                1 -> drawRect(268f, 524f, 1268f, 1524f, paint)
                2 -> drawPath(android.graphics.Path().apply { moveTo(768f, 424f); lineTo(1268f, 1524f); lineTo(268f, 1524f); close() }, paint)
                else -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = 120f; drawCircle(768f, 1024f, 450f, paint) }
            }
        }
        OnDeviceImage.prepare(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray())!!
    }

    /**
     * the maintainer 2026-10-26 ("Yes", via pm): can the on-device model take more than one image per message? One turn with 2
     * pictures and one with 4 (or the `images` argument's counts), each 768 px as OnDeviceImage prepares them, on a GPU engine built for that many images
     * (the app's is built for one). Peak PSS, first token, prefill, and whether LiteRT refuses; the low-memory killer
     * would end the process, which Test Lab reports as a crash. Direct LiteRT, so the app's engine is unchanged.
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Test fun moreThanOneImagePerTurn() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram ${totalRamMB()}MB · avail ${availMB()}MB")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = true
        // `-e images 6` (or "2,4") picks the counts; IMAGES.md draft 3 §4b asks up to 10. One count per process is the safe
        // run: two engine opens per process at most (a third crashed the GPU runtime, ContextAndBundleBenchTest).
        val counts = InstrumentationRegistry.getArguments().getString("images")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(2, 4)
        for (n in counts) {
            val images = pictures(n)
            log("images: $n × ${images.map { it.size / 1024 }} KB")
            val started = System.nanoTime()
            val engine = runCatching {
                com.google.ai.edge.litertlm.Engine(com.google.ai.edge.litertlm.EngineConfig(modelPath = file.path, backend = com.google.ai.edge.litertlm.Backend.GPU(),
                    visionBackend = com.google.ai.edge.litertlm.Backend.GPU(), maxNumTokens = 8192, maxNumImages = n,
                    cacheDir = ai.teemoon.ondevice.EngineCache.directoryFor(file.path).apply { mkdirs() }.path)).also { it.initialize() }
            }.getOrElse { log("SUMMARY $n images: the engine REFUSED maxNumImages=$n: ${it.message}"); continue }
            log("$n images: engine ready in ${"%.1f".format((System.nanoTime() - started) / 1e9)}s · ${memory()}")
            try {
                val conv = engine.createConversation(com.google.ai.edge.litertlm.ConversationConfig(maxOutputToken = 96, thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(false)))
                try {
                    val parts = images.map { com.google.ai.edge.litertlm.Content.ImageBytes(it) } +
                        com.google.ai.edge.litertlm.Content.Text("There are $n pictures. For each, in order, name the shape and its colour. One line each.")
                    val turnStart = System.nanoTime()
                    val (result, peak) = peakPssMB { runCatching { conv.sendMessage(com.google.ai.edge.litertlm.Message.user(com.google.ai.edge.litertlm.Contents.of(*parts.toTypedArray()))) } }
                    val took = (System.nanoTime() - turnStart) / 1e9
                    result.onSuccess { reply ->
                        val text = reply.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
                        val b = runCatching { conv.getBenchmarkInfo() }.getOrNull()
                        log("SUMMARY $n images: ttft ${"%.2f".format(b?.timeToFirstTokenInSecond)}s prefill ${b?.lastPrefillTokenCount} @ ${b?.lastPrefillTokensPerSecond?.toInt()}tok/s · turn ${"%.1f".format(took)}s · " +
                            "peak pss ${peak}MB · avail ${availMB()}MB · ${memory()} · <${text.replace("\n", " / ").take(200)}>")
                    }.onFailure { log("SUMMARY $n images: the turn FAILED after ${"%.1f".format(took)}s (peak pss ${peak}MB): ${it.message}") }
                } finally { runCatching { conv.close() } }
            } finally { engine.close() }
        }
    }

    /** The colour and shape words that count as naming [pictures]' picture k (its colour, its shape; a ring may be called a circle). */
    private val pictureNames = listOf(listOf("red") to listOf("circle", "dot", "disc", "disk"), listOf("blue") to listOf("square", "rectangle"),
        listOf("green") to listOf("triangle"), listOf("orange", "amber", "yellow", "gold") to listOf("ring", "circle", "donut", "outline", "o"),
        listOf("black") to listOf("circle", "dot", "disc", "disk"), listOf("purple", "violet", "magenta") to listOf("square", "rectangle"),
        listOf("pink", "magenta") to listOf("triangle"), listOf("teal", "cyan", "turquoise", "aqua") to listOf("ring", "circle", "donut", "outline"),
        listOf("brown") to listOf("circle", "dot", "disc", "disk"), listOf("gray", "grey", "silver") to listOf("square", "rectangle"))

    /** How many of the [n] pictures the answer names right: the k-th answer line holds picture k's colour and shape. */
    private fun score(answer: String, n: Int): Int {
        val lines = answer.lines().map { it.lowercase() }.filter { l -> l.any(Char::isLetter) && (pictureNames.any { (c, _) -> c.any(l::contains) }) }
        return (0 until n).count { k -> val l = lines.getOrNull(k) ?: return@count false; val (c, sh) = pictureNames[k]
            c.any(l::contains) && sh.any { w -> Regex("\\b$w").containsMatchIn(l) } }
    }

    /**
     * The chip × backend pass (the maintainer 2026-11-12, via pm: "running images through the different litert configs —
     * snapdragon npu, gpu, etc"). One backend per process, `-e backend gpu|cpu|npu` (two engine opens per process is the
     * most a GPU runtime took, ContextAndBundleBenchTest), `-e images 1,4,10`, on one engine built for the most of them.
     * The pictures are [pictures]; each answer is scored, so numeric drift on a backend shows as wrong names. `npu` opens
     * this chip's NPU bundle (when the catalogue has one) with the GPU encoder beside it: whether that bundle carries a
     * vision encoder at all is the question. Logged beside it: the app's own pick for an image turn here, and
     * PhoneImageMemory's verdict from the model's need, the formula ceiling and availMem now.
     */
    @Test fun imageTurnsOnABackend() = imageTurns(InstrumentationRegistry.getArguments().getString("backend") ?: "gpu")
    /** Test Lab passes one set of arguments per run: each backend its own method (one per process under the orchestrator). */
    @Test fun imageTurnsGpu() = imageTurns("gpu")
    @Test fun imageTurnsCpu() = imageTurns("cpu")
    @Test fun imageTurnsNpu() = imageTurns("npu")

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun imageTurns(backend: String) = runBlocking {
        val counts = InstrumentationRegistry.getArguments().getString("images")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(1, 4, 10)
        val soc = Build.SOC_MODEL.orEmpty()
        val npuBundle = LocalModelCatalog.npuBundle(gemma, soc)
        val appPick = ai.teemoon.ondevice.BackendLadder.order(soc, Build.HARDWARE.orEmpty()).firstOrNull { it == ai.teemoon.ondevice.BackendKind.GPU || it == ai.teemoon.ondevice.BackendKind.CPU }
        val sys = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val totalMB = (sys.totalMem / (1024 * 1024)).toInt(); val availNowMB = (sys.availMem / (1024 * 1024)).toInt()
        val need = gemma.imageNeedMB(if (backend == "npu") ai.teemoon.providers.ImagePath.NPU else if (backend == "cpu") ai.teemoon.providers.ImagePath.CPU else ai.teemoon.providers.ImagePath.GPU)
        val ceiling = ai.teemoon.providers.PhoneImageMemory.ceilingMB(totalMB, highestSeenMB = 0)
        log("SUMMARY device ${Build.MANUFACTURER} ${Build.MODEL} soc=$soc hw=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT} ram ${totalMB}MB · model ${gemma.id} · " +
            "npu bundle ${npuBundle?.fileName ?: "none"} · app's image pick: ${if (npuBundle != null) "npu bundle for text, the general file for images" else "the general file"} on ${appPick} · " +
            "need ${need}MB ceiling ${ceiling}MB avail ${availNowMB}MB → ${need?.let { ai.teemoon.providers.PhoneImageMemory.verdict(it, ceiling, availNowMB, killedBefore = false) }}")
        if (backend == "npu" && npuBundle == null) { log("SUMMARY npu: no NPU bundle for $soc"); return@runBlocking }
        val onDevice = if (backend == "npu") gemma.forDevice(soc) else gemma
        val file = BundleFetch.fetch(onDevice.id, onDevice.revision, onDevice.fileName, onDevice.sha256, storage.file(onDevice), ::log)
        com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = true
        val textBackend = when (backend) {
            "cpu" -> com.google.ai.edge.litertlm.Backend.CPU(4, 4)
            "npu" -> com.google.ai.edge.litertlm.Backend.NPU(NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log))
            else -> com.google.ai.edge.litertlm.Backend.GPU()
        }
        // the app's rule (LiteRtLmEngineFactory.visionBackendFor): the encoder beside a CPU text model is the CPU's, but the GPU's on a PowerVR
        val visionBackend = if (backend == "cpu" && !ai.teemoon.ondevice.BackendLadder.isPowerVr(soc)) com.google.ai.edge.litertlm.Backend.CPU(4, 4) else com.google.ai.edge.litertlm.Backend.GPU()
        val window = if (backend == "npu") npuBundle!!.maxTokens else if (backend == "cpu") 4096 else 8192
        val availBefore = availMB()
        val started = System.nanoTime()
        val (engine, loadPeak) = peakPssMB { runCatching {
            com.google.ai.edge.litertlm.Engine(com.google.ai.edge.litertlm.EngineConfig(modelPath = file.path, backend = textBackend, visionBackend = visionBackend,
                maxNumTokens = window, maxNumImages = counts.max(), cacheDir = ai.teemoon.ondevice.EngineCache.directoryFor(file.path).apply { mkdirs() }.path)).also { it.initialize() }
        } }
        val e = engine.getOrElse { log("SUMMARY $backend: the vision engine REFUSED to load (${file.name}): ${it.message}"); return@runBlocking }
        log("SUMMARY $backend: vision engine ready in ${"%.1f".format((System.nanoTime() - started) / 1e9)}s, window $window · load peak pss ${loadPeak}MB · avail ${availBefore}MB → ${availMB()}MB")
        try {
            for (n in counts) {
                val conv = e.createConversation(com.google.ai.edge.litertlm.ConversationConfig(maxOutputToken = 160, thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(false)))
                try {
                    val parts = pictures(n).map { com.google.ai.edge.litertlm.Content.ImageBytes(it) } +
                        com.google.ai.edge.litertlm.Content.Text(if (n == 1) "Name the shape in this picture and its colour, in one line." else "There are $n pictures. For each, in order, name the shape and its colour. One line each.")
                    val t0 = System.nanoTime()
                    val (result, peak) = peakPssMB { runCatching { conv.sendMessage(com.google.ai.edge.litertlm.Message.user(com.google.ai.edge.litertlm.Contents.of(*parts.toTypedArray()))) } }
                    val took = (System.nanoTime() - t0) / 1e9
                    result.onSuccess { reply ->
                        val text = reply.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
                        val b = runCatching { conv.getBenchmarkInfo() }.getOrNull()
                        log("SUMMARY $backend $n images: right ${score(text, n)}/$n · ttft ${"%.2f".format(b?.timeToFirstTokenInSecond)}s prefill ${b?.lastPrefillTokenCount} @ ${b?.lastPrefillTokensPerSecond?.toInt()}tok/s · " +
                            "turn ${"%.1f".format(took)}s · peak pss ${peak}MB · avail ${availMB()}MB · <${text.replace("\n", " / ").take(260)}>")
                    }.onFailure { log("SUMMARY $backend $n images: the turn FAILED after ${"%.1f".format(took)}s (peak pss ${peak}MB): ${it.message}") }
                } finally { runCatching { conv.close() } }
            }
        } finally { e.close() }
    }

    /**
     * Android's ceiling measured (wherefilter.json phoneImages.ceiling.android): every background process killed (shell
     * `am kill-all`, through the instrumentation's UiAutomation), 2 s, then availMem — the most memory this app could get
     * here. Three reads, the highest kept; logged against the formula (totalMem − PhoneImageMemory.SYSTEM_RESERVE_MB).
     */
    @Test fun ceilingProbe() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun raw(): Int = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.availMem.div(1024 * 1024).toInt()
        val total = totalRamMB().toInt(); val before = raw()
        ui.executeShellCommand("am kill-all").close()
        Thread.sleep(2000)
        val after = (1..3).maxOf { Thread.sleep(500); raw() }
        log("SUMMARY ceiling ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}: total ${total}MB · avail ${before}MB → ${after}MB after kill-all · " +
            "formula ${ai.teemoon.providers.PhoneImageMemory.ceilingMB(total, 0)}MB · reserve measured ${total - after}MB")
    }

    /** The phone's RAM, which Test Lab's catalogue does not say (the floor phones come in several sizes). */
    private fun totalRamMB(): Long = File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }.filter(Char::isDigit).toLong() / 1024
}
