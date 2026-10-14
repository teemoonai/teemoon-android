// ports: teemoon/Confidential/Data+LogPreview.swift @ v1.0.1
package ai.teemoon.confidential

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

const val LOG_PREVIEW_LIMIT = 2048

/**
 * A response body as a log line: bounded, decoded, honest about what was cut.
 * A cut can land inside a multi-byte sequence, so up to three trailing bytes
 * are dropped before giving up on UTF-8; a body that still will not decode is
 * described, not dumped.
 */
fun ByteArray.previewForLog(limit: Int = LOG_PREVIEW_LIMIT): String {
    val bound = maxOf(0, limit)
    val slice = copyOfRange(0, minOf(bound, size))
    var text: String? = null
    for (drop in 0 until minOf(4, slice.size + 1)) {
        val decoded = strictUtf8(slice.copyOfRange(0, slice.size - drop)) ?: continue
        text = decoded; break
    }
    var preview = text ?: return "<binary, $size bytes>"
    if (size > bound) preview += "… (+${size - bound} bytes truncated)"
    return preview
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) { null }
