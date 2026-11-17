// spec: IMAGES.md draft 3 §4 (teemoon-ios 67b39a4): which images a request carries; on the phone, §4b @ 00f72739.
package ai.teemoon.app.imaging

import ai.teemoon.inference.WireImage
import ai.teemoon.inference.WireMessage

/**
 * A continued chat resends its images, NEWEST first, up to [MAX_IMAGES] and [MAX_BYTES] per request; an older one
 * becomes [EARLIER] in its message's text, for that request only (nothing stored changes). To a model that cannot see
 * images, every image is that line and the turn's card says how many were left out. The line is fixed text, so a
 * chat's earlier turns keep their bytes from one send to the next and the prompt cache still matches.
 */
object ImageHistory {
    const val MAX_IMAGES = ImageCaps.CLOUD_PER_SEND
    const val MAX_BYTES = 8L * 1024 * 1024
    const val EARLIER = "[image earlier in this chat]"

    data class Result(val history: List<WireMessage>, val promptImages: List<WireImage>, val leftOut: Int)

    /**
     * IMAGES.md §4b @ 00f72739 (the maintainer 2026-11-12, "Yes": his Pixel's E2B, asked about two earlier photos, said
     * it didn't have them), the on-device model: the cloud's rule within the phone's own number — the question's images
     * first, then the chat's earlier ones newest first, up to [maxImages] per request in all, each prepared by
     * [OnDeviceImage]; the rest become the line. An image that can't be prepared is left out as one.
     */
    fun applyOnDevice(history: List<WireMessage>, promptImages: List<WireImage>, maxImages: Int = ImageCaps.ON_DEVICE_PER_SEND,
                      prepare: (ByteArray) -> ByteArray?): Result {
        var count = 0; var leftOut = 0
        fun take(image: WireImage): WireImage? {
            val prepared = if (count < maxImages) prepare(image.bytes) else null
            return if (prepared != null) { count++; WireImage("image/jpeg", prepared) } else { leftOut++; null }
        }
        val prompt = promptImages.mapNotNull(::take)
        val kept = arrayOfNulls<WireMessage>(history.size)
        for (i in history.indices.reversed()) {
            val m = history[i]
            if (m.images.isEmpty()) { kept[i] = m; continue }
            // within a message, the later image is the newer one
            val decisions = m.images.reversed().map(::take).reversed()
            val stay = decisions.filterNotNull()
            val gone = decisions.count { it == null }
            val text = if (gone == 0) m.content else (listOf(m.content).filter { it.isNotEmpty() } + List(gone) { EARLIER }).joinToString("\n\n")
            kept[i] = m.copy(content = text, images = stay)
        }
        return Result(kept.map { it!! }, prompt, leftOut)
    }

    fun apply(history: List<WireMessage>, promptImages: List<WireImage>, vision: Boolean): Result {
        var count = 0; var bytes = 0L; var leftOut = 0
        fun keep(image: WireImage): Boolean {
            val ok = vision && count < MAX_IMAGES && bytes + image.bytes.size <= MAX_BYTES
            if (ok) { count++; bytes += image.bytes.size } else leftOut++
            return ok
        }
        // The prompt's own images are the newest; then the history, last message first.
        val prompt = promptImages.filter(::keep)
        val kept = arrayOfNulls<WireMessage>(history.size)
        for (i in history.indices.reversed()) {
            val m = history[i]
            if (m.images.isEmpty()) { kept[i] = m; continue }
            // Within a message, the later image is the newer one.
            val decisions = m.images.reversed().map { it to keep(it) }.reversed()
            val stay = decisions.filter { it.second }.map { it.first }
            val gone = decisions.count { !it.second }
            val text = if (gone == 0) m.content else (listOf(m.content).filter { it.isNotEmpty() } + List(gone) { EARLIER }).joinToString("\n\n")
            kept[i] = m.copy(content = text, images = stay)
        }
        return Result(kept.map { it!! }, prompt, leftOut)
    }
}
