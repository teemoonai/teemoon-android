// The in-chat fades under the motion check (card motion-pass item 6): what arrives fades in, what goes fades out, one
// way each, and a card already there when the chat is drawn (or scrolled back into view) does not fade again.
package ai.teemoon.app.motion

import ai.teemoon.app.AppearFade
import ai.teemoon.app.TeemoonTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
class MotionCheckFadeTest {
    @get:Rule val rule = createComposeRule()
    private var card by mutableStateOf<String?>(null)

    private fun show(appearOnFirst: Boolean = false) = rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AppearFade(card, "card", appearOnFirst = appearOnFirst) { Box(Modifier.size(200.dp, 80.dp).background(Color.DarkGray)) }
    } } }
    private fun alphas(f: List<Frame>) = f.filter { it.ms >= 0 }.map { it.shots["card"]?.singleOrNull()?.alpha ?: 0f }

    @Test fun aCardFadesInAndOut() {
        show(); rule.mainClock.advanceTimeBy(300)
        val rec = MotionRecorder(rule, listOf("card"))
        val inn = rec.record(400) { card = "the error" }
        rec.writeStrip("fade-in", count = 18)
        val a = alphas(inn)
        assertTrue("it fades in, not at once: ${a.take(6)}", a.first() < 0.5f && a.last() > 0.99f)
        assertNone(MotionRules.monotonic(inn, "card", Edge.ALPHA))
        val out = MotionRecorder(rule, listOf("card")).record(400) { card = null }
        val b = alphas(out)
        assertTrue("it fades out, not at once: ${b.take(6)}", b.first() > 0.5f && b.last() == 0f)
    }

    @Test fun aCardAlreadyThereDoesNotFade() {
        card = "the debug card"; show()
        val f = MotionRecorder(rule, listOf("card"), pictures = false).record(200) {}
        assertTrue("solid from the first frame: ${alphas(f)}", alphas(f).all { it > 0.99f })
    }

    /** The error card is a list item that exists because the error just arrived: it fades in on its first frame. */
    @Test fun anArrivingItemFadesInOnItsFirstFrame() {
        card = "the error"
        rule.mainClock.autoAdvance = false
        show(appearOnFirst = true)
        val f = MotionRecorder(rule, listOf("card"), pictures = false).record(400) {}
        val a = f.map { it.shots["card"]?.singleOrNull()?.alpha ?: 0f }
        // the recorder's first look is a frame in, where the decelerating fade is already past half: not solid, though
        assertTrue("fading in, not solid at once: ${a.take(6)} … ${a.last()}", a.first() < 0.8f && a.last() > 0.99f)
        assertNone(MotionRules.monotonic(f, "card", Edge.ALPHA))
    }
}
