// "Whole if quick" against arrival timelines (the maintainer 2026-10-25, via pm; the rules as pm settled them for reveal.json).
// Replaced by — or run beside — ios's conformance vector when it lands.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRevealTest {
    /** Feeds [arrivals] (ms → cumulative text) and frames every [frameMs]; returns the shown text at each frame time. */
    private fun run(arrivals: List<Pair<Long, String>>, until: Long, frameMs: Long = 16, at: StreamReveal = StreamReveal()): Map<Long, StreamReveal.Shown> {
        val out = LinkedHashMap<Long, StreamReveal.Shown>()
        var next = 0; var t = 0L
        while (t <= until) {
            while (next < arrivals.size && arrivals[next].first <= t) { at.arrive(arrivals[next].second, arrivals[next].first); next++ }
            out[t] = at.frame(t)
            t += frameMs
        }
        return out
    }
    private fun text(arrivals: List<Pair<Long, String>>, t: Long, s: StreamReveal.Shown) = arrivals.last { it.first <= t }.second.take(s.length)

    @Test fun aQuickParagraphAppearsWholeWithAFade() {
        val a = listOf(0L to "Hello", 80L to "Hello there,", 160L to "Hello there, friend.", 200L to "Hello there, friend.\n\n")
        val r = run(a, 600)
        assertEquals("held while it arrives", 0, r[192]!!.length)
        val whole = r[208]!!
        assertEquals("Hello there, friend.\n\n", text(a, 208, whole))
        assertEquals(0, whole.fadeFrom); assertNotNull(whole.fadeStartedAtMs)
        assertNull("the fade lasts 0.3 s", r[512]!!.fadeFrom)
    }

    @Test fun aSlowFirstParagraphIsHeld250msThenRevealedSmoothlyAtWordBoundaries() {
        // 40 words, one every 50 ms (2 s)
        val words = (1..40).map { "word$it" }
        val a = words.indices.map { i -> i * 50L to words.take(i + 1).joinToString(" ") + " " }
        val r = run(a, 2_000)
        assertEquals(0, r[240]!!.length)
        assertTrue("moving once the first paragraph's 250 ms are up", r[256]!!.length > 0)
        var last = 0
        for ((t, s) in r) {
            if (t < 250) continue
            val arrived = a.last { it.first <= t }.second
            assertTrue("never ahead of what arrived", s.length <= arrived.length)
            assertTrue("monotonic", s.length >= last)
            assertTrue("ends on a boundary at $t: <${arrived.take(s.length)}>", s.length == 0 || s.length == arrived.length || arrived[s.length].isWhitespace())
            last = s.length
        }
        assertTrue("it is moving by 1 s", r[1008]!!.length > 0)
    }

    @Test fun stepsAreTimedNotFramed() {
        val words = (1..60).map { "w$it" }
        val a = words.indices.map { i -> i * 20L to words.take(i + 1).joinToString(" ") + " " }
        val at60 = run(a, 1_500, frameMs = 16)
        val at120 = run(a, 1_500, frameMs = 8)
        val diff = Math.abs(at60[1_200]!!.length - at120[1_200]!!.length)
        assertTrue("60 Hz and 120 Hz within a word of each other at 1.2 s: $diff", diff <= 4)
    }

    @Test fun aParagraphThatCompletesMidRevealSnapsWholeWithoutAFade() {
        val words = (1..30).map { "word$it" }
        val a = words.indices.map { i -> i * 40L to words.take(i + 1).joinToString(" ") } + (1_300L to words.joinToString(" ") + "\n\n")
        val r = run(a, 1_400)
        val s = r[1_312]!!
        assertEquals(words.joinToString(" ").length + 2, s.length)
        assertNull(s.fadeFrom)
    }

    @Test fun aTrailingPartialWordWaitsThenShows() {
        val a = listOf(0L to "Hi ", 50L to "Hi supercalifragilistic")
        val at = StreamReveal()
        val r = run(a, 1_200, at = at)
        // the hold ends at 250 and the reveal runs into the partial word there: it waits until 550
        assertEquals("waits at the boundary (a word's end, before its space)", 2, r[528]!!.length)
        assertEquals("then shows to the arrived end", a.last().second.length, r[1_200]!!.length)
    }

    @Test fun aFenceWithBlankLinesIsOneParagraph() {
        val code = "```\nline one\n\nline two\n```\n\n"
        val a = listOf(0L to "```\nline one\n", 100L to "```\nline one\n\nline two\n", 200L to code)
        val r = run(a, 400)
        assertEquals("nothing of the fence until it closes", 0, r[160]!!.length)
        assertEquals(code.length, r[208]!!.length)
    }

    @Test fun finishShowsEverything() {
        val at = StreamReveal()
        at.arrive("A long paragraph still arriving with many words", 0)
        assertEquals(0, at.frame(100).length)
        at.finish()
        assertEquals("A long paragraph still arriving with many words".length, at.frame(110).length)
    }

    @Test fun theFirstFrameAfterTheHoldTakesExactlyOneStep() {
        // 90 characters, all arrived at 0, in 9-character words: one step is ceil(90/9) = 10, inside the second word,
        // snapped forward to its end (17)
        val at = StreamReveal()
        at.arrive("aaaaaaaa ".repeat(10).trimEnd(), 0)
        assertEquals(0, at.frame(249).length)
        assertEquals(17, at.frame(250).length)
    }

    /** Only the reply's first paragraph is held 250 ms; every later one keeps 600 (the maintainer 2026-10-26). */
    @Test fun laterParagraphsKeepThe600msHold() {
        val first = "One.\n\n"
        val at = StreamReveal()
        at.arrive(first, 0); at.frame(10)
        at.arrive(first + "A second paragraph still arriving with words", 1_000)
        assertEquals(first.length, at.frame(1_249).length)
        assertEquals("still held at 1.25 s", first.length, at.frame(1_300).length)
        assertEquals(first.length, at.frame(1_599).length)
        assertTrue("revealing at 1.6 s", at.frame(1_600).length > first.length)
        // and one that completes inside its 600 ms comes in whole, with the fade
        val quick = StreamReveal()
        quick.arrive(first, 0); quick.frame(10)
        quick.arrive(first + "Second, quick.", 1_000); quick.frame(1_300)
        quick.arrive(first + "Second, quick.\n\n", 1_500)
        val s = quick.frame(1_510)
        assertEquals(first.length, s.fadeFrom); assertEquals((first + "Second, quick.\n\n").length, s.length)
    }

    /** A first paragraph that takes 300 ms to complete no longer waits to come in whole: it streams from 250 ms. */
    @Test fun aFirstParagraphSlowerThan250msStreams() {
        val a = listOf(0L to "Hello", 100L to "Hello there,", 200L to "Hello there, friend.", 300L to "Hello there, friend.\n\n")
        val r = run(a, 400)
        assertTrue("showing before it completes", r[288]!!.length in 1 until "Hello there, friend.".length + 1)
        assertNull("no fade for a paragraph already showing", r[304]!!.fadeFrom)
    }

    // Waiting is not animating: during the hold, a partial word's wait and a fade, nothing changes until a known time,
    // and the view sleeps until then instead of drawing frames (a frame loop through the hold kept Compose from idling).
    @Test fun theRevealSaysHowLongNothingWillChange() {
        val at = StreamReveal()
        at.arrive("A paragraph still arriving with several words", 0)
        at.frame(100)
        assertEquals("held until 250", 150L, at.waitMs(100))
        at.frame(250)
        assertEquals("stepping: draw the next frame", 0L, at.waitMs(250))
        val partial = StreamReveal()
        partial.arrive("Hi supercalifragilistic", 0)
        partial.frame(250); partial.frame(267)
        assertTrue("a partial word waits up to 300 ms: ${partial.waitMs(267)}", partial.waitMs(267) in 1..300)
        val quick = StreamReveal()
        quick.arrive("Hello.\n\n", 0)
        quick.frame(50)
        assertEquals("shown whole and fading: the fade's remaining time", 300L, quick.waitMs(50))
    }

    /**
     * iOS StreamRevealFeedTests @ 0598bce1: the turn's origin can be set after the row starts drawing, so the feed reads
     * it at the answer's first character, not before.
     */
    @Test fun anOnDeviceAnswerIsNotHeldAndItsOriginIsReadAtTheFirstCharacter() {
        val feed = StreamRevealFeed()
        feed.arrive("", StreamReveal.Origin.CLOUD, 0)
        assertEquals(0, feed.frame(0).length)
        feed.arrive("one two three", StreamReveal.Origin.ON_DEVICE, 10)
        assertEquals("one", "one two three".take(feed.frame(10).length))
        val cloud = StreamRevealFeed()
        cloud.arrive("one two three", StreamReveal.Origin.CLOUD, 10)
        assertEquals("", "one two three".take(cloud.frame(10).length))
    }
}
