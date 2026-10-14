// android-only: test harness. Dismisses Android 17's 16 KB "Android App Compatibility" dialog while a test runs.
package ai.teemoon.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Android 17 shows "This app isn't 16 KB compatible. RELRO alignment check failed" over every launch of a DEBUGGABLE
 * build (five prebuilt libraries; docs/ANDROID_NATIVE.md, AndroidManifest's pageSizeCompat comment). A release build
 * never shows it. The AVD (Android 15) never shows it either, which is why GroundedTurnTest's sources-sheet step went
 * green there and timed out on the Pixel 10 Pro (2026-10-02): the sheet was open UNDER the dialog.
 *
 * Presses OK, never "Don't Show Again" — that is a setting on the user's phone, not the test's to change. Polls on a
 * daemon thread for the length of the test, because the dialog lands a beat after launch, whenever the system says.
 */
class PageSizeDialogRule : TestWatcher() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun starting(description: Description) {
        // The dialog is a SYSTEM window: without this flag `uiAutomation.windows` lists only the app's own.
        runCatching {
            val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
            ua.serviceInfo = ua.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
        running = true
        thread = Thread {
            while (running) { runCatching { dismissIfShown() }; Thread.sleep(400) }
        }.apply { isDaemon = true; start() }
    }

    override fun finished(description: Description) { running = false; thread?.join(1_000) }

    private fun dismissIfShown() {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = (runCatching { ua.windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(ua.rootInActiveWindow))
        for (root in roots) {
            if (root.findAccessibilityNodeInfosByText("16 KB compatible").isEmpty()) continue
            val ok = root.findAccessibilityNodeInfosByText("OK").firstOrNull { it.text?.toString() == "OK" } ?: continue
            var t: AccessibilityNodeInfo? = ok
            while (t != null && !t.isClickable) t = t.parent
            (t ?: ok).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}
