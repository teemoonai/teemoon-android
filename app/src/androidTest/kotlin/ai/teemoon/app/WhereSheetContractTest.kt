// ports: teemoon/Views/Where/WhereSheetView+FirstRun.swift @ v1.0.4 (`.swipeActions(edge: .trailing) { rowDestructiveActions(row) }`
//        and the row's context menu), teemoon/Views/Where/WhereSheetView.swift @ v1.0.4 (rowDestructiveActions) — the
//        interaction contract of a `ready now` row: what a swipe and a long press offer, and that they agree.
package ai.teemoon.app

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereProviderPresentation
import android.graphics.Bitmap

/**
 * The seeded near.ai preset's row, as the where sheet labels it. DERIVED, not typed: these journeys named "glm-5.2"
 * and broke when the preset's default moved off that retired model (b760889) — nothing on the JVM could see it.
 */
private val NEAR_AI_ROW = WhereProviderPresentation.modelLabel(Provider.nearAI)

/**
 * A `ready now` row on iOS carries its destructive action as a trailing swipe AND in its context menu, and the two are
 * the same action (`rowDestructiveActions`). The Android rows had the menu only; a user who swiped, as they do on iOS,
 * got nothing (the user caught it, 2026-09-12). Read off the Swift: a cloud row's action is "unequip", immediate.
 */
@RunWith(AndroidJUnit4::class)
class WhereSheetContractTest {
    @get:Rule(order = 0) val compose = emptyComposeRule()
    /** What the user was looking at when a test failed: a screenshot under files/journeys and the semantics tree in logcat (JOURNEY). */
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
    private var server: FakeSseServer? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun stop() { scenario?.close(); server?.close() }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tapTag(tag: String) { compose.waitUntil(10_000) { tagExists(tag) }; compose.onAllNodesWithTag(tag)[0].performClick(); compose.waitForIdle(); Thread.sleep(700) }
    private fun row(label: String) = compose.onAllNodes(hasText(label, substring = true))[0]

    /** Two places, the home one selected: near.ai's row is the one that is NOT the chip's, so its text is only in the sheet. */
    private fun launchWithTwoPlaces() {
        server = FakeSseServer(lines = 3, delayMs = 4)
        File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }.let { File(it, ".NEAR_AI_API_KEY").apply { delete() }.writeText("uitest-near-key") }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server!!.baseUrl); putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            putExtra(UiTestSeeding.EXTRA_PRESETS, "nearai"); putExtra(UiTestSeeding.EXTRA_NO_BRAVE_KEY, true)
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
        tapTag("chat.whereChip"); compose.waitUntil(10_000) { exists("ready now") && exists(NEAR_AI_ROW) }
    }

    /** `.swipeActions(edge: .trailing)`: a trailing swipe on a cloud row unequips it — the row is gone, nothing asked. */
    @Test fun aTrailingSwipeOnACloudRowUnequipsIt() {
        launchWithTwoPlaces()
        row(NEAR_AI_ROW).performTouchInput { swipeLeft() }; compose.waitForIdle()
        compose.waitUntil(10_000) { !exists(NEAR_AI_ROW) }
        assertTrue("the sheet closed instead of dropping the row", exists("ready now"))
    }

    /** The context menu offers the SAME action the swipe does (the Swift calls `rowDestructiveActions` from both). */
    @Test fun theLongPressMenuOffersTheSameAction() {
        launchWithTwoPlaces()
        row(NEAR_AI_ROW).performTouchInput { longClick() }
        compose.waitUntil(5_000) { exists("unequip") }
        assertTrue("the menu offers a setup-level delete (removed on iOS: administration lives in places & keys)", !exists("delete this setup"))
    }

    /** No leading swipe (the Swift's comment: "equip" read as "edit this row", which is not a thing you can do). */
    @Test fun aLeadingSwipeDoesNothing() {
        launchWithTwoPlaces()
        row(NEAR_AI_ROW).performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.95f) }; compose.waitForIdle(); Thread.sleep(800)
        assertTrue("the row went away on a leading swipe", exists(NEAR_AI_ROW) && exists("ready now"))
    }
}
