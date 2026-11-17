// android-only: no Swift counterpart. The load gate's decline-to-start, with one second look (pm 2026-10-30).
package ai.teemoon.app

import ai.teemoon.inference.LocalInferenceError

/**
 * Declines a load when available memory is under [need] — decline-to-start beats dying mid-answer — but looks twice:
 * right after launch the system has not yet trimmed its caches (availMem 2645 MB on a Pixel 8a, 3399 MB a moment later,
 * Test Lab 2026-10-30), so a short wait turns a false refusal into a load. 0 from [read] means the API is silent: never
 * block on a number that could not be read.
 */
object LoadMemoryGate {
    const val RETRY_DELAY_MS = 2_000L

    fun check(need: Int, read: () -> Int, wait: (Long) -> Unit = Thread::sleep) {
        val first = read()
        if (first <= 0 || first >= need) return
        wait(RETRY_DELAY_MS)
        val second = read()
        if (second <= 0 || second >= need) return
        throw LocalInferenceError.InsufficientMemory(need, second)
    }
}
