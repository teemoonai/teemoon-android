package ai.teemoon.app

// android-only: I3 (docs/USE_CASES.md). The notification permission is asked at the FIRST SEND, and "don't allow" is
// respected — the turn still finishes. Its own class because its precondition comes from OUTSIDE: revoking a runtime
// permission kills the app's process, and an instrumentation test runs inside it. tools/device_suite.sh revokes it
// before this class runs. Needs on-device weights (a phone); skips without them.

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

@RunWith(AndroidJUnit4::class)
class NotificationPermissionJourneyTest {
    @get:Rule val compose = emptyComposeRule()
    @get:Rule val pageSizeDialog = PageSizeDialogRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @After fun close() { scenario?.close() }

    /** Every window's text, through accessibility — the system's permission dialog included. */
    private fun screenText(): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { out.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        runCatching { ins.uiAutomation.windows.forEach { walk(it.root) } }
        return out.toString()
    }
    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for $what; on screen:\n${screenText().take(700)}")
    }
    private fun clickAnywhere(label: String): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString()?.equals(label, ignoreCase = true) == true) return n
            for (k in 0 until n.childCount) find(n.getChild(k))?.let { return it }
            return null
        }
        val node = ins.uiAutomation.windows.firstNotNullOfOrNull { find(it.root) } ?: return false
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable) c = c.parent
        return (c ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private val streaming get() = compose.onAllNodes(hasTestTag("chat.stop")).fetchSemanticsNodes().isNotEmpty()

    @Test fun denyingNotificationsStillFinishesTheTurn() {
        assumeTrue("no on-device weights here", File(ctx.filesDir, "LocalModels").walkTopDown().any { it.name.endsWith(".litertlm") })
        assumeTrue("an emulator's CPU decode is too slow for this journey", !Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("SDK"))
        assumeTrue("revoke POST_NOTIFICATIONS from the host first (tools/device_suite.sh does)",
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_ON_DEVICE, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
        })
        waitFor("the composer", 30_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2_500)
        assertTrue("asked at launch, not at the first send", !screenText().contains("Don’t allow") && !screenText().contains("Don't allow"))
        compose.onAllNodes(hasTestTag("chat.composer"))[0].performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Say hello in three words."); Thread.sleep(600)
        compose.onAllNodes(hasTestTag("chat.send"))[0].performClick()
        waitFor("the permission ask", 15_000) { screenText().contains("Don’t allow") || screenText().contains("Don't allow") }
        assertTrue("could not answer the ask", clickAnywhere("Don’t allow") || clickAnywhere("Don't allow"))
        waitFor("the reply after denying", 240_000) { !streaming && Regex("(?i)hello").findAll(screenText()).count() >= 3 }
        assertTrue("denied, yet the permission is granted",
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
    }
}
