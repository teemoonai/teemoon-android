// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to first frame, on a seeded store (the setup launch seeds; the process is killed before each measured
 * launch, which is a plain launcher start with no extras). Two compilation modes: `None` is a fresh install with no
 * profile, `Partial` applies the Baseline Profile — the difference is what the profile buys.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartupNoProfile() = coldStartup(CompilationMode.None())
    @Test fun coldStartupBaselineProfile() = coldStartup(CompilationMode.Partial())

    private fun coldStartup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { seedAndOpenThreadList(); killProcess() },
    ) {
        pressHome()
        startActivityAndWait()
    }
}
