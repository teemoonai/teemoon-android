// G7 (reply-machine.json; pm sweep 2026-11-07): an on-device decode the user stopped must not run a tool. LiteRT runs
// the tool loop itself and calls the shim from its own thread; a decode that has not yet stopped when its turn ends can
// still call. Before this, the shim looked up "this turn's tool" at call time, so that call ran the stopped turn's search
// — or, once the next turn had started, the NEXT turn's, with its chip and its sources.
package ai.teemoon.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRTLiveToolsTest {
    private val ran = ArrayList<String>()
    private fun tool(turn: String) = BridgedTool("web_search", "{}") { args -> ran += "$turn:$args"; "results for $turn" }
    private fun LiteRTLiveTools.call(conversation: Int, args: String) = runBlocking { run(conversation, "web_search", args) }

    @Test fun aStoppedTurnsToolCallRunsNothing() {
        val live = LiteRTLiveTools()
        val conversation = live.newConversation()
        live.begin(listOf(tool("A")))
        live.end()   // stopped; the decode has not stopped yet
        assertEquals(LiteRTLiveTools.STOPPED, live.call(conversation, "late"))
        assertEquals(emptyList<String>(), ran)
    }

    @Test fun aStoppedTurnsLateCallNeverRunsTheNextTurnsTool() {
        val live = LiteRTLiveTools()
        val first = live.newConversation()
        live.begin(listOf(tool("A")))
        live.end()                          // A stopped: its conversation is not kept, B builds another
        val second = live.newConversation()
        live.begin(listOf(tool("B")))
        assertEquals(LiteRTLiveTools.STOPPED, live.call(first, "A's late call"))
        assertEquals("results for B", live.call(second, "B's own"))
        assertEquals(listOf("B:B's own"), ran)
    }

    @Test fun aKeptConversationsShimServesTheNextTurn() {
        val live = LiteRTLiveTools()
        val conversation = live.newConversation()
        live.begin(listOf(tool("A")))
        assertEquals("results for A", live.call(conversation, "a"))
        live.end()                          // A completed and was kept
        live.begin(listOf(tool("B")))       // B reuses it: same shims, this turn's tool
        assertEquals("results for B", live.call(conversation, "b"))
        assertEquals(listOf("A:a", "B:b"), ran)
    }

    @Test fun anUnknownToolIsNotFound() {
        val live = LiteRTLiveTools()
        val conversation = live.newConversation()
        live.begin(listOf(tool("A")))
        assertEquals("[Tool 'fetch' not found]", runBlocking { live.run(conversation, "fetch", "{}") })
    }
}
