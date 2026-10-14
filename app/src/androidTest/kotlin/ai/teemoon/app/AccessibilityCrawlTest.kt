package ai.teemoon.app

// android-only: I7 (docs/USE_CASES.md). Every route in RouteCodec.CRAWL_SAMPLES is opened directly and read the way
// TalkBack reads it — through the ACCESSIBILITY tree, not the compose semantics a test would otherwise trust:
//   1. every control a screen reader can land on and activate says what it is (text or a content description, its own
//      or merged up from its children);
//   2. behind a sheet, the chat is scenery — none of its controls are reachable (every control the chat shows with no
//      sheet up, matched by label and place) (MainActivity marks it
//      hideFromAccessibility, as UIKit's presentation does; TalkBack read the transcript under the chats list before).
// Like RouteCrawlTest it is AVD-only: the launch extras REPLACE the configured places.

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityCrawlTest {
    @get:Rule val compose = emptyComposeRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()

    private fun launch(route: String): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(ins.targetContext, MainActivity::class.java)
            .putExtra(UiTestSeeding.EXTRA_FLAG, true)
            .putExtra(UiTestSeeding.EXTRA_PRESETS, "nearai,grok,fireworks")
            .putExtra(UiTestSeeding.EXTRA_THREADS, true)
            .putExtra("seed_route", route))

    /** What TalkBack would say for [n]: its own text or description, else what its children say (it merges them). */
    private fun spoken(n: AccessibilityNodeInfo): String {
        val own = listOfNotNull(n.text, n.contentDescription, n.stateDescription).joinToString(" ").trim()
        if (own.isNotEmpty()) return own
        return (0 until n.childCount).mapNotNull { n.getChild(it) }.joinToString(" ") { spoken(it) }.trim()
    }

    /** Every node in the app's own windows (the IME and the system bars are not ours). */
    private fun appNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        ins.uiAutomation.windows.filter { it.root?.packageName == ins.targetContext.packageName }.forEach { walk(it.root) }
        return out
    }

    @Test fun everyControlSaysWhatItIsAndNothingBehindASheetIsReachable() {
        // the windows list (every window, not just the active one) needs the interactive-windows flag
        ins.uiAutomation.serviceInfo = ins.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val problems = ArrayList<String>()
        // The chat's own controls, read with no sheet up: each one's label and where it sits. Behind a sheet, a control
        // with the same label at the same place is the chat's, still reachable — the whole chat, not one button.
        val chatControls = launch("").let { scenario ->
            try {
                compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(2_500)
                // the accessibility windows list lags the first frame on a cold start: wait for the app's to arrive
                runCatching { compose.waitUntil(10_000) { appNodes().count { it.isClickable } >= 3 } }.onFailure {
                    // seen: a debuggable build's "isn't 16 KB compatible" dialog over the app on the Pixel — name what is on top
                    error("the app's window never reached the accessibility tree; on screen: " + ins.uiAutomation.windows.joinToString { w -> "${w.root?.packageName}/${w.type}/${w.title}" } + " nodes=" + appNodes().size + " clickable=" + appNodes().count { n -> n.isClickable })
                }
                appNodes().filter { it.isVisibleToUser && (it.isClickable || it.isLongClickable) }
                    .map { spoken(it) to Rect().also { r -> it.getBoundsInScreen(r) } }.toSet()
            } finally { scenario.close() }
        }
        assertTrue("the chat itself showed no controls to compare against: $chatControls", chatControls.size >= 3)
        for (route in RouteCodec.CRAWL_SAMPLES) {
            val scenario = launch(route)
            try {
                compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("chat.composer")).fetchSemanticsNodes().isNotEmpty() || appNodes().isNotEmpty() }
                Thread.sleep(2_500)   // the seed runs off the main thread; the sheet settles
                val nodes = appNodes().filter { it.isVisibleToUser }
                for (n in nodes.filter { it.isClickable || it.isLongClickable }) {
                    if (spoken(n).isEmpty()) {
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        problems += "$route: an unlabelled control at $r (${n.className})"
                    }
                }
                // a sheet route: the chat's own header controls must not be reachable behind it
                if (!route.startsWith("chat:")) {
                    val behind = nodes.filter { it.isClickable || it.isLongClickable }
                        .map { spoken(it) to Rect().also { r -> it.getBoundsInScreen(r) } }.filter { it in chatControls }
                    behind.forEach { (label, at) -> problems += "$route: the chat's \"$label\" at $at is reachable behind the sheet" }
                }
            } finally { scenario.close() }
        }
        assertTrue("screen-reader problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
