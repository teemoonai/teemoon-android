package ai.teemoon.app

// chat-search-titles (the maintainer, 2026-11-06, via pm): an imported near.ai chat keeps its near.ai title, which usually
// appears in none of its messages — and the list's search matched message text only, so searching the title found
// nothing (the Pixel import run, 2026-11-06). Titles now match too, case- and diacritic-insensitive, merged with the
// index's hits: title matches first, then the index's order.

import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.search.ChatSearchHighlight
import ai.teemoon.chat.search.ChatSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListTitleSearchTest {
    private val imported = ThreadEntity("imp", "a ~10 MB JPEG data URL (300×200, comment-padded): imports", timestamp = 1_000)
    private val recent = ThreadEntity("new", "trip to Montréal", timestamp = 3_000)
    private val other = ThreadEntity("old", "groceries", timestamp = 2_000)
    private val threads = listOf(recent, other, imported)
    private fun hit(thread: String) = ChatSearchResult("m-$thread", thread, "user", 0, "\u0001jpeg\u0002 in a message")

    @Test fun anImportedChatIsFoundByItsTitleWhenNoMessageHasIt() {
        assertEquals(listOf(imported), ChatListTitleSearch.shown(threads, "JPEG data URL", hits = emptyList()))
    }

    @Test fun titlesFoldCaseAndDiacritics() {
        assertEquals(listOf(recent), ChatListTitleSearch.shown(threads, "montreal", hits = emptyList()))
        assertEquals(listOf(recent), ChatListTitleSearch.shown(threads, "MONTRÉAL", hits = emptyList()))
    }

    @Test fun titleMatchesComeFirstThenTheIndexOrderWithoutRepeats() {
        // the index answers "groceries" then the imported chat; the imported chat's title also matches "jpeg"
        val shown = ChatListTitleSearch.shown(threads, "jpeg", hits = listOf(hit("old"), hit("imp")))
        assertEquals(listOf(imported, other), shown)
    }

    @Test fun anIndexHitWithNoThreadIsDropped() {
        assertEquals(listOf(other), ChatListTitleSearch.shown(threads, "zzz", hits = listOf(hit("gone"), hit("old"))))
    }

    @Test fun theTitleIsHighlightedAsTheReason() {
        val marked = ChatListTitleSearch.highlightedTitle(recent.title, "montreal")!!
        assertEquals("trip to Montréal", ChatSearchHighlight.plainText(marked))
        assertEquals(listOf("Montréal"), ChatSearchHighlight.segments(marked).filter { it.isMatch }.map { it.text })
        assertNull(ChatListTitleSearch.highlightedTitle(other.title, "montreal"))
        assertNull(ChatListTitleSearch.highlightedTitle(null, "montreal"))
    }

    /** The no-index fallback keeps matching titles as it did, with the same folding now. */
    @Test fun theFallbackMatchesTitlesWithTheSameRule() {
        assertTrue(ChatListTitleSearch.titleMatches("trip to Montréal", "montreal"))
        assertTrue(!ChatListTitleSearch.titleMatches(null, "montreal"))
        assertTrue(!ChatListTitleSearch.titleMatches("anything", "  "))
    }

    /** The no-index fallback orders as iOS's does: title matches first, then message matches, each in list order. */
    @Test fun theFallbackPutsTitleMatchesFirst() {
        // "old" matches by its messages only, "new" by its title; the list order is new, old, imp
        val byMessage = setOf("old", "imp")
        assertEquals(listOf(recent, other, imported), ChatListTitleSearch.fallbackShown(threads, "montreal", byMessage))
        val titleLast = listOf(other, recent)   // a title match below a message match in the list still comes first
        assertEquals(listOf(recent, other), ChatListTitleSearch.fallbackShown(titleLast, "montreal", setOf("old")))
    }
}
