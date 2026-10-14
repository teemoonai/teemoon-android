// ports: teemoon/Chat/Search/ChatSearchHighlight.swift @ v1.0.1 (segments, plainText, centred, excerpt;
//        `attributed` is the Compose AnnotatedString builder, built from `segments`)
package ai.teemoon.chat.search

import java.text.Normalizer

/**
 * Turns `snippet()`'s delimited output into runs with the matched terms marked.
 * The delimiters are safe by construction — [ChatSearchIndex.sanitizeForStorage]
 * removes them from message text on the way in — but parse defensively anyway:
 * an unpaired opener must render as text, not swallow the rest of the line.
 */
object ChatSearchHighlight {
    data class Segment(val text: String, val isMatch: Boolean)

    private const val OPEN = '\u0001'
    private const val CLOSE = '\u0002'

    /** Splits a snippet into plain and matched runs, in order. */
    fun segments(snippet: String): List<Segment> {
        val out = ArrayList<Segment>(); val current = StringBuilder(); var inMatch = false
        for (ch in snippet) {
            when (ch) {
                OPEN -> { if (inMatch) continue; if (current.isNotEmpty()) { out.add(Segment(current.toString(), false)); current.clear() }; inMatch = true }
                CLOSE -> { if (!inMatch) continue; if (current.isNotEmpty()) { out.add(Segment(current.toString(), true)); current.clear() }; inMatch = false }
                else -> current.append(ch)
            }
        }
        // An unterminated match still has to render; dropping it would silently truncate the snippet.
        if (current.isNotEmpty()) out.add(Segment(current.toString(), inMatch))
        return out
    }

    /** The snippet with its delimiters removed and nothing else changed. */
    fun plainText(snippet: String): String = segments(snippet).joinToString("") { it.text }

    /**
     * Re-centres a snippet so the first highlighted run is near the start.
     * THE TOKEN BUDGET AND THE LINE BUDGET NEVER AGREED: `snippet()` picks an
     * 18-token window, the row renders two lines, and a late match is truncated
     * off the second line — correct string, present delimiters, a row that
     * looks like a false positive. No string-level assertion catches this; the
     * test for it is a rendered screenshot.
     */
    fun centred(snippet: String, lead: Int = 24): String {
        val openAt = snippet.indexOf(OPEN)
        if (openAt < 0 || openAt <= lead) return snippet
        // Cut on a word boundary so the excerpt does not begin mid-word.
        val hardStart = openAt - lead
        val space = snippet.indexOf(' ', hardStart).takeIf { it in hardStart until openAt }
        val start = if (space != null) space + 1 else hardStart
        val ellipsis = if (snippet.startsWith("…") || snippet.startsWith("…", start)) "" else "…"
        return ellipsis + snippet.substring(start)
    }

    /**
     * A snippet built here for the degraded path, when the index could not
     * answer and the in-memory filter produced the row instead. Exists so that
     * EVERY result row shows why it matched — a fallback row that falls through
     * to the assistant preview looks identical to a result whose keyword appears
     * nowhere in it, the exact symptom that makes search feel broken.
     */
    fun excerpt(query: String, contents: List<String>, window: Int = 60): String? {
        val term = query.trim()
        if (term.isEmpty()) return null
        val foldedTerm = foldWithMap(term).folded
        for (content in contents) {
            // Case- and diacritic-insensitive, like Foundation's compare options.
            val map = foldWithMap(content)
            val at = map.folded.indexOf(foldedTerm)
            if (at < 0) continue
            val foundStart = map.origin[at]
            val foundEnd = map.origin[at + foldedTerm.length - 1] + 1
            val start = maxOf(0, foundStart - window); val end = minOf(content.length, foundEnd + window)
            val lead = if (start == 0) "" else "…"; val trail = if (end == content.length) "" else "…"
            return lead + content.substring(start, foundStart) + ChatSearchIndex.HIGHLIGHT_OPEN +
                content.substring(foundStart, foundEnd) + ChatSearchIndex.HIGHLIGHT_CLOSE + content.substring(foundEnd, end) + trail
        }
        return null
    }

    private class Folded(val folded: String, val origin: IntArray)

    /** Folds case and diacritics per character, remembering which original index each folded char came from. */
    private fun foldWithMap(s: String): Folded {
        val sb = StringBuilder(); val origin = ArrayList<Int>()
        for ((i, ch) in s.withIndex()) {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
            if (base.isEmpty()) continue
            for (c in base) { sb.append(c); origin.add(i) }
        }
        return Folded(sb.toString(), origin.toIntArray())
    }
}
