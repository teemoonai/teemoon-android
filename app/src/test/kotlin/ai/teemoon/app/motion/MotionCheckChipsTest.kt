// The chips under the motion check (card motion-pass item 8): picking another model fades the chip's name through
// rather than swapping it in a frame, and the sources chip arrives with the finished reply by fading and growing in.
package ai.teemoon.app.motion

import ai.teemoon.app.AppearFade
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WhereChip
import ai.teemoon.providers.WhereLocality
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckChipsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aModelSwitchFadesTheNameThrough() {
        var model by mutableStateOf("glm-5.3-flash")
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            WhereChip(model = model, place = "fireworks", locality = WhereLocality.CLOUD, onClick = {})
        } } }
        rule.mainClock.advanceTimeBy(300)
        val rec = MotionRecorder(rule, listOf("chip.model"))
        val f = rec.record(500) { model = "kimi-k3" }
        rec.writeStrip("chip-switch", count = 18)
        val counts = f.map { it.shots["chip.model"]?.size ?: 0 }
        assertTrue("the old name leaves as the new one comes, not one frame to the next: $counts", counts.any { it == 2 })
        assertEquals("one name at the end", 1, counts.last())
    }

    @Test fun theSourcesChipFadesAndGrowsIn() {
        var sources by mutableStateOf<String?>(null)
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AppearFade(sources, "chat.sourcesFade", scaleFrom = 0.92f) { Box(Modifier.size(120.dp, 28.dp).background(Color(0xFF0A84FF))) }
        } } }
        rule.mainClock.advanceTimeBy(300)
        val f = MotionRecorder(rule, listOf("chat.sourcesFade"), pictures = false).record(400) { sources = "3 sources" }
        val shown = f.filter { it.ms >= 0 }.mapNotNull { it.shots["chat.sourcesFade"]?.singleOrNull() }
        assertTrue("it fades in: ${shown.take(4).map { it.alpha }}", shown.first().alpha < 0.5f && shown.last().alpha > 0.99f)
        val w = shown.map { it.right - it.left }
        assertTrue("and grows to its size: ${w.take(4)} … ${w.last()}", w.first() < w.last() - 1f)
        assertEquals(MotionRules.monotonic(f, "chat.sourcesFade", Edge.LEFT), emptyList<Violation>())
    }
}
