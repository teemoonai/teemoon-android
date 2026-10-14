package ai.teemoon.app

// android-only: on a debuggable build, a launch's test extras are honoured only when seeding is armed by something
// another app cannot produce (UiTestSeeding.armed). Before, any app could send uitesting=true with seed_brave_endpoint
// and point the e2e build's Brave key and the model's search queries at its own host (the local audit, 2026-10-13).
// The scripts' marker carries a token the arming launch names, and expires (the local audit of 5af7d25, 2026-10-14).

import ai.teemoon.inference.BraveWebSearchTool
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeedGateTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = BraveWebSearchTool.endpoint

    @After fun restore() { UiTestSeeding.instrumentationArmed = true; BraveWebSearchTool.endpoint = original }

    private fun launchWithTheRedirect(token: String? = null) = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra(UiTestSeeding.EXTRA_FLAG, true).putExtra(UiTestSeeding.EXTRA_BRAVE_ENDPOINT, "https://attacker.example/llm/context")
        .apply { token?.let { putExtra(UiTestSeeding.EXTRA_ARM, it) } }).close()

    private val token = "0123456789abcdef0123456789abcdef"
    private fun marker() = File(ctx.filesDir, UiTestSeeding.ARM_FILE).apply { writeText("$token\n") }

    /** What another app's launch looks like: the extras, but nothing armed. They change nothing. */
    @Test fun anUnarmedLaunchIsNotSeeded() {
        UiTestSeeding.instrumentationArmed = false
        File(ctx.filesDir, UiTestSeeding.ARM_FILE).delete()
        launchWithTheRedirect()
        assertEquals("an unarmed launch moved the Brave endpoint", original, BraveWebSearchTool.endpoint)
    }

    /**
     * The adb scripts' marker arms ONE launch and is gone: the scripts never deleted it, so on the dev phone it outlived
     * the run and any app's launch could seed again (the local audit of 15df68e, 2026-10-13).
     */
    @Test fun theScriptsMarkerArmsOneLaunchOnly() {
        UiTestSeeding.instrumentationArmed = false
        val marker = marker()
        launchWithTheRedirect(token)
        assertEquals("the marker did not arm the launch", "https://attacker.example/llm/context", BraveWebSearchTool.endpoint)
        org.junit.Assert.assertFalse("the marker outlived the launch it armed", marker.exists())
        BraveWebSearchTool.endpoint = original
        launchWithTheRedirect(token)
        assertEquals("a second launch was seeded on a spent marker", original, BraveWebSearchTool.endpoint)
    }

    /**
     * Between the script writing the marker and its own launch, another app's launch is the one that reads it: it names
     * no token (or a guess), so it is not seeded — and it spends the marker (the local audit of 5af7d25, 2026-10-14).
     */
    @Test fun aLaunchThatCannotNameTheTokenIsNotSeeded() {
        UiTestSeeding.instrumentationArmed = false
        val marker = marker()
        launchWithTheRedirect()
        assertEquals("a launch without the token was seeded on the marker", original, BraveWebSearchTool.endpoint)
        org.junit.Assert.assertFalse("the marker outlived a launch", marker.exists())
        marker()
        launchWithTheRedirect("fedcba9876543210fedcba9876543210")
        assertEquals("a launch with the wrong token was seeded", original, BraveWebSearchTool.endpoint)
    }

    /** A marker a killed script left behind expires: past a minute it arms nothing, token or not. */
    @Test fun aStaleMarkerArmsNothing() {
        UiTestSeeding.instrumentationArmed = false
        marker().setLastModified(System.currentTimeMillis() - UiTestSeeding.ARM_MAX_AGE_MS - 5_000)
        launchWithTheRedirect(token)
        assertEquals("a stale marker armed the launch", original, BraveWebSearchTool.endpoint)
    }

    /** The suites' own launches still seed — armed by the runner. */
    @Test fun anArmedLaunchIsSeeded() {
        UiTestSeeding.instrumentationArmed = true
        launchWithTheRedirect()
        assertEquals("https://attacker.example/llm/context", BraveWebSearchTool.endpoint)
    }
}
