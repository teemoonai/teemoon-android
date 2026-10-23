// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.0.6 performance row 5: the composer over the 200-turn long thread. One iteration taps the field (the keyboard
 * rises), types a 60-character question a key at a time, and lowers the keyboard with back. Frame timing covers the
 * keyboard's two animations and every keystroke's frame; nothing is sent.
 */
@RunWith(AndroidJUnit4::class)
class ComposerBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun typeAndKeyboard() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MainThreadWorkMetric("composer")),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { openBenchChat(); device.waitForIdle() },
    ) {
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000L) ?: error("no composer")
        field.click()
        check(device.wait(Until.hasObject(By.clazz("android.widget.EditText").focused(true)), 5_000L)) { "the composer never took focus" }
        Thread.sleep(800)   // the keyboard's rise
        // one key event per character, as a hand types: `input text` with no spaces (each %s is a space)
        TYPED.split(" ").forEachIndexed { i, word -> device.executeShellCommand("input text ${if (i > 0) "%s" else ""}$word") }
        device.waitForIdle()
        device.pressBack()   // lowers the keyboard; the chat stays
        Thread.sleep(800)
    }

    private companion object {
        const val TYPED = "how would I check that the reply came from the attested model"
    }
}

/**
 * 1.0.6 performance row 15: back to the app from the home screen. HOT keeps the activity (a quick switch back);
 * WARM recreates it in the living process (the system reclaimed the activity, not the process). Both land on the
 * 200-turn long thread.
 */
@RunWith(AndroidJUnit4::class)
class ResumeBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun hotResume() = resume(StartupMode.HOT)
    @Test fun warmResume() = resume(StartupMode.WARM)

    private fun resume(mode: StartupMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = mode,
        // HOT keeps the activity between iterations, where a second seeded launch would not route anywhere: seed once
        setupBlock = {
            startActivityAndWait()
            if (!device.wait(Until.hasObject(By.res(TRANSCRIPT)), 3_000L) || !device.hasObject(By.text(BENCH_MODEL))) openBenchChat()
            device.waitForIdle(); pressHome()
        },
    ) {
        startActivityAndWait()
    }
}
