package ai.teemoon.app

// android-only: no Swift counterpart. The teemoon half of the final Gallery head-to-head (pm/features/ondevice-perf-parity.md,
// method (C) agreed 2026-10-26), driven INSIDE the app: Compose taps the composer and send, so no input can land in
// another app on the maintainer's phone (the coordinate runner once did). One instrumentation run = one prompt on a fresh store
// with the on-device model equipped and developer mode on; the Mac records the screen around it, and this test logs
// teemoon's own numbers — send, first reply text and last change as seen on screen (sampled every 50 ms), and the
// developer card's speed and timing lines — so video and in-app timing can be laid side by side.
//   -e prompt count | ok | short | long
//   -e settle <ms>  how long the launch warm-up gets before the send (default 3000): long enough and every send starts warm

import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiParityRunTest {
    @get:Rule val compose = emptyComposeRule()
    @get:Rule val pageSizeDialog = PageSizeDialogRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private fun log(s: String) { Log.w("teemoon", "[uiparity] $s"); ins.sendStatus(2, android.os.Bundle().apply { putString("uiparity", s) }) }

    @After fun close() { scenario?.close() }

    companion object {
        private val para = "The harbour town woke slowly. Fishing boats came back with the tide, gulls followed them in, and the market " +
            "opened its shutters one by one. A baker carried trays of bread across the square while the ferry sounded its horn. "
        private val passage = buildString { while (length < 4000) append(para) }
        /** The same prompts the Gallery side types (gallery_runs.py), word for word. */
        val PROMPTS = mapOf(
            "count" to "Count from 1 to 300, separated by commas.",
            "ok" to "$passage Reply with only the word OK.",
            "short" to "Write one sentence about the sea.",
            "long" to "$passage In two sentences, what happens in this passage?",
        )
    }

    private fun screenText(): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; n.text?.let { out.append(it).append('\n') }; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        runCatching { ins.uiAutomation.windows.forEach { walk(it.root) } }
        return out.toString()
    }
    private fun tagExists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun tagText(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    private fun composerText(): String = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test fun oneTurn() {
        val kind = InstrumentationRegistry.getArguments().getString("prompt") ?: "short"
        val prompt = PROMPTS.getValue(kind)
        assumeTrue("no on-device weights here", File(ctx.filesDir, "LocalModels").walkTopDown().any { it.name.endsWith(".litertlm") })
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_ON_DEVICE, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_DEVELOPER, true)
        })
        val until = System.currentTimeMillis() + 30_000
        while (!tagExists("chat.composer") && System.currentTimeMillis() < until) Thread.sleep(100)
        // the seed runs off the main thread, and the warm-up loads the engine: the turn starts warm once settle covers the load
        Thread.sleep(InstrumentationRegistry.getArguments().getString("settle")?.toLongOrNull() ?: 3_000)
        log("device ${Build.MODEL} prompt=$kind chars=${prompt.length}")
        compose.onAllNodes(hasTestTag("chat.composer"))[0].performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(prompt)
        Thread.sleep(800)
        compose.onAllNodes(hasTestTag("chat.send"))[0].performClick()
        val sentAt = System.currentTimeMillis()
        while (composerText().isNotEmpty() && System.currentTimeMillis() - sentAt < 3_000) Thread.sleep(10)
        val cleared = System.currentTimeMillis()
        // The reply is the screen's text minus what was there right after the send; first and last change, at 50 ms.
        val base = screenText()
        var first = 0L; var last = 0L; var prev = base; val deadline = cleared + 240_000
        while (System.currentTimeMillis() < deadline) {
            val t = screenText()
            if (t != prev) { val now = System.currentTimeMillis(); if (first == 0L && t.length > base.length) first = now; last = now; prev = t }
            if (first != 0L && !tagExists("chat.stop") && System.currentTimeMillis() - last > 2_000) break
            // 1.0.6's reply is not in the accessibility text (only "thinking..." is): the developer card's timing line,
            // written when the turn ends, is the finish line every build has
            if (first == 0L && !tagExists("chat.stop") && tagText("chat.debugCard.timing") != null) { last = System.currentTimeMillis(); break }
            Thread.sleep(50)
        }
        Thread.sleep(1_500)
        log("RESULT prompt=$kind sent=$sentAt cleared=${cleared - sentAt}ms first=${if (first > 0) first - cleared else -1}ms last=${if (last > 0) last - cleared else -1}ms")
        log("CARD stats=${tagText("chat.debug.localStats")} | timing=${tagText("chat.debugCard.timing")}")
        log("SCREEN ${screenText().replace("\n", " ⏎ ").take(1600)}")
    }
}
