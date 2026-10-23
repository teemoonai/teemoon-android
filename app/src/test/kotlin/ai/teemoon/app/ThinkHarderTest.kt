// ports: teemoonTests/ThinkHarderTests.swift @ 9801827, ChatView.canThinkHarder @ 7cd8c10 (itReasksTheQuestionTheReplyAnswered,
//        itAsksForHighEffortAndRoomToThink — in :inference's ReasoningEffortTest — and
//        thinkHarderRestampsTheQuestionAndARetryDoesNot), over the view model rather than SwiftData.
//        + android-only: the newest-finished-reply gate (iOS ConversationView.thinkHarderAction, UI-tested there), and
//        every user turn's note built from its stored time, byte-identical turn to turn (iOS PromptPrefixStabilityTests).
//
// "Think Harder" in the newest reply's long-press menu (the maintainer, 2026-10-19: "A only", newest only, re-stamp): the same
// question again, same provider and model, at high effort with room to think, in place of that reply.
package ai.teemoon.app

import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkHarderTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    /** Every request: its messages and the effort the transport was built with. */
    private class Wire : GenerationTransport {
        val requests = ArrayList<List<Map<String, Any>>>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            requests += messages; onPartial("answer ${requests.size}"); return TurnResult(content = "answer ${requests.size}")
        }
    }

    private var clock = 1_770_000_000_000L
    private val efforts = ArrayList<ReasoningEffort?>()
    private fun vm(wire: Wire, place: Provider = Provider.fireworks, control: ((Provider) -> ai.teemoon.inference.ReasoningControl)? = null) = ChatViewModel(provider = { place },
        reasoningControl = control ?: { ai.teemoon.inference.ReasoningControl.resolve(it, null, null) }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }, now = { clock },
        makeTransport = { _, _, _, _, effort, _ -> efforts += effort; wire })
    private fun ChatViewModel.texts() = state.value.messages.map { it.role to it.text }

    @Test fun itReasksTheQuestionTheReplyAnswered() = runTest(dispatcher) {
        val vm = vm(Wire())
        vm.send("q1"); advanceUntilIdle(); clock += 60_000
        vm.send("q2"); advanceUntilIdle()
        val m = vm.state.value.messages
        assertEquals("q1", vm.question(answeredBy = m[1].id)?.text)
        assertEquals("q2", vm.question(answeredBy = m[3].id)?.text)
        assertNull(vm.question(answeredBy = m[0].id))
    }

    /** ConversationView.thinkHarderAction: the newest finished reply only — on an earlier one it would delete every later turn. */
    @Test fun onlyTheNewestFinishedReplyOffersIt() = runTest(dispatcher) {
        val vm = vm(Wire())
        vm.send("q1"); advanceUntilIdle(); clock += 60_000
        vm.send("q2"); advanceUntilIdle()
        val m = vm.state.value.messages
        assertFalse("an earlier reply", vm.canThinkHarder(m[1].id))
        assertFalse("a question", vm.canThinkHarder(m[2].id))
        assertTrue("the newest reply", vm.canThinkHarder(m[3].id))
        vm.thinkHarder(m[1].id); advanceUntilIdle()
        assertEquals("nothing happened on an earlier reply", 4, vm.state.value.messages.size)
    }

    /**
     * iOS ChatView.canThinkHarder (7cd8c10): offered only where the provider has a higher level to ask for — not on
     * LM Studio, llama.cpp, a custom endpoint or Brave Answers, where it would only ask again.
     */
    @Test fun itIsNotOfferedWhereNothingCanChange() = runTest(dispatcher) {
        val custom = Provider(id = "c", name = "my server", endpoint = "http://192.168.1.20:8080/v1/chat/completions", model = "m")
        val vm = vm(Wire(), place = custom)
        vm.send("q1"); advanceUntilIdle()
        val reply = vm.state.value.messages.last()
        assertFalse("a custom endpoint", vm.canThinkHarder(reply.id))
        vm.thinkHarder(reply.id); advanceUntilIdle()
        assertEquals("nothing happened", 2, vm.state.value.messages.size)
        val ollama = vm(Wire(), place = custom, control = { ai.teemoon.inference.ReasoningControl.Effort(null) })
        ollama.send("q1"); advanceUntilIdle()
        assertTrue("an Ollama server takes reasoning_effort", ollama.canThinkHarder(ollama.state.value.messages.last().id))
    }

    @Test fun thinkHarderReasksAtHighEffortInPlaceOfTheReplyAndRestampsTheQuestion() = runTest(dispatcher) {
        val wire = Wire(); val vm = vm(wire)
        vm.send("why is the sky blue"); advanceUntilIdle()
        val askedAt = vm.state.value.messages.first().createdAtMs
        clock += 3_600_000
        vm.thinkHarder(vm.state.value.messages.last().id); advanceUntilIdle()
        assertEquals(listOf(ReasoningEffort.CHAT_DEFAULT, ReasoningEffort.THINK_HARDER), efforts)
        assertEquals("in place of the reply", listOf("user" to "why is the sky blue", "assistant" to "answer 2"), vm.texts())
        assertEquals("Think Harder asks again now", clock, vm.state.value.messages.first().createdAtMs)
        assertTrue(askedAt != clock)
        val sentAgain = wire.requests.last().last()["content"] as String
        assertTrue(sentAgain, sentAgain.endsWith(ChatGeneration.timeNote(clock)))
    }

    @Test fun aRetryKeepsTheTimeItWasFirstSent() = runTest(dispatcher) {
        val wire = Wire(); val vm = vm(wire)
        vm.send("why is the sky blue"); advanceUntilIdle()
        val askedAt = vm.state.value.messages.first().createdAtMs
        clock += 3_600_000
        vm.retryLastTurn(); advanceUntilIdle()
        assertEquals(listOf(ReasoningEffort.CHAT_DEFAULT, ReasoningEffort.CHAT_DEFAULT), efforts)
        assertEquals("a retry keeps the time it was first sent", askedAt, vm.state.value.messages.first().createdAtMs)
        assertEquals(wire.requests[0].last()["content"], wire.requests[1].last()["content"])
    }

    /** Every user turn carries the time IT was sent: an earlier turn is byte-identical on every later request. */
    @Test fun earlierTurnsAreByteIdenticalTurnToTurn() = runTest(dispatcher) {
        val wire = Wire(); val vm = vm(wire)
        vm.send("first"); advanceUntilIdle(); clock += 17 * 60_000
        vm.send("second"); advanceUntilIdle(); clock += 9 * 60_000
        vm.send("third"); advanceUntilIdle()
        fun users(i: Int) = wire.requests[i].filter { it["role"] == "user" }.map { it["content"] as String }
        assertEquals(users(0), users(1).take(1))
        assertEquals(users(1), users(2).take(2))
        assertTrue(users(2).all { it.contains("\n\n[Sent at ") })
        assertTrue("each note is that turn's own time", users(2).distinct().size == 3)
        assertEquals("the stored messages are as typed", listOf("first", "second", "third"), vm.state.value.messages.filter { it.role == "user" }.map { it.text })
    }

    /** The card's "cache — first message" needs to know the chat's first message from a later one (iOS ef5c272). */
    @Test fun onlyAChatsFirstMessageIsMarkedFirst() = runTest(dispatcher) {
        val vm = vm(Wire())
        vm.send("first"); advanceUntilIdle()
        assertTrue(vm.generation.turnIsFirstMessage)
        clock += 60_000; vm.send("second"); advanceUntilIdle()
        assertFalse(vm.generation.turnIsFirstMessage)
    }
}
