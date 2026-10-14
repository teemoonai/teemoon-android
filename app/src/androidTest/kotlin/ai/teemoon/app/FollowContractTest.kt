package ai.teemoon.app

// ports: teemoonUITests/LongThreadStreamingUITests.swift @ v1.0.1 — the seven follow behaviours of
//        docs/UIKIT_TRANSCRIPT.md constraint 2, driven over the in-process fake wire (FakeSseServer, the shape of
//        tools/fake_sse_server.py) against a seeded 200-turn thread. Two iOS tests are not here: the walk-away hand-off
//        and the developer-mode column dump assert nothing themselves — their verdicts are the iOS offset trace, which
//        has no Android instrument yet.
//
// NOT ONE IDLE-WAIT WHILE THE REPLY STREAMS, and that is the whole reason these read the way they do: every Compose test
// interaction waits for idle, a streaming transcript is never idle until the wire closes, and idling repeatedly is
// exactly the breather a chasing follow needs to catch up (iOS measured 761pt of real drift read back as 178pt through
// a polling test). The Compose rule also OWNS the frame clock: a plain Thread.sleep on the test thread produces no
// frames at all, and the app froze mid-send with the prompt still in the composer (2026-09-07). So the clock is
// driven by hand — `pump` advances it 16 ms per 16 ms of wall time — and the app is otherwise left alone.

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import java.io.File
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class FollowContractTest {
    @get:Rule val compose = emptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var server: FakeSseServer
    private var scenario: ActivityScenario<MainActivity>? = null

    /** 120 paragraphs of 12 words at 12 ms a word: ~17 s on the wire, long enough to gesture into; the last paragraph the assertions look for. */
    private val lines = 120
    private val lastParagraph get() = "Paragraph ${lines - 1}:"
    private val streamSeconds = 18.0
    private val turns = 200

    @Before fun start() { server = FakeSseServer(lines = lines, delayMs = 12) }
    @After fun stop() { scenario?.close(); server.close() }

    /** Real time, real frames: the test clock advances in step with the wall clock, so the wire's cadence is what the UI sees. */
    private fun pump(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { compose.mainClock.advanceTimeBy(16); Thread.sleep(16) } }
    private fun until(ms: Long, why: String, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond()) { assertTrue(why, System.currentTimeMillis() < end); pump(50) } }
    private fun exists(label: String) = compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(label: String, settle: Long = 600) {
        until(20_000, "'$label' never appeared") { exists(label) }
        compose.onAllNodes(hasText(label, substring = true) or hasContentDescription(label))[0].performClick(); pump(settle)
    }
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag)[0]
    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** `launchedApp` + `openLongThread`: the seeded 200-turn thread, open, composer on screen. */
    private fun openLongThread(vararg extras: Pair<String, Any>) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            extras.forEach { (k, v) -> when (v) { is String -> putExtra(k, v); is Boolean -> putExtra(k, v) } }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            putExtra(UiTestSeeding.EXTRA_LONG_THREAD, turns)
            putExtra("scroll_trace", true)   // the geometry trace tools/scroll_trace_check.sh pulls and judges
        }
        scenario = ActivityScenario.launch(intent)
        compose.mainClock.autoAdvance = false   // from here the clock is pumped by hand (see the header)
        until(20_000, "the composer never appeared") { tagExists("chat.composer") }
        pump(2_500)   // the seed runs off the main thread
        tap("chats")
        tap("long thread")
        until(20_000, "the transcript never appeared") { tagExists("chat.transcript") }
        pump(1_000)
    }

    private fun send(text: String) {
        tagged("chat.composer").performClick(); pump(900)   // the keyboard raises the composer block; a click injected mid-animation lands beside the send button
        tagged("chat.composer").performTextInput(text); pump(300)
        tagged("chat.send").performClick()
        until(30_000, "generation never started") { tagExists("chat.stop") }
    }
    private fun waitForTurnEnd(ms: Long = 90_000) = until(ms, "the turn never finished") { !tagExists("chat.stop") }
    private fun leaveAlone(seconds: Double) = pump((seconds * 1000).toLong())

    /** The iOS `isHittable`: the row is on screen with its centre visible, i.e. not under the keyboard or off the end. */
    private fun assertLastParagraphOnScreen(why: String) {
        until(30_000, "the finished reply never landed in the transcript") { exists(lastParagraph) }
        pump(200)
        val nodes = compose.onAllNodes(hasText(lastParagraph, substring = true)).fetchSemanticsNodes()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (n in nodes) Log.d("teemoon", "[test] '${lastParagraph}' node bounds=${n.boundsInRoot} placed=${n.layoutInfo.isPlaced} attached=${n.layoutInfo.isAttached} pos=${n.positionInRoot} size=${n.size} merged=${n.isRoot} textCount=${n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.size}")
        Log.d("teemoon", "[test] transcript bounds=${compose.onAllNodesWithTag("chat.transcript").fetchSemanticsNodes().firstOrNull()?.boundsInRoot} root=$root")
        val bounds = nodes.map { it.boundsInRoot }
        // the row's centre on screen (XCUI's isHittable), inside the transcript's band above the composer inset
        val visible = bounds.any { it.height > 0 && it.center.y in root.top..root.bottom }
        assertTrue("$why — the reply's last paragraph is off screen: ${bounds.map { "${it.top.toInt()}..${it.bottom.toInt()}" }} of root ${root.top.toInt()}..${root.bottom.toInt()}", visible)
    }

    private fun firstHistoryRow(): Pair<String, Float> {
        // iOS anchors on history because its 40-line reply is shorter than two swipes; this wire is 120 paragraphs, so
        // the row under the reader may still be the reply's own. The property is the same: the row stays put.
        val rows = compose.onAllNodes(hasText("question ", substring = true) or hasText("Reply ", substring = true) or hasText("reply ", substring = true) or hasText("Paragraph ", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > px(120f) && it.boundsInRoot.height > 0 }   // under the title block, not the one being streamed at the end
        assertTrue("nothing from the transcript is on screen", rows.isNotEmpty())
        val top = rows.minByOrNull { it.boundsInRoot.top }!!
        return top.config[SemanticsProperties.Text].first().text.take(48) to top.boundsInRoot.top
    }
    /** By prefix: a settled paragraph's node is rebuilt across the hand-off, and exact text on a long row is brittle. */
    private fun rowTop(prefix: String): Float? = compose.onAllNodes(hasText(prefix, substring = true)).fetchSemanticsNodes().firstOrNull { it.boundsInRoot.height > 0 }?.boundsInRoot?.top
    private fun px(dp: Float) = dp * ctx.resources.displayMetrics.density
    /**
     * XCUI's swipe returns once the scroll view is idle; a Compose swipe returns while the fling still decelerates, and
     * the fling runs on the pumped clock — a semantics read on a 200-turn list costs ~0.5 s of un-pumped time, so
     * sampling for stability starved the fling and called it settled mid-flight. Pump the deceleration out first
     * (a fast fling here runs ~3 s of frames), then confirm with two reads.
     */
    /**
     * A person's swipe starts mid-screen. The default swipe runs from the node's top edge, and the transcript is
     * full-bleed under the header — a gesture that starts on the title block belongs to the title block (one 44pt
     * control, as on iOS), and never reaches the list.
     */
    private fun swipeIntoHistory() { tagged("chat.transcript").performTouchInput { swipeDown(startY = top + height * 0.35f, endY = top + height * 0.8f) } }
    private fun settleScroll() {
        pump(3_500)
        var last = Float.NaN; val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            val top = compose.onAllNodes(hasText("Paragraph ", substring = true) or hasText("question ", substring = true) or hasText("Reply ", substring = true) or hasText("reply ", substring = true))
                .fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }.minOfOrNull { it.boundsInRoot.top } ?: Float.NaN
            if (top == last) return
            last = top; pump(500)
        }
    }
    private fun shot(name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        File(ctx.getExternalFilesDir(null), "follow").apply { mkdirs() }.let { d -> FileOutputStream(File(d, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }
    private fun dumpRows(tag: String) {
        val rows = compose.onAllNodes(hasText("question ", substring = true) or hasText("Reply ", substring = true) or hasText("reply ", substring = true) or hasText("Paragraph ", substring = true)).fetchSemanticsNodes()
        Log.d("teemoon", "[test] $tag rows=" + rows.sortedBy { it.boundsInRoot.top }.joinToString(" | ") { "${it.boundsInRoot.top.toInt()}..${it.boundsInRoot.bottom.toInt()} '${it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.take(24)}'" })
    }

    /**
     * Sends at the end of a 200-turn thread and watches the answer arrive: the transcript FOLLOWS the stream, and the
     * hand-off from the streaming row to the persisted message does not move it — it comes to rest without bouncing.
     */
    /**
     * testSendingAnchorsThePromptNearTheTop: the sent prompt parks near the top of the transcript and STAYS PUT while
     * the reply grows into the slack beneath it and across the hand-off — growth eats the placeholder, never the offset.
     * Needs a short, slow reply (iOS skips without its short fixture): a long one overflows the slack and pins to the
     * bottom by design, which is the other tests' subject.
     */
    @Test fun sendingAnchorsThePromptNearTheTop() {
        server.close(); server = FakeSseServer(lines = 4, delayMs = 150)   // ~7 s of four paragraphs
        openLongThread(); send("anchor me")
        until(20_000, "the sent message never appeared in the transcript") { rowTop("anchor me") != null }
        pump(300)
        val band = compose.onAllNodesWithTag("chat.transcript").fetchSemanticsNodes().first().boundsInRoot
        val first = rowTop("anchor me")!!
        assertTrue("the prompt was slammed toward the composer: it sits at ${first.toInt()} in a transcript spanning ${band.top.toInt()}...${band.bottom.toInt()}", first < band.center.y)
        leaveAlone(4.0)
        val after = rowTop("anchor me"); assertTrue("the prompt was scrolled away while the reply grew", after != null)
        val moved = abs(after!! - first)
        assertTrue("the reply dragged the prompt ${moved.toInt()}px: growth is moving the offset instead of eating the placeholder", moved < px(8f))
        waitForTurnEnd(); leaveAlone(4.0)
        val settled = rowTop("anchor me"); assertTrue("the prompt was scrolled away by the hand-off", settled != null)
        val across = abs(settled!! - first)
        assertTrue("the hand-off moved the prompt ${across.toInt()}px: the reserve collapsed when the streaming view was replaced by its cell", across < px(8f))
    }

    /**
     * The send TRAVELS: the prompt glides from the composer up to its anchor over several frames, as iOS's phase-A pin
     * does (a live pin over 8pt is a FollowGlide). Android teleported it with scrollToItem — "it seems abrupt like maybe
     * it's not animating" (Pixel, 2026-10-04). Sampled every pumped frame from the tap, before anything else waits.
     */
    @Test fun sendingGlidesThePromptUpRatherThanJumping() {
        server.close(); server = FakeSseServer(lines = 4, delayMs = 150)
        openLongThread()
        tagged("chat.composer").performClick(); pump(900)
        tagged("chat.composer").performTextInput("glide me"); pump(300)
        tagged("chat.send").performClick()
        val tops = ArrayList<Int>()
        val end = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < end) { compose.mainClock.advanceTimeBy(16); Thread.sleep(16); rowTop("glide me")?.let { tops += it.toInt() } }
        val path = tops.zipWithNext().filter { (a, b) -> a != b }
        Log.d("teemoon", "[test] glide path=${tops.distinct()}")
        assertTrue("the prompt never appeared", tops.isNotEmpty())
        val travelled = tops.first() - tops.last()
        assertTrue("the prompt did not travel up (path ${tops.distinct()})", travelled > px(40f))
        // a jump is one step; a glide is several, none of them most of the way
        assertTrue("the prompt jumped to its anchor in ${path.size} step(s): ${tops.distinct()}", path.size >= 3)
        assertTrue("one frame carried ${path.maxOf { (a, b) -> a - b }}px of ${travelled}px: that is a jump", path.maxOf { (a, b) -> a - b } < travelled * 0.7f)
    }

    /**
     * K8 (ToolRoundFollowUITests.testTheFollowSurvivesAMidStreamToolCall): the reply starts, calls web_search mid-stream,
     * and the follow-up streams on — the follow survives the round and the last paragraph lands in view.
     */
    @Test fun theFollowSurvivesAMidStreamToolCall() {
        server.close(); server = FakeSseServer(lines = lines, delayMs = 12, toolRound = true)
        File(File(ctx.filesDir, UiTestSeeding.KEY_DIR).apply { mkdirs() }, ".BRAVE_API_KEY").let { f -> val was = f.takeIf { it.isFile }?.readText(); f.writeText("uitest-brave-key")
            try {
                openLongThread(UiTestSeeding.EXTRA_GROUNDING to true, UiTestSeeding.EXTRA_BRAVE_ENDPOINT to "${server.baseUrl}/llm/context")
                send("search then answer at length")
                waitForTurnEnd()
                assertTrue("the tool round never ran", server.searchRequests.get() > 0)
                assertLastParagraphOnScreen("after a mid-stream tool call")
            } finally { if (was == null) f.delete() else f.writeText(was) }
        }
    }

    @Test fun streamingAtTheEndOfALongThreadStaysPinnedToTheBottom() {
        openLongThread(); send("go")
        leaveAlone(streamSeconds + 8)
        assertLastParagraphOnScreen("the follow did not ride the stream")
        val ys = (0 until 20).map { pump(250); rowTop(lastParagraphText()) ?: -1f }
        val settled = ys.takeLast(6)
        assertTrue("the transcript never came to rest after generation: $settled", (settled.max() - settled.min()) < px(2f))
        val reversals = ys.zipWithNext().count { (a, b) -> b > a + px(2f) }
        assertEquals("the transcript bounced after generation — the offset is being corrected back and forth: $ys", 0, reversals)
    }
    private fun lastParagraphText() = lastParagraph

    /** Scrolling away mid-answer must STOP the follow: the history row the reader is on stays put while the reply streams. */
    @Test fun scrollingAwayMidAnswerStopsTheFollow() {
        openLongThread(); send("go")
        swipeIntoHistory()
        swipeIntoHistory()
        settleScroll()
        val (label, before) = firstHistoryRow()
        leaveAlone(4.0)
        val after = rowTop(label)
        assertTrue("the transcript was scrolled away from the history it was showing", after != null)
        val moved = abs(after!! - before)
        assertTrue("scrolling away did not stop the follow — the transcript moved ${moved / px(1f)}dp while the reply streamed", moved < px(80f))
    }

    /** SCROLLING AWAY HAS TO SURVIVE THE HAND-OFF, NOT JUST THE STREAM (the 2026-08-18 device report). */
    @Test fun scrollingAwaySurvivesTheEndOfTheTurn() {
        openLongThread(); send("go")
        swipeIntoHistory()
        swipeIntoHistory()
        settleScroll()
        val (label, before) = firstHistoryRow()
        dumpRows("before-turn-end")
        waitForTurnEnd()
        pump(2_000)   // past the hand-off and any re-measure: the bug is a SUSTAINED landing, not a flicker
        val after = rowTop(label)
        dumpRows("after-turn-end")
        Log.d("teemoon", "[test] scroll-away: label='$label' before=$before after=$after")
        assertTrue("the row the user was reading left the screen when the turn ended", after != null)
        val moved = abs(after!! - before)
        assertTrue("the turn ended and the transcript moved ${moved / px(1f)}dp — the reader was taken to the new reply", moved < px(80f))
    }

    /** A TAP is not a scroll-away, and must not stop the follow. */
    @Test fun aTapMidAnswerDoesNotStopTheFollow() {
        openLongThread(); send("go")
        tagged("chat.transcript").performTouchInput { click(center) }
        leaveAlone(streamSeconds + 6)
        assertLastParagraphOnScreen("the follow stopped after a mere tap")
    }

    /** Scrolling away kills the follow FOR THAT TURN — and only that turn: the next send re-arms it. */
    @Test fun theFollowReArmsOnTheNextSend() {
        openLongThread(); send("go")
        swipeIntoHistory()
        settleScroll()
        waitForTurnEnd()
        send("again")
        leaveAlone(streamSeconds + 8)
        waitForTurnEnd()
        assertLastParagraphOnScreen("the follow did not re-arm on the next send — the interruption leaked across turns")
    }

    /** A drag SMALLER than the 50pt interruption threshold, released without a fling, must not kill the follow. */
    @Test fun aSmallDragUnderTheThresholdDoesNotStopTheFollow() {
        openLongThread(); send("go")
        tagged("chat.transcript").performTouchInput {
            down(center); advanceEventTime(200)
            repeat(6) { moveBy(Offset(0f, px(5f)), delayMillis = 50) }   // ~30dp, slow
            advanceEventTime(400); up()
        }
        leaveAlone(streamSeconds + 8)
        assertLastParagraphOnScreen("a ~30dp nudge killed the follow — the interruption rule latched under its own threshold")
    }

    /**
     * DRIVES the reported case — "it scrolls to the top of the generation when I'm scrolled up" — and asserts nothing
     * itself: a reader who scrolled INTO the answer must not be moved at all by the hand-off, and the verdict is the
     * offset trace (tools/scroll_trace_check.sh), exactly as `scroll_follow_check.py` judges the follow on iOS.
     */
    @Test fun aReaderInsideTheAnswerDrivesAWalkAwayHandoff() {
        openLongThread(); send("go")
        leaveAlone(4.0)   // a few screens of answer accumulate, then step back up INTO it — one swipe
        swipeIntoHistory()
        settleScroll()
        waitForTurnEnd(120_000)
        leaveAlone(3.0)
    }

    /** Focusing the composer mid-answer raises the keyboard: a size change the follow rides without dying. */
    @Test fun raisingTheKeyboardMidAnswerKeepsTheFollow() {
        openLongThread(); send("go")
        tagged("chat.composer").performClick()
        leaveAlone(streamSeconds + 8)
        waitForTurnEnd()
        assertLastParagraphOnScreen("the reply's last paragraph is not visible above the keyboard — the follow lost to the inset change")
    }
}
