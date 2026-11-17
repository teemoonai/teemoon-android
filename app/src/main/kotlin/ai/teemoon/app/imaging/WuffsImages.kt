package ai.teemoon.app.imaging

/**
 * The memory-safe decoder (Wuffs, vendored and pinned in app/src/main/cpp; see docs/IMAGE_DECODER.md), reached over JNI
 * from INSIDE the isolated process only — never from the app's. Header first, then pixels: [probe] reads the dimensions
 * so the pixel cap is checked before anything is allocated for them.
 */
internal object WuffsImages {
    /** False when the native library is absent or failed to load: every image is then a placeholder, never a fallback. */
    val available: Boolean = runCatching { System.loadLibrary("teemoon_wuffs"); true }.getOrDefault(false)

    /** [width, height] of the first frame, or null when the header does not parse. */
    @JvmStatic external fun probe(bytes: ByteArray): IntArray?

    /** The first frame as premultiplied RGBA, width × height × 4 bytes, or null on any decode error. */
    @JvmStatic external fun decodeRgba(bytes: ByteArray, width: Int, height: Int): ByteArray?
}
