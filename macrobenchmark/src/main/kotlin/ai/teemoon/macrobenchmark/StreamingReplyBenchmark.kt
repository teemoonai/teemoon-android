// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while a ~6,000-character markdown reply STREAMS into an empty chat: headings, lists, a code block and a
 * table, a few characters a partial at ~30 tokens/s (UiTestSeeding's `seed_stream_reply` — ScriptedReplyTransport
 * through the real ChatViewModel and GenerationEngine; nothing goes over the wire). Each iteration types the prompt,
 * taps send, and waits for the stop button to give way to send again: the whole reply, about 50 s of streaming.
 */
@RunWith(AndroidJUnit4::class)
class StreamingReplyBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun streamLongMarkdownReply() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric(), StreamLagMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = { seedStreamingChat() },
        ) {
            sendAndAwaitReply()
        }
    }
}

/** The fixture's prompt (StreamingReplyFixture.PROMPT); any prompt is answered with the same reply. */
private const val PROMPT = "how would I check an attested reply myself?"
/** The fixture streams for ~50 s; the wait is generous for a slow emulator. */
private const val STREAM_WAIT_MS = 180_000L

/**
 * A fresh store, a keyless home server for a place (the emulator's host address, already on the §4 list: the scripted transport answers every turn, so the chat
 * path never dials it), and the scripted reply armed. Lands on the empty chat and types the prompt into the composer.
 */
fun MacrobenchmarkScope.seedStreamingChat() {
    // The first send asks for notifications (the turn service's); the system dialog would cover the transcript.
    device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS")
    startActivityAndWait { intent: Intent ->
        intent.putExtra("uitesting", true)
        intent.putExtra("seed_fresh_store", true)
        intent.putExtra("seed_local_endpoint", "http://10.0.2.2:9/v1")
        intent.putExtra("seed_local_model", "bench-model")
        intent.putExtra("seed_stream_reply", true)
    }
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000L) ?: error("no composer on screen")
    field.text = PROMPT
    check(device.wait(Until.hasObject(By.text(PROMPT)), 5_000L)) { "the prompt never reached the composer" }
    device.waitForIdle()
}

/** Taps send, waits for the reply to start (the stop button), then for it to finish (send is back). */
fun MacrobenchmarkScope.sendAndAwaitReply() {
    val send = device.findObject(By.desc("send")) ?: error("no send button")
    send.click()
    check(device.wait(Until.hasObject(By.desc("stop")), 15_000L)) { "the reply never started" }
    check(device.wait(Until.gone(By.desc("stop")), STREAM_WAIT_MS)) { "the reply never finished" }
}
