// ports: teemoon/Inference/LocalRuntime.swift @ v1.0.4 (LocalMemoryPressure — onTrimMemory in place of the memory-warning and
//        did-enter-background notifications)
package ai.teemoon.app

import android.app.Application
import android.content.ComponentCallbacks2

class TeemoonApplication : Application() {
    /**
     * The process's one AppState: created by the first MainActivity, reused by every later one (it owns the on-device
     * engine, and two of those do not fit in memory). The trim hook also reads it.
     */
    @Volatile var state: AppState? = null

    override fun onCreate() {
        super.onCreate()
        // a copied key whose clear was pending when the process died without its alarm (a force-stop cancels both)
        Clipboard.onProcessStart(this)
    }

    /**
     * Releases loaded weights when the system asks for memory back. A
     * backgrounded teemoon holding a 2.5 GB engine is a prime kill candidate,
     * and the pre-load gate does nothing about a model that is ALREADY
     * resident. Both moments matter: running-low while in the foreground, and
     * backgrounding, when the weights nobody is using are most likely to get
     * the app killed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW (10) is deprecated from API 35, which only delivers UI_HIDDEN (20) and BACKGROUND (40) — both
        // above it, so one comparison covers every release from minSdk 29 up.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) state?.evictEngines(level)
    }
}
