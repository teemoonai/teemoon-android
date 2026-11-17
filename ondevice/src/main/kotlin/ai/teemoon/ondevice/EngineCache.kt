// ports: teemoon/Inference/LiteRTTransport.swift @ afc4327 (engineCacheKey / engineCachePresent / engineCacheSnapshot, for the load line)
package ai.teemoon.ondevice

import java.io.File

/**
 * Where LiteRT keeps what it builds from a bundle: the GPU's packed weights (`*_mldrift_weight_cache.bin`, ~780 MB),
 * XNNPack's on the CPU (`*.xnnpack_cache`, ~790 MB) and, since 1.1.0, the vision encoder's compiled kernels and packed
 * weights (914 MB on the 8a — the first vision load took 58.8–92.6 s cold, 8.0 s on this cache).
 *
 * BESIDE THE MODEL, in its own directory (the 1.0.6 rule; pm, 2026-10-26, for the audit's disk gate): deleting the model
 * deletes the directory and the cache with it, and the size the Where sheet shows for a model (installedBytes walks the
 * directory) includes it. Android kept these in the app's cache directory from 1.0.6 to 1.1.0, so a delete left them
 * behind; [isStaleAppCacheFile] names those leftovers for the one-time sweep.
 */
object EngineCache {
    const val DIRECTORY = "litert-cache"

    fun directoryFor(modelPath: String): File = File(File(modelPath).parentFile, DIRECTORY)

    private val SUFFIXES = listOf("_mldrift_weight_cache.bin", ".xnnpack_cache", "_mldrift_program_cache.bin")
    fun isStaleAppCacheFile(name: String) = name.contains(".litertlm") && SUFFIXES.any { name.endsWith(it) }

    /**
     * The key LiteRT puts in a cache file's name: the bundle's file name, its mtime in seconds and its size
     * (`gemma-4-E2B-it.litertlm_1792699638_2588147712_mldrift_weight_cache.bin`, on both platforms). A new copy of the
     * bundle gets a new key, so a cache under the old one is no cache for this load.
     */
    fun key(bundle: File): String = "${bundle.name}_${bundle.lastModified() / 1000}_${bundle.length()}"

    /**
     * A cache for THIS bundle was there when the load started — the load line's `cachePresent` (EngineLoadLine). A
     * vision load also needs the vision encoder's own cache, the 144 MB piece of a first vision load: LiteRT names it
     * `<bundle>.vision_encoder_<mtime>_<size>_mldrift_weight_cache.bin` — the same mtime and size, after the encoder's
     * name rather than before it (the 8a, 2026-10-26, near-import eaf3458). If this test is wrong the rebuild watch still
     * catches a first load: LiteRT writes its files during initialize().
     */
    fun present(bundle: File, vision: Boolean): Boolean {
        val key = key(bundle)
        val names = directoryFor(bundle.path).list()?.toList() ?: return false
        val encoder = "${bundle.name}.vision_encoder_${bundle.lastModified() / 1000}_${bundle.length()}_"
        return names.any { it.startsWith(key) } && (!vision || names.any { it.startsWith(encoder) })
    }

    /** What the cache directory holds — name, size, mtime. A file created or rewritten during a load changes it. */
    fun snapshot(bundle: File): Map<String, Pair<Long, Long>> =
        directoryFor(bundle.path).listFiles()?.associate { it.name to (it.length() to it.lastModified()) } ?: emptyMap()
}

/**
 * What a load does, for the chip's load line (EngineLoadLine in :app; iOS EngineLoadObserver). Times are wall-clock ms,
 * so the app can place them on a turn's clock.
 */
interface EngineLoadEvents {
    fun began(atMs: Long, imageCapable: Boolean, cachePresent: Boolean) {}
    fun rebuildSeen(atMs: Long) {}
    fun ended(atMs: Long) {}

    object None : EngineLoadEvents
}
