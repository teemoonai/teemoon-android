package ai.teemoon.app

// android-only: THE MOST USED PATH, on a device — a keyed cloud-style place with the Brave llm context tool attached,
// a message that makes the model search, the tool round against a fake Brave, and a reply that carries the source.
// Nothing exercised this before 2026-09-08: the JVM tests inject the fetch, the smoke streams a plain turn, and the
// capture prompts never make the model search — so a fetch running on the main thread failed every real search
// (NetworkOnMainThreadException, reported as "check your network connection") and no test noticed.

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo

@RunWith(AndroidJUnit4::class)
class GroundedTurnTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    @get:Rule(order = 3) val pageSizeDialog = PageSizeDialogRule()
    /** The sources sheet as the user sees it (GroundingSourcesView: inline title, xmark close, inset-grouped rows). */
    @get:Rule(order = 1) val goldens = GoldenRule()
    /** What the user was looking at when it failed: a screenshot under files/journeys and the tree in logcat (JOURNEY). */
    @get:Rule(order = 2) val onFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("JOURNEY") }
            runCatching {
                val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "journeys").apply { mkdirs() }
                File(dir, description.methodName + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun stop() { }   // the scenario is NOT closed here: @After runs before the failure watcher, which needs the screen; the process ends with the class

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * ActivityChipUITests' BLIND WINDOW, offline: a model writes a preamble, calls its tool, and the follow-up is slow.
     * With text on screen the leading chip is gone, so without the TRAILING chip nothing says teemoon is still working —
     * iOS measured 3.0 s of that per round on DeepSeek. The fake server's tool round opens with exactly that preamble;
     * the follow-up is held for 5 s, and the chip must be visible inside the hold, under the preamble.
     */
    @Test fun theActivityChipCoversTheSecondRoundAfterAPreamble() { FakeSseServer(toolRound = true, delayMs = 6, followUpDelayMs = 5_000).use { server ->
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".BRAVE_API_KEY").apply { delete() }.writeText("uitest-brave-key") }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            putExtra(UiTestSeeding.EXTRA_GROUNDING, true)
            putExtra(UiTestSeeding.EXTRA_BRAVE_ENDPOINT, "${server.baseUrl}/llm/context")
        }
        scenario?.close(); scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }
        Thread.sleep(2_500)
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput("what is the weather in paris"); Thread.sleep(300)
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        // The preamble lands, then the call is elided, the search runs, and the follow-up is held.
        compose.waitUntil(20_000) { exists("looking that up.") }
        assertTrue("the follow-up must still be held for this to test anything", !exists("Paragraph 1"))
        compose.waitUntil(3_000) { tagExists("chat.activityChip") }
        assertTrue("text on screen and the model still working: the trailing chip must say so", exists("looking that up.") && tagExists("chat.activityChip"))
        // And it goes when the answer resumes — the growing text is its own progress again.
        compose.waitUntil(30_000) { exists("Paragraph 1") }
        compose.waitUntil(20_000) { !tagExists("chat.stop") }
    } }

    @Test fun aGroundedTurnCarriesItsSource() { FakeSseServer(toolRound = true, delayMs = 6).use { server ->
        // the grounding key the seed reads: any non-empty value — the fake Brave never checks it
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".BRAVE_API_KEY").apply { delete() }.writeText("uitest-brave-key") }   // the seed script leaves a read-only copy of the host file
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            putExtra(UiTestSeeding.EXTRA_GROUNDING, true)
            putExtra(UiTestSeeding.EXTRA_BRAVE_ENDPOINT, "${server.baseUrl}/llm/context")
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }
        Thread.sleep(2_500)   // the seed runs off the main thread
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput("what is the weather in paris"); Thread.sleep(300)
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        // the wire is short, so the first idle-wait resolves after the whole turn: marker → tool → fake Brave → reply
        compose.waitUntil(60_000) { tagExists("chat.sourcesChip") || exists("web_search error") }
        assertTrue("the search round failed: " + describeError(), !exists("web_search error"))
        assertTrue("the reply carries no source", tagExists("chat.sourcesChip"))
        // the sheet: a navigation bar with the inline title and an xmark close, the source as an inset-grouped row
        // the chip shows the instant the turn ends, which is also when the hand-off rebuilds the row: let it settle
        compose.waitUntil(20_000) { !tagExists("chat.stop") }; Thread.sleep(1_500)
        compose.onAllNodesWithTag("chat.sourcesChip")[0].performClick(); compose.waitForIdle()
        // Read through the accessibility tree of every window — what a screen reader, and a person, would find. (The
        // sheet was a ModalBottomSheet dialog window until 2026-10-02; it is the root's IosSheet now, as iOS presents it.)
        compose.waitUntil(10_000) { windowHas("sources") && windowHas("close") && windowHas(server.sourceTitle) }
        Thread.sleep(900); goldens.capture("grounded.sources-sheet")
        assertTrue("the close did not dismiss the sheet", clickInWindows("close"))
        compose.waitUntil(10_000) { !windowHas(server.sourceTitle) }
        assertTrue("the second reply never landed", exists("Paragraph ${5}:"))
    } }

    private fun describeError(): String = compose.onAllNodes(hasText("web_search error", substring = true)).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }?.take(200) ?: ""

    /** Text or content description anywhere in any window's accessibility tree. */
    private fun windowHas(text: String): Boolean = windowNodes().any { n -> (n.text?.toString()?.contains(text, ignoreCase = true) == true) || (n.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) }
    private fun clickInWindows(text: String): Boolean {
        val n = windowNodes().firstOrNull { (it.contentDescription?.toString() ?: it.text?.toString() ?: "").contains(text, ignoreCase = true) } ?: return false
        var t: AccessibilityNodeInfo? = n
        while (t != null && !t.isClickable) t = t.parent
        return (t ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun windowNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out.add(n); for (i in 0 until n.childCount) walk(n.getChild(i)) }
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching { ua.windows.forEach { walk(it.root) } }; if (out.isEmpty()) walk(ua.rootInActiveWindow)
        return out
    }

}
