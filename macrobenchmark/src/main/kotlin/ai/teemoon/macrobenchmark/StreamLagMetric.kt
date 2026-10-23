// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * How a streaming reply reaches the screen (1.0.6 row 18, "watching the reply arrive"), from two counters the app
 * sets: `stream.received` (the view model takes a delta: its text length) and `stream.shown` (the reply composes
 * that length into a frame). Per iteration:
 *
 *   revealLagP50Ms / P90 / Max   a delta's arrival to the end of the frame that first shows it
 *   shownGapP50Ms / P90 / Max    the time between visible text changes (what the eye gets)
 *   charsPerShownP50 / P90       how much text each visible change adds
 *   deltasPerShown               deltas folded into one visible change (> 1: several arrived inside one frame)
 */
@OptIn(ExperimentalMetricApi::class)
class StreamLagMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
        val pkg = captureInfo.targetPackageName
        fun counter(name: String) = traceSession.query("""
            SELECT c.ts AS ts, c.value AS v FROM counter c JOIN process_counter_track t ON c.track_id = t.id JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.name = '$name' ORDER BY c.ts""").map { it.long("ts") to it.double("v").toLong() }.toList()
        val received = counter("stream.received")
        // a recomposition re-sets the same length; only a change is a new thing on screen
        val shown = counter("stream.shown").fold(mutableListOf<Pair<Long, Long>>()) { acc, e -> if (acc.lastOrNull()?.second != e.second) acc += e; acc }
        val frames = traceSession.query("""
            SELECT s.ts AS ts, s.dur AS dur FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid) JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.tid = p.pid AND s.name LIKE 'Choreographer#doFrame%' ORDER BY s.ts""").map { it.long("ts") to it.long("dur") }.toList()
        if (received.isEmpty() || shown.isEmpty()) return emptyList()

        fun frameEnd(ts: Long) = frames.lastOrNull { it.first <= ts }?.takeIf { ts <= it.first + it.second }?.let { it.first + it.second } ?: ts
        val lags = received.mapNotNull { (rts, len) -> shown.firstOrNull { it.first >= rts && it.second >= len }?.let { (frameEnd(it.first) - rts) / 1e6 } }
        val gaps = shown.zipWithNext { a, b -> (b.first - a.first) / 1e6 }
        val chars = shown.zipWithNext { a, b -> (b.second - a.second).toDouble() }
        fun List<Double>.q(p: Int) = if (isEmpty()) 0.0 else sorted()[(size * p / 100).coerceAtMost(size - 1)]
        return listOf(
            Metric.Measurement("revealLagP50Ms", lags.q(50)), Metric.Measurement("revealLagP90Ms", lags.q(90)), Metric.Measurement("revealLagMaxMs", lags.maxOrNull() ?: 0.0),
            Metric.Measurement("shownGapP50Ms", gaps.q(50)), Metric.Measurement("shownGapP90Ms", gaps.q(90)), Metric.Measurement("shownGapMaxMs", gaps.maxOrNull() ?: 0.0),
            Metric.Measurement("charsPerShownP50", chars.q(50)), Metric.Measurement("charsPerShownP90", chars.q(90)),
            Metric.Measurement("deltasPerShown", received.size.toDouble() / shown.size),
        )
    }
}
