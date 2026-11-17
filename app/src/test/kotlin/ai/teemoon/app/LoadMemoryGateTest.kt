// android-only: the load gate looks twice before declining (pm 2026-10-30) — right after launch the system has not yet
// trimmed its caches (Pixel 8a, Test Lab: availMem 2645 MB at launch, 3399 MB before the load).
package ai.teemoon.app

import ai.teemoon.inference.LocalInferenceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadMemoryGateTest {
    @Test fun enoughAtOnceNeverWaits() {
        var waited = 0L
        LoadMemoryGate.check(need = 2468, read = { 2645 }, wait = { waited += it })
        assertEquals(0L, waited)
    }

    @Test fun shortAtFirstThenEnoughAfterTheWaitLoads() {
        val reads = ArrayDeque(listOf(2400, 3399)); var waited = 0L
        LoadMemoryGate.check(need = 2468, read = { reads.removeFirst() }, wait = { waited += it })
        assertEquals(LoadMemoryGate.RETRY_DELAY_MS, waited)
    }

    @Test fun shortTwiceDeclinesWithTheSecondFigure() {
        val reads = ArrayDeque(listOf(1800, 1900))
        val e = assertThrows(LocalInferenceError.InsufficientMemory::class.java) { LoadMemoryGate.check(need = 2980, read = { reads.removeFirst() }, wait = {}) }
        assertTrue(e.message!!.contains("2980") && e.message!!.contains("1900"))
    }

    @Test fun anUnreadFigureNeverBlocks() { LoadMemoryGate.check(need = 2980, read = { 0 }, wait = { error("must not wait") }) }
}
