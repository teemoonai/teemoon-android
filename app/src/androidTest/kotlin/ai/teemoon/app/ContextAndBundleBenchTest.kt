package ai.teemoon.app

// android-only: no Swift counterpart. Two one-off measurements for 1.0.6 (the maintainer, 2026-10-19), reported through
// sendStatus, not asserted:
//   gpuContextSizes       the general bundle on GPU at 4k/8k/16k/32k: load, first token, prefill and decode for one
//                         ~1k-token prompt, memory (PSS, its graphics share, peak RSS) and what the app's memory gate
//                         (file size + LocalMemory.headroomMB against availMem - threshold) would say. Google's card
//                         says 32k; the app asks for 8k (LiteRTTransport.DEFAULT_CONTEXT_TOKENS).
//   gpuBundleVsGeneric    litert-community's gemma-4-E2B-it-gpu.litertlm against the generic gemma-4-E2B-it.litertlm
//                         the app ships, on GPU: file size, load, the same three prompts, and their answers.
//   gpuLoadColdThenCached the 22.9 s GPU load (1.0.6 row 10): the same file at the app's 8k loaded three times in one install,
//                         with what the engine left in its cache directory between — compile, or a cache that pays.
//                         Then the audit's gate (2026-10-19): a three-turn chat on the KEPT conversation carrying a marker,
//                         and every file under the app's data directory before and after — what is new or changed, by
//                         name and size, and whether any file but the bundles holds the marker (UTF-8 or UTF-16)
//   deltaCadence          row 18: the gaps between the runtime's text callbacks and the characters in each, one ~250-word
//                         answer on the first backend that loads (what the view model is handed, as PhaseBaselineLiveTest.deltaCadence)
//   sustainedTurns        row 11: ten back-to-back turns on the first backend that loads, decode tok/s and the
//                         thermal status per turn (lab phones charge, so battery drain is not measured here)
// Keyless and tool-free: nothing is sent anywhere but the bundle downloads.

import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.inference.LocalDefaults
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalMemory
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextAndBundleBenchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) {
        Log.w("teemoon", "[bench] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("bench", s) })
    }

    private val gemma = LocalModelCatalog.all.first()
    private val storage get() = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
    private fun factory() = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log,
        nativeLibraryDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir, ::log),
        socModel = Build.SOC_MODEL.orEmpty(), hardware = Build.HARDWARE.orEmpty())

    private fun availableMB(): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return ((info.availMem - info.threshold) / (1024 * 1024)).toInt()
    }
    private fun memory(): String {
        val m = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.firstOrNull { it.startsWith(key) }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
        return "pss ${m.totalPss / 1024}MB (graphics ${m.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)}MB) rss ${kb("VmRSS")}MB peakRss ${kb("VmHWM")}MB"
    }

    /** ~1k tokens of plain text, so prefill is measured on a real prompt, not a greeting. */
    private val longPrompt = "Summarise the following notes in two sentences.\n" +
        (1..90).joinToString(" ") { "Note $it: the shipment for region $it arrived ${it % 7} days late because of weather." }

    private suspend fun turn(engine: ai.teemoon.inference.LocalEngineHandle, prompt: String, maxOut: Int): String {
        val text = StringBuilder()
        engine.stream(LocalConversationSpec(null, emptyList(), emptyList(), prompt, maxOut, LocalDefaults.ENABLE_THINKING, LocalDefaults.TEMPERATURE)) { text.append(it) }
        return text.toString()
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        .also { it.moveToState(Lifecycle.State.RESUMED) }

    @Test fun gpuContextSizes() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ${memory()}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val sizeMB = (file.length() / 1_000_000).toInt()
        for (context in listOf(4096, 8192, 16384, 32768)) {
            System.gc()
            val before = availableMB(); val need = sizeMB + LocalMemory.headroomMB
            val gate = if (before in 1 until need) "REFUSE" else "allow"
            val f = factory()
            val t0 = System.currentTimeMillis()
            val engine = runCatching { f.open(file.path, context, requireNpu = false) }.getOrElse { log("SUMMARY ctx=$context load failed: ${it.message?.take(160)} · gate $gate (need $need of $before MB)"); continue }
            val loadMs = System.currentTimeMillis() - t0
            val afterLoad = memory(); val availAfter = availableMB()
            try {
                val reply = runCatching { turn(engine, longPrompt, 128) }
                val b = engine.benchmark()
                log("SUMMARY ctx=$context backend=${engine.backend} load ${loadMs}ms · first ${b?.timeToFirstTokenSeconds?.let { "%.2f".format(it) }}s · prefill ${b?.prefillTokens}tok " +
                    "${b?.prefillTokensPerSecond?.toInt()}tok/s · decode ${b?.decodeTokensPerSecond?.toInt()}tok/s · after load $afterLoad, avail $before→$availAfter MB · " +
                    "after turn ${memory()} · gate $gate (need $need of $before MB)${reply.exceptionOrNull()?.let { " · turn failed: ${it.message?.take(120)}" } ?: ""}")
            } finally { engine.close() }
        }
        scenario.close()
    }

    private fun cacheListing(): String = ctx.cacheDir.walkTopDown().filter { it.isFile }
        .joinToString(", ") { "${it.relativeTo(ctx.cacheDir)} ${it.length() / 1024}KB" }.ifEmpty { "empty" }

    @Test fun gpuLoadColdThenCached() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        log("SUMMARY cache before: ${cacheListing()}")
        // two loads, not three: a THIRD GPU engine in one process segfaulted in the GPU runtime (Pixel 10 Pro, Test Lab
        // 2026-10-20) — a test-harness pattern the app never repeats; cold 23.5 s, cached 3.7 s already answered it
        for (pass in listOf("first", "second")) {
            System.gc()
            val t0 = System.currentTimeMillis()
            val engine = factory().open(file.path, 8192, requireNpu = false)
            val loadMs = System.currentTimeMillis() - t0
            try {
                val reply = runCatching { turn(engine, "What is the capital of Australia? One sentence.", 32) }
                val b = engine.benchmark()
                log("SUMMARY gpu load $pass ${loadMs}ms backend=${engine.backend} · first ${b?.timeToFirstTokenSeconds?.let { "%.2f".format(it) }}s${reply.exceptionOrNull()?.let { " · turn failed: ${it.message?.take(80)}" } ?: ""}")
            } finally { engine.close() }
            log("SUMMARY cache after $pass: ${cacheListing()}")
        }
        scenario.close()
    }

    /** The GPU half of the disk gate, in a process of its own (run with the orchestrator): one engine, three marker turns. */
    @Test fun gpuDiskGate() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        diskGate(file, requireNpu = false)
        scenario.close()
    }

    /**
     * The audit's disk gate (2026-10-19): does the runtime write anything conversation-shaped? A three-turn chat on ONE
     * handle (the kept conversation), a marker in every user turn and asked back in the replies. Before and after:
     * size, mtime and sha256 of every file in the runtime's cacheDir and the NPU dispatch directory (this covers the
     * caches over 400 MB the grep skips, like XNNPack's); every file new or changed under the app's roots; and a byte
     * search of every file under 400 MB for the marker.
     */
    private suspend fun diskGate(file: File, requireNpu: Boolean) {
        val label = if (requireNpu) "npu" else "gpu"
        val marker = "TMNMARK7Q4Z"
        val dispatch = File(NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, Build.SOC_MODEL.orEmpty(), ctx.noBackupFilesDir))
        val hashedBefore = hashed(listOf(ctx.cacheDir, dispatch))
        val before = snapshot()
        val t0 = System.currentTimeMillis()
        val engine = factory().open(file.path, if (requireNpu) 4096 else 8192, requireNpu = requireNpu)
        log("SUMMARY gate $label load ${System.currentTimeMillis() - t0}ms backend=${engine.backend}")
        var echoed = false; val kept = mutableListOf<Boolean>()
        try {
            val history = mutableListOf<Pair<ai.teemoon.inference.LocalRole, String>>()
            for (i in 1..3) {
                val user = "Remember the code word $marker-$i and repeat it back in one short sentence."
                val reply = turn(engine, history, user)
                if (i == 1) engine.benchmark()?.let { b -> log("SUMMARY gate $label turn 1 first ${"%.2f".format(b.timeToFirstTokenSeconds)}s · prefill ${b.prefillTokensPerSecond.toInt()}tok/s · decode ${b.decodeTokensPerSecond.toInt()}tok/s") }
                log("SUMMARY gate $label turn $i backend=${engine.backend}: reply ${reply.length} chars, marker echoed ${reply.contains(marker)}, kept ${engine.holdsConversation}")
                echoed = echoed || reply.contains(marker); kept += engine.holdsConversation
                history += ai.teemoon.inference.LocalRole.USER to user; history += ai.teemoon.inference.LocalRole.MODEL to reply
            }
        } finally { engine.close() }
        val hashedAfter = hashed(listOf(ctx.cacheDir, dispatch))
        for (path in (hashedBefore.keys + hashedAfter.keys).sorted()) {
            val b = hashedBefore[path]; val a = hashedAfter[path]
            log("SUMMARY gate $label runtime file $path: before ${b ?: "absent"} · after ${a ?: "absent"} · ${if (a == b) "unchanged" else "CHANGED"}")
        }
        val after = snapshot()
        val changed = after.filter { (path, sig) -> before[path] != sig }
        log("SUMMARY gate $label files new or changed under the app's roots: " + changed.entries.joinToString(", ") { "${it.key} ${it.value.first}B" }.ifEmpty { "none" })
        val holders = roots().flatMap { r -> r.walkTopDown().filter { it.isFile && it.length() < 400_000_000L }
            .filter { f -> runCatching { containsMarker(f, marker) }.getOrDefault(false) }.map { "${r.name}/${it.relativeTo(r).path}" }.toList() }
        log("SUMMARY gate $label files holding the marker: ${holders.ifEmpty { listOf("none") }.joinToString(", ")}")
        // The gate's verdict, not only its log line: a run that found the marker passed green until 2026-10-21 (the audit, via release).
        org.junit.Assert.assertTrue("chat text left on disk by the $label runtime: $holders", holders.isEmpty())
        // Files of 400 MB and over are too big to search, so they must not change at all (size and mtime) — the bundles
        // above all. The one exception is the GPU runtime's weight cache (`*_mldrift_weight_cache.bin`, 782 MB), and only
        // on a first load: absent before, created now. One that existed before must come through unchanged.
        val big = (before.keys + after.keys).filter { (after[it] ?: before[it])!!.first >= 400_000_000L }
        val bigChanged = big.filter { before[it] != after[it] && !(it.endsWith("_mldrift_weight_cache.bin") && before[it] == null) }
        org.junit.Assert.assertTrue("files of 400 MB and over changed by the $label runtime: $bigChanged", bigChanged.isEmpty())
        // Asserted last, so a failing run still logs the files above. A marker the model never wrote back proves less:
        // the search would only have the prompts to find. And the gate is about the KEPT conversation: a turn that
        // rebuilt it tested a different path.
        org.junit.Assert.assertTrue("the $label model never echoed the marker", echoed)
        org.junit.Assert.assertEquals("the $label conversation kept on every turn", listOf(true, true, true), kept)
    }

    /** `size B · mtime · sha256` of every file under [dirs] (symlinks followed: the Qualcomm dispatch directory is links). */
    private fun hashed(dirs: List<File>): Map<String, String> = dirs.distinct().flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }.associate { f ->
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        f.path to "${f.length()}B · ${f.lastModified()} · ${md.digest().joinToString("") { "%02x".format(it) }.take(16)}"
    }

    /** The NPU half of the gate (Pixel 10 Pro): the chip's NPU bundle, skipped where the catalogue has none. */
    @Test fun npuDiskGate() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val bundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        if (bundle == null) { log("SUMMARY gate npu: skipped (no NPU bundle for ${Build.SOC_MODEL})"); scenario.close(); return@runBlocking }
        val file = BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, storage.npuFile(gemma, bundle), ::log)
        diskGate(file, requireNpu = true)
        scenario.close()
    }

    /** The roots the app or the runtime can write: the data directory (cache, files, no-backup) and the external cache. */
    private fun roots(): List<File> = listOfNotNull(ctx.dataDir, ctx.externalCacheDir)

    /** Every file under [roots]: size and modification time. */
    private fun snapshot(): Map<String, Pair<Long, Long>> = roots().flatMap { r -> r.walkTopDown().filter { it.isFile }.map { r to it } }
        .associate { (r, f) -> "${r.name}/${f.relativeTo(r).path}" to (f.length() to f.lastModified()) }

    /** The marker in UTF-8 or UTF-16LE anywhere in [f], read in overlapping 1 MB windows. */
    private fun containsMarker(f: File, marker: String): Boolean {
        val needles = listOf(marker.toByteArray(Charsets.UTF_8), marker.toByteArray(Charsets.UTF_16LE))
        val overlap = needles.maxOf { it.size }
        f.inputStream().buffered().use { input ->
            var carry = ByteArray(0); val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf); if (n <= 0) return false
                val window = carry + buf.copyOf(n)
                if (needles.any { indexOf(window, it) >= 0 }) return true
                carry = window.copyOfRange(maxOf(0, window.size - overlap), window.size)
            }
        }
    }
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) { for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer; return i }
        return -1
    }

    private suspend fun turn(engine: ai.teemoon.inference.LocalEngineHandle, history: List<Pair<ai.teemoon.inference.LocalRole, String>>, prompt: String): String {
        val text = StringBuilder()
        engine.stream(LocalConversationSpec(null, history, emptyList(), prompt, 64, LocalDefaults.ENABLE_THINKING, LocalDefaults.TEMPERATURE)) { text.append(it) }
        return text.toString()
    }

    @Test fun deltaCadence() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val engine = factory().open(file.path, 8192, requireNpu = false)
        try {
            val stamps = mutableListOf<Pair<Long, Int>>()
            engine.stream(LocalConversationSpec(null, emptyList(), emptyList(), "Explain how a lighthouse lens works, in about 250 words of plain prose.", 512,
                LocalDefaults.ENABLE_THINKING, LocalDefaults.TEMPERATURE)) { stamps += System.nanoTime() to it.length }
            val gaps = stamps.zipWithNext { a, b -> (b.first - a.first) / 1e6 }.sorted(); val chars = stamps.map { it.second.toDouble() }.sorted()
            fun List<Double>.q(p: Int) = if (isEmpty()) 0.0 else this[(size * p / 100).coerceAtMost(size - 1)]
            log("SUMMARY cadence backend=${engine.backend}: ${stamps.size} deltas, ${stamps.sumOf { it.second }} chars · gap P50 ${"%.0f".format(gaps.q(50))} / P90 ${"%.0f".format(gaps.q(90))} / max ${"%.0f".format(gaps.lastOrNull() ?: 0.0)} ms · " +
                "chars/delta P50 ${"%.0f".format(chars.q(50))} / P90 ${"%.0f".format(chars.q(90))} / max ${"%.0f".format(chars.lastOrNull() ?: 0.0)}")
        } finally { engine.close() }
        scenario.close()
    }

    /**
     * iOS parity (release, 2026-10-19): only a KEPT conversation outlives its turn. A completed plain turn is kept; a
     * thinking turn and a stopped turn free their conversation (and its partial KV) when they end, and the developer
     * card's numbers survive the close.
     */
    @Test fun aTurnThatIsNotKeptLeavesNoConversation() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val engine = factory().open(file.path, 8192, requireNpu = false)
        try {
            fun spec(text: String, thinking: Boolean, out: Int = 32) = LocalConversationSpec(null, emptyList(), emptyList(), text, out, thinking, LocalDefaults.TEMPERATURE)
            engine.stream(spec("Say hello in three words.", thinking = false)) {}
            val keptAfterPlain = engine.holdsConversation
            engine.stream(spec("What is 2 + 2?", thinking = true, out = 64)) {}
            val keptAfterThinking = engine.holdsConversation; val statsAfterThinking = engine.benchmark() != null
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                engine.stream(spec("Write a long story about a lighthouse.", thinking = false, out = 512)) { currentCoroutineContext().cancel() }
            }
            job.join()
            val keptAfterStop = engine.holdsConversation
            log("SUMMARY lifecycle backend=${engine.backend}: after a plain turn holds=$keptAfterPlain · after a thinking turn holds=$keptAfterThinking (stats kept $statsAfterThinking) · after a stopped turn holds=$keptAfterStop")
            org.junit.Assert.assertTrue("a completed plain turn is kept", keptAfterPlain)
            org.junit.Assert.assertFalse("a thinking turn is not kept", keptAfterThinking)
            org.junit.Assert.assertTrue("its numbers survive the close", statsAfterThinking)
            org.junit.Assert.assertFalse("a stopped turn is not kept", keptAfterStop)
        } finally { engine.close() }
        scenario.close()
    }

    /** iOS fc09a91 (LiteRTReuseLiveTests.deletingAChatDropsTheKeptConversation): after a delete the next turn rebuilds. */
    @Test fun deletingAChatDropsTheKeptConversation() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        deleteDrop(file, requireNpu = false)
        scenario.close()
    }

    /** The same on the chip's NPU bundle (the maintainer, 2026-10-21, via pm: keep and delete-clears on the NPU), skipped without one. */
    @Test fun npuDeletingAChatDropsTheKeptConversation() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val bundle = LocalModelCatalog.npuBundle(gemma, Build.SOC_MODEL.orEmpty())
        if (bundle == null) { log("SUMMARY delete drop npu: skipped (no NPU bundle for ${Build.SOC_MODEL})"); scenario.close(); return@runBlocking }
        val file = BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, storage.npuFile(gemma, bundle), ::log)
        deleteDrop(file, requireNpu = true)
        scenario.close()
    }

    private suspend fun deleteDrop(file: File, requireNpu: Boolean) {
        val engine = factory().open(file.path, if (requireNpu) 4096 else 8192, requireNpu = requireNpu)
        try {
            val first = "Name a colour."
            val a = turn(engine, emptyList(), first)
            org.junit.Assert.assertTrue("a plain turn is kept", engine.holdsConversation)
            engine.dropKept()   // what LocalEngineCache.forgetKeptConversations does to every handle on a delete
            org.junit.Assert.assertFalse("the conversation is gone", engine.holdsConversation)
            turn(engine, listOf(ai.teemoon.inference.LocalRole.USER to first, ai.teemoon.inference.LocalRole.MODEL to a), "Another one.")
            log("SUMMARY delete drop ${if (requireNpu) "npu" else "gpu"} backend=${engine.backend}: after the delete the next turn ${engine.lastConversation}")
            org.junit.Assert.assertEquals("the same history would have been kept", "rebuilt", engine.lastConversation)
        } finally { engine.close() }
    }

    @Test fun sustainedTurns() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        val power = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val engine = factory().open(file.path, 8192, requireNpu = false)
        try {
            for (i in 1..10) {
                val t0 = System.currentTimeMillis()
                val reply = runCatching { turn(engine, longPrompt, 256) }
                val b = engine.benchmark()
                log("SUMMARY sustained turn $i backend=${engine.backend} ${System.currentTimeMillis() - t0}ms · prefill ${b?.prefillTokensPerSecond?.toInt()}tok/s · decode ${b?.decodeTokens}tok " +
                    "${b?.decodeTokensPerSecond?.toInt()}tok/s · thermal ${power.currentThermalStatus} headroom ${"%.2f".format(power.getThermalHeadroom(0))} · ${memory()}" +
                    (reply.exceptionOrNull()?.let { " · failed: ${it.message?.take(80)}" } ?: ""))
            }
        } finally { engine.close() }
        scenario.close()
    }

    @Test fun gpuBundleVsGeneric() = runBlocking {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT}")
        val scenario = launch()
        val only = InstrumentationRegistry.getArguments().getString("bench_only")
        val generic = if (only == "gpu") storage.file(gemma) else BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        // Pinned by its LFS oid from the Hub's tree listing (2026-10-19); `main`, since the app does not ship it.
        val gpu = if (only == "generic") File("/nonexistent") else BundleFetch.fetch(gemma.id, "main", "gemma-4-E2B-it-gpu.litertlm", "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
            File(storage.file(gemma).parentFile, "gemma-4-E2B-it-gpu.litertlm"), ::log)
        val prompts = listOf(longPrompt, "What is the capital of Australia? One sentence.", "Write a haiku about rain.")
        // -e bench_only gpu|generic: one bundle per process. The -gpu bundle crashed the PowerVR OpenCL driver (SIGSEGV in
        // OCL_UpdateImageCacheFromHost) loading right after the generic one closed in the same process (Pixel 10 Pro, 2026-10-19).
        for ((label, file) in listOf("generic" to generic, "gpu" to gpu).filter { only == null || it.first == only }) {
            System.gc()
            val t0 = System.currentTimeMillis()
            val engine = factory().open(file.path, 8192, requireNpu = false)
            val loadMs = System.currentTimeMillis() - t0
            try {
                log("SUMMARY $label file ${file.length() / 1_000_000}MB backend=${engine.backend} load ${loadMs}ms · ${memory()}")
                prompts.forEachIndexed { i, p ->
                    val reply = runCatching { turn(engine, p, 128) }
                    val b = engine.benchmark()
                    log("SUMMARY $label prompt ${i + 1}: first ${b?.timeToFirstTokenSeconds?.let { "%.2f".format(it) }}s · prefill ${b?.prefillTokens}tok ${b?.prefillTokensPerSecond?.toInt()}tok/s · " +
                        "decode ${b?.decodeTokens}tok ${b?.decodeTokensPerSecond?.toInt()}tok/s · reply: ${(reply.getOrNull() ?: "failed: ${reply.exceptionOrNull()?.message}").replace("\n", " ").take(140)}")
                }
            } finally { engine.close() }
        }
        scenario.close()
    }
}
