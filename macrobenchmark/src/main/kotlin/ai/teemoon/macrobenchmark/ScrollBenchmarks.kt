// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing while flinging the chats list: 50 short threads plus the long one (`threads.list`). */
@RunWith(AndroidJUnit4::class)
class ThreadListScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun scrollThreadList() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = { seedAndOpenThreadList() },
        ) {
            flingRepeatedly(THREADS_LIST, Direction.DOWN, times = 4)
        }
    }
}

/**
 * Frame timing while flinging a 200-message transcript (`chat.transcript`) — code blocks, tables and folded think
 * blocks, the history the 2026-08-06 freeze needed. It opens at the bottom, so every fling is up into history — rows composed for the first time.
 */
@RunWith(AndroidJUnit4::class)
class LongChatScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun scrollLongChat() {
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = { seedAndOpenLongChat() },
        ) {
            flingRepeatedly(TRANSCRIPT, Direction.UP, times = 6)
        }
    }
}
