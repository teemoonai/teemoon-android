// android-only: the image decoder's deadline (IsolatedImageDecoder.decode). Its binder call and pipe reads block, and a
// blocking call ignores cancellation, so withTimeoutOrNull around them waited a hung decoder out — the deadline never
// fired and the attachment hung (the A7 trap; BlockingDeadline in :ondevice is the same fix for a JNI load).
package ai.teemoon.app.imaging

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AwaitDetachedTest {
    @Test fun blockingWorkPastTheDeadlineReturnsNullAtTheDeadline() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val got = awaitDetached(timeoutMs = 100) { release.await(); "decoded" }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertNull("the deadline gives up on the work", got)
        assertTrue("it did not wait the blocking call out: ${waitedMs}ms", waitedMs < 2_000)
        release.countDown()
    }

    @Test fun workInTimeReturnsItsResult() = runBlocking {
        assertEquals(7, awaitDetached(timeoutMs = 5_000) { 7 })
    }

    @Test fun aThrowInTimeIsRethrown() = runBlocking {
        val thrown = runCatching { awaitDetached(timeoutMs = 5_000) { error("garbage frame") } }.exceptionOrNull()
        assertEquals("garbage frame", thrown?.message)
    }

    @Test fun childrenRunTogether() = runBlocking {
        // decode()'s writer, call and reader are async children that need each other (a pipe): all three must run.
        val got = awaitDetached(timeoutMs = 5_000) {
            val gate = CountDownLatch(1)
            val a = async { gate.await(5, TimeUnit.SECONDS); "read" }
            val b = async { gate.countDown(); "written" }
            b.await() + "+" + a.await()
        }
        assertEquals("written+read", got)
    }
}
