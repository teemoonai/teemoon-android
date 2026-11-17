package ai.teemoon.app

import android.util.Log

/**
 * Where a turn's warnings go: the log, as iOS's `logger.warning` (HTTPTransport.swift, GenerationEngine.swift) —
 * a truncated prompt, stripped tool markup, a withheld reply, an unverified signature. Never the screen: showing them
 * would be new on both platforms, a product item of its own (pm, 2026-10-25). Until 1.1.0 they were collected into
 * ChatUiState.warnings, which nothing read, while the docs said they were shown (audit gardener #1).
 *
 * The engine's messages carry counts, not content (GenerationEngineTest.theContainmentWarningCarriesCountsNotTheRemovedText).
 */
object TurnWarnings {
    /** Replaced in tests; `adb logcat -s teemoon` reads it on a device. */
    @Volatile var sink: (String) -> Unit = { Log.w("teemoon", "[warning] $it") }

    fun log(message: String) = sink(message)
}
