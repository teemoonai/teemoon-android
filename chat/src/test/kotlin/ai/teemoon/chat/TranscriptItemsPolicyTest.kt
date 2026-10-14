// ports: teemoonTests/TranscriptItemsPolicyTests.swift @ v1.0.4
package ai.teemoon.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptItemsPolicyTest {

    /** Rows as strings, so the assertions read as the list a person would see. */
    private fun lists(
        ids: List<String>, freshStarts: Set<Int> = emptySet(),
        searchConfigured: Boolean = false, offeredOn: Set<String> = emptySet(),
    ) = TranscriptItemsPolicy.lists(
        ids, freshStarts, searchConfigured, offered = { it in offeredOn },
        message = { "msg:$it" }, freshStart = { "fresh:$it" }, offer = { "offer:$it" })

    /**
     * The hand-off sizes the LAST transcript item, so it must be the reply. The
     * newest message's offer opens the tail instead of following it.
     */
    @Test fun theNewestOfferOpensTheTailRatherThanEndingTheTranscript() {
        val l = lists(listOf("a", "b"), offeredOn = setOf("b"))
        assertEquals(listOf("msg:a", "msg:b"), l.transcript)
        assertEquals("offer:b", l.tailOffer)
    }

    /** An offer on an EARLIER message stays where it is — nothing is sized off it. */
    @Test fun anEarlierOfferStaysInPlace() {
        val l = lists(listOf("a", "b"), offeredOn = setOf("a"))
        assertEquals(listOf("msg:a", "offer:a", "msg:b"), l.transcript)
        assertNull(l.tailOffer)
    }

    /**
     * AN OFFER IS A CLAIM THAT SEARCH IS OFF. Once a key is in, every offer row
     * goes — whatever the offer map still remembers. A key entered in SETTINGS
     * used to leave "turn on web search" standing under a reply while searches
     * ran, because only the card's own paste path cleared the map.
     */
    @Test fun aConfiguredSearchRemovesEveryOfferHoweverTheKeyArrived() {
        val l = lists(listOf("a", "b"), searchConfigured = true, offeredOn = setOf("a", "b"))
        assertEquals(listOf("msg:a", "msg:b"), l.transcript)
        assertNull(l.tailOffer)
    }

    /** A fresh-start rule goes BEFORE the message it precedes. */
    /** The inline renderer's form of the same rule (ChatScreen draws offers under their reply). */
    @Test fun showsOfferIsTheSameRule() {
        assertTrue(TranscriptItemsPolicy.showsOffer(searchConfigured = false, offered = true))
        assertFalse(TranscriptItemsPolicy.showsOffer(searchConfigured = true, offered = true))
        assertFalse(TranscriptItemsPolicy.showsOffer(searchConfigured = false, offered = false))
    }

    @Test fun aFreshStartPrecedesItsMessage() {
        val l = lists(listOf("a", "b", "c"), freshStarts = setOf(1))
        assertEquals(listOf("msg:a", "fresh:b", "msg:b", "msg:c"), l.transcript)
    }

    @Test fun anEmptyTranscriptHasNothingAndNoTail() {
        val l = lists(emptyList())
        assertEquals(emptyList<String>(), l.transcript)
        assertNull(l.tailOffer)
    }

    /** A single message that is offered on is still the newest, so its offer is the tail. */
    @Test fun theOnlyMessageIsAlsoTheNewest() {
        val l = lists(listOf("a"), offeredOn = setOf("a"))
        assertEquals(listOf("msg:a"), l.transcript)
        assertEquals("offer:a", l.tailOffer)
    }
}
