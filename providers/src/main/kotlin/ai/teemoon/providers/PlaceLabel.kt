// ports: teemoon/Providers/PlaceLabel.swift @ v1.0.1
package ai.teemoon.providers

/**
 * Auto-generated provider names are the PLACE, never the model. A typed name
 * is never overwritten. Pins healAutoLabels' other half — see PlaceLabelTest.
 */
object PlaceLabel {
    /** Next auto label, or null if the current name must be left alone. */
    fun proposed(
        currentName: String,
        lastAuto: String,
        presetName: String,
        host: String?,
    ): String? {
        val trimmed = currentName.trim()
        if (trimmed.isNotEmpty() && trimmed != lastAuto) return null
        if (presetName.isNotEmpty()) return presetName.lowercase()
        if (host != null) return HostLabel.friendly(host).lowercase()
        return null
    }
}
