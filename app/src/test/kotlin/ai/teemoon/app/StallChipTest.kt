package ai.teemoon.app

// android-first while iOS ports the same rule (the maintainer 2026-10-25, via pm: "Both"): a reply that PAUSES does not earn the
// chip. At ~10 tok/s a provider like near.ai leaves 1 s gaps between 24-character chunks, and the 1 s stall timer put a
// "thinking… Ns" row under the reply every few seconds. Now: nothing until 3 s of silence; once shown, at least 1 s on
// screen, and gone 0.5 s after text flows again.

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallChipTest {
    /** Replays text arrivals (ms) against a 50 ms clock and returns when the chip was visible. */
    private fun visibleAt(arrivals: List<Long>, until: Long): List<Long> {
        var chip = StallChip()
        var last = arrivals.first()
        val shown = ArrayList<Long>()
        var t = arrivals.first()
        while (t <= until) {
            if (t in arrivals) { last = t; chip = chip.onText(t) }
            chip = chip.tick(t, last)
            if (chip.visible) shown += t
            t += 50
        }
        return shown
    }

    // iOS's StallIndicatorTests cases (ios, 2026-10-25), 1–4 in this type's terms.
    @Test fun nearAiCadenceNeverShowsTheChip() {
        // 24-char chunks on the gaps the clip used: up to 1.25 s apart
        val gaps = listOf(450L, 600L, 750L, 520L, 1100L, 640L, 580L, 1250L, 700L, 480L)
        val arrivals = gaps.runningFold(0L) { a, g -> a + g }.map { it / 50 * 50 }
        assertTrue(visibleAt(arrivals, arrivals.last()).isEmpty())
        // 0.6 s gaps with pauses of 1.1–1.6 s; and gaps of 2.9 s
        val paused = listOf(600L, 600L, 1_100L, 600L, 1_600L, 600L, 1_350L).runningFold(0L) { a, g -> a + g }
        assertTrue(visibleAt(paused, paused.last()).isEmpty())
        val slow = (0L..14_500L step 2_900)
        assertTrue(visibleAt(slow.toList(), slow.last).isEmpty())
    }

    @Test fun aLongStallShowsItAtThreeSeconds() {
        val shown = visibleAt(listOf(0L, 500L), 5_000)
        assertFalse(3_400L in shown)
        assertTrue(3_500L in shown)
    }

    @Test fun onceShownItStaysASecondEvenIfTextReturnsAtOnce() {
        // shown at 3,000 (silence from 0); text resumes at 3,200 and flows
        val shown = visibleAt(listOf(0L) + (3_200L..4_400L step 100), 4_400)
        assertTrue(3_600L in shown && 3_900L in shown)
        assertFalse(4_000L in shown)
    }

    @Test fun oneChunkHidesItHalfASecondLater() {
        // shown at 3,000; one chunk at 5,000, then quiet
        val shown = visibleAt(listOf(0L, 5_000L), 5_600)
        assertTrue(5_300L in shown)
        assertFalse(5_500L in shown)
    }
}
