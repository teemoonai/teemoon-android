// spec: IMAGES.md draft 1 §2 (teemoon-ios b2c2ea2) with pm's defaults of 2026-10-25 (rounding half up; the untrusted set
// is what the memory-safe decoder covers: JPEG, PNG, GIF). the maintainer, 2026-10-25: "I dont want parsing errors to throw
// access to process." Everything here runs BEFORE a decoder sees a byte, and is plain bounds-checked Kotlin: it reads
// magic numbers and lengths, never pixels.
package ai.teemoon.app.imaging

import java.util.Base64

/** The shared caps (IMAGES.md §2). Over any of them: a placeholder, counted — never a decode. */
object ImageCaps {
    /** Encoded bytes per image. */
    const val MAX_BYTES = 20 * 1024 * 1024
    /** Pixels, read from the header before decoding (e.g. 8660 × 5773). */
    const val MAX_PIXELS = 50_000_000L
    /** …and neither side over this (IMPORT_NEAR.md draft 9). */
    const val MAX_SIDE = 16_384
    /** The stored image's long side after re-encoding. */
    const val MAX_LONG_SIDE = 2048
    /** The thumbnail's long side (bubble, list). */
    const val THUMB_LONG_SIDE = 256
    const val JPEG_QUALITY = 85
    /** The importer's own cap (NearChatImport), the same as [CLOUD_PER_SEND] (the maintainer 2026-11-07). */
    const val PER_IMPORTED_MESSAGE = ai.teemoon.chat.imports.NearChatImport.IMAGES_PER_MESSAGE
    /**
     * IMAGES.md draft 3 §4 (teemoon-ios 67b39a4; the maintainer 2026-10-28): 10 per message to a cloud model, one value that feeds
     * the composer, the picker's remaining count and the history budget ([ImageHistory.MAX_IMAGES]), with the 8 MiB
     * request cap kept.
     */
    const val CLOUD_PER_SEND = 10
    /**
     * §4b, the maintainer 2026-11-12: up to 10 on the phone where the memory check passes; where it fails, images are off
     * on that phone (no smaller number).
     */
    const val ON_DEVICE_PER_SEND = ai.teemoon.inference.LocalConversationSpec.MAX_IMAGES_PER_TURN
    fun perSend(onDevice: Boolean) = if (onDevice) ON_DEVICE_PER_SEND else CLOUD_PER_SEND
    /** How long one decode may take in the service before it is abandoned and the image becomes a placeholder. */
    const val DECODE_TIMEOUT_MS = 15_000L

    /**
     * The size a [width] × [height] image is stored at: unchanged when its long side fits [longSide], else the long side
     * becomes [longSide] and the short side is round-half-up of short × longSide / long — the one rule both platforms
     * use, so the corpus can pin width and height to the pixel. Never below 1.
     */
    fun targetSize(width: Int, height: Int, longSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val long = maxOf(width, height)
        if (long <= longSide) return width to height
        val short = minOf(width, height)
        val scaled = maxOf(1L, (short.toLong() * longSide * 2 + long) / (2L * long)).toInt()   // round half up, integer
        return if (width >= height) longSide to scaled else scaled to longSide
    }
}

/**
 * The formats the memory-safe decoder (Wuffs) reads. The import takes only JPEG, PNG and GIF ([DataUrl]'s declared
 * types); BMP is a picked or pasted image's (the maintainer 2026-11-11).
 */
enum class ImageFormat(val mime: String) {
    JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), BMP("image/bmp");

    companion object {
        /** From the bytes' magic number — never from a declared MIME type or a file name. */
        fun sniff(bytes: ByteArray): ImageFormat? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> PNG
            bytes.size >= 6 && (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" || bytes.copyOfRange(0, 6).decodeToString() == "GIF89a") -> GIF
            // "BM", then a 12-byte file header and at least a 12-byte info header before Wuffs is asked anything
            bytes.size >= 26 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> BMP
            else -> null
        }
    }
}

/**
 * The maintainer, 2026-11-11 (iOS S42): a picked or pasted WebP (any kind), HEIC/HEIF, AVIF or TIFF is read too — by Android's ImageDecoder, in
 * the same isolated process, after the same byte cap, with the pixel caps checked from its header before any pixel is
 * allocated, and re-encoded to JPEG or PNG as Wuffs' output is. It is not memory-safe as Wuffs is; the isolated process
 * (its own UID, no permissions, no files, no network) is what holds a parser bug. Never the import's: [DataUrl] refuses
 * these types before any decoder.
 */
enum class PlatformImageFormat(private val mimes: Set<String>) {
    WEBP(setOf("image/webp")), HEIF(setOf("image/heif", "image/heic")), AVIF(setOf("image/avif")), TIFF(setOf("image/tiff"));

    /** iOS S42: the decoder's own detected type must be this family — a container that lies about its content is refused. */
    fun matches(decoderMime: String?): Boolean = decoderMime?.lowercase() in mimes

    companion object {
        /** HEIF still-image brands (ISO/IEC 23008-12); AVIF's own; video brands are not taken. */
        private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
        private val avifBrands = setOf("avif", "avis")

        /** From the magic number only, after [ImageFormat.sniff] found none of Wuffs' formats. */
        fun sniff(bytes: ByteArray): PlatformImageFormat? = when {
            bytes.size >= 16 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> WEBP
            bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" && bytes.copyOfRange(8, 12).decodeToString() in heifBrands -> HEIF
            bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" && bytes.copyOfRange(8, 12).decodeToString() in avifBrands -> AVIF
            bytes.size >= 8 && (bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x49, 0x49, 0x2A, 0)) || bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4D, 0x4D, 0, 0x2A))) -> TIFF
            else -> null
        }
    }
}

/** Why an image became a placeholder; counted in the import summary, said in the composer. */
enum class ImageRejection { NOT_AN_IMAGE_URL, BAD_BASE64, TOO_MANY_BYTES, UNSUPPORTED_FORMAT, TOO_MANY_PIXELS, UNREADABLE, DECODER_FAILED, TIMED_OUT }

/** A `data:` URL taken apart, its payload decoded from base64 — the only base64 the import ever decodes. */
object DataUrl {
    private val declaredTypes = mapOf("image/jpeg" to ImageFormat.JPEG, "image/jpg" to ImageFormat.JPEG, "image/png" to ImageFormat.PNG, "image/gif" to ImageFormat.GIF)

    /**
     * IMPORT_NEAR.md draft 9 / reference_import.py `ref_image`: the header, case-insensitive, is exactly
     * `image/(jpeg|jpg|png|gif);base64`; the payload is at most 20 MiB·4/3+4 characters; characters outside the base64
     * alphabet are dropped (as Python's non-validating decoder does) and the rest must decode, non-empty, to at most
     * 20 MiB; and the bytes' magic number must be the declared type — a lying label is a placeholder, never decoded as
     * something else. An http(s) URL, `data:text/html` or anything else is refused here, before any decoder, and
     * nothing is fetched.
     */
    fun decode(url: String): Result {
        if (!url.startsWith("data:")) return Result.Rejected(ImageRejection.NOT_AN_IMAGE_URL)
        val comma = url.indexOf(','); if (comma < 0) return Result.Rejected(ImageRejection.NOT_AN_IMAGE_URL)
        val parts = url.substring(5, comma).lowercase().split(';')
        if (parts.size != 2 || parts[1] != "base64" || !parts[0].startsWith("image/")) return Result.Rejected(ImageRejection.NOT_AN_IMAGE_URL)
        val declared = declaredTypes[parts[0]] ?: return Result.Rejected(ImageRejection.UNSUPPORTED_FORMAT)
        val payload = url.substring(comma + 1)
        if (payload.length.toLong() > ImageCaps.MAX_BYTES / 3 * 4 + 4) return Result.Rejected(ImageRejection.TOO_MANY_BYTES)
        val clean = payload.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
        if (clean.length % 4 != 0) return Result.Rejected(ImageRejection.BAD_BASE64)
        val bytes = try { Base64.getDecoder().decode(clean) } catch (_: IllegalArgumentException) { return Result.Rejected(ImageRejection.BAD_BASE64) }
        if (bytes.isEmpty()) return Result.Rejected(ImageRejection.BAD_BASE64)
        if (bytes.size > ImageCaps.MAX_BYTES) return Result.Rejected(ImageRejection.TOO_MANY_BYTES)
        if (ImageFormat.sniff(bytes) != declared) return Result.Rejected(ImageRejection.UNSUPPORTED_FORMAT)
        return Result.Bytes(bytes, declared)
    }

    sealed interface Result {
        /** [format]: the declared type, which the bytes' magic number matched. */
        class Bytes(val bytes: ByteArray, val format: ImageFormat) : Result
        data class Rejected(val why: ImageRejection) : Result
    }
}
