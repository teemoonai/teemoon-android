// The title's lock under the motion check (card motion-pass item 4; iOS E2EETitleBlock.VerifyingLock): it breathes while
// near.ai's hardware is being checked, settles solid once verified, and stops — nothing pulses forever behind a verified
// chat — and with animations off it is simply solid.
package ai.teemoon.app.motion

import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.VerifyingLock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckTrustTest {
    @get:Rule val rule = createComposeRule()
    private var verifying by mutableStateOf(true)

    private fun alphas(f: List<Frame>) = f.mapNotNull { it.shots["title.lock"]?.singleOrNull()?.alpha }

    /** Verified: the lock eases to solid and stays still — the breath is over, nothing runs on. */
    @Test fun verifiedSettlesSolidAndStill() {
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { VerifyingLock(verifying) } } }
        rule.mainClock.advanceTimeBy(500)
        val rec = MotionRecorder(rule, listOf("title.lock"))
        val settled = rec.record(1_200) { verifying = false }
        rec.writeStrip("lock-verified", count = 24)
        val after = alphas(settled.filter { it.ms >= 400 })
        assertTrue("solid once verified: $after", after.all { it > 0.99f })
        assertEquals("still: ${after.toSet()}", 1, after.toSet().size)
    }

    /**
     * The breath itself, on a test frame clock (the compose test rule holds infinite work back so it can idle, as it
     * holds an infinite transition): down to 0.45 and back to 1, 0.85 s each way, again and again.
     */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun theBreathGoesDownAndBack() = kotlinx.coroutines.test.runTest {
        val pulse = androidx.compose.animation.core.Animatable(1f)
        val clock = androidx.compose.ui.test.TestMonotonicFrameClock(this)
        val seen = ArrayList<Pair<Long, Float>>()
        val job = launch(clock) { ai.teemoon.app.lockBreath(pulse) }
        repeat(220) { testScheduler.advanceTimeBy(16); testScheduler.runCurrent(); seen += testScheduler.currentTime to pulse.value }
        job.cancel()
        val low = seen.minBy { it.second }; val values = seen.map { it.second }
        assertEquals("down to 0.45", 0.45f, low.second, 0.02f)
        assertTrue("near the bottom at ~0.85 s: $low", low.first in 780L..940L)
        assertTrue("and back up past 0.98 after: ${values.drop(60).max()}", values.drop(60).max() > 0.98f)
        assertTrue("and down again: ${values.drop(120).min()}", values.drop(120).min() < 0.5f)
    }
}

/** Remove animations: the lock is solid even while verifying — no breath, no blink. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckTrustNoAnimationsTest {
    @get:Rule val rule = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    @Test fun theLockIsSolid() {
        rule.setContent { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { VerifyingLock(true) } } }
        rule.mainClock.advanceTimeBy(500)
        val f = MotionRecorder(rule, listOf("title.lock"), pictures = false).record(1_000) {}
        val a = f.mapNotNull { it.shots["title.lock"]?.singleOrNull()?.alpha }
        assertEquals("one alpha throughout: ${a.toSet()}", 1, a.toSet().size)
    }
}
