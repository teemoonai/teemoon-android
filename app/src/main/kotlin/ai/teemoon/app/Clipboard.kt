// ports: teemoon/Views/PlatformChrome.swift @ v1.0.1 (Clipboard only)
package ai.teemoon.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

object Clipboard {
    fun copy(context: Context, text: String) {
        revoke(); cancelPending(context.applicationContext)
        manager(context)?.setPrimaryClip(ClipData.newPlainText("teemoon", text))
    }

    /** How long a copied credential may sit on the clipboard, in seconds. iOS sets an expiration; here the clip is cleared. */
    const val SENSITIVE_LIFETIME_SECONDS = 120L
    /** The clear's delay; ClipboardClearTest shortens it. */
    @androidx.annotation.VisibleForTesting @Volatile var lifetimeMs = SENSITIVE_LIFETIME_SECONDS * 1000

    /**
     * A key on the clipboard: marked sensitive (so the system's clipboard
     * preview and sync stay out of it), replacing rather than accumulating,
     * and gone after [SENSITIVE_LIFETIME_SECONDS] unless the user has copied
     * something else since.
     *
     * "Still ours" is decided from a token this object keeps, not by reading the clipboard back: Android 10+ answers
     * null to an app without focus, so once the user left to paste the key (the usual reason to copy one) the clear
     * never ran (the local audit, an INFO since 3910, 2026-10-13). Each key goes out under a label of its own; when the
     * clipboard changes and this process is told (it is told only while it may read), the label decides whether it is
     * still the key — counting changes did not work, because the system re-announces a clip after classifying its
     * text (AVD 2026-10-13: two callbacks per copy). Clearing is allowed unfocused. Changes made while teemoon is in the
     * background are not reported to it, so a copy the user makes elsewhere within the lifetime is cleared with the
     * key: the price of the key not outliving it. Android 13+ also clears the clipboard itself after an hour.
     */
    fun copySensitive(context: Context, secret: String) {
        val manager = manager(context) ?: return
        val app = context.applicationContext; appContext = app
        watch(manager)
        val token = ++generation
        val clip = ClipData.newPlainText(labelFor(token), secret)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        owned = token
        manager.setPrimaryClip(clip)
        // The process can die inside the lifetime; a system alarm, and a pending flag on disk, stand behind the timer
        // below (ClipClearReceiver). Whichever runs first clears and cancels the other.
        prefs(app).edit().putBoolean(PENDING, true).apply()
        alarms(app)?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + lifetimeMs, clearIntent(app))
        handler.postDelayed({
            if (owned != token) return@postDelayed
            owned = 0
            clear(manager)
            cancelPending(app)
        }, TIMER, lifetimeMs)
    }

    /**
     * The process starting: a clear still pending means the process died inside a key's lifetime without its alarm — a
     * force-stop (Settings, or an OEM's recents swipe) cancels alarms too, and nothing read the flag at launch before,
     * so the key stayed (the local audit of 15df68e, 2026-10-13). TeemoonApplication.onCreate calls this.
     */
    fun onProcessStart(context: Context) = clearIfPending(context.applicationContext)

    /** The alarm's half (ClipClearReceiver): clears only while a clear is pending — nothing this app saw replaced the key. */
    internal fun clearIfPending(context: Context) {
        if (!prefs(context).getBoolean(PENDING, false)) return
        manager(context)?.let(::clear)
        cancelPending(context)
        // the in-process timer too: still armed, it would fire later and clear whatever was copied since
        handler.removeCallbacksAndMessages(TIMER); owned = 0
    }

    /** ClipboardClearTest: whether the in-process timer is still armed. */
    @androidx.annotation.VisibleForTesting internal fun clearScheduled(): Boolean = handler.hasMessages(0, TIMER)

    /** ClipboardClearTest: a force-stop — the alarm cancelled with the process; the pending flag survives on disk. */
    @androidx.annotation.VisibleForTesting internal fun simulateForceStop(context: Context) {
        clearIntent(context).let { alarms(context)?.cancel(it); it.cancel() }
    }

    /** ClipboardClearTest: the process dying — the in-process timer and ownership gone, the alarm and the flag left. */
    @androidx.annotation.VisibleForTesting internal fun simulateProcessDeath() { handler.removeCallbacksAndMessages(TIMER); owned = 0 }

    private fun clear(manager: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= 28) runCatching { manager.clearPrimaryClip() } else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun cancelPending(context: Context) {
        prefs(context).edit().remove(PENDING).apply()
        clearIntent(context).let { alarms(context)?.cancel(it); it.cancel() }
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val TIMER = Any()   // the token the delayed clear is posted with, so it alone can be found and removed
    private var appContext: Context? = null
    private const val PENDING = "clear_pending"
    private fun prefs(context: Context) = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun clearIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0, clearBroadcast(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    internal fun clearBroadcast(context: Context) = Intent(context, ClipClearReceiver::class.java).setAction(ClipClearReceiver.ACTION)

    // Main thread only: copySensitive, the listener and the delayed clear all run on the main looper.
    private var generation = 0
    private var owned = 0
    private var watching = false

    private fun labelFor(token: Int) = "teemoon-key-$token"

    private fun watch(manager: ClipboardManager) {
        if (watching) return
        watching = true
        manager.addPrimaryClipChangedListener {
            if (owned == 0) return@addPrimaryClipChangedListener
            // told only while this process may read: a description it cannot read is left alone, one that is not the
            // key's own label means the user has copied something else
            val label = runCatching { manager.primaryClipDescription }.getOrNull()?.label?.toString() ?: return@addPrimaryClipChangedListener
            if (label != labelFor(owned)) { owned = 0; appContext?.let(::cancelPending) }
        }
    }

    /** Any other copy through this object replaces the key, and is not to be cleared. */
    private fun revoke() { owned = 0 }

    /** ClipDescription.EXTRA_IS_SENSITIVE — the constant is API 33; the string works on every release that honours it. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private fun manager(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
