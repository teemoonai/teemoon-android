package ai.teemoon.app

// android-only: the foreground-service start/stop race. TurnService.start calls startForegroundService, whose
// onStartCommand (where startForeground happens) is posted to the main looper; a turn refused before the wire ends
// within the same main-thread tick, and a stopService that lands before onStartCommand makes Android kill the app with
// ForegroundServiceDidNotStartInTimeException ("did not then call Service.startForeground()"). Found 2026-09-24 by the
// switch-then-send journey's mutant — an instant "verification is still running" refusal crashed the process.

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurnServiceRaceTest {
    /** Start and finish in one main-thread tick, then outlive the system's startForeground deadline (10 s): alive, and no ongoing notification left. */
    @Test fun aTurnThatEndsBeforeTheServiceStartsDoesNotCrashTheApp() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        ins.runOnMainSync {
            TurnService.start(ctx)
            TurnService.finish(ctx, TurnNotification.None, null)
        }
        Thread.sleep(12_000)
        ins.waitForIdleSync()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertFalse("the ongoing notification outlived its turn", nm.activeNotifications.any { it.id == TurnService.ONGOING_ID })
    }
}
