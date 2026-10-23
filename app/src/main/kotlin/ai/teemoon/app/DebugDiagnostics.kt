// Android-only (no Swift source): debug-build performance diagnostics. docs/ANDROID_NATIVE.md lists it.
package ai.teemoon.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * StrictMode and JankStats, for DEBUG builds only.
 *
 * Every entry point returns at once unless [BuildConfig.DEBUG]: in a release (or benchmark) build that compile-time
 * constant is false, so no policy is set, no JankStats exists, and nothing here logs, counts or sends anything. Both report to logcat and nowhere
 * else — penaltyLog only, never penaltyDeath or penaltyDropBox, so a violation is something to read, not a crash
 * that hides the next one. Read them with `adb logcat -s StrictMode JankStats`.
 */
object DebugDiagnostics {
    const val JANK_TAG = "JankStats"
    /** The [PerformanceMetricsState] key the current screen is reported under (see [JankScreenState]). */
    const val SCREEN_STATE = "screen"

    fun install(app: Application) {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                .penaltyLog().build(),
        )
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        app.registerActivityLifecycleCallbacks(JankTracker())
    }

    /**
     * One JankStats per activity window. Created after onCreate (the window has its content by then, so
     * [JankScreenState] finds the holder this sets up), tracking only while the activity is resumed.
     */
    private class JankTracker : Application.ActivityLifecycleCallbacks {
        private val stats = HashMap<Activity, JankStats>()
        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            stats[activity] = JankStats.createAndTrack(activity.window) { frame ->
                if (frame.isJank) Log.w(JANK_TAG, "janky frame ${frame.frameDurationUiNanos / 1_000_000} ms ${frame.states.joinToString { "${it.key}=${it.value}" }}")
            }.apply { isTrackingEnabled = false }
        }
        override fun onActivityResumed(activity: Activity) { stats[activity]?.isTrackingEnabled = true }
        override fun onActivityPaused(activity: Activity) { stats[activity]?.isTrackingEnabled = false }
        override fun onActivityDestroyed(activity: Activity) { stats.remove(activity)?.isTrackingEnabled = false }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/**
 * Names the screen on screen for JankStats, so a janky frame says where it happened (`screen=chat`). A no-op in a
 * non-debug build; in a debug build the holder's state is null until JankStats tracks the window, so it is safe
 * either way.
 */
@Composable
fun JankScreenState(name: String) {
    if (!BuildConfig.DEBUG) return
    val view = LocalView.current
    DisposableEffect(view, name) {
        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
        holder.state?.putState(DebugDiagnostics.SCREEN_STATE, name)
        onDispose { holder.state?.removeState(DebugDiagnostics.SCREEN_STATE) }
    }
}
