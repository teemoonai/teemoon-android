// Each motion rule red and green on made-up frames, shaped like the bugs the maintainer found by hand on 2026-11-12/13: the double
// composer, the photo riding the keyboard, the two-step shrink, the late scroll. A rule that cannot go red here guards
// nothing in MotionCheck*.
package ai.teemoon.app.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRulesTest {
    private fun box(top: Float, height: Float, alpha: Float = 1f) = Shot(0f, top, 400f, top + height, alpha)
    /** Frames every 16 ms from the trigger (frame 0), one entry per element per frame. */
    private fun series(vararg perFrame: Map<String, List<Shot>>) = perFrame.mapIndexed { i, m -> Frame(i * 16L, m) }

    @Test fun r1_twoComposersInOneFrameIsRed() {
        val crossfade = series(mapOf("composer" to listOf(box(800f, 100f))), mapOf("composer" to listOf(box(800f, 100f, 0.5f), box(700f, 100f, 0.5f))), mapOf("composer" to listOf(box(700f, 100f))))
        assertEquals(1, MotionRules.oneInstance(crossfade, listOf("composer")).size)
        val one = series(mapOf("composer" to listOf(box(800f, 100f))), mapOf("composer" to listOf(box(750f, 100f))), mapOf("composer" to listOf(box(700f, 100f))))
        assertTrue(MotionRules.oneInstance(one, listOf("composer")).isEmpty())
    }

    @Test fun r2_aTurnBackOrAJumpIsRed() {
        fun tops(vararg t: Float) = series(*t.map { mapOf("sheet" to listOf(box(it, 500f))) }.toTypedArray())
        assertTrue(MotionRules.monotonic(tops(900f, 850f, 800f, 770f, 760f), "sheet", Edge.TOP).isEmpty())
        assertEquals("a bounce past the rest", 1, MotionRules.monotonic(tops(900f, 700f, 620f, 640f), "sheet", Edge.TOP).count { "turned back" in it.detail })
        assertEquals("a cut", 1, MotionRules.monotonic(tops(900f, 300f, 290f), "sheet", Edge.TOP).count { "jumped" in it.detail })
        // the strip's spring: past its rest by 3.7 dp, then back a third of a dp a frame
        val creep = tops(811f, 790f, 760f, 735f, 727f, 726.7f, 727f, 727.3f, 727.7f, 728f, 728.3f, 728.7f, 729f, 729.3f, 729.7f, 730.3f)
        assertEquals("a slow creep back", 1, MotionRules.monotonic(creep, "sheet", Edge.TOP).count { "turned back" in it.detail })
        assertTrue("rounding is not a turn", MotionRules.monotonic(tops(811f, 790f, 760f, 730.3f, 730f, 730.33f, 730f), "sheet", Edge.TOP).isEmpty())
    }

    /** e1cea410's bug: on the send frame the photo was still drawn, then rode the keyboard down. */
    @Test fun r3_aPhotoDrawnAfterTheSendIsRed() {
        val riding = listOf(Frame(-16, mapOf("photo" to listOf(box(600f, 64f)))), Frame(0, mapOf("photo" to listOf(box(620f, 64f)))), Frame(16, mapOf("photo" to listOf(box(700f, 64f, 0.4f)))))
        assertEquals(2, MotionRules.goneFromTheFirstFrame(riding, "photo").size)
        val gone = listOf(Frame(-16, mapOf("photo" to listOf(box(600f, 64f)))), Frame(0, mapOf("photo" to listOf(box(600f, 64f, 0f)))), Frame(16, emptyMap()))
        assertTrue(MotionRules.goneFromTheFirstFrame(gone, "photo").isEmpty())
    }

    /** The two-step shrink: the panel's height closed in 120 ms, then the keyboard went down for 300 more. */
    @Test fun r4_aPanelThatSettlesBeforeTheKeyboardIsRed() {
        fun f(i: Int, panelH: Float, kbTop: Float) = Frame(i * 16L, mapOf("panel" to listOf(box(kbTop - panelH, panelH)), "keyboard" to listOf(box(kbTop, 2000f - kbTop))))
        val twoStep = (0..25).map { i -> f(i, if (i < 8) 300f - i * 25f else 100f, 1000f + i * 30f) }
        assertEquals(1, MotionRules.together(twoStep, "panel" to Edge.HEIGHT, "keyboard" to Edge.TOP).count { "end" in it.detail })
        val one = (0..25).map { i -> f(i, 300f - i * 8f, 1000f + i * 30f) }
        assertTrue(MotionRules.together(one, "panel" to Edge.HEIGHT, "keyboard" to Edge.TOP).isEmpty())
    }

    /** The late scroll: the transcript moved again 400 ms after the send had settled. */
    @Test fun r5_aMoveAfterTheEndIsRed() {
        val late = (0..40).map { i -> Frame(i * 16L, mapOf("transcript" to listOf(box(if (i in 30..35) 100f - (i - 29) * 10f else if (i > 35) 40f else 100f, 800f)))) }
        assertEquals("its top and bottom", setOf("R5"), MotionRules.stillAfter(late, listOf("transcript"), endMs = 300).map { it.rule }.toSet())
        assertTrue(MotionRules.stillAfter(late, listOf("transcript"), endMs = 600).isEmpty())
        val back = (0..10).map { i -> Frame(i * 16L, mapOf("chip" to listOf(box(if (i in 3..6) 500f + i else 500f, 40f)))) }
        assertEquals(1, MotionRules.doesNotReturn(back, "chip", Edge.TOP).size)
    }

    @Test fun r6_withAnimationsOffFrameZeroIsTheEnd() {
        val animated = series(mapOf("sheet" to listOf(box(900f, 500f))), mapOf("sheet" to listOf(box(400f, 500f))))
        assertEquals(1, MotionRules.endStateAtOnce(animated, listOf("sheet")).size)
        val atOnce = series(emptyMap(), mapOf("sheet" to listOf(box(400f, 500f))), mapOf("sheet" to listOf(box(400f, 500f))))
        assertTrue("drawn on the next frame, already at rest", MotionRules.endStateAtOnce(atOnce, listOf("sheet")).isEmpty())
        val late = series(emptyMap(), emptyMap(), emptyMap(), mapOf("sheet" to listOf(box(400f, 500f))))
        assertEquals("first drawn 48 ms after", 1, MotionRules.endStateAtOnce(late, listOf("sheet")).size)
    }
}
