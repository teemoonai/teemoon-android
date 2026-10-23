// Android-only (no Swift source).
package ai.teemoon.macrobenchmark

import android.util.Log
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Where the app's main thread spent an iteration: the named slices one level under the frame (Compose's recompose,
 * measure and layout, draw, input), most time first. The total is a measurement; the breakdown goes to logcat
 * (tag teemoon-bench), since a metric can only carry numbers.
 */
@OptIn(ExperimentalMetricApi::class)
class MainThreadWorkMetric(private val label: String) : TraceMetric() {
    override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
        val pkg = captureInfo.targetPackageName
        val rows = traceSession.query("""
            SELECT s.name AS name, SUM(s.dur) AS d, COUNT(*) AS n FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid) JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.tid = p.pid AND s.depth BETWEEN 1 AND 3
            GROUP BY s.name ORDER BY d DESC LIMIT 12""").toList()
        val frames = traceSession.query("""
            SELECT SUM(s.dur) AS d FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid) JOIN process p USING (upid)
            WHERE p.name LIKE '$pkg%' AND t.tid = p.pid AND s.name LIKE 'Choreographer#doFrame%'""").toList()
        Log.i("teemoon-bench", "$label main thread: " + rows.joinToString("; ") { "${it.string("name")} ${"%.1f".format(it.long("d") / 1e6)}ms x${it.long("n")}" })
        return listOf(Metric.Measurement("mainThreadFrameMs", (frames.firstOrNull()?.long("d") ?: 0L) / 1e6))
    }
}
