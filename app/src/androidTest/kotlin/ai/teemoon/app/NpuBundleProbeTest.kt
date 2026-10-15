package ai.teemoon.app

// android-only: no Swift counterpart. A probe for a chip we cannot hold: it runs where the farm has the phone
// (Firebase Test Lab's Pixel 11 for Tensor G6, 2026-10-14), fetches THIS chip's NPU bundle itself, and proves the
// app's own NPU path answers on it. Opt-in (the suites skip *Probe*); it downloads ~3 GB.

import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class NpuBundleProbeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) { Log.w("teemoon", "[probe] $s") }

    @Test fun thisChipsNpuBundleDownloadsVerifiesAndAnswersOnTheNpu() = runBlocking {
        val soc = Build.SOC_MODEL.orEmpty()
        log("device ${Build.MODEL} soc=$soc hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT}")
        val gemma = LocalModelCatalog.all.first()
        val bundle = LocalModelCatalog.npuBundle(gemma, soc)
        assumeTrue("no NPU bundle for $soc", bundle != null)
        val file = LocalModelStorage(File(ctx.filesDir, "LocalModels")).npuFile(gemma, bundle!!)

        BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, file, ::log)

        // Tensor G6 refuses NPU work from an app that is not in the foreground ("NPU schedule adjuster denied the
        // permission to run the inference", Pixel 11, 2026-10-14) — a bare instrumentation process counts as background.
        // So the app's own screen is up for the NPU runs, as it is when a user chats.
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario.moveToState(Lifecycle.State.RESUMED)
        log("app in the foreground")

        // The APP's path: NpuDispatch picks the directory, the factory opens the NPU-only bundle and proves it decodes.
        val npuDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log)
        log("npu dispatch directory: $npuDir")
        val factory = LiteRtLmEngineFactory(ctx.cacheDir.path, ::log, nativeLibraryDir = npuDir, socModel = soc, hardware = Build.HARDWARE.orEmpty())
        val t0 = System.currentTimeMillis()
        factory.open(file.path, bundle.maxTokens, requireNpu = true).close()
        log("app factory opened the bundle on ${factory.backendLabel} in ${System.currentTimeMillis() - t0} ms")
        assertEquals("npu", factory.backendLabel)

        // Numbers, raw runtime, exactly as BenchSpec defines them for every chip.
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = BenchSpec.SPECULATIVE
        for ((label, prompt, out) in listOf(Triple("short", BenchSpec.SHORT_PROMPT, BenchSpec.SHORT_MAX_OUTPUT),
                                            Triple("long", BenchSpec.LONG_PROMPT, BenchSpec.LONG_MAX_OUTPUT))) {
            val engine = Engine(EngineConfig(modelPath = file.path, backend = Backend.NPU(npuDir), maxNumTokens = BenchSpec.MAX_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                val l0 = System.currentTimeMillis(); engine.initialize(); val load = System.currentTimeMillis() - l0
                val conv = engine.createConversation(ConversationConfig(maxOutputToken = out, thinkingConfig = ThinkingConfig(false)))
                try {
                    val reply = conv.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val b = conv.getBenchmarkInfo()
                    log("$label load=${load}ms ttft=${"%.2f".format(b.timeToFirstTokenInSecond)}s prefill=${b.lastPrefillTokenCount}tok (${b.lastPrefillTokensPerSecond.toInt()}tok/s) decode=${b.lastDecodeTokenCount}tok (${b.lastDecodeTokensPerSecond.toInt()}tok/s)")
                    log("$label text: " + reply.take(200).replace('\n', ' '))
                    assertTrue("the NPU answered nothing", reply.isNotBlank())
                } finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }

        // The same short run with the app sent to the background: does this chip still let it use the NPU? A turn the user
        // leaves the app during (TurnService) depends on the answer. Reported, not asserted.
        scenario.moveToState(Lifecycle.State.CREATED)
        log("app in the background")
        val background = runCatching {
            val engine = Engine(EngineConfig(modelPath = file.path, backend = Backend.NPU(npuDir), maxNumTokens = BenchSpec.MAX_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                engine.initialize()
                val conv = engine.createConversation(ConversationConfig(maxOutputToken = BenchSpec.SHORT_MAX_OUTPUT, thinkingConfig = ThinkingConfig(false)))
                try { conv.sendMessage(BenchSpec.SHORT_PROMPT).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
                finally { runCatching { conv.close() } }
            } finally { runCatching { engine.close() } }
        }
        log("background npu: " + background.fold({ "answered (${it.length} chars)" }, { "FAILED: ${it.message?.take(200)}" }))
        scenario.close()
    }
}
