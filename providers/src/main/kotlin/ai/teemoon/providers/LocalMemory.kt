// ports: teemoon/Inference/LocalRuntime.swift @ v1.0.4 (LocalMemory only)
package ai.teemoon.providers

import java.util.Locale

/**
 * Memory teemoon refuses to dip below, over and above the weights. KV cache
 * and activations grow with context length, and being jetsammed mid-answer
 * is a far worse failure than declining to start.
 *
 * The process-budget reader (`os_proc_available_memory`) is iOS-specific and
 * is not ported here — callers pass [availableMB]. `0` means the API is silent.
 */
object LocalMemory {
    const val headroomMB = 512

    /**
     * Download size vs process budget. [availableMB] `== 0` means the API is
     * silent — do not warn. See WhereGetPolicyTest.
     */
    fun exceedsAvailable(sizeMB: Int, availableMB: Int): Boolean =
        availableMB > 0 && sizeMB > availableMB

    /** "4.1 gb" / "12 gb" — the get-section footer's figure. null when memory is unknown: no figure beats a wrong one. */
    fun weightsHeadroomFigure(availableMB: Int): String? {
        if (availableMB <= 0) return null
        val gb = availableMB / 1024.0
        return if (gb >= 10) "${Math.round(gb)} gb" else String.format(Locale.US, "%.1f gb", gb)
    }

    fun weightsHeadroomLabel(availableMB: Int): String? {
        if (availableMB <= 0) return null
        val gb = availableMB / 1024.0
        val figure = if (gb >= 10) "${Math.round(gb)}gb" else String.format(Locale.US, "%.1fgb", gb)
        return "around $figure free for loading weights"
    }
}
