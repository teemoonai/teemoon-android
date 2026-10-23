package ai.teemoon.app

// android-only: no Swift counterpart. The 1.0.6 phase baseline ON DEVICE: the developer card's phase line for Gemma
// E2B through the app's own path — LocalEngineCache, LiteRTTransport, GenerationEngine, the keyless web tool (it
// answers locally; nothing is sent anywhere) — on each backend the phone has: the chip's NPU bundle, then the general
// bundle (GPU, then CPU, as the app tries them). Two short prompts, once each: a baseline, not a sweep. Reported
// through sendStatus (a farm's logcat reader drops), not asserted.
//
//   -e probe_backends npu,general   which bundles (default both)
//   -e probe_fetch false             skip downloading a missing bundle

import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.inference.GenerationEngine
import ai.teemoon.inference.LiteRTTransport
import ai.teemoon.inference.LocalDefaults
import ai.teemoon.inference.LocalEngineCache
import ai.teemoon.inference.RequestResult
import ai.teemoon.inference.StreamCallbacks
import ai.teemoon.inference.TurnPhase
import ai.teemoon.inference.WireMessage
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseBaselineDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun log(s: String) {
        Log.w("teemoon", "[baseline] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("baseline", s) })
    }

    @Test fun phasesPerBackend() = runBlocking {
        val soc = Build.SOC_MODEL.orEmpty()
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=$soc sdk=${Build.VERSION.SDK_INT}")
        val wanted = (args.getString("probe_backends") ?: "npu,general").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val fetch = args.getString("probe_fetch") != "false"
        val gemma = LocalModelCatalog.all.first()
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val nativeDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log)
        val factory = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log, nativeLibraryDir = nativeDir, socModel = soc, hardware = Build.HARDWARE.orEmpty())
        // The Tensor G6 refuses NPU work from a process not in the foreground (NpuBundleProbeTest).
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario.moveToState(Lifecycle.State.RESUMED)
        val system = ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT)
        var ran = 0
        for (b in wanted) {
            val npu = b == "npu"
            val bundle = if (npu) LocalModelCatalog.npuBundle(gemma, soc) else null
            if (npu && bundle == null) { log("npu: skipped (no bundle for $soc)"); continue }
            val file = if (bundle != null) storage.npuFile(gemma, bundle).takeIf { it.isFile || fetch }
                ?.let { BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, it, ::log) }
            else storage.file(gemma).takeIf { it.isFile || fetch }
                ?.let { BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, it, ::log) }
            if (file == null) { log("$b: skipped (not fetched)"); continue }
            val context = bundle?.maxTokens ?: LiteRTTransport.DEFAULT_CONTEXT_TOKENS
            val cache = LocalEngineCache(factory, log = ::log)
            try {
                // The load is the app's warm-up, not part of a send: take it out of the first prompt's numbers.
                val loadStart = System.currentTimeMillis()
                cache.engine(file.path, context, (file.length() / 1_000_000).toInt(), 0, npu)
                log("$b: loaded in ${System.currentTimeMillis() - loadStart} ms")
                for (prompt in listOf("What is the capital of France? One sentence.", "What's the weather in Tokyo right now?")) {
                    var last: RequestResult? = null
                    val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { last = result } }
                    val transport = LiteRTTransport(file.path, (file.length() / 1_000_000).toInt(), contextTokens = context, cache = cache, events = events,
                        headroomMB = 0, backendLabel = factory.backendLabel, requireNpu = npu, log = ::log)
                    val tool = BraveWebSearchTool(apiKey = "", onDevice = true, contextTokens = context)
                    val start = Instant.now()
                    val outcome = runCatching {
                        GenerationEngine(transport, listOf(tool), events, maxToolRounds = LocalDefaults.MAX_TOOL_ROUNDS).run(listOf(WireMessage("system", system)), prompt)
                    }
                    val stats = last?.localStats?.let { " · ${it.backend} prefill ${it.prefillTokens}tok ${it.prefillTokensPerSecond.toInt()}tok/s" } ?: ""
                    log("SUMMARY $b \"${prompt.take(30)}\": ${TurnPhase.line(start, last?.phases.orEmpty())}$stats · " +
                        (outcome.exceptionOrNull()?.let { "failed: ${it.message?.take(160)}" } ?: "${outcome.getOrNull()?.length} chars"))
                }
            } finally { cache.evictAll() }
            ran += 1
        }
        scenario.close()
        assertTrue("no backend ran", ran > 0)
    }

    /**
     * ConversationReuse, before and after: a three-turn chat with the kept Conversation off, then on, per backend. The
     * system prompt is resolved ONCE so the {{datetime}} minute cannot turn a reuse into a rebuild mid-run. A turn that
     * searches is not kept (its KV holds the call) — the summary line says which turns reused.
     */
    @Test fun threeTurnChatReuse() = runBlocking {
        val soc = Build.SOC_MODEL.orEmpty()
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=$soc sdk=${Build.VERSION.SDK_INT}")
        val wanted = (args.getString("probe_backends") ?: "npu,general").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val fetch = args.getString("probe_fetch") != "false"
        val gemma = LocalModelCatalog.all.first()
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val nativeDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario.moveToState(Lifecycle.State.RESUMED)
        val system = ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT)
        val prompts = listOf("Name one fruit. One word.", "What colour is it usually? One sentence.", "Give one dish that uses it. One sentence.")
        var ran = 0
        for (b in wanted) {
            val npu = b == "npu"
            val bundle = if (npu) LocalModelCatalog.npuBundle(gemma, soc) else null
            if (npu && bundle == null) { log("npu: skipped (no bundle for $soc)"); continue }
            val file = if (bundle != null) storage.npuFile(gemma, bundle).takeIf { it.isFile || fetch }
                ?.let { BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, it, ::log) }
            else storage.file(gemma).takeIf { it.isFile || fetch }
                ?.let { BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, it, ::log) }
            if (file == null) { log("$b: skipped (not fetched)"); continue }
            val context = bundle?.maxTokens ?: LiteRTTransport.DEFAULT_CONTEXT_TOKENS
            for (reuse in listOf(false, true)) {
                val reusedTurns = ArrayList<Int>()
                var turnNo = 0
                val factory = LiteRtLmEngineFactory(ctx.cacheDir.path, { line -> if (line.contains("conversation: reused")) reusedTurns += turnNo; log(line) },
                    nativeLibraryDir = nativeDir, socModel = soc, hardware = Build.HARDWARE.orEmpty(), reuseConversations = reuse)
                val cache = LocalEngineCache(factory, log = ::log)
                try {
                    cache.engine(file.path, context, (file.length() / 1_000_000).toInt(), 0, npu)
                    val history = ArrayList<WireMessage>().apply { add(WireMessage("system", system)) }
                    for (prompt in prompts) {
                        turnNo += 1
                        var last: RequestResult? = null
                        val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { last = result } }
                        val transport = LiteRTTransport(file.path, (file.length() / 1_000_000).toInt(), contextTokens = context, cache = cache, events = events,
                            headroomMB = 0, backendLabel = factory.backendLabel, requireNpu = npu, log = ::log)
                        val start = Instant.now()
                        val answer = runCatching {
                            GenerationEngine(transport, listOf(BraveWebSearchTool(apiKey = "", onDevice = true, contextTokens = context)), events,
                                maxToolRounds = LocalDefaults.MAX_TOOL_ROUNDS).run(history, prompt)
                        }
                        val stats = last?.localStats?.let { " · prefill ${it.prefillTokens}tok" } ?: ""
                        log("SUMMARY $b reuse=$reuse turn $turnNo${if (turnNo in reusedTurns) " (reused)" else ""}: ${TurnPhase.line(start, last?.phases.orEmpty())}$stats · " +
                            (answer.exceptionOrNull()?.let { "failed: ${it.message?.take(120)}" } ?: "${answer.getOrNull()?.length} chars"))
                        history += WireMessage("user", prompt)
                        history += WireMessage("assistant", answer.getOrNull().orEmpty())
                    }
                } finally { cache.evictAll() }
            }
            ran += 1
        }
        scenario.close()
        assertTrue("no backend ran", ran > 0)
    }
}
