// Android-only (no Swift source): trace sections for screen transitions, read by :macrobenchmark's TransitionBenchmarks.
package ai.teemoon.app

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * Named sections a system trace shows around each screen transition (docs/PERFORMANCE_1.0.6.md, "screen transitions").
 *
 *   sheet:<which>:<open|close|detent>   an IosSheet settle, from its spring's start to its end (async)
 *   page:<route>                        a push or pop inside a sheet: the AnimatedContent slide (async)
 *   screen:<route>                      one composition of that route's screen (sync, main thread)
 *   chat:loading / chat:landing         a chat from its first frame to Room's answer, then its one-second settle window
 *
 * android.os.Trace only: nothing is logged, counted or sent, and with no trace being recorded every call is the
 * framework's one enabled check. Release builds carry them, as Compose's own sections are.
 */
internal object NavTrace {
    private val cookies = AtomicInteger()

    fun name(r: Route?): String = r?.let { RouteCodec.encode(it).substringBefore(':') } ?: "none"

    fun begin(label: String): Int = cookies.incrementAndGet().also { if (Trace.isEnabled()) Trace.beginAsyncSection(label, it) }
    fun end(label: String, cookie: Int) { if (Trace.isEnabled()) Trace.endAsyncSection(label, cookie) }
    fun count(label: String, value: Long) { if (Trace.isEnabled()) Trace.setCounter(label, value) }

    /** No try/finally: a composable body cannot sit in one, and a throw here ends the process anyway. */
    @Composable
    inline fun section(label: String, content: @Composable () -> Unit) {
        Trace.beginSection(label)
        content()
        Trace.endSection()
    }
}

/** An async section [label] for as long as [running] is true. */
@Composable
internal fun TraceWhile(label: String, running: Boolean) {
    if (running) DisposableEffect(label) { val cookie = NavTrace.begin(label); onDispose { NavTrace.end(label, cookie) } }
}
