// ports: teemoon/Inference/ThinkingContentParser.swift @ v1.0.1
package ai.teemoon.inference

object ThinkingContentParser {
    /**
     * [thinking] is the text inside the think tags (null if no `<think>` tag);
     * [answer] is the text after the closing tag (null if still thinking or empty).
     */
    data class Parsed(val thinking: String?, val answer: String?)

    /**
     * Splits content into a thinking portion and an answer portion. Two markups, because two bundles:
     *
     *   `<think>…</think>`                 — what the general `.litertlm` emits (and what iOS parses)
     *   `<|channel>thought…<channel|>`     — Gemma 4's channel markup, what the Tensor NPU bundle emits
     *
     * Whichever opener comes first wins and its own closer ends it. Uses the FIRST opener and the FIRST closer;
     * anything before the opener is discarded. Called once per streamed tick on the whole document, so it stays
     * index scans and no regex. The channel form is Android-only until iOS has a bundle that emits it — its
     * parser at tf/1.0.2-34 knows `<think>` alone, and without this the whole reasoning streamed into the
     * transcript as body text with a literal `<channel|>` before the answer (Pixel 10 Pro, 2026-09-16).
     */
    fun parse(content: String): Parsed {
        val think = content.indexOf(THINK_OPEN)
        val channel = content.indexOf(CHANNEL_OPEN)
        val (start, open, close) = when {
            think < 0 && channel < 0 -> return Parsed(null, content.trim())
            channel < 0 || (think in 0 until channel) -> Triple(think, THINK_OPEN, THINK_CLOSE)
            else -> Triple(channel, CHANNEL_OPEN, CHANNEL_CLOSE)
        }
        val afterOpen = start + open.length
        val end = content.indexOf(close, afterOpen)
        if (end < 0) return Parsed(content.substring(afterOpen).trim(), null)
        val thinking = content.substring(afterOpen, end).trim()
        val afterThink = content.substring(end + close.length).trim()
        return Parsed(thinking, afterThink.ifEmpty { null })
    }

    /**
     * True while [content] could still BECOME a thinking opener: it is a proper prefix of `<think>` or
     * `<|channel>thought` (leading whitespace aside). A streamed opener arrives in pieces — "<|", "chan", "nel>",
     * "thought" — and every piece before the last parsed as answer text, so "<|channel>" flashed in the bubble on the
     * Pixel (2026-10-03). A streaming renderer holds such text back until it completes or stops matching.
     */
    fun mayBeginOpener(content: String): Boolean {
        val s = content.trimStart()
        if (s.isEmpty()) return false
        return (s.length < THINK_OPEN.length && THINK_OPEN.startsWith(s)) || (s.length < CHANNEL_OPEN.length && CHANNEL_OPEN.startsWith(s))
    }

    const val THINK_OPEN = "<think>"
    const val THINK_CLOSE = "</think>"
    /** The channel name follows the opener; only the `thought` channel is reasoning. */
    const val CHANNEL_OPEN = "<|channel>thought"
    const val CHANNEL_CLOSE = "<channel|>"
}
