// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Baseline Profile: the classes and methods a user's first minute runs, compiled ahead of time at install.
 *
 *     ./gradlew :app:generateBaselineProfile      (API 33+ device or the teemoon AVD; never the owner's phone)
 *
 * writes app/src/main/generated/baselineProfiles/baseline-prof.txt — commit it. No startup profile: that one orders the DEX
 * for R8, and :app is not minified. The journey: a cold start
 * onto the long thread (the newest, so the launch opens it), the chats list, opening a thread from it, flinging the
 * 200-message transcript into history, and flinging the list. StartupBenchmark's None/Partial pair measures the result.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        // the seed runs on its own launch, then a plain cold start — the launch a user makes
        seedAndOpenThreadList()
        killProcess()
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res(TRANSCRIPT)), 15_000)) { "no $TRANSCRIPT after launch" }

        device.findObject(By.desc("chats"))?.click() ?: error("no chats button")
        check(device.wait(Until.hasObject(By.text(LONG_THREAD_TITLE)), 15_000)) { "the chats list never showed the long thread" }
        device.findObject(By.text(LONG_THREAD_TITLE)).click()
        check(device.wait(Until.hasObject(By.res(TRANSCRIPT)), 15_000)) { "opening the long thread showed no transcript" }
        device.waitForIdle()
        flingRepeatedly(TRANSCRIPT, Direction.UP, times = 3)

        device.findObject(By.desc("chats"))?.click() ?: error("no chats button")
        check(device.wait(Until.hasObject(By.res(THREADS_LIST)), 15_000)) { "no $THREADS_LIST" }
        flingRepeatedly(THREADS_LIST, Direction.DOWN, times = 2)
    }
}
