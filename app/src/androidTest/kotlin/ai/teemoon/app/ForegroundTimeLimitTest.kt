package ai.teemoon.app

// android-only: Android 15+ gives dataSync foreground services 6 hours in 24, shared by every such service in the app
// (TurnService and DownloadService both). At the limit the system calls Service.onTimeout and expects the service to
// stop within seconds; one that does not is crashed (ForegroundServiceDidNotStopInTimeException), taking the app with
// it. Flagged by the local audit 2026-10-13. The limit is shrunk to seconds here through device_config — the same key
// the system reads — and restored after.

import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelDownloader
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundTimeLimitTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun shell(cmd: String) = ins.uiAutomation.executeShellCommand(cmd).close()

    @Suppress("DEPRECATION")   // getRunningServices still reports the caller's own services, which is all this needs
    private fun running(cls: Class<*>) = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(50).any { it.service.className == cls.name }

    private fun shellOut(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    @Before fun shrinkTheLimit() {
        assumeTrue("the dataSync time limit is Android 15+", Build.VERSION.SDK_INT >= 35)
        shell("device_config put activity_manager data_sync_fgs_timeout_duration $LIMIT_MS")
        // ActivityManager takes a device_config change asynchronously: a service started before it lands is timed
        // against the 6 h default, and the first test of a run failed now and then as "outlived the time limit".
        val deadline = System.currentTimeMillis() + 10_000
        while (!shellOut("dumpsys activity settings").contains("data_sync_fgs_timeout_duration=$LIMIT_MS")) {
            check(System.currentTimeMillis() < deadline) { "ActivityManager never took the shrunk limit" }
            Thread.sleep(100)
        }
    }

    @After fun restoreTheLimit() {
        shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
        ins.runOnMainSync { TurnService.finish(ctx, TurnNotification.None, null); DownloadService.reflect(ctx, emptyList()) }
    }

    /** Past the limit and the crash grace (10 s), the app is still here, and the turn's service and moon are gone. */
    @Test fun aTurnAtTheTimeLimitStopsItsServiceInsteadOfCrashing() {
        ins.runOnMainSync { TurnService.start(ctx) }
        Thread.sleep(LIMIT_MS + GRACE_MS)
        assertFalse("the turn's service outlived the time limit", running(TurnService::class.java))
        assertFalse("the moon outlived its service", nm.activeNotifications.any { it.id == TurnService.ONGOING_ID })
    }

    @Test fun aDownloadAtTheTimeLimitStopsItsServiceInsteadOfCrashing() {
        val job = LocalModelDownloader.Job("timeout-test", 0.1, LocalModelCatalog.all.first())
        ins.runOnMainSync { DownloadService.reflect(ctx, listOf(job)) }
        Thread.sleep(LIMIT_MS + GRACE_MS)
        assertFalse("the download's service outlived the time limit", running(DownloadService::class.java))
        assertFalse("the progress notification outlived its service", nm.activeNotifications.any { it.id == DownloadService.NOTIFICATION_ID })
    }

    /**
     * After the limit, a moving download does not ask for the service again on every progress update — each ask was a
     * service created and refused, once a percent (the local audit, 2026-10-13). It waits for the next download.
     */
    @Test fun aDownloadPastTheLimitDoesNotRetryTheServiceOnEveryUpdate() {
        val model = LocalModelCatalog.all.first()
        ins.runOnMainSync { DownloadService.reflect(ctx, listOf(LocalModelDownloader.Job("timeout-test", 0.1, model))) }
        Thread.sleep(LIMIT_MS + GRACE_MS)
        val before = DownloadService.startRequests
        for (pct in 11..30) ins.runOnMainSync { DownloadService.reflect(ctx, listOf(LocalModelDownloader.Job("timeout-test", pct / 100.0, model))) }
        Thread.sleep(2_000)
        assertEquals("the service was asked for again after the limit", before, DownloadService.startRequests)
    }

    /**
     * A refusal that lands after the downloads have all stopped does not hold the NEXT download back: the flag that
     * stops the per-update retries is for the downloads it refused, not for later ones (the local audit's re-run).
     */
    @Test fun aRefusalAfterTheDownloadsStoppedDoesNotBlockTheNextOne() {
        val model = LocalModelCatalog.all.first()
        ins.runOnMainSync { TurnService.start(ctx) }
        Thread.sleep(LIMIT_MS + GRACE_MS)                               // a turn spends the allowance
        ins.runOnMainSync {
            TurnService.finish(ctx, TurnNotification.None, null)
            DownloadService.reflect(ctx, listOf(LocalModelDownloader.Job("first", 0.1, model)))
            DownloadService.reflect(ctx, emptyList())                    // done before the service even started
        }
        Thread.sleep(3_000)                                             // the refusal lands now, with nothing moving
        val before = DownloadService.startRequests
        ins.runOnMainSync { DownloadService.reflect(ctx, listOf(LocalModelDownloader.Job("second", 0.1, model))) }
        assertEquals("the next download never asked for the service", before + 1, DownloadService.startRequests)
    }

    /** A turn sent once the time is used up runs without the service — refused at startForeground, not crashed. */
    @Test fun aTurnStartedPastTheLimitIsRefusedQuietly() {
        ins.runOnMainSync { TurnService.start(ctx) }
        Thread.sleep(LIMIT_MS + GRACE_MS)      // the first turn spends the allowance
        ins.runOnMainSync { TurnService.finish(ctx, TurnNotification.None, null); TurnService.start(ctx) }
        Thread.sleep(GRACE_MS)                 // the second is refused; an uncaught refusal would have crashed by now
        assertFalse("a refused turn left its service", running(TurnService::class.java))
    }

    private companion object {
        const val LIMIT_MS = 4_000L
        const val GRACE_MS = 14_000L   // the system's 10 s crash wait, plus margin
    }
}
