package ai.teemoon.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The instrumentation runner, which arms seeding (UiTestSeeding.armed) inside the app's process before any test runs.
 * Only code running in this process can set the flag — an intent extra, which any app can send, no longer can.
 */
class TeemoonTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        UiTestSeeding.instrumentationArmed = true
        super.onCreate(arguments)
    }
}
