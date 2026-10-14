// ports: teemoon/Views/Settings/PlacesKeysViews.swift @ v1.0.4 (CloudKeysManageView's `.swipeActions(edge: .trailing)` and the
//        confirm it insists on) — the interaction contract of a place row: the trailing swipe asks before it deletes.
package ai.teemoon.app

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

/**
 * A place row on iOS deletes on a trailing swipe, and CONFIRMS: the Swift's note is that a setup and its Keychain entry
 * once went on one careless thumb. The Android rows had the long-press menu only (the where sheet's fix of 2026-09-12
 * brought the swipe to every row iOS lets you swipe).
 */
@RunWith(AndroidJUnit4::class)
class PlacesKeysContractTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    @get:Rule(order = 1) val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
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
    // Not closed in @After: that runs before the failure watcher, and the watcher needs the screen. The process ends with the class.

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(label: String) { compose.waitUntil(10_000) { exists(label) }; compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label))[0].performClick(); compose.waitForIdle(); Thread.sleep(700) }
    /** The settings page's "places & keys" is a section header AND the row under it: the row is the one that can be clicked. */
    private fun tapRow(label: String) { val m = hasText(label, substring = true) and hasClickAction(); compose.waitUntil(10_000) { compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }; compose.onAllNodes(m)[0].performClick(); compose.waitForIdle(); Thread.sleep(700) }

    /** near.ai keyed, then settings → places & keys → cloud keys. */
    private fun openCloudKeys() {
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".NEAR_AI_API_KEY").apply { delete() }.writeText("uitest-near-key") }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_PRESETS, "nearai"); putExtra(UiTestSeeding.EXTRA_NO_BRAVE_KEY, true)
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
        tap("settings"); tapRow("places & keys"); tapRow("cloud keys")
        compose.waitUntil(10_000) { exists("saved") && exists("near.ai") }
    }

    /** The trailing swipe asks ("delete near.ai?"); "keep" leaves the row where it was. */
    @Test fun aTrailingSwipeOnAPlaceRowAsksBeforeDeleting() {
        openCloudKeys()
        // exact: the chat's chip under the sheet reads "· near.ai" and would take the swipe
        compose.onAllNodes(hasText("near.ai"))[0].performTouchInput { swipeLeft() }; compose.waitForIdle()
        compose.waitUntil(10_000) { exists("delete near.ai?") }
        tap("keep"); Thread.sleep(500)
        assertTrue("keep removed the row", exists("near.ai") && !exists("delete near.ai?"))
    }
}
