package ai.teemoon.app

// android-only: G2's process-kill half (docs/USE_CASES.md). A test cannot outlive the process it kills, so this is two
// runs with the kill between them — tools/download_kill_resume.sh: `-e g2 start` begins a phone-model download from a
// slow fake host and leaves it mid-transfer; the script kills the app's process (what the system does to a backgrounded
// app it needs the memory of); `-e g2 resume` launches again, finds the partial the kill left, taps the model again and
// asserts the host was asked for `bytes=<partial>-` and the file that lands is whole (its checksum pinned for the run,
// and the served bytes vary by offset, so a resume at the wrong place fails it). DownloadService is START_NOT_STICKY on
// purpose: a killed transfer is not restarted behind the user's back; the partial waits for their tap.

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelDownloader
import android.util.Log

@RunWith(AndroidJUnit4::class)
class DownloadKillResumeTest {
    @get:Rule val compose = emptyComposeRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val size = 2 * 1024 * 1024   // ~13 s at the fake host's 160 KB/s: long enough to kill mid-way

    private fun tap(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(text, substring = true, ignoreCase = true))[0].apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }
    private fun tapTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag(tag))[0].performClick(); compose.waitForIdle()
    }

    @Test fun aDownloadKilledMidwayResumesFromItsPartial() {
        val phase = InstrumentationRegistry.getArguments().getString("g2")
        assumeTrue("run by tools/download_kill_resume.sh (-e g2 start|resume)", phase == "start" || phase == "resume")
        val server = FakeSseServer(rangedBytes = size)
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        LocalModelDownloader.shaOverride = mapOf(e4b.id to FakeSseServer.rangedSha(size))
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(UiTestSeeding.EXTRA_FLAG, true).putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            .putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl).putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            .putExtra(UiTestSeeding.EXTRA_MODEL_HOST, server.rootUrl).putExtra(UiTestSeeding.EXTRA_MOBILE_DATA, false)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        val app = (ctx.applicationContext as TeemoonApplication)
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() }
        val dir = app.state!!.localStorage.file(e4b).parentFile!!
        fun partial(): File? = dir.listFiles()?.firstOrNull { it.name.endsWith(".part") }
        if (phase == "start") {
            // a clean slate: no partial or weights from an earlier run
            ins.runOnMainSync { app.state!!.deleteWeights(e4b) }
            tapTag("chat.whereChip"); tap("phone"); tap(e4b.displayName.lowercase())
            compose.waitUntil(30_000) { (partial()?.length() ?: 0L) > size / 4 }
            // leave it running: the script kills the process from outside, mid-transfer
            Log.i("SMOKE", "[g2] killing at ${partial()?.length()} of $size")
            return
        }
        try {
            val left = partial()
            assertTrue("the kill left no partial to resume from (${dir.list()?.toList()})", left != null && left.length() in 1 until size)
            val have = left!!.length()
            tapTag("chat.whereChip"); tap("phone"); tap(e4b.displayName.lowercase())
            compose.waitUntil(60_000) { partial() == null && dir.listFiles()?.any { it.isFile && it.length() == size.toLong() } == true }
            assertEquals("the relaunch started over instead of resuming", listOf("bytes=$have-"), server.modelRanges.toList())
            compose.waitUntil(10_000) { app.state!!.providers.value.any { it.localModelID == e4b.id && it.id == app.state!!.selectedId.value } }
        } finally {
            ins.runOnMainSync { app.state?.localDownloads?.cancel(e4b.id); app.state?.network?.uiTestOverrideMobileData(null); app.state?.deleteWeights(e4b) }
            LocalModelDownloader.hostOverride = null
            LocalModelDownloader.shaOverride = emptyMap()
            server.close(); scenario.close()
        }
    }
}
