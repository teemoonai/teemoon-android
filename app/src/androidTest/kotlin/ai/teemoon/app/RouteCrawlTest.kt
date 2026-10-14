package ai.teemoon.app

// android-only: the screen crawl. Every route in RouteCodec.CRAWL_SAMPLES is opened directly — not navigated to —
// screenshotted, and checked against the invariants that need NO per-screen expectation.
//
// WHAT THIS IS FOR, and what it is not. ParityCaptureTest already captures screens by walking hand-scripted paths,
// which covers what someone remembered to script. This covers what the app HAS: coverage comes from the route
// table, so adding a screen adds a crawl case without anyone deciding to. the 2026-09 port review (§5) records that 19
// of ~39 surfaces have no pixel record at all; this is the cheapest way to stop that being true.
//
// It cannot tell you a screen is MISSING something. Nothing generic knows the encrypted-model CTA should be on the
// not-attestable screen — that is the expectation problem, and the 2026-09-21 device session found it by eye. What
// this catches is the class that needs no expectation: a screen that crashes, renders nothing, leaks a `null` into
// visible text, or has no way back out of it.
//
// AVD ONLY, and not only because of the taps it does not do: the launch extras REPLACE the configured places, so
// pointing this at a real phone discards its provider setup before the first screenshot. The developer's Pixel is
// not a test device (a connected run wiped it on 2026-09-17).
//
// READ THE SHOTS FOR THE SCREEN, NOT THE BACKDROP. A route opened directly has nothing behind it, so every sheet
// sits over a black void where the chat would be. That is an artefact of launching straight into a route and not
// a design reference; ParityCaptureTest, which walks in through the app, is the one to look at for how a sheet
// reads over its presenter.

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.semantics.SemanticsNode

@RunWith(AndroidJUnit4::class)
class RouteCrawlTest {
    @get:Rule val compose = emptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val outDir: File by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "crawl").apply { mkdirs() }
    }

    /** Text that means the app printed a value it did not have. None of these is ever deliberate copy. */
    private val LEAKED = listOf("null", "NaN", "Infinity", "???", "kotlin.", "androidx.", "java.lang.", "%s", "%d", "{0}")

    private fun launch(route: String): ActivityScenario<MainActivity> {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra(UiTestSeeding.EXTRA_FLAG, true)
            .putExtra(UiTestSeeding.EXTRA_PRESETS, "nearai,grok,fireworks")
            .putExtra(UiTestSeeding.EXTRA_THREADS, true)
            .putExtra("seed_route", route)
        return ActivityScenario.launch(intent)
    }

    /** Every string the screen is showing, from the semantics tree. */
    private fun visibleText(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out.add(it) }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    private fun shot(name: String) {
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * THE CRAWL. One test, not one per route: a route that fails should not stop the others being captured, so
     * every failure is collected and reported together — a crawl that stops at the first problem tells you about
     * one screen when it could have told you about all of them.
     */
    @Test fun everyRouteOpensRendersAndSaysSomething() {
        val problems = ArrayList<String>()
        for (route in RouteCodec.CRAWL_SAMPLES) {
            val name = route.replace(Regex("[^A-Za-z0-9]+"), "_")
            try {
                launch(route).use {
                    compose.waitForIdle()
                    Thread.sleep(900)   // the seeding task and the first attestation refresh land off the main thread
                    val text = visibleText()
                    shot(name)

                    // 1. IT RENDERED SOMETHING. An empty screen is the failure mode a route-driven crawl is best at:
                    //    a screen reachable in principle, wired to state that never arrives.
                    if (text.none { it.isNotBlank() }) problems += "$route: rendered no text at all"

                    // 2. NO LEAKED VALUES. "null" on screen is always a bug and never copy.
                    for (t in text) for (bad in LEAKED) {
                        if (t.contains(bad, ignoreCase = false)) problems += "$route: shows \"$bad\" in \"${t.take(60)}\""
                    }

                    // 3. A WAY BACK. Every screen but the root must offer one, or it is a trap — and the crawl is
                    //    the only thing that visits a screen without having walked in through its own entrance.
                    //    CONTAINS, not equals. The first run of this reported `settings` as a trap, because the
                    //    settings sheet's close button had just been relabelled "close settings" for TalkBack —
                    //    a real improvement that an equality check reads as the affordance vanishing. A generic
                    //    invariant that punishes a better label is a generic invariant nobody will keep.
                    if (route != "threads" && !route.startsWith("chat")) {
                        val exits = text.count { t ->
                            listOf("back", "close", "cancel", "done").any { t.contains(it, ignoreCase = true) }
                        }
                        if (exits == 0) problems += "$route: no back, close, cancel or done — nothing to leave by"
                    }
                }
            } catch (e: Throwable) {
                problems += "$route: threw ${e::class.simpleName}: ${e.message?.take(120)}"
            }
        }
        assertTrue("screen crawl found ${problems.size} problem(s):\n" + problems.joinToString("\n") { "  · $it" },
            problems.isEmpty())
    }
}
