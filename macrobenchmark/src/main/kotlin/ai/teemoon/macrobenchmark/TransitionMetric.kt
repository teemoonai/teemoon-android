// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * What one push-and-pop costs, read from the app's NavTrace sections (app/NavTrace.kt) in the iteration's trace.
 *
 * The iteration is a push, a rest, a pop, a rest (TransitionBenchmarks). Its animation sections — `sheet:*` settles
 * and `page:*` slides — fall into two clusters a rest apart: the push, then the pop. Per cluster:
 *
 *   <push|pop>Ms               first animation section's start to the last one's end: the transition as drawn
 *   <push|pop>FramesAfter      main-thread frames AFTER the animation ended, up to the next cluster (or the trace's end).
 *                              Nothing should draw once a transition has landed and nobody touches the screen; a frame
 *                              here is content arriving late, or an animation still running (a keyboard, a spinner).
 *   <push|pop>LastFrameAfterMs how long after the animation ended the last such frame began: how late the screen settled
 *   firstComposeMs             the destination's first `screen:<dest>` composition (the frame that pays for it)
 *   composeCount               every `screen:<dest>` composition in the iteration: more than a few means the screen
 *                              recomposes per animation frame
 *   chatLoadingMs / chatLandingMs / chatLandingMoves   a chat's wait for Room, its settle window, and how many times
 *                              that window moved the transcript (only routes that open a chat)
 */
@OptIn(ExperimentalMetricApi::class)
class TransitionMetric(private val destination: String) : TraceMetric() {
    override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
        val pkg = captureInfo.targetPackageName
        fun rows(sql: String) = traceSession.query(sql).toList()
        // async sections sit on process tracks; sync ones on the main thread's
        val anim = rows("""
            SELECT s.name AS name, s.ts AS ts, s.dur AS dur FROM slice s
            JOIN process_track pt ON s.track_id = pt.id JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND (s.name LIKE 'sheet:%' OR s.name LIKE 'page:%') ORDER BY s.ts""")
            .map { Span(it.string("name"), it.long("ts"), it.long("dur")) }
        val chat = rows("""
            SELECT s.name AS name, s.ts AS ts, s.dur AS dur FROM slice s
            JOIN process_track pt ON s.track_id = pt.id JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND s.name LIKE 'chat:%' ORDER BY s.ts""")
            .map { Span(it.string("name"), it.long("ts"), it.long("dur")) }
        val mainThread = """
            FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid) JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.tid = p.pid"""
        val frames = rows("SELECT s.ts AS ts $mainThread AND s.name LIKE 'Choreographer#doFrame%' ORDER BY s.ts").map { it.long("ts") }
        val composes = rows("SELECT s.ts AS ts, s.dur AS dur $mainThread AND s.name = 'screen:$destination' ORDER BY s.ts").map { it.long("ts") to it.long("dur") }
        val moves = rows("""
            SELECT c.value AS value FROM counter c JOIN process_counter_track t ON c.track_id = t.id JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.name = 'chat.landing.moves' ORDER BY c.ts DESC LIMIT 1""").map { it.double("value") }

        // two clusters: a gap of more than REST_GAP_NS between one section's end and the next one's start splits them
        val clusters = mutableListOf<MutableList<Span>>()
        for (s in anim) {
            val last = clusters.lastOrNull()
            if (last == null || s.ts - last.maxOf { it.end } > REST_GAP_NS) clusters += mutableListOf(s) else last += s
        }
        val out = mutableListOf<Metric.Measurement>()
        listOf("push", "pop").forEachIndexed { i, phase ->
            val c = clusters.getOrNull(i) ?: return@forEachIndexed
            val start = c.minOf { it.ts }; val end = c.maxOf { it.end }
            // up to the next cluster, less the frame its own input draws before its first section begins
            val until = clusters.getOrNull(i + 1)?.let { n -> n.minOf { it.ts } - INPUT_LEAD_NS } ?: Long.MAX_VALUE
            val after = frames.filter { it > end && it < until }
            if (after.isNotEmpty()) {
                // what those frames did: the main thread's slices inside them, most time first (logcat, tag teemoon-bench)
                val work = rows("""SELECT s.name AS name, SUM(s.dur) AS d, COUNT(*) AS n $mainThread AND s.depth > 0 AND s.ts > $end AND s.ts < ${minOf(until, end + 4_000_000_000L)}
                    GROUP BY s.name ORDER BY d DESC LIMIT 8""").joinToString("; ") { "${it.string("name")} ${"%.1f".format(it.long("d") / 1e6)}ms x${it.long("n")}" }
                android.util.Log.i("teemoon-bench", "$destination $phase: ${after.size} frames after the animation: $work")
            }
            out += Metric.Measurement("${phase}Ms", (end - start) / 1e6)
            out += Metric.Measurement("${phase}FramesAfter", after.size.toDouble())
            out += Metric.Measurement("${phase}LastFrameAfterMs", after.maxOrNull()?.let { (it - end) / 1e6 } ?: 0.0)
        }
        composes.firstOrNull()?.let { out += Metric.Measurement("firstComposeMs", it.second / 1e6) }
        out += Metric.Measurement("composeCount", composes.size.toDouble())
        chat.firstOrNull { it.name == "chat:loading" }?.let { out += Metric.Measurement("chatLoadingMs", it.dur / 1e6) }
        chat.firstOrNull { it.name == "chat:landing" }?.let { out += Metric.Measurement("chatLandingMs", it.dur / 1e6) }
        moves.firstOrNull()?.let { out += Metric.Measurement("chatLandingMoves", it) }
        return out
    }

    private class Span(val name: String, val ts: Long, val dur: Long) { val end get() = ts + dur }

    private companion object {
        const val REST_GAP_NS = 500_000_000L
        const val INPUT_LEAD_NS = 50_000_000L
    }
}
