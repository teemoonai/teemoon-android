package ai.teemoon.app

// android-first while iOS ports the same rule (the maintainer 2026-10-25, via pm: "whole if quick", picked in the canvas lab).
// The first paragraph's 250 ms hold is iOS teemoon/Views/Chat/StreamReveal.swift @ 95d6a0a (firstHoldMs, kit 0.5.1);
// none on the phone, and the reveal made at the answer's first character (Origin, StreamRevealFeed) @ 0598bce1, kit 0.5.4.
// Display only: what is SHOWN of a streaming reply, given what has ARRIVED and when.

/**
 * "Whole if quick". A paragraph — the text between blank lines; a fence or a table is one — is held up to [HOLD_MS]
 * from its first arrived character; the reply's FIRST paragraph only [FIRST_HOLD_MS] (the maintainer 2026-10-26: the first
 * words should not wait 600 ms, on-device replies start in half a second) — and an ON-DEVICE reply's first paragraph not
 * at all ([ON_DEVICE_FIRST_HOLD_MS]; the maintainer 2026-10-31, "drop it on device", after the Pixel clip: first words at 0.80 s
 * with the hold, 0.55 s without, Gallery 0.45 s). If it completes in that time it appears whole, fading in over [FADE_MS]. If not,
 * it is revealed smoothly from then on: every [STEP_MS] of elapsed time the shown end moves ceil(deficit / [DIVISOR])
 * characters toward what has arrived, snapped to a word's end that has arrived (a trailing partial word waits at most
 * [PARTIAL_WAIT_MS]), never past it. A
 * paragraph that finishes is shown at once; the end of the stream, or a stop, shows everything ([finish]).
 *
 * Why: providers burst (Fireworks put 231 of a reply's 311 deltas in single reads; near.ai sends 24-character chunks
 * up to 0.7 s apart), so text arrived in jumps of a line or more and then sat still. A short paragraph that is complete
 * within the hold reads best whole; a long one reads best as a steady stream rather than its arrival bursts.
 *
 * Steps are counted in elapsed time, not frames, so a 120 Hz screen reveals at the speed of a 60 Hz one. Pure and
 * clocked by the caller, so it is tested against arrival timelines (StreamRevealTest).
 */
class StreamReveal(origin: Origin = Origin.CLOUD) {
    /** Where the stream comes from; only the first paragraph's hold differs. */
    enum class Origin { CLOUD, ON_DEVICE }

    data class Shown(val length: Int, val fadeFrom: Int? = null, val fadeStartedAtMs: Long? = null)

    private val firstHoldMs = if (origin == Origin.ON_DEVICE) ON_DEVICE_FIRST_HOLD_MS else FIRST_HOLD_MS
    private var arrived = ""
    private val firstArrivalAt = HashMap<Int, Long>()   // paragraph start → when its first character arrived
    private var shown = 0
    private var lastStepAt: Double? = null
    private var fadeFrom: Int? = null
    private var fadeStartedAt: Long? = null
    private var finished = false

    /** The reply's text so far (cumulative, as the engine emits it) arrived at [now]. */
    fun arrive(text: String, now: Long) {
        if (text.length < arrived.length || !text.startsWith(arrived)) { shown = minOf(shown, commonPrefix(arrived, text)) }
        arrived = text
        for (p in paragraphs(text)) if (p.end > p.start) firstArrivalAt.putIfAbsent(p.start, now)
    }

    /** The end of the stream, or a stop: everything that arrived is shown. */
    fun finish() { finished = true; shown = arrived.length }

    /** What to show at [now] (a frame). */
    fun frame(now: Long): Shown {
        holdUntil = null; stuckOnPartial = false
        if (finished) return Shown(arrived.length)
        val paras = paragraphs(arrived)
        for (p in paras) {
            if (shown >= p.end) continue
            val t0 = firstArrivalAt[p.start] ?: now
            val hold = holdMs(p.start)
            if (p.complete) {
                // Finished: shown at once. Whole, with the fade, only when nothing of it was showing yet.
                if (shown <= p.start && now - t0 <= hold) { fadeFrom = p.start; fadeStartedAt = now }
                shown = p.end
                continue
            }
            // The paragraph still arriving.
            if (now - t0 < hold) { lastStepAt = null; holdUntil = t0 + hold; break }
            if (shown < p.start) shown = p.start
            // The first frame after the hold takes exactly one step; after that, one per STEP_MS elapsed (a 120 Hz frame
            // may take none). Counted in Double: 1000/60 in Long is 16, which over a 300 ms catch-up is 18 steps, not 17
            // (ios, settling reveal.json).
            var steps: Int
            val last = lastStepAt
            if (last == null) { steps = 1; lastStepAt = now.toDouble() }
            else { steps = ((now - last) / STEP_MS).toInt(); if (steps > 0) lastStepAt = last + steps * STEP_MS }
            while (steps-- > 0 && shown < arrived.length) {
                val deficit = arrived.length - shown
                val next = snap(shown + (deficit + DIVISOR - 1) / DIVISOR, now)
                if (next == shown) { stuckOnPartial = true; break }
                shown = next
            }
            break
        }
        val fading = fadeStartedAt?.takeIf { now - it < FADE_MS }
        if (fading == null) { fadeFrom = null; fadeStartedAt = null }
        return Shown(shown, fadeFrom, fadeStartedAt)
    }

    private var holdUntil: Long? = null
    private var stuckOnPartial = false

    /**
     * How long after the last [frame] at [now] nothing will change: the rest of a paragraph's hold, of a partial word's
     * wait, or of a fade once everything is shown; 0 when the next frame steps. The view sleeps that long instead of
     * drawing frames — a frame loop through a hold drew nothing and kept Compose from ever going idle.
     */
    fun waitMs(now: Long): Long {
        holdUntil?.let { return maxOf(1, it - now) }
        if (stuckOnPartial) partialSince?.let { return maxOf(1, it + PARTIAL_WAIT_MS - now) }
        if (shown >= arrived.length) fadeStartedAt?.let { return maxOf(1, it + FADE_MS - now) }
        return 0
    }

    /**
     * Forward to the end of the word [at] falls in, when that end has arrived. A trailing word whose end has not arrived
     * waits at the last boundary — until its boundary arrives, its paragraph completes, or [PARTIAL_WAIT_MS] pass, then
     * shows to the arrived end, so one long word cannot stall the reveal.
     */
    private fun snap(at: Int, now: Long): Int {
        var i = minOf(at, arrived.length)
        while (i < arrived.length && !arrived[i].isWhitespace()) i++
        if (i < arrived.length) { partialSince = null; return i }
        // the word at the arrived end is still arriving
        val since = partialSince ?: now.also { partialSince = it }
        if (now - since >= PARTIAL_WAIT_MS) return arrived.length
        val boundary = arrived.lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { if (it < 0) 0 else it }
        return maxOf(shown, boundary)
    }
    private var partialSince: Long? = null

    private fun holdMs(paragraphStart: Int) = if (paragraphStart == 0) firstHoldMs else HOLD_MS

    private data class Para(val start: Int, val end: Int, val complete: Boolean)

    /** Paragraphs of [text]: split at blank lines outside a fence; `end` includes the blank line that closes one. */
    private fun paragraphs(text: String): List<Para> {
        val out = ArrayList<Para>()
        var start = 0; var i = 0; var inFence = false; var lineStart = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                val line = text.substring(lineStart, i)
                val marker = line.trimStart(' ').take(3)
                if (marker == "```" || marker == "~~~") inFence = !inFence
                // a blank line outside a fence closes the paragraph before it
                if (!inFence && line.isBlank() && i < text.length && lineStart > start) {
                    out += Para(start, i + 1, complete = true); start = i + 1
                }
                lineStart = i + 1
            }
            i++
        }
        if (start < text.length) out += Para(start, text.length, complete = false)
        return out
    }

    private fun commonPrefix(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i] == b[i]) i++; return i }

    companion object {
        const val HOLD_MS = 600L
        const val FIRST_HOLD_MS = 250L
        /** On the phone the first paragraph is not held at all (the maintainer, 2026-10-31). */
        const val ON_DEVICE_FIRST_HOLD_MS = 0L
        const val FADE_MS = 300L
        const val STEP_MS = 1000.0 / 60
        const val DIVISOR = 9
        const val PARTIAL_WAIT_MS = 300L
    }
}

/**
 * The reveal for one streaming answer, MADE AT ITS FIRST CHARACTER (iOS StreamRevealFeed @ 0598bce1): only then is the
 * turn's origin known for certain, so the first paragraph's hold is chosen there, not when the row first draws.
 */
class StreamRevealFeed {
    private var reveal: StreamReveal? = null

    fun arrive(answer: String, origin: StreamReveal.Origin, now: Long) {
        if (answer.isEmpty() && reveal == null) return
        (reveal ?: StreamReveal(origin).also { reveal = it }).arrive(answer, now)
    }

    fun frame(now: Long): StreamReveal.Shown = reveal?.frame(now) ?: StreamReveal.Shown(0)

    fun waitMs(now: Long): Long = reveal?.waitMs(now) ?: 0
}
