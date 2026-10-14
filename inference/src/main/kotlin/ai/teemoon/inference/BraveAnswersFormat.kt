// ports: teemoon/Inference/BraveAnswersFormat.swift @ v1.0.1
package ai.teemoon.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Brave Answers (`POST /res/v1/chat/completions`) is OpenAI-compatible on the
 * envelope but NOT in the delta payload: it interleaves two XML-ish blocks into
 * the assistant's `content` stream, so a client that just renders `content`
 * shows raw JSON in the middle of the prose.
 *
 *     …most populous city of France.<citation>{"start_index": 58, "end_index": 58,
 *       "number": 1, "url": "https://…", "favicon": "https://imgs.search.brave.com/…",
 *       "snippet": "Paris is the capital…"}</citation><citation>{…}</citation>
 *     …renowned for the Eiffel Tower.<usage>{"X-Request-Tokens-In": 9970,
 *       "X-Request-Total-Cost": 0.0548…}</usage>
 *
 * Observed live 2026-07-25 (brave-pro): `<citation>` blocks appear when the
 * request sets `enable_citations` (teemoon's preset does — they ARE the
 * provider's value); the trailing `<usage>` cost block is emitted **always**,
 * citations on or off. A 16.8 KB answer to "capital of france" was mostly
 * citation JSON, base64 favicon URLs included.
 *
 * So teemoon splits the stream: prose to the message, citations to the same
 * [GroundingSource] rail the web-search tool feeds, usage discarded (it is
 * already reported per-request in the debug panel).
 *
 * Docs: https://api-dashboard.search.brave.com/app/documentation
 */
object BraveAnswersFormat {

    /** The text to display and the citations found so far. */
    data class Split(val visible: String, val sources: List<GroundingSource>)

    /** Blocks Brave inlines into `content`. Order matters only for readability. */
    private val blocks = listOf("<citation>" to "</citation>", "<usage>" to "</usage>")

    /**
     * Splits a (possibly partial) answer into the text to display and the
     * citations found so far.
     *
     * Safe to call on every streaming delta: a block that hasn't closed yet is
     * withheld rather than shown half-parsed, and so is a bare `<` that could
     * still become an opening tag — otherwise the user watches `{"start_ind`
     * type across the screen before it disappears.
     */
    fun split(text: String): Split {
        if (!text.contains('<')) return Split(text, emptyList())

        val visible = StringBuilder()
        val citations = ArrayList<String>()
        var rest = text

        while (true) {
            val open = nextOpen(rest) ?: break
            visible.append(rest, 0, open.index)
            val afterOpen = rest.substring(open.index + open.opening.length)
            val close = afterOpen.indexOf(open.closing)
            if (close < 0) {
                // Unterminated — the rest is a block still streaming in. Drop it.
                return Split(visible.toString(), parseCitations(citations))
            }
            if (open.opening == "<citation>") citations.add(afterOpen.substring(0, close))
            rest = afterOpen.substring(close + open.closing.length)
        }

        // A trailing "<", "<cit", "<citation" … could be the start of a block.
        val partial = trailingPartialTag(rest)
        if (partial != null) visible.append(rest, 0, partial) else visible.append(rest)
        return Split(visible.toString(), parseCitations(citations))
    }

    /**
     * Sources for the rail, in first-cited order and deduplicated by URL.
     * Brave's citation JSON carries no title, so the title is left empty — the
     * row falls back to the domain rather than inventing one.
     */
    fun parseCitations(payloads: List<String>): List<GroundingSource> {
        val seen = HashSet<String>()
        return payloads.mapNotNull { json ->
            val c = runCatching { citationJson.decodeFromString(Citation.serializer(), json) }
                .getOrNull() ?: return@mapNotNull null
            val url = c.url?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            GroundingSource(
                url = url,
                domain = hostOf(url)?.replace("www.", "") ?: url,
                title = "",
                snippet = c.snippet ?: "",
            )
        }
    }

    /**
     * One `<citation>` payload. `favicon` is a Brave image-proxy URL and
     * `start_index`/`end_index` are offsets into the answer text — teemoon
     * renders a source list, not inline superscripts, so neither is used yet.
     */
    @Serializable
    data class Citation(
        val url: String? = null,
        val snippet: String? = null,
        val number: Int? = null,
        val favicon: String? = null,
        val start_index: Int? = null,
        val end_index: Int? = null,
    )

    private val citationJson = Json { ignoreUnknownKeys = true }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    // MARK: - Scanning

    private class Open(val opening: String, val closing: String, val index: Int)

    private fun nextOpen(text: String): Open? = blocks
        .mapNotNull { (opening, closing) ->
            text.indexOf(opening).takeIf { it >= 0 }?.let { Open(opening, closing, it) }
        }
        .minByOrNull { it.index }

    /**
     * Index of a trailing `<…` that is still a viable prefix of an opening tag
     * ("<", "<cit", "<usage" …), or null when the tail can't become one.
     */
    private fun trailingPartialTag(text: String): Int? {
        val last = text.lastIndexOf('<').takeIf { it >= 0 } ?: return null
        val tail = text.substring(last)
        return if (blocks.any { it.first.startsWith(tail) }) last else null
    }
}
