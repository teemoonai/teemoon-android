package ai.teemoon.app

// android-only: no Swift counterpart. The model card's method, so a number here can sit beside Google's published
// ones (BenchSpec.GOOGLE_*). Opt-in like every *Bench*/*Probe* class: it may download ~3 GB per bundle.

import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.benchmark
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The runtime's own `benchmark()` — the entry point the model card's numbers come from — at the card's 1,024 prefill
 * and 256 decode tokens, on every backend this phone has: the chip's NPU bundle on `npu`, the general bundle on `gpu`
 * and `cpu`. One discarded warm-up per backend, then [BenchSpec.REPEATS] rounds with the backends interleaved, so a
 * phone that heats up penalises all of them alike; the thermal status and battery temperature are logged per run.
 *
 * `-e gbench_backends npu,gpu,cpu` picks the backends (default: all three). On a farm phone a backend whose bundle is
 * missing is downloaded first (`-e gbench_fetch false` skips it instead).
 *
 * WHAT IT CANNOT MATCH. `benchmark()` takes no context size: the engine uses the bundle's own (4,096 compiled into the
 * NPU bundles), where the card says 2,048. Speculative decoding is set off explicitly — the card's baseline rows.
 *
 * MEASURED, medians of 3 (litertlm-android 0.18.0), against the card's row for the same phone:
 *
 * | phone | backend | prefill | decode | first token | card: prefill / decode / first token |
 * |---|---|---|---|---|---|
 * | Galaxy S26 Ultra (SM8850, Samsung RTL, 2026-10-14) | gpu | 3,026 | 44.0 (44.0, 44.0, 37.7) | 0.36 s | 3,808 / 52.1 / 0.3 s |
 * | Galaxy S26 Ultra | cpu | 175 | 20.1 (20.1, 20.0, 20.7) | 5.91 s | 557 / 46.9 / 1.8 s |
 * | Galaxy S25 Ultra (SM8750, Android 15, Samsung RTL, 2026-10-14) | npu | 8,661 | 44.1 (43.9, 44.3, 44.1) | 0.14 s | no S25 row; IQ8 NPU 3,747 / 31.7 / 0.3 s |
 * | Galaxy S25 Ultra | gpu | 2,735 | 42.6 (43.1, 42.6, 42.6) | 0.40 s | — |
 * | Galaxy S25 Ultra | cpu | 165 | 19.8 (22.6, 19.8, 19.4) | 6.25 s | — |
 * | Pixel 11 (Tensor G6, Android 17, Test Lab, 2026-10-15) | npu | 3,391 | 34.0 (36.2, 34.0, 31.0) | 0.33 s | no Pixel rows on the card |
 * | Pixel 11 | gpu | 1,345 | 20.1 (20.7, 18.7, 20.1) | 0.81 s | — |
 * | Pixel 11 | cpu | 200 | 21.9 (21.9, 15.3, 23.5) | 5.17 s | — |
 * | Pixel 10 Pro (Tensor G5, Android 16, Test Lab, 2026-10-15) | npu | 2,010 | 17.3 (17.4, 17.3, 17.1) | 0.57 s | — |
 * | Pixel 10 Pro | gpu | 998 | 13.9 (14.0, 11.8, 13.9) | 1.10 s | — |
 * | Pixel 10 Pro | cpu | 179 | 18.7 (18.7, 12.9, 19.3) | 5.77 s | — |
 *
 * The GPU is within ~15% of the card; the app-turn figures quoted before (24–30 tok/s) were not this method. The
 * CPU is less than half the card's on both Galaxies, with the runtime's default thread count — unexplained. Thermal 1 → 2
 * over each run. On the S25 Ultra the NPU's decode only matches the GPU's (44 vs 43): its lead is prefill, 3.2x, and the
 * first token (0.14 s vs 0.40 s) and load (1.1 s vs 7.4 s).
 *
 * On the Pixels the NPU is the fast path, and decode follows: the Tensor G6 decodes 34 tok/s on its NPU against 20 on
 * the GPU, the G5 17 against 14. The Tensor GPUs are a third (G6) to a half (G5) of the Snapdragon GPUs' prefill and
 * decode. The CPU lands at ~20 tok/s on all four phones — the same unexplained gap to the card's 46.9. Both Pixels
 * stayed at thermal 0–1; their CPU's second round dropped to ~13–15 each time.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class GoogleMethodBenchTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    // Also into the instrumentation's own output: a remote lab's tunnel drops a logcat reader, the `am instrument`
    // output file survives (Samsung RTL, 2026-10-14).
    private fun log(s: String) {
        Log.w("teemoon", "[gbench] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("gbench", s) })
    }

    private fun thermal(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val headroom = if (Build.VERSION.SDK_INT >= 30) "%.2f".format(pm.getThermalHeadroom(10)) else "n/a"
        return "thermal=${pm.currentThermalStatus} headroom=$headroom battery=${tenths / 10.0}C"
    }

    @Test fun theModelCardsMethodOnEveryBackend() {
        val soc = Build.SOC_MODEL.orEmpty()
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=$soc hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT} " +
            "method=google prefill=${BenchSpec.GOOGLE_PREFILL_TOKENS} decode=${BenchSpec.GOOGLE_DECODE_TOKENS} repeats=${BenchSpec.REPEATS}")
        val wanted = (args.getString("gbench_backends") ?: "npu,gpu,cpu").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val fetch = args.getString("gbench_fetch") != "false"
        val gemma = LocalModelCatalog.all.first()
        val storage = LocalModelStorage(File(ctx.filesDir, "LocalModels"))
        val npuDir = NpuDispatch.directory(ctx.applicationInfo.nativeLibraryDir, soc, ctx.noBackupFilesDir, ::log)

        // backend label → (bundle file, the Backend to hand the runtime)
        val runs = linkedMapOf<String, Pair<File, Backend>>()
        for (b in wanted) {
            val file = when (b) {
                "npu" -> LocalModelCatalog.npuBundle(gemma, soc)?.let { bundle ->
                    storage.npuFile(gemma, bundle).takeIf { it.isFile || fetch }
                        ?.let { BundleFetch.fetch(gemma.id, bundle.revision, bundle.fileName, bundle.sha256, it, ::log) }
                }
                "gpu", "cpu" -> storage.file(gemma).takeIf { it.isFile || fetch }
                    ?.let { BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, it, ::log) }
                else -> null
            }
            if (file == null) { log("$b: skipped (no bundle for this chip, or not fetched)"); continue }
            runs[b] = file to when (b) { "npu" -> Backend.NPU(npuDir); "gpu" -> Backend.GPU(); else -> Backend.CPU() }
        }
        assertTrue("no backend to measure", runs.isNotEmpty())

        // Tensor G6 refuses NPU work from a process not in the foreground (NpuBundleProbeTest), so the app is up.
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario.moveToState(Lifecycle.State.RESUMED)
        ExperimentalFlags.enableSpeculativeDecoding = BenchSpec.SPECULATIVE

        fun once(label: String, round: String): BenchmarkInfo? {
            val (file, backend) = runs.getValue(label)
            val before = thermal()
            val info = runCatching {
                benchmark(modelPath = file.path, backend = backend, prefillTokens = BenchSpec.GOOGLE_PREFILL_TOKENS,
                          decodeTokens = BenchSpec.GOOGLE_DECODE_TOKENS, cacheDir = ctx.cacheDir.path)
            }.onFailure { log("$label $round FAILED: ${it.message?.take(200)}") }.getOrNull() ?: return null
            log("$label $round init=${"%.2f".format(info.initTimeInSecond)}s ttft=${"%.2f".format(info.timeToFirstTokenInSecond)}s " +
                "prefill=${info.lastPrefillTokenCount}tok (${"%.1f".format(info.lastPrefillTokensPerSecond)}tok/s) " +
                "decode=${info.lastDecodeTokenCount}tok (${"%.1f".format(info.lastDecodeTokensPerSecond)}tok/s) [$before]")
            return info
        }

        for (label in runs.keys) once(label, "warm-up")
        val results = runs.keys.associateWith { mutableListOf<BenchmarkInfo>() }
        repeat(BenchSpec.REPEATS) { i -> for (label in runs.keys) once(label, "run=${i + 1}")?.let { results.getValue(label) += it } }

        fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        for ((label, rs) in results) {
            log("SUMMARY $label ${Build.MODEL} soc=$soc n=${rs.size} method=google " +
                "init=${"%.2f".format(median(rs.map { it.initTimeInSecond }))}s ttft=${"%.2f".format(median(rs.map { it.timeToFirstTokenInSecond }))}s " +
                "prefill=${"%.0f".format(median(rs.map { it.lastPrefillTokensPerSecond }))}tok/s " +
                "decode=${"%.1f".format(median(rs.map { it.lastDecodeTokensPerSecond }))}tok/s " +
                "decode-range=${rs.map { "%.1f".format(it.lastDecodeTokensPerSecond) }}")
        }
        log("after: ${thermal()}")
        scenario.close()
        assertTrue("a backend produced no run", results.values.all { it.isNotEmpty() })
    }
}
