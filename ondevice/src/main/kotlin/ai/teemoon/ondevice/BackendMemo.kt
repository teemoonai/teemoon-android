// android-only: no Swift counterpart — iOS never probes, so it has nothing to remember.
package ai.teemoon.ondevice

import java.io.File

/**
 * Remembers which accelerator actually decoded, so the probe is paid once rather than on every cold start.
 *
 * The probe is honest and therefore expensive: a rejected rung costs a whole model load first (18 s for the GPU on a
 * Pixel 10 Pro, 2026-09-15). Paying that on every launch to relearn the same answer would be the most visible thing
 * about on-device inference. So the verdict is written next to the engine's cache.
 *
 * It is a CACHE, never a source of truth: the file may be purged (it lives in cacheDir, which the system may empty),
 * may name a rung that has since stopped working after a system update, and is simply re-derived when wrong — a
 * remembered rung that fails falls through the rest of the ladder and the memo is rewritten. Nothing is lost by
 * losing it, which is why it may live somewhere the system can delete.
 */
class BackendMemo(private val file: File) {

    /** The remembered rung for [key], or null when nothing is remembered or the file no longer parses. */
    fun remembered(key: String): BackendKind? = runCatching {
        file.takeIf { it.isFile }?.readLines()?.firstNotNullOfOrNull { line ->
            val (k, v) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (k == key) BackendKind.entries.firstOrNull { it.name == v } else null
        }
    }.getOrNull()

    /** Records [kind] for [key], replacing any earlier verdict. Best effort: an unwritable cache is not an error. */
    fun remember(key: String, kind: BackendKind) {
        runCatching {
            val kept = (file.takeIf { it.isFile }?.readLines() ?: emptyList()).filterNot { it.startsWith("$key\t") }
            file.parentFile?.mkdirs()
            // Bounded: one line per model per ladder. A device that keeps re-probing must not grow a file forever.
            file.writeText((kept.takeLast(MAX_ENTRIES - 1) + "$key\t${kind.name}").joinToString("\n"))
        }
    }

    /** The ladder with the remembered rung first — the others still follow, because a memo can be stale. */
    fun reorder(ladder: List<BackendKind>, key: String): List<BackendKind> {
        val first = remembered(key)?.takeIf { it in ladder } ?: return ladder
        return listOf(first) + ladder.filterNot { it == first }
    }

    companion object {
        private const val MAX_ENTRIES = 16

        /**
         * What the verdict is about. The ladder is part of it: a new app version that offers a rung this device has
         * never tried must re-probe rather than coast on an answer taken from a shorter ladder.
         */
        fun key(modelPath: String, ladder: List<BackendKind>): String =
            File(modelPath).name + "|" + ladder.joinToString(",") { it.name }
    }
}
