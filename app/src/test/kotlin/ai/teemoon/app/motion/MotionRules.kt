// The motion check's rules (the maintainer 2026-11-13, "can you automate validation that the animations in general are good";
// card motion-pass, pm/features/motion-audit.md). A transition is recorded one 16 ms frame at a time as the bounds and
// opacity of each named element; these rules read that series. Pure functions over the series, so each rule is proven
// red and green on made-up frames (MotionRulesTest) before it judges the app (MotionCheck*).
//
//   R1 one instance per name in every frame (the double composer)
//   R2 each edge moves one way only, no jump over JUMP_PX between frames
//   R3 the first frame after the trigger is already right: what the end hides is not drawn (the photo riding the keyboard)
//   R4 linked elements start and end together, within one frame (the two-step shrink: panel height vs keyboard)
//   R5 nothing moves after the transition ends, and nothing returns to where it started unless it should (the late scroll)
//   R6 with animations off, the end state on the first frame
package ai.teemoon.app.motion

import kotlin.math.abs

/** One element in one frame: its box in root dp, and its opacity (1 when it carries no MotionAlpha). */
data class Shot(val left: Float, val top: Float, val right: Float, val bottom: Float, val alpha: Float = 1f) {
    val height get() = bottom - top
    /** Visible at all: an 8-bit screen draws nothing under 1/255 opacity. */
    val drawn get() = alpha >= 1f / 255 && right > left && bottom > top
    fun edge(e: Edge) = when (e) { Edge.LEFT -> left; Edge.TOP -> top; Edge.RIGHT -> right; Edge.BOTTOM -> bottom; Edge.HEIGHT -> height; Edge.ALPHA -> alpha }
}

enum class Edge { LEFT, TOP, RIGHT, BOTTOM, HEIGHT, ALPHA }

/** Every named element in one frame; a name absent from the map is not in the tree. [ms] since the trigger (0 = its frame). */
data class Frame(val ms: Long, val shots: Map<String, List<Shot>>) {
    /** The one drawn [name] in this frame; null when it is absent, not drawn (zero size, opacity 0), or doubled (R1's). */
    fun one(name: String): Shot? = shots[name]?.filter { it.drawn }?.singleOrNull()
}

data class Violation(val rule: String, val name: String, val ms: Long, val detail: String) {
    override fun toString() = "$rule $name @${ms}ms: $detail"
}

object MotionRules {
    /** The largest move of an edge between two frames, dp: a full-height sheet (~900 dp) rising in ~350 ms peaks at ~70. */
    const val JUMP_PX = 96f
    /** Under this an edge has not moved (layout rounding, dp); opacity steps under 0.02 are not a change. */
    const val STILL_PX = 0.5f
    private fun still(e: Edge) = if (e == Edge.ALPHA) 0.02f else STILL_PX

    /** R1: no frame holds two of [names] — two composers crossfading read as a doubled panel. */
    fun oneInstance(frames: List<Frame>, names: Collection<String>): List<Violation> = frames.flatMap { f ->
        names.mapNotNull { n -> f.shots[n]?.count { it.drawn }?.takeIf { it > 1 }?.let { Violation("R1", n, f.ms, "$it drawn at once") } }
    }

    /** How far back from the furthest point reached counts as turning back, dp: past layout rounding (~0.33 dp). */
    const val TURN_DP = 1f

    /**
     * R2: [edge] of [name] never turns back, and never jumps more than [jump] dp in one frame. A turn back is measured
     * from the furthest point reached, not frame to frame: the strip's 0.7 spring overshot by 3.7 dp and crept back at
     * 0.33 dp a frame, under any per-frame threshold.
     */
    fun monotonic(frames: List<Frame>, name: String, edge: Edge, jump: Float = JUMP_PX): List<Violation> {
        val v = frames.mapNotNull { f -> f.one(name)?.let { f.ms to it.edge(edge) } }
        if (v.isEmpty()) return emptyList()
        val out = ArrayList<Violation>()
        for ((a, b) in v.zipWithNext()) {
            val d = b.second - a.second
            if (edge != Edge.ALPHA && abs(d) > jump) out += Violation("R2", name, b.first, "$edge jumped ${"%.0f".format(d)} dp in one frame")
        }
        val turn = if (edge == Edge.ALPHA) 0.02f else TURN_DP
        val start = v.first().second
        var dir = 0; var extreme = start
        for ((ms, x) in v) {
            if (dir == 0) { if (abs(x - start) >= still(edge)) { dir = if (x > start) 1 else -1; extreme = x }; continue }
            if ((x - extreme) * dir > 0) extreme = x
            else if (abs(x - extreme) > turn) { out += Violation("R2", name, ms, "$edge turned back ${"%.1f".format(abs(x - extreme))} from ${"%.1f".format(extreme)}"); break }
        }
        return out
    }

    /** R3: from the trigger's frame on, [name] is not drawn — what the end state hides must not show for a frame. */
    fun goneFromTheFirstFrame(frames: List<Frame>, name: String): List<Violation> =
        frames.filter { it.ms >= 0 }.mapNotNull { f -> f.shots[name]?.filter { it.drawn }?.takeIf { it.isNotEmpty() }?.let { Violation("R3", name, f.ms, "drawn after the trigger (alpha ${it.maxOf { s -> s.alpha }})") } }

    /** R3, the other half: [name] is in its end place from the first frame (no flash at the old place). */
    fun inPlaceFromTheFirstFrame(frames: List<Frame>, name: String, edge: Edge): List<Violation> {
        val end = frames.lastOrNull()?.one(name)?.edge(edge) ?: return listOf(Violation("R3", name, 0, "absent at the end"))
        return frames.filter { it.ms >= 0 }.mapNotNull { f -> f.one(name)?.edge(edge)?.takeIf { abs(it - end) > STILL_PX }?.let { Violation("R3", name, f.ms, "$edge $it, ends $end") } }
    }

    /**
     * When [edge] of [name] starts and ends its travel, ms: past 1% of the way from where it was before the trigger to
     * where it ends, and at 99% to stay. Relative, so a scrim's opacity and a sheet's position compare fairly (a spring's
     * sub-dp tail is the last 1%). Null when it travels nowhere.
     */
    fun span(frames: List<Frame>, name: String, edge: Edge): Pair<Long, Long>? {
        val v = frames.mapNotNull { f -> f.one(name)?.let { f.ms to it.edge(edge) } }
        if (v.size < 2) return null
        val from = v.first().second; val to = v.last().second
        if (abs(to - from) < still(edge)) return null
        fun p(x: Float) = (x - from) / (to - from)
        val start = v.firstOrNull { abs(p(it.second)) > 0.01f }?.first ?: return null
        val end = v.indices.firstOrNull { i -> v.drop(i).all { abs(1 - p(it.second)) < 0.01f } }?.let { v[it].first } ?: v.last().first
        return start to end
    }

    /**
     * R4: [a] and [b] start and end within [slackMs] of each other: two frames, since where an element stops counting as
     * drawn (a 1.7 dp sliver of sheet, a scrim at 0.007) moves its measured end by a frame either way.
     */
    fun together(frames: List<Frame>, a: Pair<String, Edge>, b: Pair<String, Edge>, slackMs: Long = 33): List<Violation> {
        val sa = span(frames, a.first, a.second); val sb = span(frames, b.first, b.second)
        if (sa == null && sb == null) return emptyList()
        if (sa == null || sb == null) return listOf(Violation("R4", "${a.first}/${b.first}", 0, "one moves alone: ${a.first} $sa, ${b.first} $sb"))
        val out = ArrayList<Violation>()
        if (abs(sa.first - sb.first) > slackMs) out += Violation("R4", "${a.first}/${b.first}", minOf(sa.first, sb.first), "start ${sa.first} vs ${sb.first} ms")
        if (abs(sa.second - sb.second) > slackMs) out += Violation("R4", "${a.first}/${b.first}", minOf(sa.second, sb.second), "end ${sa.second} vs ${sb.second} ms")
        return out
    }

    /** R5: nothing in [names] moves after [endMs] (the transition's own end: the last linked motion). */
    fun stillAfter(frames: List<Frame>, names: Collection<String>, endMs: Long): List<Violation> = names.flatMap { n ->
        Edge.entries.filter { it != Edge.HEIGHT }.mapNotNull { e -> span(frames, n, e)?.takeIf { it.second > endMs }?.let { Violation("R5", n, it.second, "$e still moving ${it.second - endMs} ms after the end") } }
    }

    /** R5, the other half: [name] does not end where it started (a bounce back, or a move undone). */
    fun doesNotReturn(frames: List<Frame>, name: String, edge: Edge): List<Violation> {
        val v = frames.mapNotNull { it.one(name)?.edge(edge) }
        if (v.size < 3) return emptyList()
        val moved = v.any { abs(it - v.first()) > STILL_PX }
        return if (moved && abs(v.last() - v.first()) <= STILL_PX) listOf(Violation("R5", name, frames.last().ms, "$edge returned to where it started")) else emptyList()
    }

    /**
     * R6: with animations off, each of [names] is at its end state on the first frame it is drawn, and it is drawn within
     * [withinMs] of the trigger (a composition and a layout pass; no motion between).
     */
    fun endStateAtOnce(frames: List<Frame>, names: Collection<String>, withinMs: Long = 32): List<Violation> {
        val last = frames.last()
        return names.mapNotNull { n ->
            val end = last.one(n) ?: return@mapNotNull null
            val first = frames.firstOrNull { it.ms >= 0 && it.one(n) != null } ?: return@mapNotNull Violation("R6", n, 0, "never drawn")
            val at = first.one(n)!!
            when {
                first.ms > withinMs -> Violation("R6", n, first.ms, "animations off: first drawn ${first.ms} ms after the trigger")
                Edge.entries.any { e -> abs(at.edge(e) - end.edge(e)) > still(e) } -> Violation("R6", n, first.ms, "animations off: first drawn $at, ends $end")
                else -> null
            }
        }
    }
}
