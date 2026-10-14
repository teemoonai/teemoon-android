package ai.teemoon.app

import android.app.ActivityManager

// android-only: when the on-device engine may be warmed (docs/ANDROID_NATIVE.md "No engine is loaded behind the user's back").

/**
 * iOS warms the engine on every selection with no foreground check, and needs none: a backgrounded iOS app is
 * suspended and cannot reach the GPU. Android keeps the process running — a download holds it up with a foreground
 * service — so a phone model finishing in the background selected itself and loaded ~4 GB onto the GPU behind the
 * user's back, where it stayed: the UI_HIDDEN trim that evicts engines had fired before the load (Pixel 10 Pro,
 * 2026-10-05; it starved the device suite of the memory "start here" needs). MainActivity.onStart warms up on every
 * return, so skipping in the background costs nothing.
 */
object BackgroundWarmUp {
    fun shouldStart(foreground: Boolean, busy: Boolean): Boolean = foreground && !busy

    /** The app left while the engine was building: give it back — unless a turn started meanwhile and is using it. */
    fun shouldEvictAfterBuild(foreground: Boolean, busy: Boolean): Boolean = !foreground && !busy

    /** Whether this process is what the user is looking at (no lifecycle-process dependency for one bit). */
    fun isForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)
}
