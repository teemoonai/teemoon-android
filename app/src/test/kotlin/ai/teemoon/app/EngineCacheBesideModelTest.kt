// The 1.0.6 rule (pm, 2026-10-26, for the audit's disk gate): LiteRT's caches — the GPU's packed weights, XNNPack's,
// and since 1.1.0 the vision encoder's compiled kernels (914 MB on the 8a) — sit BESIDE the model and go with it.
// Android kept them in the app's cache directory since 1.0.6, so deleting a model left ~0.8–1.7 GB behind and the
// size the Where sheet showed for a model left them out.
package ai.teemoon.app

import ai.teemoon.ondevice.EngineCache
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalModelStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineCacheBesideModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theCacheSitsBesideTheModelCountsInItsSizeAndGoesWithIt() {
        val storage = LocalModelStorage(tmp.root)
        val model = LocalModelCatalog.all.first()
        val file = storage.file(model).also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(1000)) }
        val cache = EngineCache.directoryFor(file.path)
        assertEquals(storage.directory(model.id), cache.parentFile)
        cache.mkdirs(); java.io.File(cache, "vision_encoder_mldrift_program_cache.bin").writeBytes(ByteArray(500))
        assertEquals("the model's size includes its cache", 1500L, storage.installedBytes(model.id))
        storage.delete(model)
        assertFalse("deleting the model takes its cache", cache.exists())
    }

    @Test fun oldCachesInTheAppCacheDirectoryAreRecognised() {
        assertTrue(EngineCache.isStaleAppCacheFile("gemma-4-E2B-it.litertlm_mldrift_weight_cache.bin"))
        assertTrue(EngineCache.isStaleAppCacheFile("gemma-4-E2B-it.litertlm.xnnpack_cache"))
        assertFalse(EngineCache.isStaleAppCacheFile("backend-verdict.tsv"))
        assertFalse(EngineCache.isStaleAppCacheFile("import-spill"))
    }
}
