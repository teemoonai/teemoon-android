// android-only: the foreground service that lets a phone-model download outlive the foreground. iOS gets this from a
// background URLSession, which keeps transferring with the app suspended; Android has no equivalent for a plain
// socket, so the process stays in the foreground state for as long as bytes are moving, and says so.
package ai.teemoon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ai.teemoon.providers.LocalModelDownloader

/**
 * Keeps the network while a model downloads.
 *
 * Measured on the AVD (Android 15, 2026-10-02): a 3.4 GB download started from Where died SIX SECONDS after Home with
 * `SocketException: Software caused connection abort` — a backgrounded app loses network access, and the user reported
 * the same on the Pixel ("downloading gemma e2b fails when backgrounded"). A turn never had this problem because
 * TurnService holds it in the foreground; the downloader had nothing. The partial survives a drop (resume), but a
 * 2.9 GB download that only moves while the screen is on teemoon is not a download a user can finish.
 *
 * Runs while any download is MOVING: a wi-fi-only job parked on mobile data holds nothing (it is waiting on purpose,
 * and a foreground notification over a stopped transfer would be a lie). dataSync is the type: a user-started transfer
 * of a file. Start and stop are main-thread only and use TurnService's deferred stop — a download that fails in the
 * same tick it started must not stopService before onStartCommand runs startForeground, or Android kills the app.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startsPending = (startsPending - 1).coerceAtLeast(0)
        // Refused here once the dataSync time is used up (Android 15+, see TurnService): the download keeps moving
        // while the app is open, and the row offers resume if the network goes when it is not.
        if (runCatching { startForeground(NOTIFICATION_ID, notification(this, lastText, lastPercent)) }.isFailure) {
            // refused only if a download still wants the service: when they all stopped before this landed, the flag
            // would have held the NEXT download back (the local audit's re-run, 2026-10-13)
            if (running) refused = true
            running = false; stopRequested = false
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopSelf(); return START_NOT_STICKY
        }
        if (stopRequested && startsPending == 0) {
            stopRequested = false
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
        // NOT_STICKY: if the system kills the process anyway, the transfer died with it; the row offers resume, and a
        // service relaunched with no download in memory would show a notification about nothing.
        return START_NOT_STICKY
    }

    /**
     * Android 15+'s dataSync limit, shared with TurnService: stop within seconds or the app is crashed (see
     * TurnService.onTimeout). The transfer loses its network soon after, as before this service existed, and the row
     * offers resume from the partial when the user is back.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (running) refused = true
        running = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val NOTIFICATION_ID = 3
        // Main thread only (AppState's scope collects the jobs on Main; onStartCommand runs on the main looper).
        private var running = false
        private var startsPending = 0
        private var stopRequested = false
        private var lastText = ""
        private var lastPercent = -1
        /**
         * Set when Android refused or timed out the service (the dataSync allowance, Android 15+): the downloads
         * already moving carry on without it, and nothing asks again until they are all done. Without this, every
         * progress update asked — a service created and refused once a percent (the local audit, 2026-10-13).
         */
        private var refused = false
        /** How many times the service was asked for — for ForegroundTimeLimitTest. */
        @androidx.annotation.VisibleForTesting internal var startRequests = 0

        /**
         * The jobs as they are now: start the service when something is moving, update its progress, stop it when
         * nothing is. Called from AppState on every change of [LocalModelDownloader.jobs].
         */
        fun reflect(context: Context, jobs: Collection<LocalModelDownloader.Job>) {
            val moving = jobs.filter { !it.parked }
            if (moving.isEmpty()) { refused = false; if (running) stop(context); return }
            if (refused) return
            val first = moving.first()
            val text = first.model.displayName.lowercase() + if (moving.size > 1) " and ${moving.size - 1} more" else ""
            val percent = (first.fraction * 100).toInt().coerceIn(0, 100)
            if (!running) { start(context, text, percent); return }
            if (text == lastText && percent == lastPercent) return   // one update per percent, not per megabyte
            lastText = text; lastPercent = percent
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text, percent)) }
        }

        private fun start(context: Context, text: String, percent: Int) {
            channel(context)
            lastText = text; lastPercent = percent
            running = true; startsPending += 1; stopRequested = false; startRequests += 1
            val i = Intent(context, DownloadService::class.java)
            // Refused when the dataSync time is used up (Android 15+): the download still moves while the app is open.
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
                .onFailure { running = false; refused = true; startsPending = (startsPending - 1).coerceAtLeast(0) }
        }

        private fun stop(context: Context) {
            running = false
            if (startsPending > 0) stopRequested = true else context.stopService(Intent(context, DownloadService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_DOWNLOADS) != null) return
            // LOW: a progress bar the user asked for, never a sound.
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "while a model downloads to this phone"
                setShowBadge(false)
            })
        }

        private fun notification(context: Context, text: String, percent: Int): Notification {
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("downloading $text")
                .setContentText(if (percent >= 0) "$percent%" else "starting…")
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
