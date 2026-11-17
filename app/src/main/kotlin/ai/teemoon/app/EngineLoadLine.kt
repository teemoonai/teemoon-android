// ports: teemoon/Inference/EngineLoadLine.swift @ afc4327
//
// The activity chip while an on-device engine loads (the maintainer, 2026-10-26). Decided from what the engine and the cache
// folder report, never a timer guessing at a load. conformance/loading.json pins it with iOS. The engine's events
// come from LiteRtLmEngineFactory (EngineLoadEvents) into [EngineLoadObserver]; the chat's chip reads it.
package ai.teemoon.app

import ai.teemoon.ondevice.EngineLoadEvents

object EngineLoadLine {
    const val PREPARING_FOR_IMAGES = "preparing the model for images… this happens once"
    const val LOADING = "loading the model…"
    /** A cached load says nothing until it has taken this long. */
    const val QUIET_MS = 3000.0

    /**
     * One turn's load, in ms from the send. [loadStartMs] null: the engine was already resident. [cachePresent]: a
     * cache for this bundle existed when the load started — on Android, a LiteRT cache file beside the model whose
     * name carries the CURRENT bundle's mtime and size (EngineCache); iOS tests the file-name prefix. Only the bool
     * is shared. [rebuildSeenAtMs]: when a cache file was first created or rewritten during the load — LiteRT gives
     * no "cache rejected" callback, so a rebuild after a LiteRT update shows only as the file being written.
     */
    data class Load(
        val loadStartMs: Double?,
        val loadEndsAtMs: Double?,
        val imageCapable: Boolean,
        val cachePresent: Boolean,
        val rebuildSeenAtMs: Double?,
    )

    /** The chip's text [atMs] after the send. Outside a load it is the thinking clock, counted from the send. */
    fun line(atMs: Double, load: Load): String {
        val thinking = activityLabel(ActivityState.Thinking, atMs / 1000)
        val start = load.loadStartMs ?: return thinking
        if (atMs < start) return thinking
        if (load.loadEndsAtMs != null && atMs >= load.loadEndsAtMs) return thinking
        val rebuilding = !load.cachePresent || load.rebuildSeenAtMs?.let { atMs >= it } == true
        if (load.imageCapable && rebuilding) return PREPARING_FOR_IMAGES
        return if (atMs - start >= QUIET_MS) LOADING else thinking
    }
}

/**
 * The last engine load, as the factory reported it (LiteRtLmEngineFactory → [EngineLoadEvents]). A mirror the chip
 * reads synchronously on its once-a-second clock; written from the loading thread, so the snapshot is one volatile value.
 */
class EngineLoadObserver : EngineLoadEvents {
    data class Observed(
        val startedMs: Long,
        val endedMs: Long? = null,
        val imageCapable: Boolean,
        val cachePresent: Boolean,
        val rebuildSeenMs: Long? = null,
    ) {
        /** The rule's input for a turn sent at [sendMs]. A load that ended before the send is no load. */
        fun load(sendMs: Long): EngineLoadLine.Load {
            if (endedMs != null && endedMs <= sendMs) return EngineLoadLine.Load(null, null, imageCapable, cachePresent, null)
            fun ms(t: Long) = (t - sendMs).toDouble()
            return EngineLoadLine.Load(ms(startedMs), endedMs?.let(::ms), imageCapable, cachePresent, rebuildSeenMs?.let(::ms))
        }
    }

    @Volatile var current: Observed? = null
        private set

    companion object {
        /** The app's one engine factory reports here (AppState), as iOS's `EngineLoadObserver.shared`. */
        val shared = EngineLoadObserver()
    }

    override fun began(atMs: Long, imageCapable: Boolean, cachePresent: Boolean) {
        current = Observed(startedMs = atMs, imageCapable = imageCapable, cachePresent = cachePresent)
    }

    override fun rebuildSeen(atMs: Long) {
        current?.let { if (it.rebuildSeenMs == null) current = it.copy(rebuildSeenMs = atMs) }
    }

    override fun ended(atMs: Long) {
        current?.let { current = it.copy(endedMs = atMs) }
    }

    /** The chip's line for an on-device turn sent at [sendMs], or null while it should read as thinking. */
    fun line(sendMs: Long?, nowMs: Long): String? {
        val send = sendMs ?: return null
        val observed = current ?: return null
        val line = EngineLoadLine.line((nowMs - send).toDouble(), observed.load(send))
        return line.takeIf { it == EngineLoadLine.PREPARING_FOR_IMAGES || it == EngineLoadLine.LOADING }
    }
}
