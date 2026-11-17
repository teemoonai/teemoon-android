// ports: teemoonTests/EngineLoadObserverTests.swift @ v1.1.0 (the cache key, presence and snapshot half)
//
// The load line reads facts the engine and its cache folder produce (conformance/loading.json holds the rule): the
// cache key LiteRT puts in each file name, and a file written during the load.
package ai.teemoon.ondevice

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCacheKeyTest {
    private fun scratchBundle(): File {
        val bundle = File(Files.createTempDirectory("load-").toFile(), "m.litertlm")
        bundle.writeBytes(ByteArray(1234))
        bundle.setLastModified(1_792_699_638_000)
        return bundle
    }

    @Test fun theCacheKeyIsTheBundlesNameMtimeAndSize() {
        val bundle = scratchBundle()
        try { assertEquals("m.litertlm_1792699638_1234", EngineCache.key(bundle)) } finally { bundle.parentFile!!.deleteRecursively() }
    }

    @Test fun onlyACacheForThisBundleCounts() {
        val bundle = scratchBundle()
        try {
            val cache = EngineCache.directoryFor(bundle.path).apply { mkdirs() }
            assertFalse(EngineCache.present(bundle, vision = false))
            File(cache, "m.litertlm_1700000000_1234_mldrift_weight_cache.bin").writeBytes(ByteArray(1))
            assertFalse("an older copy's cache counted", EngineCache.present(bundle, vision = false))
            File(cache, "m.litertlm_1792699638_1234_mldrift_weight_cache.bin").writeBytes(ByteArray(1))
            assertTrue(EngineCache.present(bundle, vision = false))
            assertFalse("a text cache is not a vision one", EngineCache.present(bundle, vision = true))
            // the 8a's names (2026-10-26, eaf3458): the encoder's files carry the mtime and size after its own name
            File(cache, "m.litertlm_1792699638_1234.vision_adapter.xnnpack_cache").writeBytes(ByteArray(1))
            assertFalse("the 4 MB adapter alone is not the encoder's cache", EngineCache.present(bundle, vision = true))
            File(cache, "m.litertlm.vision_encoder_1700000000_1234_mldrift_weight_cache.bin").writeBytes(ByteArray(1))
            assertFalse("an older copy's encoder cache counted", EngineCache.present(bundle, vision = true))
            File(cache, "m.litertlm.vision_encoder_1792699638_1234_mldrift_weight_cache.bin").writeBytes(ByteArray(1))
            assertTrue(EngineCache.present(bundle, vision = true))
        } finally { bundle.parentFile!!.deleteRecursively() }
    }

    @Test fun aFileWrittenDuringTheLoadChangesTheSnapshot() {
        val bundle = scratchBundle()
        try {
            val cache = EngineCache.directoryFor(bundle.path).apply { mkdirs() }
            val before = EngineCache.snapshot(bundle)
            assertEquals(before, EngineCache.snapshot(bundle))
            val f = File(cache, "m.litertlm_1792699638_1234_mldrift_program_cache.bin").apply { writeBytes(ByteArray(1)) }
            val created = EngineCache.snapshot(bundle)
            assertNotEquals(before, created)
            f.appendBytes(ByteArray(1))
            assertNotEquals("a file growing in place is a write too", created, EngineCache.snapshot(bundle))
        } finally { bundle.parentFile!!.deleteRecursively() }
    }
}
