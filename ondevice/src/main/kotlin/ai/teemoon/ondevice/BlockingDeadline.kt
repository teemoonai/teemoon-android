// android-only: waiting on a blocking native call with a deadline (A7, pm sweep 2026-11-06).
package ai.teemoon.ondevice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A blocking call (a JNI load) with a deadline the caller can actually keep. withTimeoutOrNull around the call itself
 * never fires — timeouts are cooperative, and a call that doesn't suspend holds the coroutine until it returns. Here
 * the call runs on its own thread; the caller stops waiting at the deadline, and nothing can stop the native call, so
 * its result, when it comes, goes to [abandoned] — the factory closes the engine there rather than leaving it loaded
 * beside the next rung's.
 */
internal object BlockingDeadline {
    /** The call's result, or null at the deadline. A throw within the deadline is rethrown. */
    suspend fun <T> run(timeoutMs: Long, block: () -> T, abandoned: (Result<T>) -> Unit): T? {
        val done = CompletableDeferred<Result<T>>()
        val lock = Any()
        var gaveUp = false
        Thread({
            val r = runCatching(block)
            val late = synchronized(lock) { if (gaveUp) true else { done.complete(r); false } }
            if (late) abandoned(r)
        }, "teemoon-blocking-load").apply { isDaemon = true }.start()
        val r = withTimeoutOrNull(timeoutMs) { done.await() }
            ?: synchronized(lock) { if (done.isCompleted) done.getCompleted() else { gaveUp = true; null } }
            ?: return null
        return r.getOrThrow()
    }
}
