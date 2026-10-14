package ai.teemoon.app

// android-only: I4 (docs/USE_CASES.md). Every route in RouteCodec.CRAWL_SAMPLES survives the activity being torn down
// and rebuilt from its saved state — the path process death takes (the route rides the same RouteCodec Saver). Opened
// straight onto the route, recreated, and the screen must come back: most of what was on it, on it again. Like the
// crawls it is AVD-only: the launch extras REPLACE the configured places.

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteRestoreTest {
    @get:Rule val compose = emptyComposeRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()

    /** The visible text on screen, minus what changes by itself (times, counts of seconds). */
    private fun texts(): Set<String> = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: SemanticsNode): List<String> =
            (if (n.boundsInRoot.height > 0) n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() else emptyList()) + n.children.flatMap { walk(it) }
        walk(root)
    }.map { it.trim().lowercase() }.filter { it.length > 2 && !Regex("\\d{1,2}:\\d{2}|\\d+s\\b").containsMatchIn(it) }.toSet()

    @Test fun everyRouteComesBackAfterTheActivityIsRebuilt() {
        val problems = ArrayList<String>()
        for (route in RouteCodec.CRAWL_SAMPLES) {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)
                .putExtra(UiTestSeeding.EXTRA_FLAG, true).putExtra(UiTestSeeding.EXTRA_PRESETS, "nearai,grok,fireworks")
                .putExtra(UiTestSeeding.EXTRA_THREADS, true).putExtra("seed_route", route))
            try {
                compose.waitUntil(20_000) { texts().isNotEmpty() }   // a sheet route hides the chat (and its composer) from semantics
                Thread.sleep(2_500)
                val before = texts()
                scenario.recreate(); Thread.sleep(2_500)
                val after = texts()
                val kept = before.count { it in after }
                // 0.7: the screen must come back — its title and most of its rows. On the Pixel 10 Pro every route comes
                // back whole (>= 0.95, 2026-10-05); on the AVD the rebuilt sheet measures a window ~200px shorter (its
                // inset state after a recreate), so the last rows of the long pages — credits, search — start below the
                // edge. An emulator artefact, not one a phone shows; the threshold leaves room for it.
                if (before.isNotEmpty() && kept < before.size * 0.7)
                    problems += "$route: ${kept}/${before.size} came back; missing ${(before - after).take(6)}"
            } finally { scenario.close() }
        }
        assertTrue("routes that did not come back:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
