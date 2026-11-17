// The motion check on a sheet (card motion-pass, transition "chip → where sheet open and close"): the sheet rises from
// the bottom to its resting detent and the scrim darkens in the same frames; closing undoes both, together; with
// animations off both land on the first frame. Recorded under Robolectric, a 16 ms frame at a time (MotionRecorder).
package ai.teemoon.app.motion

import ai.teemoon.app.IosSheet
import ai.teemoon.app.SheetDetents
import ai.teemoon.app.TeemoonTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NAMES = listOf("sheet", "sheet.scrim")

private fun assertNone(v: List<Violation>) = assertEquals(v.joinToString("\n"), emptyList<Violation>(), v)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckSheetTest {
    @get:Rule val rule = createComposeRule()
    private var visible by mutableStateOf(false)

    // black behind, as the app (dark only in 1.1.0) draws its chat: the strips show what a user sees
    private fun sheet() = rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
        IosSheet(visible = visible, tops = listOf(SheetDetents.WHERE, SheetDetents.LARGE), onDismiss = { visible = false }, grabber = true, containerColor = Color.DarkGray) {
            Box(Modifier.fillMaxSize().testTag("sheet.content"))
        }
    } } }

    @Test fun theSheetOpensInOneMotionWithItsScrim() {
        sheet()
        val rec = MotionRecorder(rule, NAMES)
        val f = rec.record(800) { visible = true }
        rec.writeStrip("sheet-open")
        assertNone(MotionRules.oneInstance(f, NAMES))
        assertNone(MotionRules.monotonic(f, "sheet", Edge.TOP))
        assertNone(MotionRules.monotonic(f, "sheet.scrim", Edge.ALPHA))
        assertNone(MotionRules.together(f, "sheet" to Edge.TOP, "sheet.scrim" to Edge.ALPHA))
        val end = MotionRules.span(f, "sheet", Edge.TOP)!!.second
        assertNone(MotionRules.stillAfter(f, NAMES, end))
    }

    @Test fun theSheetClosesInOneMotionWithItsScrim() {
        visible = true; sheet()
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, NAMES)
        val f = rec.record(800) { visible = false }
        rec.writeStrip("sheet-close")
        assertNone(MotionRules.monotonic(f, "sheet", Edge.TOP))
        assertNone(MotionRules.together(f, "sheet" to Edge.TOP, "sheet.scrim" to Edge.ALPHA))
    }
}

/** Remove animations: Compose's durations scaled to 0, as the system setting does through MotionDurationScale. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckSheetNoAnimationsTest {
    @get:Rule val rule = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })
    private var visible by mutableStateOf(false)

    @Test fun theSheetIsOpenOnTheFirstFrame() {
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize()) {
            IosSheet(visible = visible, tops = listOf(SheetDetents.WHERE, SheetDetents.LARGE), onDismiss = {}, grabber = true, containerColor = Color.DarkGray) { Box(Modifier.fillMaxSize()) }
        } } }
        val rec = MotionRecorder(rule, NAMES, pictures = false)
        val f = rec.record(300) { visible = true }
        assertNone(MotionRules.endStateAtOnce(f, NAMES))
    }
}
