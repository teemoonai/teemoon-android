// ports: teemoon/Inference/LocalRuntime.swift @ v1.0.4 (LocalMemory only), @ f3773d9 (the memory-fit rule)
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
     * The phone's RAM in marketed GB: total memory rounded up to the next whole GiB. Android's
     * `ActivityManager.MemoryInfo.totalMem` (iOS `physicalMemory`): an "8 GB" Pixel 8a reports about 7.5 GiB.
     */
    fun marketedRAMGB(totalBytes: Long): Int = Math.ceil(totalBytes / 1_073_741_824.0).toInt()

    /**
     * The download-time "may not fit": the phone is below the model's RAM floor. Never free memory, which made an 8 GB
     * Pixel 8a that runs E2B say it may not fit (Test Lab, 2026-10-30): free memory moves with whatever else is open,
     * and the file size is not the cost, because the weights are mapped from the file. [totalBytes] `<= 0` means the
     * API is silent — do not warn.
     */
    fun belowFloor(model: LocalModel, totalBytes: Long): Boolean = totalBytes > 0 && marketedRAMGB(totalBytes) < model.minRAMGB

    /** What the load gate asks for: the measured peak, or the file size until it is measured, plus headroom. */
    fun loadNeedMB(sizeMB: Int, peakFootprintMB: Int?): Int = (peakFootprintMB ?: sizeMB) + headroomMB

    /**
     * Android-only (pm 2026-10-30, within the maintainer's "Yes" to fixing the false warnings): the headroom the load gate adds.
     * At or above the model's RAM floor, none — a load takes availMem down by its file size within 1% (Pixel 8a and 6a,
     * Test Lab 2026-10-30), and a 6 GB phone still had ~600 MB after three image turns; with 512 the gate declined both
     * phones in the first seconds after launch (availMem 2645 / 2539 MB against 2980). Below the floor, [headroomMB].
     * docs/ANDROID_NATIVE.md.
     */
    fun androidHeadroomMB(model: LocalModel, totalBytes: Long): Int =
        if (totalBytes > 0 && !belowFloor(model, totalBytes)) 0 else headroomMB

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
