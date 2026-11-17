package ai.teemoon.app

// android-only: no Swift counterpart. The load gate at LAUNCH on a real 6–8 GB phone (pm 2026-10-30): availMem read
// 2645 MB on a Pixel 8a right after the process started and 3399 MB a moment later (Test Lab, OnDeviceVisionBenchTest),
// under the old gate's 2980 MB need. LoadMemoryGate now asks the file size alone at or above the RAM floor and looks
// twice; this shows whether the app's own warm-up — MainActivity.onStart / AppState.select — loads in a FRESH process.
// Run with Test Lab's orchestrator (`--use-orchestrator`), so the second test starts a new process after the first one
// staged the weights: in one process the download would have warmed memory up and proved nothing.

import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LaunchWarmUpGateTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val gemma = LocalModelCatalog.all.first()
    private val storage get() = LocalModelStorage(File(ctx.filesDir, "LocalModels"))

    private fun log(s: String) {
        Log.w("teemoon", "[launchgate] $s")
        ins.sendStatus(2, android.os.Bundle().apply { putString("launchgate", s) })
    }

    private fun sysMem(): String {
        val m = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val mb = { b: Long -> b / (1024 * 1024) }
        return "total ${mb(m.totalMem)}MB (${ai.teemoon.providers.LocalMemory.marketedRAMGB(m.totalMem)} GB) avail ${mb(m.availMem)}MB threshold ${mb(m.threshold)}MB"
    }

    /** First, in its own process: the general bundle where the app looks for it. */
    @Test fun a_stageTheWeights() {
        log("device ${Build.MANUFACTURER} ${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} · ${sysMem()}")
        val file = BundleFetch.fetch(gemma.id, gemma.revision, gemma.fileName, gemma.sha256, storage.file(gemma), ::log)
        log("SUMMARY staged ${file.name} ${file.length() / (1024 * 1024)}MB · ${sysMem()}")
    }

    /** Then, in a fresh process: launch onto the phone's model and watch the app's own warm-up load it, or decline. */
    @Test fun b_theLaunchWarmUpLoadsInAFreshProcess() {
        assumeTrue("no staged weights (run a_stageTheWeights first)", storage.file(gemma).isFile)
        val app = ctx.applicationContext as TeemoonApplication
        log("SUMMARY process start · uptime ${android.os.SystemClock.uptimeMillis() / 1000}s · ${sysMem()}")
        val started = System.currentTimeMillis()
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_ON_DEVICE, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
        }).use {
            var loadedAt = -1L; var minAvail = Long.MAX_VALUE
            val deadline = started + 300_000
            while (System.currentTimeMillis() < deadline) {
                val elapsed = System.currentTimeMillis() - started
                val m = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
                minAvail = minOf(minAvail, m.availMem / (1024 * 1024))
                if (elapsed < 10_000 || elapsed % 5_000 < 250) log("t+${elapsed}ms avail ${m.availMem / (1024 * 1024)}MB resident ${app.state?.engineCache?.residentCount}")
                if ((app.state?.engineCache?.residentCount ?: 0) > 0) { loadedAt = elapsed; break }
                Thread.sleep(250)
            }
            log("SUMMARY launch warm-up ${if (loadedAt >= 0) "LOADED at t+${loadedAt}ms" else "NOT loaded in 300 s"} · lowest avail ${minAvail}MB · " +
                "gate need ${gemma.sizeMB}MB + headroom ${app.state?.loadHeadroomMB(gemma.id)}MB · ${sysMem()}")
            assertTrue("the launch warm-up did not load the phone's model (see '[litert] warm-up declined' in logcat)", loadedAt >= 0)
        }
    }
}
