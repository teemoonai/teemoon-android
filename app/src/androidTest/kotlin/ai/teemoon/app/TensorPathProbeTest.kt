// android-only DIAGNOSTIC, not a gate: two questions about the Tensor NPU that litertlm-android 0.18.0 reopened.
//   1. Does speculative decoding still lose on it? 0.16.1 measured 16 → 14 tok/s (BackendLadder); 0.18.0 changed the
//      NPU's KV cache (on-demand growth), so the loss is re-measured, interleaved off/on with repeats.
//   2. Does `Backend.GOOGLE_TENSOR` start now? 0.16.1 and 0.17.0 rejected it: "Not recognized backend: 5".
// Logs only; skips where this chip has no NPU bundle installed.
//   adb shell am instrument -w -e class ai.teemoon.app.TensorPathProbeTest <pkg>.test/ai.teemoon.app.TeemoonTestRunner
package ai.teemoon.app

import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.os.Build
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
import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class TensorPathProbeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(line: String) = Log.w("teemoon", "[tensor] $line")
    private val soc = Build.SOC_MODEL.orEmpty()
    private val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
    private val gemma = LocalModelCatalog.all.first()

    private fun npuPath(): String? = LocalModelCatalog.npuBundle(gemma, soc)?.let { storage.npuFile(gemma, it) }?.takeIf { it.isFile }?.path
    private fun generalPath(): String? = storage.file(gemma).takeIf { it.isFile }?.path

    /** One raw turn on a fresh engine; returns decode tok/s, or null when it failed (logged). */
    private fun turn(label: String, path: String, backend: Backend, prompt: String, maxOut: Int): Double? = runCatching {
        ExperimentalFlags.enableBenchmark = true
        val engine = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = BenchSpec.MAX_TOKENS, cacheDir = ctx.cacheDir.path))
        try {
            val t0 = System.currentTimeMillis(); engine.initialize(); val load = System.currentTimeMillis() - t0
            val conv = engine.createConversation(ConversationConfig(maxOutputToken = maxOut, thinkingConfig = ThinkingConfig(false)))
            try {
                val text = conv.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val b = conv.getBenchmarkInfo()
                log("$label load=${load}ms ttft=${"%.2f".format(b.timeToFirstTokenInSecond)}s prefill=${b.lastPrefillTokenCount}tok " +
                    "(${b.lastPrefillTokensPerSecond.toInt()}tok/s) decode=${b.lastDecodeTokenCount}tok (${"%.1f".format(b.lastDecodeTokensPerSecond)}tok/s) " +
                    "text: ${text.take(80).replace('\n', ' ')}")
                b.lastDecodeTokensPerSecond
            } finally { runCatching { conv.close() } }
        } finally { runCatching { engine.close() } }
    }.onFailure { log("$label FAILED: ${it.javaClass.simpleName}: ${it.message?.take(300)}") }.getOrNull()

    @Test fun speculativeDecodingOnTheNpuOffAndOnInterleaved() {
        val path = npuPath(); assumeTrue("no NPU bundle installed for $soc", path != null)
        ModelInfo.from(path!!).use { info ->
            log("bundle ${File(path).name}: drafter=${(info as? LlmCapability)?.hasSpeculativeDecodingSupport()} " +
                "backends=${info.supportedBackends(Modality.TEXT)} npu=${info.npuBrand(Modality.TEXT)} soc=${info.socName(Modality.TEXT)} " +
                "context=${info.maxContextTokens()} dynamic=${info.isDynamicContext()}")
        }
        val npu = Backend.NPU(NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log))
        val results = mapOf(false to ArrayList<Double>(), true to ArrayList())
        turn("warm-up", path, npu, BenchSpec.SHORT_PROMPT, BenchSpec.SHORT_MAX_OUTPUT)
        repeat(3) { i ->
            for (spec in listOf(false, true)) {
                ExperimentalFlags.enableSpeculativeDecoding = spec
                turn("npu speculative=$spec run=${i + 1}", path, npu, BenchSpec.SHORT_PROMPT, BenchSpec.SHORT_MAX_OUTPUT)?.let { results.getValue(spec) += it }
            }
        }
        ExperimentalFlags.enableSpeculativeDecoding = null
        for ((spec, r) in results) log("SUMMARY npu speculative=$spec decode tok/s: ${r.map { "%.1f".format(it) }} median=${r.sorted().getOrNull(r.size / 2)?.let { "%.1f".format(it) }}")
    }

    @Test fun theGoogleTensorBackendOnBothBundles() {
        assumeTrue("not a Tensor phone", soc.lowercase().startsWith("tensor"))
        ExperimentalFlags.enableSpeculativeDecoding = false
        npuPath()?.let { turn("GOOGLE_TENSOR on the NPU bundle", it, Backend.GOOGLE_TENSOR(), BenchSpec.SHORT_PROMPT, BenchSpec.SHORT_MAX_OUTPUT) }
        generalPath()?.let { turn("GOOGLE_TENSOR on the general bundle", it, Backend.GOOGLE_TENSOR(), BenchSpec.SHORT_PROMPT, BenchSpec.SHORT_MAX_OUTPUT) }
        ExperimentalFlags.enableSpeculativeDecoding = null
    }
}
