package ai.teemoon.app

// android-only: a phone-model download runs inside DownloadService. Without it a backgrounded app lost its network six
// seconds after Home and the download died ("Software caused connection abort", AVD 2026-10-02; the user's report on
// the Pixel). The network cut itself cannot be reproduced from an instrumented process, so this pins the wiring that
// was missing: a moving download IS a foreground service, and a cancelled one is not.

import ai.teemoon.providers.LocalModelDownloader
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadServiceTest {
    @get:Rule val compose = emptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = FakeSseServer()
    @After fun stop() { server.close(); LocalModelDownloader.hostOverride = null }

    @Suppress("DEPRECATION")   // getRunningServices still reports the caller's own services, which is all this needs
    private fun downloadServiceForeground(): Boolean? = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(50).firstOrNull { it.service.className == DownloadService::class.java.name }?.foreground

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (cond()) return; Thread.sleep(250) }
        throw AssertionError(what)
    }

    @Test fun aMovingDownloadIsAForegroundServiceAndACancelledOneIsNot() {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(UiTestSeeding.EXTRA_FLAG, true); putExtra(UiTestSeeding.EXTRA_FRESH_STORE, true)
            putExtra(UiTestSeeding.EXTRA_MODEL_HOST, server.rootUrl)
        }
        ActivityScenario.launch<MainActivity>(intent)
        compose.waitUntil(20_000) { (ctx.applicationContext as TeemoonApplication).state != null }
        val app = (ctx.applicationContext as TeemoonApplication).state!!
        // Gemma 4 E4B, never E2B: a phone that holds E2B keeps it.
        val e4b = app.localCatalog.first { it.id.contains("E4B") }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.localDownloads.start(e4b) }
        try {
            waitFor(10_000, "the download never became a foreground service") { downloadServiceForeground() == true }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.deleteWeights(e4b) }
        }
        waitFor(10_000, "a cancelled download still holds the foreground service") { downloadServiceForeground() == null }
        assertTrue(server.modelRequests.get() >= 1)
    }
}
