// Android-only (no Swift source): drives the app's UiTestSeeding from :macrobenchmark.
package ai.teemoon.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** :app's `benchmark` build type (applicationIdSuffix ".benchmark"). */
const val TARGET_PACKAGE = "ai.teemoon.benchmark"

/** The long thread (UiTestSeeding.seedLongThread) — 200 messages with code blocks, tables and think blocks. */
const val LONG_THREAD_TURNS = 200
const val LONG_THREAD_TITLE = "long thread ($LONG_THREAD_TURNS turns)"
/** The list fixture (UiTestSeeding.seedManyThreads). */
const val MANY_THREADS = 50

/** The app's testTags, exposed as resource ids on those two lists (testTagsAsResourceId). */
const val THREADS_LIST = "threads.list"
const val TRANSCRIPT = "chat.transcript"

private const val WAIT_MS = 15_000L

/**
 * Launches with UiTestSeeding's extras — `uitesting`, a 200-turn long thread and 50 short threads — onto [route]
 * (a RouteCodec string, e.g. `threads`). The benchmark build type is its own seeding arm
 * (BuildConfig.BENCHMARK_SEEDING); the seed is idempotent, so every launch after the first only routes.
 */
fun MacrobenchmarkScope.startSeeded(route: String) {
    startActivityAndWait { intent: Intent ->
        intent.putExtra("uitesting", true)
        intent.putExtra("seed_long_thread", LONG_THREAD_TURNS)
        intent.putExtra("seed_many_threads", MANY_THREADS)
        intent.putExtra("seed_route", route)
    }
}

/** Seeds, then waits for the list to show the newest short thread — the seed runs off the main thread. */
fun MacrobenchmarkScope.seedAndOpenThreadList() {
    startSeeded("threads")
    check(device.wait(Until.hasObject(By.textStartsWith("thread 0:")), WAIT_MS)) { "the seeded threads never appeared" }
    check(device.wait(Until.hasObject(By.res(THREADS_LIST)), WAIT_MS)) { "no $THREADS_LIST on screen" }
}

/**
 * Seeds, opens the chats list, and taps the long thread — the newest, so the first row. Through the list rather than a
 * `chat:` route: a seeded route names the screen but not the current thread, and the chat underneath stays empty.
 */
fun MacrobenchmarkScope.seedAndOpenLongChat() {
    seedAndOpenThreadList()
    val row = device.findObject(By.text(LONG_THREAD_TITLE)) ?: error("no \"$LONG_THREAD_TITLE\" row")
    row.click()
    check(device.wait(Until.hasObject(By.res(TRANSCRIPT)), WAIT_MS)) { "no $TRANSCRIPT on screen" }
    check(device.wait(Until.hasObject(By.textContains("eply")), WAIT_MS)) { "the long thread never loaded" }
    device.waitForIdle()
}

/**
 * Flings the list tagged [res] [times] times in [direction], waiting for idle between flings so each frame counted is
 * a scroll frame. ONE way only, on purpose: the chats list is a sheet, and a fling back up that reaches its top drags
 * the sheet down and dismisses it. Each iteration's warm start reopens the screen where it began (the seeded route),
 * so nothing is lost. The node is looked up before every fling: Compose replaces it as rows load, and a held one goes stale.
 */
fun MacrobenchmarkScope.flingRepeatedly(res: String, direction: Direction, times: Int) {
    repeat(times) {
        val list = device.findObject(By.res(res)) ?: error("no $res on screen")
        list.setGestureMargin(device.displayWidth / 5)   // keep the gesture off the system-gesture edges
        list.fling(direction)
        device.waitForIdle()
    }
}
