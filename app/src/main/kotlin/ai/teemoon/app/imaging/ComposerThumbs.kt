// spec: teemoon-ios docs/IMAGES.md §4d @ d14c703a (arrival and removal motion, identity by image; the maintainer 2026-11-12)
package ai.teemoon.app.imaging

/**
 * Which composer thumbnails are drawn, and which are on their way out. A thumbnail is known by its image's key, never
 * its position (iOS ComposerThumbnailIDs): an x on it acts on that image even while another is leaving.
 */
object ComposerThumbs {
    data class Shown(val key: Int, val leaving: Boolean)

    /**
     * The row after [current] changed: every current image in order; an image the x took ([xed]) stays where it was,
     * leaving, until its exit is done; anything else that went (a send) goes at once. A new image arrives where
     * [current] has it.
     */
    fun next(shown: List<Shown>, current: List<Int>, xed: Set<Int>): List<Shown> {
        val now = current.toSet()
        val out = ArrayList<Shown>(current.size + 1)
        var p = 0
        for (s in shown) {
            if (s.key in now) {
                val at = current.indexOf(s.key)
                while (p <= at) { out += Shown(current[p], false); p++ }
            } else if (s.key in xed) out += Shown(s.key, true)
        }
        while (p < current.size) { out += Shown(current[p], false); p++ }
        return out.distinctBy { it.key }
    }

    /**
     * What the strip lays out: [shown] — or, when a send cleared every image at once, the [lastRow] it had, which keeps its
     * place while the panel's height closes with the keyboard (the maintainer 2026-11-12: every send dismisses the keyboard,
     * and the photo send is one motion). It is not drawn then ([sent]). A row whose last image the x took is not kept.
     */
    fun drawn(shown: List<Shown>, lastRow: List<Shown>): List<Shown> =
        if (sent(shown, lastRow)) lastRow else shown

    /**
     * A send took every photo at once (no x): the strip is not drawn from that frame, as the text goes — the iOS way, as
     * Messages and Google Messages do (the maintainer 2026-11-13); only the panel's height animates, on the keyboard's frames.
     * becd1277 folded the drawn strip with the keyboard instead.
     */
    fun sent(shown: List<Shown>, lastRow: List<Shown>): Boolean = shown.isEmpty() && lastRow.isNotEmpty() && lastRow.none { it.leaving }

    /** Where [key]'s image is now, or null once it has gone: an x then removes nothing in its place. */
    fun indexOf(current: List<Int>, key: Int): Int? = current.indexOf(key).takeIf { it >= 0 }
}
