// The maintainer's Pixel, 2026-11-11 (21643eba): "there's a delay in android when tapping buttons like the + button and
// the x button. it doesn't feel responsive". A tap's first visible response is the pressed look at touch-down and the
// action on the frame after touch-up. The composer runs in a plain activity on the real frame clock (as
// AttachMenuPositionTest did: a Compose test rule only draws frames when it syncs), the taps are real touch events with a
// finger's hold, and a recording Indication — given to the composer through LocalIndication, nothing in the app changed —
// stamps the moment Compose emits the press. Each case logs its numbers under tag teemoon ("TAPLATENCY").
package ai.teemoon.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapLatencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dp get() = instrumentation.targetContext.resources.displayMetrics.density

    @Volatile private var pressedAt = 0L
    @Volatile private var actedAt = 0L
    @Volatile private var composerLeft = 0f
    @Volatile private var composerTop = 0f
    @Volatile private var composerBottom = 0f

    /** Stamps the first press it sees; draws nothing over the content. */
    private inner class Recording : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : androidx.compose.ui.Modifier.Node(), DrawModifierNode {
            override fun onAttach() { coroutineScope.launch { interactionSource.interactions.collect { if (it is PressInteraction.Press && pressedAt == 0L) pressedAt = SystemClock.uptimeMillis() } } }
            override fun ContentDrawScope.draw() { drawContent() }
        }
        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
    }

    private fun launch(images: Int): ActivityScenario<ComponentActivity> = ActivityScenario.launch(ComponentActivity::class.java).also { s ->
        val recording = Recording()
        s.onActivity { a ->
            WindowCompat.setDecorFitsSystemWindows(a.window, false)
            a.setContent {
                TeemoonTheme {
                    CompositionLocalProvider(LocalIndication provides recording) {
                        Column(Modifier.fillMaxSize().background(TeemoonColors.background).imePadding()) {
                            Spacer(Modifier.weight(1f))
                            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {},
                                onAttach = { actedAt = SystemClock.uptimeMillis() },
                                images = List(images) { androidx.compose.ui.graphics.ImageBitmap(64, 64) },
                                onRemoveImage = { actedAt = SystemClock.uptimeMillis() },
                                modifier = Modifier.onGloballyPositioned { c -> val p = c.positionOnScreen(); composerLeft = p.x; composerTop = p.y; composerBottom = p.y + c.size.height })
                        }
                    }
                }
            }
        }
        val end = SystemClock.uptimeMillis() + 3000
        while (composerBottom == 0f && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        Thread.sleep(500)
    }

    /** A finger's tap: down, held [holdMs], up. Returns the press delay after down and the action delay after up, in ms. */
    private fun tap(x: Float, y: Float, holdMs: Long = 80): Pair<Long, Long> {
        pressedAt = 0L; actedAt = 0L
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        Thread.sleep(holdMs)
        val up = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0))
        val end = up + 1000
        while (actedAt == 0L && SystemClock.uptimeMillis() < end) Thread.sleep(5)
        val press = if (pressedAt == 0L) -1 else pressedAt - down
        val act = if (actedAt == 0L) -1 else actedAt - up
        return press to act
    }

    private fun check(what: String, r: Pair<Long, Long>) {
        android.util.Log.i("teemoon", "TAPLATENCY $what press=${r.first}ms act=${r.second}ms")
        // the press shows at touch-down (within two frames at 60 Hz), and the action runs on the frame after touch-up
        assertTrue("$what: the press showed ${r.first} ms after touch-down", r.first in 0..33)
        assertTrue("$what: the action ran ${r.second} ms after touch-up", r.second in 0..33)
    }

    /** The "+": 32dp at the row's start, 8dp in, bottom-aligned in a row padded 8dp. */
    @Test fun thePlusRespondsAtOnce() {
        launch(images = 0).use { check("plus", tap(composerLeft + 24 * dp, composerBottom - 24 * dp)) }
    }

    /** The first thumbnail's remove x: a 48dp target centred on the 24dp circle at the 64dp thumbnail's top-right corner. */
    @Test fun theRemoveXRespondsAtOnce() {
        launch(images = 2).use { check("remove", tap(composerLeft + (12 + 64) * dp, composerTop + 18 * dp)) }
    }
}
