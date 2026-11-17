// Kotlin-self pins for ConversationReuse (1.0.6): a kept on-device Conversation serves the next turn only when that
// turn would seed exactly what it holds; every other case rebuilds as before.
package ai.teemoon.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReuseTest {
    private val search = BridgedTool("web_search", """{"type":"function","function":{"name":"web_search"}}""") { "" }
    private fun spec(history: List<Pair<LocalRole, String>> = emptyList(), user: String = "hi", system: String? = "be terse",
                     tools: List<BridgedTool> = listOf(search), maxOut: Int = 4800, thinking: Boolean = false) =
        LocalConversationSpec(system, history, tools, user, maxOut, thinking)

    /** Turn one answered "hello"; turn two's seed is that exchange. */
    private val first = spec()
    private val kept = ConversationReuse.afterTurn(first, "hello", toolsRan = 0, completed = true)
    private val second = spec(history = listOf(LocalRole.USER to "hi", LocalRole.MODEL to "hello"), user = "and then?")

    @Test fun theNextTurnOfTheSameChatReuses() {
        assertTrue(ConversationReuse.canReuse(kept, second))
        // ...and keeps reusing as the chat goes on.
        val third = spec(history = second.history + (LocalRole.USER to "and then?") + (LocalRole.MODEL to "more"), user = "ok")
        assertTrue(ConversationReuse.canReuse(ConversationReuse.afterTurn(second, "more", 0, true), third))
    }

    @Test fun anyDifferenceFromTheSeedRebuilds() {
        assertFalse("nothing kept", ConversationReuse.canReuse(null, second))
        assertFalse("an edited earlier message", ConversationReuse.canReuse(kept, second.copy(history = listOf(LocalRole.USER to "hey", LocalRole.MODEL to "hello"))))
        assertFalse("a regenerated or differently replayed answer", ConversationReuse.canReuse(kept, second.copy(history = listOf(LocalRole.USER to "hi", LocalRole.MODEL to "hello!"))))
        assertFalse("a trimmed history (the window filling)", ConversationReuse.canReuse(kept, second.copy(history = listOf(LocalRole.MODEL to "hello"))))
        assertFalse("another thread", ConversationReuse.canReuse(kept, spec(user = "new chat")))
        assertFalse("the system prompt changed — the minute in {{datetime}}", ConversationReuse.canReuse(kept, second.copy(systemMessage = "be brief")))
        assertFalse("the tools changed", ConversationReuse.canReuse(kept, second.copy(tools = emptyList())))
        assertFalse("the output cap changed", ConversationReuse.canReuse(kept, second.copy(maxOutputTokens = 1024)))
        assertFalse("thinking changed", ConversationReuse.canReuse(kept, second.copy(enableThinking = true)))
    }

    /**
     * iOS 634908e / 72f5874: the user turn is kept AS SENT, time note included, and the next seed rebuilds the same note
     * from the stored timestamp — so a kept chat reuses with plain equality. A turn asked again at another time (Think
     * Harder re-stamps to now) carries a different note and rebuilds.
     */
    @Test fun theUserTurnIsKeptAsSentAndTheNextSeedSpellsItTheSame() {
        val asked = spec(user = "hi\n\n[Sent at 9:02 AM]")
        val kept = ConversationReuse.afterTurn(asked, "hello", 0, true)
        val next = second.copy(history = listOf(LocalRole.USER to "hi\n\n[Sent at 9:02 AM]", LocalRole.MODEL to "hello"))
        assertTrue(ConversationReuse.canReuse(kept, next))
        val restamped = next.copy(history = listOf(LocalRole.USER to "hi\n\n[Sent at 9:40 AM]", LocalRole.MODEL to "hello"))
        assertFalse("a question asked again at another time rebuilds", ConversationReuse.canReuse(kept, restamped))
        assertFalse("nothing is stripped: a note the kept turn did not carry rebuilds", ConversationReuse.canReuse(ConversationReuse.afterTurn(spec(user = "hi"), "hello", 0, true), next))
    }

    /** The KV would hold something the next seed does not carry, or its state is unknown. */
    @Test fun aTurnThatRanAToolThoughtFailedOrSaidNothingIsNotKept() {
        assertNull("a tool ran: its call and result are in the KV, never replayed", ConversationReuse.afterTurn(first, "hello", toolsRan = 1, completed = true))
        assertNull("thinking on", ConversationReuse.afterTurn(spec(thinking = true), "hello", 0, true))
        assertNull("cancelled or failed", ConversationReuse.afterTurn(first, "hel", 0, completed = false))
        assertNull("no answer", ConversationReuse.afterTurn(first, "", 0, true))
    }

    /** iOS 7cd8c10: Think Harder turns thinking on; its KV holds the thought, which no seed carries. */
    @Test fun aThinkingTurnIsNeverKept() {
        assertNull(ConversationReuse.afterTurn(spec(thinking = true), "a", 0, true))
    }

    @Test fun aConversationIsNotReusedAcrossThinkingModes() {
        val kept = ConversationReuse.afterTurn(spec(), "a", 0, true)!!
        val next = spec(history = kept.transcript)
        assertFalse(ConversationReuse.canReuse(kept, next.copy(enableThinking = true)))
        assertTrue(ConversationReuse.canReuse(kept, next))
    }
}
