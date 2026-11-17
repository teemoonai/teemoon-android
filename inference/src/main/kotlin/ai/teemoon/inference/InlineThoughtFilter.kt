// ports: teemoon/Inference/LiteRTTransport.swift @ v1.0.6 (InlineThoughtFilter)
package ai.teemoon.inference

/**
 * Holds back an inline `<|channel>…<channel|>` thought as it streams, a marker split across chunks included, and
 * releases only the text outside it. The Tensor NPU bundle writes Gemma's thought inline; the maintainer, 2026-10-22: it is
 * handled like cloud reasoning — the "thinking…" timer runs, the thought never reaches the screen or the saved reply.
 * ThinkingContentParser's opener check looked only at the START of the text, so "Sure. <|cha" could flash; this looks
 * at every tail.
 */
class InlineThoughtFilter {
    private var buffer = ""
    private var inThought = false

    fun append(text: String): String {
        buffer += text
        val out = StringBuilder()
        while (true) {
            if (inThought) {
                val end = buffer.indexOf(CLOSE)
                if (end < 0) { buffer = buffer.takeLast(CLOSE.length - 1); return out.toString() }
                buffer = buffer.substring(end + CLOSE.length)
                inThought = false
            } else {
                val start = buffer.indexOf(OPEN)
                if (start >= 0) {
                    out.append(buffer, 0, start)
                    buffer = buffer.substring(start + OPEN.length)
                    inThought = true
                } else {
                    val keep = heldPrefix(buffer)
                    out.append(buffer, 0, buffer.length - keep)
                    buffer = buffer.takeLast(keep)
                    return out.toString()
                }
            }
        }
    }

    /** What was held back as a possible marker start; nothing if the stream ended inside a thought. */
    fun finish(): String {
        val rest = if (inThought) "" else buffer
        buffer = ""
        return rest
    }

    private companion object {
        const val OPEN = "<|channel>"
        const val CLOSE = "<channel|>"

        /** The longest tail of [text] that could still become the opening marker. */
        fun heldPrefix(text: String): Int {
            for (n in minOf(OPEN.length - 1, text.length) downTo 1) if (OPEN.startsWith(text.takeLast(n))) return n
            return 0
        }
    }
}
