// chat-search-titles (the maintainer approved, 2026-11-06, via pm): both platforms in 1.1.0. iOS's port of the same rule is in
// flight; the ordering below is pm's (title matches first, then the index's order) until iOS's commit pins it.
package ai.teemoon.app

import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.search.ChatSearchHighlight
import ai.teemoon.chat.search.ChatSearchResult

/**
 * The chats list's search also matches TITLES. The index is fts5 over message text only, and an imported near.ai chat
 * keeps its near.ai title, which usually appears in none of its messages: searching the title found nothing (the Pixel
 * import run, 2026-11-06). A title match is case- and diacritic-insensitive — [ChatSearchHighlight.excerpt]'s folding,
 * the one the no-index fallback's snippets already use — and needs no index or schema change.
 */
object ChatListTitleSearch {
    /** The whole title with the matched run marked, or null when the title does not contain [query]. */
    fun highlightedTitle(title: String?, query: String): String? =
        title?.let { ChatSearchHighlight.excerpt(query, listOf(it), window = it.length) }

    fun titleMatches(title: String?, query: String): Boolean = highlightedTitle(title, query) != null

    /**
     * No index: title matches first, then the threads whose messages contain the text, each in the list's order — as
     * iOS's fallback orders them (pm, 2026-11-06).
     */
    fun fallbackShown(threads: List<ThreadEntity>, query: String, messageMatchIds: Set<String>): List<ThreadEntity> {
        val byTitle = threads.filter { titleMatches(it.title, query) }
        val seen = byTitle.mapTo(HashSet()) { it.id }
        return byTitle + threads.filter { it.id in messageMatchIds && it.id !in seen }
    }

    /** Title matches first, in the list's order; then the index's hits in its rank order, each thread once. */
    fun shown(threads: List<ThreadEntity>, query: String, hits: List<ChatSearchResult>): List<ThreadEntity> {
        val byTitle = threads.filter { titleMatches(it.title, query) }
        val seen = byTitle.mapTo(HashSet()) { it.id }
        val byId = threads.associateBy { it.id }
        return byTitle + hits.mapNotNull { h -> byId[h.threadID]?.takeIf { seen.add(it.id) } }
    }
}
