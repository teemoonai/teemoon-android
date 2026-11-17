// Predictive back under the motion check (card motion-pass item 1): with the finger in flight the screen being left
// follows it — the sheet narrows and sinks, a pushed page slides toward its edge with its parent coming in — and a
// cancel puts it back. Before this, the gesture's progress was read and never used: nothing moved until release.
package ai.teemoon.app.motion

import ai.teemoon.app.BackPreview
import ai.teemoon.app.IosSheet
import ai.teemoon.app.Route
import ai.teemoon.app.SheetDetents
import ai.teemoon.app.SheetPages
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.backPreview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun assertNone(v: List<Violation>) = assertEquals(v.joinToString("\n"), emptyList<Violation>(), v)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckBackTest {
    @get:Rule val rule = createComposeRule()
    private var progress by mutableFloatStateOf(0f)

    /** A finger: 0 → [to] over 300 ms, held, then let go back to 0 (a cancel) from 500 ms. */
    private fun finger(ms: Long, to: Float = 0.6f) { progress = when { ms < 0 -> 0f; ms <= 300 -> to * ms / 300f; ms <= 500 -> to; else -> 0f } }

    @Test fun theSheetFollowsTheFingerAndComesBack() {
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            IosSheet(visible = true, tops = listOf(SheetDetents.WHERE, SheetDetents.LARGE), onDismiss = {}, grabber = true, containerColor = Color.DarkGray, back = { progress }) {
                Box(Modifier.fillMaxSize())
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("sheet"))
        val f = rec.record(800, onFrame = ::finger) {}
        rec.writeStrip("back-sheet")
        val during = f.filter { it.ms in 0..300 }
        assertNotNull("the sheet moved with the finger: ${during.map { it.one("sheet")?.left }}", MotionRules.span(during, "sheet", Edge.LEFT))
        assertNone(MotionRules.monotonic(during, "sheet", Edge.LEFT))
        assertNone(MotionRules.monotonic(during, "sheet", Edge.TOP))
        val held = f.first { it.ms == 400L }.one("sheet")!!; val before = f.first().one("sheet")!!
        // 0.6 of 48 dp narrower, half each side, of the full-width layer (the box read here is inside its 8 dp sides)
        assertEquals("narrower by 0.6 of 48 dp", 0.6f * 24f * (before.right - before.left) / 411f, held.left - before.left, 1f)
        val back = f.last().one("sheet")!!
        assertEquals("a cancel puts it back", before.left, back.left, 0.5f)
    }

    @Test fun aPushedPageSlidesWithTheFingerAndComesBack() {
        val parentOf: (Route) -> Route? = { if (it == Route.ReasoningLevels) Route.Where else null }
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            SheetPages(Route.ReasoningLevels, parentOf, seeking = progress > 0f, progress = progress) { page ->
                Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("page." + (if (page == Route.Where) "where" else "levels")))
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("page.levels", "page.where"))
        val f = rec.record(1_300, onFrame = ::finger) {}
        rec.writeStrip("back-page")
        val during = f.filter { it.ms in 16..300 }
        assertNotNull("the page moved with the finger: ${during.map { it.one("page.levels")?.left }}", MotionRules.span(during, "page.levels", Edge.LEFT))
        assertNone(MotionRules.monotonic(during, "page.levels", Edge.LEFT))
        assertTrue("its parent comes in from the left", during.any { (it.one("page.where")?.left ?: 0f) < -0.5f })
        assertNone(MotionRules.together(during, "page.levels" to Edge.LEFT, "page.where" to Edge.LEFT))
        // released, it goes straight back: no kick further out first (it went 27 dp past where the finger left it)
        assertNone(MotionRules.monotonic(f.filter { it.ms >= 500 }, "page.levels", Edge.LEFT))
        assertEquals("a cancel puts it back", 0f, f.last().one("page.levels")!!.left, 0.5f)
        assertEquals("and the parent goes", null, f.last().one("page.where"))
    }

    /** Released past the point of no return: the sheet goes on sinking as narrow as the finger left it, never re-widening. */
    @Test fun aCommittedSheetBackLeavesAsItWas() {
        var visible by mutableStateOf(true)
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            IosSheet(visible = visible, tops = listOf(SheetDetents.WHERE, SheetDetents.LARGE), onDismiss = {}, grabber = true, containerColor = Color.DarkGray, back = { progress }) {
                Box(Modifier.fillMaxSize())
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("sheet"))
        // the finger to 0.6 over 300 ms; let go at 320: the route closes the sheet and the caller's progress drops to 0
        val f = rec.record(900, onFrame = { ms -> if (ms <= 300) progress = 0.6f * ms / 300f else if (ms >= 320) { visible = false; progress = 0f } }) {}
        rec.writeStrip("back-sheet-commit")
        val exit = f.filter { it.ms >= 300 }   // from the last frame under the finger
        assertNone(MotionRules.monotonic(exit, "sheet", Edge.LEFT))
        assertNone(MotionRules.monotonic(exit, "sheet", Edge.TOP))
    }

    /** Released: the route moves to the parent and the pop runs on from where the finger left it, one way. */
    @Test fun aCommittedPagePopRunsOn() {
        var page by mutableStateOf<Route>(Route.ReasoningLevels)
        val parentOf: (Route) -> Route? = { if (it == Route.ReasoningLevels) Route.Where else null }
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            SheetPages(page, parentOf, seeking = progress > 0f, progress = progress) { p ->
                Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("page." + (if (p == Route.Where) "where" else "levels")))
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("page.levels", "page.where"))
        val f = rec.record(900, onFrame = { ms -> if (ms <= 300) progress = 0.6f * ms / 300f else if (ms >= 320) { page = Route.Where; progress = 0f } }) {}
        rec.writeStrip("back-page-commit")
        assertNone(MotionRules.monotonic(f.filter { it.ms >= 16 }, "page.levels", Edge.LEFT))
        assertNone(MotionRules.monotonic(f.filter { it.ms >= 16 }, "page.where", Edge.LEFT))
        assertEquals("the parent lands", 0f, f.last().one("page.where")!!.left, 0.5f)
        assertEquals("the page is gone", null, f.last().one("page.levels"))
    }

    /**
     * A push on Material's shared axis X (the maintainer's "do all", pm's recommendation, motion-pass item 10): the new page comes
     * 30 dp from its place and fades through, where it slid the whole width; the old page goes 30 dp the other way.
     */
    @Test fun aPushIsTheSharedAxis() {
        var page by mutableStateOf<Route>(Route.Where)
        val parentOf: (Route) -> Route? = { if (it == Route.ReasoningLevels) Route.Where else null }
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            SheetPages(page, parentOf, seeking = false, progress = 0f) { p ->
                Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("page." + (if (p == Route.Where) "where" else "levels")))
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("page.levels", "page.where"))
        val f = rec.record(600) { page = Route.ReasoningLevels }
        rec.writeStrip("push-axis")
        val starts = f.filter { it.ms >= 0 }.mapNotNull { it.one("page.levels")?.left }
        assertTrue("the new page comes from 30 dp, not the width: $starts", starts.first() <= 31f)
        assertNone(MotionRules.monotonic(f, "page.levels", Edge.LEFT))
        assertTrue("the old page goes at most 30 dp: ${f.mapNotNull { it.one("page.where")?.left }}", f.mapNotNull { it.one("page.where")?.left }.all { it >= -31f })
        assertEquals(0f, f.last().one("page.levels")!!.left, 0.5f)
    }

    /** A back swipe from the right edge moves the finger left: the page goes left with it, its parent from the right. */
    @Test fun aBackFromTheRightEdgeGoesWithTheFinger() {
        val parentOf: (Route) -> Route? = { if (it == Route.ReasoningLevels) Route.Where else null }
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            SheetPages(Route.ReasoningLevels, parentOf, seeking = progress > 0f, progress = progress, fromRightEdge = true) { p ->
                Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("page." + (if (p == Route.Where) "where" else "levels")))
            }
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("page.levels", "page.where"))
        val f = rec.record(400, onFrame = { ms -> progress = if (ms <= 300) 0.6f * ms / 300f else 0.6f }) {}
        val lefts = f.filter { it.ms in 16..300 }.mapNotNull { it.one("page.levels")?.left }
        assertTrue("the page goes left: $lefts", lefts.last() < -1f)
        assertNone(MotionRules.monotonic(f.filter { it.ms in 16..300 }, "page.levels", Edge.LEFT))
    }

    @Test fun eachGestureKnowsWhatItLeaves() {
        val parentOf: (Route) -> Route? = { if (it == Route.ReasoningLevels) Route.Where else null }
        assertEquals(BackPreview.CHAT, backPreview(Route.Chat("t"), null, null, parentOf))
        assertEquals(BackPreview.SHEET, backPreview(Route.Where, Route.Where, null, parentOf))
        assertEquals(BackPreview.PAGE, backPreview(Route.ReasoningLevels, Route.ReasoningLevels, null, parentOf))
        assertEquals(BackPreview.NONE, backPreview(null, null, null, parentOf))
    }
}
