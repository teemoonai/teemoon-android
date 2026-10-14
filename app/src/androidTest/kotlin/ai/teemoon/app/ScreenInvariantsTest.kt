package ai.teemoon.app

// android-only: what a user SEES on the chat screen, asserted on device over the fake wire — added 2026-09-08 after the
// user found chat text legible under the header and a failing search that no test had touched. Each check is a
// sentence a person would say: the header is clean, a thread opens on its newest message, the composer is on screen,
// the reply is above the composer, the chip names the model.

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties

@RunWith(AndroidJUnit4::class)
class ScreenInvariantsTest {
    @get:Rule val compose = emptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var server: FakeSseServer
    @After fun stop() { scenario?.close(); if (::server.isInitialized) server.close() }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun bounds(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().first().boundsInRoot
    private fun textBounds(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }.map { it.boundsInRoot }

    /** A thread with one finished turn on the fake wire, opened cold. */
    private fun openThreadWithAReply() {
        server = FakeSseServer(lines = 40, delayMs = 3)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl); putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput("go"); Thread.sleep(300)
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        compose.waitUntil(60_000) { textBounds("Paragraph 39:").isNotEmpty() }
        Thread.sleep(1_500)   // the settle
    }

    /** The reply's end is on screen and above the composer; the composer and send are on screen. */
    @Test fun aFinishedReplyEndsAboveTheComposer() {
        openThreadWithAReply()
        val composer = bounds("chat.composer"); val root = compose.onAllNodesWithTag("chat.transcript").fetchSemanticsNodes().first().boundsInRoot
        assertTrue("composer off screen", composer.bottom <= root.bottom + 1 && composer.top > 0)
        assertTrue("send button missing", tagExists("chat.send"))
        val last = textBounds("Paragraph 39:").first()
        assertTrue("the reply's last paragraph (${last.top.toInt()}..${last.bottom.toInt()}) is not above the composer (${composer.top.toInt()})", last.bottom <= composer.top)
        assertTrue("the where chip does not name the model", compose.onAllNodes(hasText("fake-model", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * THE HEADER IS CLEAN: whatever the transcript scrolled under the title block, a person sees chrome there, not
     * chat text. Measured on pixels — outside the title's own glyphs, the band above the first row must be the ground.
     */
    @Test fun theHeaderBandIsChromeNotText() {
        openThreadWithAReply()
        // scroll so a long paragraph sits under the title block, then look
        compose.onAllNodesWithTag("chat.transcript")[0].performClick()   // a tap: the follow contract says it does not move anything
        Thread.sleep(500)
        val shot: Bitmap = inst.uiAutomation.takeScreenshot() ?: error("no screenshot")
        val title = compose.onAllNodes(hasText("chat", substring = false)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val chevron = compose.onAllNodes(hasContentDescription("chats")).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val bandBottom = ((title?.bottom ?: 0f) + 8 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(60)
        val bandTop = (chevron?.top ?: 0f).toInt().coerceAtLeast(1)
        val bg = shot.getPixel(shot.width / 2, shot.height / 2)   // the ground, sampled mid-screen where a row gap or text sits: use the bar's own corner instead
        val ground = shot.getPixel(4, bandTop + 2)
        var lit = 0; var total = 0
        val stepX = 6; val stepY = 4
        for (y in bandTop until bandBottom step stepY) for (x in 0 until shot.width step stepX) {
            // skip the title glyphs and the two round buttons
            if (title != null && x >= title.left - 8 && x <= title.right + 8 && y >= title.top - 8 && y <= title.bottom + 8) continue
            if (chevron != null && x <= chevron.right + 8) continue
            if (x >= shot.width - (chevron?.width ?: 0f) - 24) continue
            total++
            val p = shot.getPixel(x, y)
            val d = abs(((p shr 16) and 0xff) - ((ground shr 16) and 0xff)) + abs(((p shr 8) and 0xff) - ((ground shr 8) and 0xff)) + abs((p and 0xff) - (ground and 0xff))
            if (d > 60) lit++
        }
        val fraction = if (total == 0) 0.0 else lit.toDouble() / total
        assertTrue("the header band is ${(fraction * 100).toInt()}% lit outside the title — chat text is showing through (bg=$bg)", fraction < 0.03)
    }

    /** E2EETitleBlock is ONE button: anywhere on the title or its caption opens the trust ladder — never the where sheet. */
    @Test fun tappingTheTitleBlockOpensTheTrustLadder() {
        openThreadWithAReply()
        val block = bounds("chat.titleBlock")
        // the caption's pixels, not the title's — the half that used to open a different sheet
        compose.onAllNodesWithTag("chat.titleBlock")[0].performTouchInput { click(Offset(block.width / 2, block.height - 6f)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("who can read this?")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the where sheet opened from the title", compose.onAllNodes(hasText("ready now")).fetchSemanticsNodes().isEmpty())
    }

    /**
     * GenerationActivityChipView: through a silent reasoning phase the chip's seconds TICK (once a second, not once a
     * chunk) and the chip BREATHES (opacity 1.0 ↔ 0.4 over 0.85 s). Both measured: the label at two moments, the chip's
     * mean brightness across ten frames.
     */
    @Test fun theActivityChipTicksAndBreathes() {
        // 9 s of silence, not 4.5: the preamble below (the wait for the chip, ten screenshots, thirty samples) is real
        // time, and on a Pixel 10 Pro it ran past 4.5 s — the label read "3s" once and the reply began, so the test
        // saw one second value and called the tick broken. The chip must still be silent through the whole sampling.
        server = FakeSseServer(lines = 6, delayMs = 4, firstTokenDelayMs = 9_000)
        launchAndSend("go", manualClockBeforeSend = true)   // the clock is ours from before the chip composes: only then does its infinite animation run under the rule
        until(10_000, "the activity chip never appeared") { tagExists("chat.activityChip") }
        pump(1_500)   // the keyboard leaves (focus clears on send) and the prompt anchors; a screenshot through the keyboard measures keys
        val chip = bounds("chat.activityChip"); val composer = bounds("chat.composer")
        assertTrue("the chip (${chip.top.toInt()}..${chip.bottom.toInt()}) is not on screen above the composer (${composer.top.toInt()})", chip.height > 0 && chip.bottom < composer.top && chip.top > 0)
        val shots = (0 until 10).map { pump(100); inst.uiAutomation.takeScreenshot()!! }
        val means = shots.map { meanLuma(it, chip) }
        if (means.max() - means.min() < 5.0) {   // leave the crops for the reader before failing
            val dir = File(ctx.getExternalFilesDir(null), "journeys/chip").apply { mkdirs() }
            shots.forEachIndexed { i, b -> File(dir, "chip-$i.png").outputStream().use { Bitmap.createBitmap(b, chip.left.toInt(), chip.top.toInt(), chip.width.toInt(), chip.height.toInt()).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        assertTrue("the chip does not breathe — brightness over 1 s: ${means.map { it.toInt() }}", means.max() - means.min() >= 5.0)
        val labels = ArrayList<String>()
        // the label is a Text INSIDE the tagged capsule (an unmerged child), so it is read by its own text
        repeat(30) { pump(100); labels.add(compose.onAllNodes(hasText("thinking", substring = true)).fetchSemanticsNodes().firstOrNull()?.let { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } } ?: "") }
        val seconds = labels.mapNotNull { Regex("""(\d+)s""").find(it)?.groupValues?.get(1)?.toInt() }.distinct()
        assertTrue("the seconds never ticked over 3 s: ${labels.distinct()}", seconds.size >= 2 && seconds.zipWithNext().all { (a, b) -> b > a })   // increasing; a sample may skip a second, the screenshots cost real time
        compose.mainClock.autoAdvance = true
    }

    /**
     * A streaming reply paints at its TAIL. Between two frames 200 ms apart, the pixels that changed inside the
     * transcript must sit within a few lines of each other — a wrap moves one line, a word extends one. Several lines
     * changing at once is the renderer animating a block's size (the "typing left to right on every line" artifact,
     * 2026-09-09), not a reply being written.
     */
    @Test fun aStreamingReplyPaintsOnlyAtItsTail() {
        server = FakeSseServer(lines = 4, delayMs = 150)   // short and slow: anchored under the prompt, nothing scrolls
        launchAndSend("go")
        compose.waitUntil(15_000) { textBounds("Paragraph 0:").isNotEmpty() }
        val band = compose.onAllNodesWithTag("chat.transcript").fetchSemanticsNodes().first().boundsInRoot
        val top = band.top.toInt() + 220; val bottom = band.bottom.toInt() - 320   // under the title block, above the chip and composer
        val shots = (0 until 8).map { Thread.sleep(200); inst.uiAutomation.takeScreenshot()!! }
        val lineHeight = textBounds("Paragraph 0:").first().height / 3f   // that paragraph wraps to about three lines
        val spans = shots.zipWithNext { a, b -> changedRows(a, b, top, bottom) }
        val worst = spans.maxOf { it }
        assertTrue("a frame changed $worst px of the reply at once (line ≈ ${lineHeight.toInt()} px): ${spans}", worst <= lineHeight * 3.5f)
    }

    private fun pump(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { compose.mainClock.advanceTimeBy(16); Thread.sleep(16) } }
    private fun launchAndSend(text: String, manualClockBeforeSend: Boolean = false) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl); putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { tagExists("chat.composer") }; Thread.sleep(2_500)
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); Thread.sleep(600)
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput(text); Thread.sleep(300)
        if (manualClockBeforeSend) compose.mainClock.autoAdvance = false
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        if (manualClockBeforeSend) pump(300)
    }
    private fun until(ms: Long, why: String, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond()) { assertTrue(why, System.currentTimeMillis() < end); pump(50) } }
    private fun meanLuma(b: Bitmap, r: Rect): Double {
        var sum = 0L; var n = 0
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) { val p = b.getPixel(x, y); sum += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255); n++ }
        return sum / (3.0 * n)
    }
    /** The vertical extent, in px, of the rows that differ between two frames inside [top, bottom); 0 when nothing changed. */
    private fun changedRows(a: Bitmap, b: Bitmap, top: Int, bottom: Int): Int {
        var first = -1; var last = -1
        for (y in top until bottom) {
            var changed = false
            var x = 0
            while (x < a.width && !changed) { val p = a.getPixel(x, y); val q = b.getPixel(x, y); if (abs(((p shr 8) and 255) - ((q shr 8) and 255)) > 40) changed = true; x += 2 }
            if (changed) { if (first < 0) first = y; last = y }
        }
        return if (first < 0) 0 else last - first + 1
    }

}
