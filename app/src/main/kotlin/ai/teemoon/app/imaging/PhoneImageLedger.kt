// spec: teemoon-ios docs/IMAGES.md §4b @ 6296dbad (phone image memory at run time; the maintainer 2026-11-12)
package ai.teemoon.app.imaging

import ai.teemoon.app.Prefs

/**
 * What this phone has shown about memory for images on a phone model, kept across launches: the most memory the app
 * has seen free here (PhoneImageMemory.ceilingMB counts it; it only rises), and the models whose image turn the OS
 * killed — refused with the permanent line from then on, never retried. A kill ends the process mid-turn, so it is
 * found at the next launch ([onLaunch]): the turn's marker, set at [beginImageTurn], is still there.
 */
class PhoneImageLedger(private val prefs: Prefs) {
    val highestAvailMB: Int get() = prefs.getInt(HIGHEST) ?: 0

    fun sawAvailable(mb: Int) { if (mb > highestAvailMB) prefs.putInt(HIGHEST, mb) }

    fun killed(modelID: String): Boolean = modelID in killedModels()

    fun beginImageTurn(modelID: String) = prefs.putString(IN_FLIGHT, modelID)

    fun endImageTurn() = prefs.putString(IN_FLIGHT, "")

    /**
     * At launch: an image turn still marked in flight died with the process. It counts as a kill only when the OS's
     * low-memory killer ended it ([lowMemoryKill], from ApplicationExitInfo) — a swipe-away, a force stop, an update, a
     * crash or a reboot mid-turn is not this phone's memory talking (the design review of e793238b). The mark is
     * cleared either way.
     */
    fun onLaunch(lowMemoryKill: Boolean) {
        val died = prefs.getString(IN_FLIGHT)?.takeIf { it.isNotEmpty() } ?: return
        if (lowMemoryKill) prefs.putString(KILLED, (killedModels() + died).joinToString("\n"))
        prefs.putString(IN_FLIGHT, "")
    }

    private fun killedModels(): Set<String> = prefs.getString(KILLED)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private companion object {
        const val HIGHEST = "phoneImagesHighestAvailMB"
        const val KILLED = "phoneImagesKilledModels"
        const val IN_FLIGHT = "phoneImagesTurnInFlight"
    }
}
