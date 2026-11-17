// A5 (pm sweep 2026-11-06): the engine cache keeps ONE engine resident, but only against engines already built. Two
// builds at once (a warm-up of one bundle, a turn on another) each evicted what was resident before they started, and
// both kept their engine: two multi-gigabyte engines. And an evictAll during a build found nothing to evict, so the
// engine that finished after it stayed resident against the eviction.
package ai.teemoon.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBuildRaceTest {
    private class Handle(val path: String) : LocalEngineHandle {
        var closed = false
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {}
        override fun benchmark(): LocalBenchmark? = null
        override fun close() { closed = true }
    }
    /** Each open waits for the test to finish it. */
    private class Gated : LocalEngineFactory {
        val opens = LinkedHashMap<String, CompletableDeferred<Unit>>()
        val built = ArrayList<Handle>()
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle {
            opens.getOrPut(modelPath) { CompletableDeferred() }.await()
            return Handle(modelPath).also { built += it }
        }
        override val backendLabel = "test"
    }
    private fun cache(f: LocalEngineFactory, gate: LocalGenerationGate = LocalGenerationGate()) =
        LocalEngineCache(f, residency = LocalEngineResidency(), gate = gate, fileExists = { true })

    @Test fun twoBuildsAtOnceLeaveOneEngineResident() = runTest {
        val f = Gated(); val c = cache(f)
        val a = async { c.engine("a.litertlm", 4096, 100, 0) }
        val b = async { c.engine("b.litertlm", 4096, 100, 0) }
        advanceUntilIdle()
        f.opens.values.forEach { it.complete(Unit) }; advanceUntilIdle()
        // the second may still be waiting for its turn to build: let it
        f.opens.values.forEach { it.complete(Unit) }; advanceUntilIdle()
        a.await(); b.await()
        assertEquals(1, c.residentCount)
        assertEquals("the one not resident was closed", 1, f.built.count { !it.closed })
    }

    @Test fun anEvictionDuringAWarmUpBuildLeavesNothingResident() = runTest {
        val f = Gated(); val c = cache(f)
        val warm = async { runCatching { c.engine("a.litertlm", 4096, 100, 0) } }
        advanceUntilIdle()
        c.evictAll()                       // the app trimmed memory while the warm-up was loading
        f.opens.getValue("a.litertlm").complete(Unit); advanceUntilIdle()
        warm.await()
        assertEquals(0, c.residentCount)
        assertTrue(f.built.all { it.closed })
    }

    @Test fun anEvictionDuringATurnsBuildWaitsForTheTurnToEnd() = runTest {
        val f = Gated(); val gate = LocalGenerationGate(); val c = cache(f, gate)
        val turn = async { gate.withTurn { c.engine("a.litertlm", 4096, 100, 0).also { c.afterTurn() } } }
        advanceUntilIdle()
        c.evictAll()                       // deferred: a turn holds the gate
        f.opens.getValue("a.litertlm").complete(Unit); advanceUntilIdle()
        val h = turn.await() as Handle
        assertTrue("the turn's engine went at the end of the turn", h.closed)
        assertEquals(0, c.residentCount)
    }
}
