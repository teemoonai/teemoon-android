// ports: teemoon/Inference/ToolCallFormat.swift @ v1.0.1 (StreamingMarkupElision)
package ai.teemoon.inference

/**
 * Elides text tool-call markup REGIONS out of a streaming reply, so raw syntax
 * never reaches the user while the prose around a call keeps streaming: forward
 * until an opening marker, suppress through that format's closing marker, then
 * resume forwarding.
 *
 * The bug this shape exists to prevent: suppression that LATCHED on the first
 * marker and withheld the whole rest of the turn, so a prose→call→prose reply
 * arrived with its second half in one batch at [DONE]. Resuming after the close
 * is the fix, and it is why every format must declare a closing marker.
 *
 * Consumes one delta plus the bounded tail the previous delta left behind; it
 * never rescans the accumulated reply, which is what keeps a long stream from
 * going quadratic.
 */
class StreamingMarkupElision(private val enabled: Boolean = true) {

    var visibleContent: String = ""
        private set
    var markerSeen: Boolean = false
        private set

    private sealed interface State {
        data object Forwarding : State
        data class Suppressing(val close: String) : State
    }

    private var state: State = State.Forwarding

    /**
     * While forwarding: a suffix of otherwise-visible text held back because it
     * is a proper prefix of an opening marker. Released by the next chunk or by
     * [finish].
     */
    private var heldBack = ""

    /**
     * While suppressing: the last close.length - 1 suppressed chars, so a
     * closing marker split across chunks is still seen. Never forwarded.
     */
    private var suppressedTail = ""

    val isSuppressing: Boolean get() = state is State.Suppressing

    /**
     * Consumes one delta and returns its visible portion. An empty return means
     * nothing new may be shown yet — either the chunk was markup through and
     * through, or its tail is an ambiguous maybe-marker being held back.
     */
    fun append(chunk: String): String {
        if (!enabled) {
            visibleContent += chunk
            return chunk
        }
        val visible = elide(chunk)
        visibleContent += visible
        return visible
    }

    /**
     * End of turn while forwarding: the held-back tail was NOT the start of a
     * marker after all — it belongs to the visible reply. While suppressing this
     * is deliberately a no-op: the tail of an UNTERMINATED region stays elided,
     * because partial markup must never leak.
     */
    fun finish(): String {
        if (state !is State.Forwarding || heldBack.isEmpty()) return ""
        val released = heldBack
        heldBack = ""
        visibleContent += released
        return released
    }

    private fun elide(chunk: String): String {
        var work = when (state) {
            is State.Forwarding -> (heldBack + chunk).also { heldBack = "" }
            is State.Suppressing -> (suppressedTail + chunk).also { suppressedTail = "" }
        }
        val out = StringBuilder()
        while (work.isNotEmpty()) {
            when (val s = state) {
                is State.Forwarding -> {
                    // Earliest opening marker of ANY registered format wins — a
                    // reply could legitimately contain a later marker of another
                    // family.
                    var earliestAt = -1
                    var earliestOpen = ""
                    var earliestClose = ""
                    for ((open, close) in ToolCallFormat.markerPairs) {
                        val at = work.indexOf(open)
                        if (at >= 0 && (earliestAt < 0 || at < earliestAt)) {
                            earliestAt = at; earliestOpen = open; earliestClose = close
                        }
                    }
                    if (earliestAt >= 0) {
                        out.append(work, 0, earliestAt)
                        work = work.substring(earliestAt + earliestOpen.length)
                        state = State.Suppressing(earliestClose)
                        markerSeen = true
                    } else {
                        // No marker — but the chunk may END with the start of one.
                        // Hold that tail back rather than forward-then-retract it.
                        val hold = markerPrefixHoldback(work)
                        out.append(work, 0, work.length - hold.length)
                        heldBack = hold
                        return out.toString()
                    }
                }
                is State.Suppressing -> {
                    val at = work.indexOf(s.close)
                    if (at >= 0) {
                        work = work.substring(at + s.close.length)
                        state = State.Forwarding
                    } else {
                        // Keep just enough suppressed tail to recognise a close
                        // split across the next boundary.
                        suppressedTail = work.takeLast(maxOf(0, s.close.length - 1))
                        return out.toString()
                    }
                }
            }
        }
        return out.toString()
    }

    companion object {
        /**
         * The longest suffix of [s] that is a PROPER prefix of any opening marker
         * — text that cannot be shown yet because the next chunk may complete a
         * marker. Full markers never reach here: elide already consumed them.
         */
        internal fun markerPrefixHoldback(s: String): String {
            val maxLen = minOf(s.length, ToolCallFormat.maxMarkerLength - 1)
            if (maxLen <= 0) return ""
            for (len in maxLen downTo 1) {
                val suffix = s.takeLast(len)
                if (ToolCallFormat.allMarkers.any { it.length > len && it.startsWith(suffix) }) {
                    return suffix
                }
            }
            return ""
        }
    }
}
