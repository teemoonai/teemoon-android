package ai.teemoon.app

// android-only: the timed clear of a copied key. It read the clipboard to check the key was still there, and Android
// 10+ answers null to an app without focus — so once the user left teemoon (the usual reason to copy a key: to paste
// it somewhere else) the clear never ran (the local audit, an INFO since 3910, 2026-10-13).

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardClearTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val secret = "sk-clipboard-test-0000000000000000"

    @Before fun shortLifetime() { Clipboard.lifetimeMs = 3_000 }
    @After fun restore() { Clipboard.lifetimeMs = Clipboard.SENSITIVE_LIFETIME_SECONDS * 1000 }

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** What is on the clipboard, read with focus (the activity in front, its window focused). */
    private fun clipText(scenario: ActivityScenario<MainActivity>): String? {
        var text: String? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { a -> focused = a.hasWindowFocus()
                if (focused) text = (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString() }
            if (focused) return text
            Thread.sleep(100)
        }
        error("the activity never took focus")
    }

    @Test fun aCopiedKeyIsClearedEvenAfterTheUserLeaves() {
        launch().use { s ->
            s.onActivity { a -> Clipboard.copySensitive(a, secret) }
            assertEquals(secret, clipText(s))
            ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(Clipboard.lifetimeMs + 2_000)
        }
        launch().use { s -> assertNotEquals("the key outlived its lifetime once the app had left", secret, clipText(s)) }
    }

    /**
     * The process dies inside the lifetime (swiped away, a low-memory kill): the clear is a system alarm as well, so it
     * still runs. The in-process timer alone left the key there — indefinitely on Android 10–12 (the local audit of
     * c0ed24c, 2026-10-13). Death is simulated by dropping the in-process timer and state; the alarm's own broadcast
     * is then delivered.
     */
    @Test fun aCopiedKeyIsClearedEvenIfTheProcessDies() {
        launch().use { s ->
            s.onActivity { a -> Clipboard.copySensitive(a, secret) }
            assertEquals(secret, clipText(s))
            // the alarm's PendingIntent exists only while a clear is scheduled (cancelPending cancels both)
            val scheduled = android.app.PendingIntent.getBroadcast(ctx, 0, Clipboard.clearBroadcast(ctx),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            org.junit.Assert.assertTrue("no system alarm stands behind the clear", scheduled != null)
            s.onActivity { Clipboard.simulateProcessDeath() }
            ctx.sendBroadcast(Clipboard.clearBroadcast(ctx))   // what the alarm delivers
            Thread.sleep(2_000)
            assertNotEquals("the key outlived the process", secret, clipText(s))
        }
    }

    /**
     * A force-stop inside the lifetime cancels the alarm too; the pending flag survives it, and the next start reads it
     * (TeemoonApplication.onCreate → Clipboard.onProcessStart). Nothing read it at launch before, so the key stayed (the
     * local audit of 15df68e, 2026-10-13).
     */
    @Test fun aCopiedKeyIsClearedOnTheNextStartAfterAForceStop() {
        launch().use { s ->
            s.onActivity { a -> Clipboard.copySensitive(a, secret) }
            assertEquals(secret, clipText(s))
            s.onActivity { a -> Clipboard.simulateProcessDeath(); Clipboard.simulateForceStop(a) }
            s.onActivity { a -> Clipboard.onProcessStart(a) }
            Thread.sleep(500)
            assertNotEquals("the key outlived a force-stop and a restart", secret, clipText(s))
        }
    }

    /** The alarm clearing first (the process alive) stops the in-process timer, which would clear a later copy. */
    @Test fun theAlarmsClearStopsTheTimer() {
        launch().use { s ->
            s.onActivity { a -> Clipboard.copySensitive(a, secret) }
            ctx.sendBroadcast(Clipboard.clearBroadcast(ctx))
            Thread.sleep(1_000)
            var scheduled = true
            s.onActivity { scheduled = Clipboard.clearScheduled() }
            org.junit.Assert.assertFalse("the timer still runs after the alarm cleared", scheduled)
        }
    }

    @Test fun somethingTheUserCopiedAfterTheKeyIsLeftAlone() {
        launch().use { s ->
            s.onActivity { a -> Clipboard.copySensitive(a, secret) }
            Thread.sleep(500)
            s.onActivity { a -> Clipboard.copy(a, "the user's own text") }
            Thread.sleep(Clipboard.lifetimeMs + 2_000)
            assertEquals("the clear wiped what the user copied after the key", "the user's own text", clipText(s))
        }
    }
}
