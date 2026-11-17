package ai.teemoon.inference

// conformance/reply-machine.json's engineCache (G8), replayed against the real LocalEngineCache: an eviction while an
// engine builds bumps the epoch, so the engine that build finishes with is released, never kept; a second request for the
// same engine while it builds joins that build. Android's epoch is LocalEngineCache.evictions (A5, 42d5671).

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCacheKitTest {
    private class Engine : LocalEngineHandle {
        var closed = false
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {}
        override fun benchmark() = LocalBenchmark(0.0, 0, 0, 0.0, 0.0)
        override fun close() { closed = true }
        override fun dropKept() {}
    }
    /** Opens hold until [release]: the build is in flight while the vector's next events happen. */
    private class Factory : LocalEngineFactory {
        val release = CompletableDeferred<Unit>(); val built = ArrayList<Engine>()
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle { release.await(); return Engine().also { built += it } }
        override val backendLabel = "test"
    }

    private val vectors = Json.parseToJsonElement(javaClass.getResourceAsStream("/reply-machine.json")!!.reader().readText())
        .jsonObject.getValue("engineCache").jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }

    @Test fun everyKitVectorReplaysAgainstTheRealCache() = runTest {
        assertEquals(2, vectors.size)
        for (v in vectors) {
            val name = v.getValue("name").jsonPrimitive.content
            val f = Factory()
            val cache = LocalEngineCache(f, LocalEngineResidency(), fileExists = { true })
            val builds = ArrayList<kotlinx.coroutines.Deferred<LocalEngineHandle>>()
            for (e in v.getValue("events").jsonArray.map { it.jsonObject }) when (e.getValue("event").jsonPrimitive.content) {
                "build" -> { builds += async { cache.engine("/m/model.litertlm", 2048, sizeMB = 1, headroomMB = 0) }; runCurrent() }
                "evict" -> cache.evictAll()
                "initialized" -> { f.release.complete(Unit); runCatching { builds.forEach { it.await() } } }
                else -> error("$name: unknown event")
            }
            val expect = v.getValue("expect").jsonObject
            assertEquals("$name: resident", expect.getValue("resident").jsonPrimitive.int, cache.residentCount)
            assertEquals("$name: one open per key", 1, f.built.size)
            if (expect.getValue("resident").jsonPrimitive.int == 0) assertTrue("$name: the stale engine is released", f.built.all { it.closed })
        }
    }
}
