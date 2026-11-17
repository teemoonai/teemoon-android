// A7 (pm sweep 2026-11-06): withTimeoutOrNull around a blocking JNI call (engine.initialize) cannot fire — timeouts are
// cooperative, and a call that never suspends holds the coroutine until it returns. The ladder then waited the whole
// native load out, and an engine abandoned at the deadline stayed resident beside the next rung's. BlockingDeadline
// runs the call on its own thread: the caller stops waiting at the deadline, and a result that arrives after it is
// handed to `abandoned` (the factory closes it) instead of leaking.
package ai.teemoon.ondevice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockingDeadlineTest {
    @Test fun aCallThatOutlivesTheDeadlineIsAbandonedAndCleanedUpWhenItEnds() = runBlocking {
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        var abandonedWith: Result<String>? = null
        val started = System.nanoTime()
        val got = BlockingDeadline.run(timeoutMs = 100, block = { release.await(); "engine" }, abandoned = { abandonedWith = it; cleaned.countDown() })
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertNull("the caller stops waiting at the deadline", got)
        assertTrue("it did not wait the blocking call out: ${waitedMs}ms", waitedMs < 2_000)
        release.countDown()
        assertTrue(cleaned.await(5, TimeUnit.SECONDS))
        assertEquals("engine", abandonedWith?.getOrNull())
    }

    @Test fun aCallInTimeReturnsItsResultAndAbandonsNothing() = runBlocking {
        var abandoned = false
        assertEquals(42, BlockingDeadline.run(timeoutMs = 5_000, block = { 42 }, abandoned = { abandoned = true }))
        assertTrue(!abandoned)
    }

    @Test fun aThrowInTimeIsRethrown() = runBlocking {
        val thrown = runCatching { BlockingDeadline.run(timeoutMs = 5_000, block = { error("refused") }, abandoned = {}) }.exceptionOrNull()
        assertEquals("refused", thrown?.message)
    }
}
