// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every push and pop in the app, one at a time (the maintainer, 2026-10-19: "sometimes you would actually see the new screen
 * animating in rather than it being smooth"). An iteration opens the hop's starting screen (setup, not measured), then
 * pushes, rests, pops and rests. FrameTimingMetric covers the frames; TransitionMetric splits them into the animation
 * and what drew after it ended — content that lands after the slide is the second failure, invisible to frame times.
 *
 * The world: the 200-turn long thread, 50 short threads, and one keyless home server ("bench-model" at the emulator's
 * host address, already on the §4 list — nothing here sends a turn, and the server never answers).
 *
 *   -e transitions_only threads,settings     run only the named hops
 */
@RunWith(Parameterized::class)
class TransitionBenchmarks(private val hop: Hop) {
    @get:Rule val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test fun transition() {
        val only = InstrumentationRegistry.getArguments().getString("transitions_only")?.split(",")?.map { it.trim() }
        assumeTrue("not in transitions_only", only == null || hop.name in only)
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric(), TransitionMetric(hop.destination), MainThreadWorkMetric("transition ${hop.name}")),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = { animationsOn(); hop.from(this); device.waitForIdle(); Thread.sleep(REST_MS) },
        ) {
            hop.push(this); hop.pushed?.let { check(device.wait(Until.hasObject(it), WAIT_MS)) { "${hop.name}: never landed" } }
            Thread.sleep(REST_MS)
            hop.pop(this); hop.popped?.let { check(device.wait(Until.hasObject(it), WAIT_MS)) { "${hop.name}: never came back" } }
            Thread.sleep(REST_MS)
        }
    }

    /**
     * One hop: [from] reaches its starting screen, [push] goes, [pushed] is on screen once it has, [pop] comes back.
     * [destination] is the pushed route's RouteCodec name, as its `screen:` trace section carries it.
     */
    class Hop(val name: String, val destination: String, val from: MacrobenchmarkScope.() -> Unit, val push: MacrobenchmarkScope.() -> Unit,
              val pushed: BySelector?, val pop: MacrobenchmarkScope.() -> Unit = { device.pressBack() }, val popped: BySelector?) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun hops(): List<Hop> {
            val chat: MacrobenchmarkScope.() -> Unit = { openBenchChat() }
            val list: MacrobenchmarkScope.() -> Unit = { openBenchChat(); tap(By.desc("chats")); await(By.res(THREADS_LIST)) }
            // a SHORT chat open, then the list: tapping the long thread from here is a cold open — its view model and rows
            // have never been built in this process (the activity is recreated each iteration)
            val listFromShort: MacrobenchmarkScope.() -> Unit = { openBenchChat(short = true); tap(By.desc("chats")); await(By.res(THREADS_LIST)) }
            val settings: MacrobenchmarkScope.() -> Unit = { openBenchChat(); tap(By.desc("settings")); await(By.text("appearance")) }
            val places: MacrobenchmarkScope.() -> Unit = { settings(); tapLast(By.text("places & keys")); await(By.text("home machines")) }
            val where: MacrobenchmarkScope.() -> Unit = { openBenchChat(); tap(By.text(BENCH_MODEL)); await(By.text("connect a computer")) }
            val inChat = By.res(TRANSCRIPT); val inSettings = By.text("appearance"); val inPlaces = By.text("home machines")
            return listOf(
                // the chat's own sheets
                // back on the chats list leaves the app (MainActivity's back handler stands down there), so this pop is its close button
                Hop("threads", "threads", chat, { tap(By.desc("chats")) }, By.res(THREADS_LIST), pop = { tap(By.desc("close")) }, popped = inChat),
                Hop("where", "where", chat, { tap(By.text(BENCH_MODEL)) }, By.text("connect a computer"), popped = inChat),
                Hop("trust", "trust", chat, { tap(By.text(LONG_THREAD_TITLE)) }, null, popped = inChat),
                Hop("settings", "settings", chat, { tap(By.desc("settings")) }, inSettings, popped = inChat),
                Hop("modelcard", "modelcard", chat, { device.findObject(By.text(BENCH_MODEL))?.longClick() ?: error("no chip"); tap(By.text("model info")) }, null,
                    // not a route: back in the chat opens the chats list, so the card is closed by its own button
                    pop = { tap(By.text("back")) }, popped = inChat),
                // the chats list into a chat: the long one (200 turns) and a short one
                Hop("open-long-chat", "chat", listFromShort, { tap(By.text(LONG_THREAD_TITLE)) }, By.textContains("eply"), popped = By.res(THREADS_LIST)),
                Hop("open-short-chat", "chat", list, { tap(By.textStartsWith("thread 1:")) }, By.textStartsWith("Three steps"), popped = By.res(THREADS_LIST)),
                // pushes inside the settings sheet
                Hop("appearance", "appearance", settings, { tap(By.text("appearance")) }, By.text("color"), popped = inSettings),
                Hop("chats-settings", "chats", settings, { tap(By.text("chats")) }, null, popped = inSettings),
                Hop("search-settings", "search", settings, { tap(By.text("search")) }, null, popped = inSettings),
                // the scroll to the row is setup: inside the measure it counted as frames after the slide
                Hop("credits", "credits", { settings(); settle(inSettings); raiseSheet(); settle(inSettings); reveal(By.text("credits")) }, { tap(By.text("credits")) }, null, popped = By.text("credits")),
                Hop("places-keys", "placeshub", settings, { tapLast(By.text("places & keys")) }, inPlaces, popped = inSettings),
                Hop("home-machines", "manage", places, { tap(By.text("home machines")) }, null, popped = inPlaces),
                Hop("cloud-keys", "manage", places, { tap(By.text("cloud keys")) }, By.text("no cloud keys yet."), popped = inPlaces),
                // where's child sheet: the add form (its field takes focus and raises the keyboard)
                Hop("add-computer", "add", where, { tap(By.text("connect a computer")) }, null,
                    // the first back only lowers the keyboard the form raised
                    pop = { if (imeShown()) { device.pressBack(); device.waitForIdle() }; device.pressBack() }, popped = By.text("connect a computer")),
            )
        }
    }
}

const val BENCH_MODEL = "bench-model"

/**
 * The lab's phones run with animations scaled to 0 (Test Lab, 2026-10-20: every push measured 3–22 ms against ~750 ms
 * on the AVD), so no slide was drawn. A transition benchmark needs them at the user's default.
 */
fun MacrobenchmarkScope.animationsOn() {
    for (k in listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")) device.executeShellCommand("settings put global $k 1")
}
private const val WAIT_MS = 15_000L
/** Long enough for any spring to land and anything late to draw; frames after the animation are counted up to here. */
private const val REST_MS = 1_500L

private fun MacrobenchmarkScope.tap(selector: BySelector) {
    (device.wait(Until.findObject(selector), WAIT_MS) ?: error("nothing matches $selector")).click()
}
/** "places & keys" is both the section header and the row; the row is the later one. */
private fun MacrobenchmarkScope.tapLast(selector: BySelector) {
    check(device.wait(Until.hasObject(selector), WAIT_MS)) { "nothing matches $selector" }
    device.findObjects(selector).last().click()
}
/** Drags the sheet up to its large detent, where a short screen (the AVD's) shows settings' last row. */
private fun MacrobenchmarkScope.raiseSheet() {
    val h = device.displayHeight; val x = device.displayWidth / 2
    device.swipe(x, h * 6 / 10, x, h / 10, 30)
}
/**
 * Scrolls the raised sheet until [selector] is on screen. The large detent shows settings' last row on the AVD, not on
 * the Pixel 8a (Test Lab 2026-10-21: "nothing matches credits" there, every iteration).
 */
private fun MacrobenchmarkScope.reveal(selector: BySelector) {
    val h = device.displayHeight; val x = device.displayWidth / 2
    for (i in 0 until 6) { if (device.hasObject(selector)) break; device.swipe(x, h * 7 / 10, x, h * 3 / 10, 20); device.waitForIdle() }
    check(device.wait(Until.hasObject(selector), WAIT_MS)) { "never saw $selector" }
    device.waitForIdle()
}
/**
 * Waits until [selector]'s bounds hold still: the sheet is done sliding. waitForIdle returns while it still moves (Test Lab
 * 2026-10-21, Pixel 10 Pro: raiseSheet's drag began 60 ms after "appearance" showed, the sheet never rose, and the six
 * scrolls never brought "credits" on screen).
 */
private fun MacrobenchmarkScope.settle(selector: BySelector) {
    var last: android.graphics.Rect? = null
    val end = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < end) {
        val now = device.findObject(selector)?.visibleBounds
        if (now != null && now == last) return
        last = now; Thread.sleep(100)
    }
}
private fun MacrobenchmarkScope.imeShown() = device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")
private fun MacrobenchmarkScope.await(selector: BySelector) { check(device.wait(Until.hasObject(selector), WAIT_MS)) { "never saw $selector" }; device.waitForIdle() }

/**
 * The seeded world plus the keyless home server, onto the chats list, then the long thread ([short]: a short one) opened through it (a
 * seeded `chat:` route names the screen, not the current thread — see seedAndOpenLongChat).
 */
fun MacrobenchmarkScope.openBenchChat(short: Boolean = false) {
    startActivityAndWait { intent: Intent ->
        intent.putExtra("uitesting", true)
        intent.putExtra("seed_long_thread", LONG_THREAD_TURNS)
        intent.putExtra("seed_many_threads", MANY_THREADS)
        intent.putExtra("seed_local_endpoint", "http://10.0.2.2:9/v1")
        intent.putExtra("seed_local_model", BENCH_MODEL)
        intent.putExtra("seed_route", "threads")
    }
    check(device.wait(Until.hasObject(By.textStartsWith("thread 0:")), WAIT_MS)) { "the seeded threads never appeared" }
    tap(if (short) By.textStartsWith("thread 1:") else By.text(LONG_THREAD_TITLE))
    await(By.res(TRANSCRIPT))
    check(device.wait(Until.hasObject(if (short) By.textStartsWith("Three steps") else By.textContains("eply")), WAIT_MS)) { "the chat never loaded" }
    check(device.wait(Until.hasObject(By.text(BENCH_MODEL)), WAIT_MS)) { "the seeded place never reached the chip" }
}
