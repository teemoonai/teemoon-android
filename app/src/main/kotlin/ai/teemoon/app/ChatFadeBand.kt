// ports: teemoon/Views/Chat/ChatFadeBand.swift @ v1.0.1 (ChatFadeBand, ChatGlassFade, ChatChrome) — the arithmetic
//        of the transcript's edges, as pure values the Compose transcript lays its overlays out from
package ai.teemoon.app

/**
 * The shape of the mask that dissolves the transcript into the chat's bottom chrome. Split out of the view because
 * this edge has been got wrong three separate ways, each time in a manner no preview and no unit test could see —
 * so the arithmetic lives somewhere `ChatFadeBandTest` can assert on it.
 *
 * All offsets are measured DOWN FROM THE TOP OF THE INSET. The inset holds two floating capsules with a gap:
 *
 *     ─────────────────────  0            inset top
 *        (padding)
 *     ┌───────────────────┐  chipTop      ← nothing above here may fade
 *     │   Where chip      │
 *     └───────────────────┘  chipBottom
 *        (gap)                            ← text bled through HERE
 *     ┌───────────────────┐
 *     │   composer        │
 *     └───────────────────┘
 *        (padding)
 *     ─────────────────────  insetHeight
 *
 * The mask is therefore three regions, not two: OPAQUE above chipTop (the transcript is untouched until it reaches
 * something to hide behind — a band that starts higher dissolves text into empty background); a RAMP across the chip
 * (a gradient, so the dissolve reads as depth rather than a hard edge slicing glyphs); HIDDEN from chipBottom down,
 * fully masked, including the gap. The third region is the whole point: a single ramp spanning the inset leaves the
 * gap between the capsules around 78% opaque, which is exactly legible — the recurring "text bleeds through between
 * the chip and the composer".
 */
data class ChatFadeBand(val chipTop: Double, val chipBottom: Double, val insetHeight: Double) {
    // A geometry read can arrive before layout settles: clamp nonsense ordering rather than go negative.
    val top: Double = maxOf(chipTop, 0.0)
    val bottom: Double = maxOf(chipBottom, top)
    val inset: Double = maxOf(insetHeight, bottom)

    /** Height of the gradient — the chip's own height. */
    val ramp: Double get() = bottom - top
    /** Height of the fully-masked region below the ramp: the gap, the composer, and the padding under it. */
    val hidden: Double get() = inset - bottom
    /**
     * Total height the mask must reserve at the bottom of its container. Load-bearing: the mask stacks this beneath
     * a flexible spacer, so if it ever exceeds the container the spacer collapses, the stack overflows and centres,
     * and the band is drawn far above the chrome — which is what happened with the keyboard up.
     */
    val total: Double get() = ramp + hidden

    /** The transcript's opacity at [offset] points below the inset's top: 1 above the chip, a plateau then a linear dissolve across it, 0 below. */
    fun alpha(offset: Double): Double {
        if (offset >= bottom) return 0.0
        if (offset <= top) return 1.0
        if (ramp <= 0) return 0.0
        val travelled = (offset - top) / ramp
        if (travelled <= PLATEAU) return 1.0
        return 1 - (travelled - PLATEAU) / (1 - PLATEAU)
    }

    /**
     * Where the ramp lands in a container of [height]. The collection view ignores the container's bottom safe area
     * so it can run behind the composer, which also extends it through the home indicator: if the mask still parks
     * `hidden` against the screen bottom, the ramp sits 34pt too low and text runs through the chips. Passing the
     * indicator as extra bottom clear puts the ramp back on the chip; with the keyboard up the indicator is gone and
     * the extra clear must be 0, or the ramp climbs into empty background and eats the last lines above the keyboard.
     */
    fun rampRange(height: Double, homeIndicator: Double = 0.0): ClosedRange<Double> {
        val end = height - hidden - homeIndicator
        return (end - ramp)..end
    }
    fun hiddenRange(height: Double, homeIndicator: Double = 0.0): ClosedRange<Double> {
        val start = height - hidden - homeIndicator
        return start..(height - homeIndicator)
    }
    /** The bottom glass overlay's height: the approach above the chip, the band, and the indicator it runs through. */
    fun overlayHeight(homeIndicator: Double = 0.0): Double = ChatGlassFade.BOTTOM_APPROACH + total + homeIndicator

    companion object {
        /** The first fifth of the ramp stays fully opaque, so the dissolve begins ON the chip rather than at its edge. */
        const val PLATEAU = 0.2
    }
}

/**
 * The glass edges (GlassEdgeFadeView): a frost of the content, not a glass plate — a full-width glass effect reads as
 * a card; this is a progressive blur under a progressive dim. Each stop is a location along the overlay, a blur
 * weight, and the dim (system background) alpha.
 */
object ChatGlassFade {
    data class Stop(val location: Double, val blur: Double, val dim: Double)

    /** The fade starts this far above the chip so the last line dissolves into the glass. */
    const val BOTTOM_APPROACH = 56.0
    /** The dissolve's run-out BELOW the title. Not 64pt — that dimmed the first readable lines under the header as a flat band. */
    const val TOP_APPROACH = 28.0

    /**
     * `t = 0` is the top of the screen, `t = 1` is the run-out's end.
     *
     * THE DIM DOES NOT END ON THE TITLE'S BOTTOM EDGE. It used to (0.08 at `titleEnd`), on the rule that the first body
     * line should be full brightness — but the anchor now parks the prompt at exactly `U.minY - topFade`, so there is
     * always body text sitting on that boundary, and it read as the previous reply colliding with the header. Measured
     * on the capture fixture: 147/255 at the title's edge. The dim now runs out over [TOP_APPROACH] instead, so the
     * first FULL-brightness line starts below the header rather than against it.
     *
     * Two things this must never become, both paid for on device: an opaque shelf (dim stays < 1 — "the black band that
     * fights the material") and a hard edge (a hard clip sliced glyphs at the bar line and was reverted the same day).
     * If the header still loses against busy content, the fix is the title block's 11pt/0.45, not more band.
     */
    fun topStops(titleHeight: Double): List<Stop> {
        val h = maxOf(titleHeight + TOP_APPROACH, 1.0)
        val titleEnd = (titleHeight / h).coerceIn(0.0, 1.0)
        return listOf(
            Stop(0.0, 1.0, 0.80),
            Stop(titleEnd * 0.82, 0.95, 0.88),
            Stop(titleEnd, 0.55, 0.45),
            Stop(1.0, 0.0, 0.0),
        )
    }

    /** From the chip down, dim is high enough that body text is not a second sentence on the capsules. */
    fun bottomStops(ramp: Double, hidden: Double, home: Double): List<Stop> {
        val h = maxOf(BOTTOM_APPROACH + ramp + hidden + home, 1.0)
        val chipTop = BOTTOM_APPROACH / h
        val chipBottom = (BOTTOM_APPROACH + ramp) / h
        return listOf(
            Stop(0.0, 0.0, 0.0),
            Stop(chipTop, 0.55, 0.28),
            Stop(chipBottom, 1.0, 0.94),
            Stop(1.0, 1.0, 0.96),
        )
    }
}

/** Title and composer geometry. The canvas is full-bleed; these numbers become the content insets and the two fades. */
data class ChatChrome(val topInset: Double = 0.0, val bottom: ChatFadeBand) {
    /** The nav overlap is the title band, not a band on the first body line. */
    fun navOverlap(safeAreaTop: Double): Double = if (topInset > 0) topInset else maxOf(safeAreaTop, 0.0)
    /** One-line titles and the two-line e2ee header are different heights: the overlay is the MEASURED title, never status + a constant. */
    fun topChromeHeight(titleMaxYInCanvas: Double): Double {
        val measured = maxOf(topInset, titleMaxYInCanvas)
        return if (measured > 1) measured else 0.0
    }
    fun topOverlayHeight(titleMaxYInCanvas: Double): Double {
        val chrome = topChromeHeight(titleMaxYInCanvas)
        return if (chrome > 1) chrome + ChatGlassFade.TOP_APPROACH else 0.0
    }
}
