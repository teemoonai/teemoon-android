// spec: teemoon-ios docs/IMAGES.md §4b @ 6296dbad (phone image memory, decided at run time; the maintainer 2026-11-12)
package ai.teemoon.providers

/**
 * Whether this phone can run an image turn on a phone model, decided at run time from memory (the maintainer
 * 2026-11-12: "i dont like statically fixing requirements when it can be done dynamically"): the model's measured need,
 * this phone's ceiling — the most the app could ever have — and what is free now. Replaces the fixed RAM floor.
 */
object PhoneImageMemory {
    /** RUN; NOW: short right now, close some apps; PERMANENT: this phone never has the room (or the OS killed a turn). */
    enum class Verdict { RUN, NOW, PERMANENT }

    /**
     * In order (wherefilter.json phoneImages.turnOrder): killed before, or the ceiling under the need → permanent, even
     * when free memory is short too — never "close some apps" on a phone that can never fit it; free now under the
     * need → now; else run.
     */
    fun verdict(needMB: Int, ceilingMB: Int, availableMB: Int, killedBefore: Boolean): Verdict = when {
        killedBefore || ceilingMB < needMB -> Verdict.PERMANENT
        availableMB < needMB -> Verdict.NOW
        else -> Verdict.RUN
    }

    /**
     * Android's ceiling (phoneImages.ceiling.android): totalMem less what the kernel and the system always hold, or the
     * highest availMem this app has seen on this phone, whichever is more — a phone that has had the room is never told
     * it can't. The reserve errs high: a false "close some apps" is the worse error (Test Lab 2026-10-30: totalMem −
     * availMem before a load was 2477 MB on a Pixel 6a, 4159 on an 8a with cached apps resident).
     */
    fun ceilingMB(totalMB: Int, highestSeenMB: Int, reserveMB: Int = SYSTEM_RESERVE_MB): Int =
        maxOf(totalMB - reserveMB, highestSeenMB).coerceAtLeast(0)

    /**
     * Whether images on a phone model can ever fit this phone: asked with what the turn's memory gate will ask, the need
     * plus the load headroom — or a phone whose ceiling sits between the two is told "close some apps" for ever (the
     * design review of e793238b).
     */
    fun fits(needMB: Int, headroomMB: Int, ceilingMB: Int): Boolean = needMB + headroomMB <= ceilingMB

    /**
     * The refused row's "use <smaller model>" (IMAGES.md §4b; iOS PhoneImageMemory.smallerModel): the nearest phone model
     * ahead of [current] in the catalogue (smallest first) that [fits] this phone; null when none does.
     */
    fun smallerModel(catalog: List<LocalModel>, current: LocalModel, fits: (LocalModel) -> Boolean): LocalModel? =
        catalog.takeWhile { it.id != current.id }.lastOrNull(fits)

    const val SYSTEM_RESERVE_MB = 3000
}
