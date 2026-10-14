package ai.teemoon.app

// android-only: one AppState per process. A second MainActivity built a second AppState — a second on-device engine
// cache — and the next local turn refused for memory while the first engine was resident (Pixel 10 Pro, 2026-10-03,
// found by OnDeviceThinkingJourneyTest running its journeys back to back).

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStateLifetimeTest {
    @Test fun aSecondActivityReusesTheProcesssAppState() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as TeemoonApplication
        fun launch() = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        launch().use { }
        val first = app.state; assertNotNull(first)
        launch().use { }
        assertSame("a new activity built a second AppState (and a second engine cache)", first, app.state)
    }
}
