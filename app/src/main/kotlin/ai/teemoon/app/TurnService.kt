// android-only: the foreground service that lets a cloud turn outlive the foreground. Copy and timing rules are
// TurnNotification.kt; this is the plumbing that carries them.
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

/**
 * Keeps the process alive while a turn is in flight, and says so.
 *
 * NOT WorkManager. WorkManager is for work that must survive process death and be retried later; a cloud turn
 * cannot survive process death at all — the answer IS the HTTP response stream, and teemoon does not own the
 * server that is producing it, so a dropped socket loses the generation (and is still billed for it). What this
 * needs is the opposite: keep THIS process, with THIS socket, alive. That is a plain foreground service, and it
 * keeps `androidx.work` off the dependency list entirely.
 *
 * The measurement that justifies it, Pixel 10 Pro, 2026-09-21: a backgrounded app goes to `oom_score_adj` 900
 * within a minute — the top of the kill list. It survived five minutes because nothing else wanted the memory.
 * A foreground service holds it near 200.
 *
 * If POST_NOTIFICATIONS is denied the service still runs and the turn still completes; only the telling is lost.
 * That is the right degradation and the reason the permission is asked for in context rather than at launch.
 */
class TurnService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startsPending = (startsPending - 1).coerceAtLeast(0)
        // Refused here, not at startForegroundService, once the dataSync time is used up (Android 15+: "Time limit
        // already exhausted", AVD 2026-10-13) — uncaught, that crashed the app. The turn runs on without the service.
        if (runCatching { startForeground(ONGOING_ID, ongoing(this)) }.isFailure) {
            stopRequested = false; stopSelf(); return START_NOT_STICKY
        }
        // A turn that ended before this command arrived asked to stop; honour it now that startForeground has run.
        if (stopRequested && startsPending == 0) {
            stopRequested = false
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
        // NOT_STICKY: if the system kills us anyway the turn is gone with the socket, and relaunching a service
        // with no turn to guard would show a notification about nothing.
        return START_NOT_STICKY
    }

    /**
     * Android 15+: dataSync foreground services get 6 hours in 24, shared with DownloadService. At the limit the system
     * calls this and crashes the app if the service has not stopped within seconds (measured on the AVD 2026-10-13 with
     * the limit shrunk: ForegroundServiceDidNotStopInTimeException). So it stops. The turn itself keeps running for as
     * long as the process lives — it just loses the priority — and its result still posts when it ends.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    companion object {
        const val ONGOING_ID = 1
        const val RESULT_ID = 2
        /** The thread a result notification opens; MainActivity reads it on create and on a new intent. */
        const val EXTRA_OPEN_THREAD = "open_thread"

        /**
         * Starts asked for whose onStartCommand has not run yet, and whether the turn already ended. Main thread only:
         * start/finish are called from the view model's scope and onStartCommand is delivered on the main looper.
         *
         * A turn refused before the wire ends in the same main-thread tick it began — before the service's
         * onStartCommand, where startForeground happens, has been delivered. A stopService then made Android kill the
         * app ("startForegroundService() did not then call Service.startForeground()", 2026-09-24: an instant
         * "verification is still running" refusal crashed the process). The stop waits for the start instead.
         */
        private var startsPending = 0
        private var stopRequested = false

        /** Creates both channels (no-op for ones that exist) and deletes the retired `turns`. See [TurnChannels]. */
        fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            for (id in TurnChannels.RETIRED) if (nm.getNotificationChannel(id) != null) nm.deleteNotificationChannel(id)
            for (c in TurnChannels.ALL) {
                if (nm.getNotificationChannel(c.id) != null) continue
                // Never LOW: LOW is filed under Silent with no status-bar icon. HIGH is what drops a heads-up banner;
                // DEFAULT chimes without one. Silence is set per channel below.
                val importance = if (c.headsUp) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
                nm.createNotificationChannel(NotificationChannel(c.id, c.name, importance).apply {
                    description = c.description
                    setShowBadge(false)
                    if (!c.audible) { setSound(null, null); enableVibration(false) }
                })
            }
        }

        /** Called as the turn starts — before the app can lose the foreground, which is the whole point. */
        fun start(context: Context) {
            channel(context)
            val i = Intent(context, TurnService::class.java)
            startsPending += 1; stopRequested = false
            // Refused when the dataSync time is used up (Android 15+) and in the other cases Android 12+ lists: the turn
            // then runs without the service, as it does when notifications are denied — only the protection is lost.
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
                .onFailure { startsPending = (startsPending - 1).coerceAtLeast(0) }
        }

        /**
         * Called as the turn ends. Drops the ongoing notification, and posts the result only when [notification]
         * is something — TurnNotificationPolicy decides that, not this.
         */
        fun finish(context: Context, notification: TurnNotification, threadId: String?) {
            if (startsPending > 0) stopRequested = true else context.stopService(Intent(context, TurnService::class.java))
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(ONGOING_ID)
            if (notification is TurnNotification.None) return
            channel(context)
            val (title, body) = when (notification) {
                TurnNotification.Ready -> TurnNotification.Ready.title to null
                TurnNotification.Failed -> TurnNotification.Failed.title to TurnNotification.Failed.body
                is TurnNotification.Working -> return          // a working state is the ongoing one, not a result
                TurnNotification.None -> return
            }
            // The moon, as the working one, with a badge: with one icon for both the status bar looked unchanged
            // when the answer came (2026-10-13). A dot for an answer, a "!" for a failure.
            val icon = if (notification is TurnNotification.Failed) R.drawable.ic_stat_moon_failed else R.drawable.ic_stat_moon_reply
            runCatching { nm.notify(RESULT_ID, result(context, icon, title, body, threadId)) }
        }

        internal fun open(context: Context, threadId: String?): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            threadId?.let { i.putExtra(EXTRA_OPEN_THREAD, it) }
            return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun base(context: Context, channel: TurnChannel, icon: Int, title: String, body: String?, threadId: String?) =
            Notification.Builder(context, channel.id)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentIntent(open(context, threadId))
                .apply { body?.let { setContentText(it) } }

        private fun ongoing(context: Context): Notification =
            base(context, TurnChannels.WORKING, R.drawable.ic_stat_moon, TurnNotification.Working.title, TurnNotification.Working.body, null).setOngoing(true).setShowWhen(false)
                // Android 12+ holds a foreground service's notification back ~10 s unless asked not to (measured on
                // the AVD 2026-10-13: 10.1 s), so the moon appeared seconds after Home. It is the sign that leaving
                // is safe; it has to be there when they leave.
                .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .build()

        private fun result(context: Context, icon: Int, title: String, body: String?, threadId: String?): Notification =
            base(context, TurnChannels.ANSWERS, icon, title, body, threadId).setAutoCancel(true)
                // The copy carries nothing of the chat already (TurnNotification.kt); this keeps the lock screen to the
                // same words if it ever does — a secure lock screen shows the public version, not "contents hidden".
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(Notification.Builder(context, TurnChannels.ANSWERS.id).setSmallIcon(icon).setContentTitle(title)
                    .apply { body?.let { setContentText(it) } }.build())
                .build()
    }
}
