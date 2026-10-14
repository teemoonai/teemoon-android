// ports: teemoon/Views/Chat/TranscriptItemsPolicy.swift @ v1.0.4
//        Generic over the row type: iOS builds TranscriptItem cases directly, but that sealed
//        interface lives in app/Transcript.kt, a `human` row (the transcript contract). Taking
//        factories keeps the POLICY testable here without reaching into it.
package ai.teemoon.chat

/**
 * Which list a row belongs to.
 *
 * The transcript's LAST item is the one the hand-off sizes — the latest
 * section's absolute height, the streamed height stored as its floor, the one
 * re-measure — so it must be the REPLY. The newest message's web-search offer
 * therefore opens the tail instead of following its message; offers on earlier
 * messages stay in place.
 *
 * AN OFFER IS A CLAIM THAT SEARCH IS OFF. Once a key is in — from the card,
 * from settings, from the web chip — every offer row goes, whatever the offer
 * map still remembers. Only the card's own paste path cleared that map, so a
 * key entered in settings left "turn on web search" standing under a reply
 * while searches ran (device, 2026-09-18).
 */
object TranscriptItemsPolicy {

    data class Lists<T>(
        val transcript: List<T>,
        /** The newest message's offer, or null. Goes first in the tail. */
        val tailOffer: T?,
    )

    /** The rule on its own, for a renderer that draws offers inline: a configured search means no offer survives. */
    fun showsOffer(searchConfigured: Boolean, offered: Boolean): Boolean = offered && !searchConfigured

    fun <T> lists(
        messageIDs: List<String>,
        freshStarts: Set<Int>,
        searchConfigured: Boolean,
        offered: (String) -> Boolean,
        message: (String) -> T,
        freshStart: (String) -> T,
        offer: (String) -> T,
    ): Lists<T> {
        val transcript = ArrayList<T>(messageIDs.size + 1)
        var tailOffer: T? = null
        for ((index, id) in messageIDs.withIndex()) {
            if (index in freshStarts) transcript.add(freshStart(id))
            transcript.add(message(id))
            // The one condition, and it is the whole rule: a configured search
            // means no offer survives, however the key arrived.
            if (searchConfigured || !offered(id)) continue
            if (index == messageIDs.lastIndex) tailOffer = offer(id) else transcript.add(offer(id))
        }
        return Lists(transcript, tailOffer)
    }
}
