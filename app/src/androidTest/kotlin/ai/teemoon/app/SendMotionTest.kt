// A photo send is one motion (the maintainer 2026-11-12, becd1277; pm 2026-11-13: iOS failed this on a new chat's first
// send), the iOS way (the maintainer 2026-11-13): the strip goes on the send frame, only the panel's height animates. In real time on a device with its real keyboard (the AVD's animation scales at 1): after the send tap there is
// one composer in every frame, its height never grows while the strip folds and the keyboard goes down, and no photo is
// left. In a new chat (its first send) and in a chat that already has a turn.
//
// Read from the app's own geometry trace (ComposerTrace, `scroll_trace` launches only), not by polling the tree: a poll
// steps the test clock and the keyboard's slide runs on the real one, so a stepped run could not see the two together.
// The series goes to logcat (teemoon-motion) and the app's files/send-motion/<case>.tsv.
package ai.teemoon.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class SendMotionTest {
    @get:Rule val compose = emptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var server: FakeSseServer
    private var scenario: ActivityScenario<MainActivity>? = null

    // a fake Ollama that reads images, and short replies so a turn ends quickly
    @Before fun start() {
        server = FakeSseServer(lines = 2, delayMs = 20, ollama = true, ollamaVision = true)
        // the first send asks for notifications; the system's dialog would cover the chat mid-sample
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun stop() { scenario?.close(); server.close() }

    /**
     * Real time: Compose's test clock stepped 16 ms per 16 ms of wall time, ON the main thread, so the strip's spring and the
     * system's keyboard slide run together as on a phone. Left to auto-advance, the test clock only moves when the test
     * idles it, and during a wait the strip neither grew nor folded while the keyboard still slid.
     */
    private fun pump(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) { val next = System.nanoTime() + 16_000_000; inst.runOnMainSync { compose.mainClock.advanceTimeBy(16) }; while (System.nanoTime() < next) Thread.sleep(1) }
    }
    private fun until(ms: Long, why: String, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond()) { assertTrue(why, System.currentTimeMillis() < end); pump(50) } }
    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private fun shown(tag: String) = nodes(tag).filter { it.boundsInRoot.height > 0f && it.boundsInRoot.width > 0f }

    private fun launch() {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, server.baseUrl)
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
            putExtra(UiTestSeeding.EXTRA_LOCAL_KIND, "ollama")
            putExtra("scroll_trace", true)   // ComposerTrace's lines
        }
        scenario = ActivityScenario.launch(intent)
        compose.mainClock.autoAdvance = false
        until(20_000, "the composer never appeared") { shown("chat.composer").isNotEmpty() }
        pump(2_500)   // the seed runs off the main thread
        // The where sheet reads the machine's models (HomeCapabilityRefresh), so the photo goes straight to a model known to
        // see. Without it the send asks the machine first (recheckImages) and resumes after: the app resumes on the main
        // thread, the compose test rule on the IO thread, where the send's focus clear threw.
        compose.onAllNodesWithTag("chat.whereChip")[0].performClick()
        until(20_000, "the where sheet never opened") { compose.onAllNodes(androidx.compose.ui.test.hasText("ready now")).fetchSemanticsNodes().isNotEmpty() }
        pump(3_000)
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        until(20_000, "the where sheet never closed") { compose.onAllNodes(androidx.compose.ui.test.hasText("ready now")).fetchSemanticsNodes().isEmpty() }
        pump(1_000)
    }

    /** A real 600×400 PNG in the app's own files, offered as the clipboard's image (no MediaStore, nothing of the phone's). */
    private fun pastePhoto() {
        val f = File(ctx.filesDir, "attachments").apply { mkdirs() }.let { File(it, "send-motion.png") }
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.rgb(230, 120, 40)) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        inst.runOnMainSync { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(uri))) }
        compose.onAllNodesWithTag("chat.composer")[0].performSemanticsAction(SemanticsActions.PasteText)
        until(20_000, "the photo never reached the strip") { shown("chat.image.remove").isNotEmpty() }
        pump(800)   // the strip's arrival spring
    }

    private data class Frame(val t: Double, val id: Int, val top: Float, val height: Int, val photos: Int, val drawn: Int)

    private fun trace(): List<Frame> = File(ctx.filesDir, "scrolltrace.log").readLines().mapNotNull { l ->
        if (!l.startsWith("[composertrace]")) return@mapNotNull null
        val f = l.split(" ").drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        Frame(f.getValue("t").toDouble(), f.getValue("id").toInt(), f.getValue("top").toFloat(), f.getValue("h").toInt(), f.getValue("photos").toInt(), f.getValue("drawn").toInt())
    }

    /** Keyboard up, a photo in the strip, a line typed; then the send, and the trace from the frame before it to 2.5 s after. */
    private fun sendAndTrace(case: String): Pair<Frame, List<Frame>> {
        compose.onAllNodesWithTag("chat.composer")[0].performClick(); pump(900)   // the keyboard up, as a person sends
        pastePhoto()
        compose.onAllNodesWithTag("chat.composer")[0].performTextInput("what colour is this?"); pump(600)
        val t0 = System.nanoTime() / 1e9
        val before = trace().last { it.t < t0 }
        Log.i("teemoon-motion", "$case send-wall ${System.currentTimeMillis()}")   // where a screen recording's frame strip starts
        compose.onAllNodesWithTag("chat.send")[0].performClick()
        pump(2_500)
        val after = trace().filter { it.t >= t0 }
        val rows = listOf(before) + after
        val tsv = "ms\tid\ttop\theight\tphotos\tdrawn\n" + rows.joinToString("\n") { "${((it.t - t0) * 1000).toInt()}\t${it.id}\t${it.top}\t${it.height}\t${it.photos}\t${it.drawn}" }
        File(ctx.filesDir, "send-motion").apply { mkdirs() }.let { File(it, "$case.tsv").writeText(tsv) }
        tsv.lines().forEach { Log.i("teemoon-motion", "$case $it") }
        return before to after
    }

    private fun assertOneMotion(case: String, traced: Pair<Frame, List<Frame>>) {
        val (before, after) = traced
        assertTrue("$case: the composer moved after the send", after.isNotEmpty())
        assertEquals("$case: one composer, before and after the send: ${(listOf(before) + after).map { it.id }.distinct()}", setOf(before.id), after.map { it.id }.toSet())
        val heights = listOf(before.height) + after.map { it.height }
        val grew = heights.zipWithNext().firstOrNull { (a, b) -> b > a + 1 }
        assertEquals("$case: the composer's height never grows after the send (px, frame to frame): $heights", null, grew)
        assertTrue("$case: shorter without the photo (${before.height} → ${heights.last()})", heights.last() < before.height)
        assertEquals("$case: no photo once the send settles", 0, after.last().photos)
        // The maintainer 2026-11-13 ("Ios way"): the strip goes on the send frame as the text does, and only the panel's height
        // animates, in the keyboard's frames — as iOS, Messages and Google Messages do
        assertEquals("$case: no photo drawn from the first frame after the tap: ${after.map { it.drawn }}", 0, after.maxOf { it.drawn })
        val keyboardEnds = after.last { it.top != after.last().top }.t
        val heightEnds = after.last { it.height != after.last().height }.t
        assertTrue("$case: the height settles with the keyboard (height ${((heightEnds - keyboardEnds) * 1000).toInt()} ms after it)",
            kotlin.math.abs(heightEnds - keyboardEnds) <= 0.05)
    }

    @Test fun aNewChatsFirstPhotoSendIsOneMotion() {
        launch()
        assertOneMotion("new-chat", sendAndTrace("new-chat"))
    }

    @Test fun aPhotoSendInAChatWithATurnIsOneMotion() {
        launch()
        sendAndTrace("first")
        until(60_000, "the first turn never finished") { shown("chat.stop").isEmpty() }
        pump(1_500)
        assertOneMotion("existing-chat", sendAndTrace("existing-chat"))
    }
}
