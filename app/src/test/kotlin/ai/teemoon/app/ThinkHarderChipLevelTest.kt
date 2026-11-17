// The maintainer 2026-11-14 (card chip-level-during-think-harder): "for the duration of a think harder, the composer should show
// the actual reasoning level which then goes back to low afterwards". Developer mode's chip shows the level the Think
// Harder request sent while it generates — high, or the pick when that is higher — and the pick again when the turn
// ends, stops, fails, or the model refuses the level.
package ai.teemoon.app

import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.ReasoningControl
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.ReasoningLevel
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkHarderChipLevelTest {
    private val dispatcher = StandardTestDispatcher()
    private var pick: String? = "low"
    @Before fun up() { Dispatchers.setMain(dispatcher); ReasoningLevel.chooser = { pick } }
    @After fun down() { Dispatchers.resetMain(); ReasoningLevel.chooser = { null }; ReasoningEffort.forgetRefusals(); ReasoningControl.forget() }

    /** Answers at once, or holds the reply open until [hold] completes (so the test can look mid-turn), or fails. */
    private class Wire : GenerationTransport {
        var hold: CompletableDeferred<Unit>? = null
        var fail = false
        /** After [hold]: the model refuses the level and the transport sends again without it, held by [resent]. */
        var resent: CompletableDeferred<Unit>? = null
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult =
            runTurn(messages, includeTools, tools, {}, onPartial)
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onResponse: () -> Unit, onPartial: (String) -> Unit): TurnResult {
            onResponse(); onPartial("thinking it over")
            hold?.await()
            resent?.let { ReasoningEffort.noteRefused(Provider.fireworks); onResponse(); onPartial("thinking again"); it.await() }   // OpenAiTransport's EffortRefused path
            if (fail) throw java.io.IOException("the connection dropped")
            return TurnResult(content = "an answer")
        }
    }

    private var clock = 1_770_000_000_000L
    private fun vm(wire: Wire, place: Provider = Provider.fireworks) = ChatViewModel(provider = { place },
        reasoningControl = { ReasoningControl.current(it) }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }, now = { clock },
        makeTransport = { _, _, _, _, _, _ -> wire })

    /** One plain turn answered, then Think Harder on its reply held open. */
    private suspend fun kotlinx.coroutines.test.TestScope.thinkingHarder(wire: Wire, place: Provider = Provider.fireworks): ChatViewModel {
        val vm = vm(wire, place)
        vm.send("is it private?"); advanceUntilIdle(); clock += 60_000
        assertTrue("think harder offered", vm.canThinkHarder(vm.state.value.messages.last().id))
        assertNull("a plain turn claims no other level", vm.state.value.thinkHarderLevel)
        wire.hold = CompletableDeferred()
        vm.thinkHarder(vm.state.value.messages.last().id); advanceUntilIdle()
        assertTrue("held mid-turn", vm.state.value.isStreaming)
        return vm
    }

    @Test fun whileItGeneratesTheChipShowsHighThenThePickAgain() = runTest(dispatcher) {
        val wire = Wire(); val vm = thinkingHarder(wire)
        assertEquals("high", vm.state.value.thinkHarderLevel)
        wire.hold!!.complete(Unit); advanceUntilIdle()
        assertNull("back to the pick when it ends", vm.state.value.thinkHarderLevel)
    }

    @Test fun aPickAboveHighIsWhatItSends() = runTest(dispatcher) {
        pick = "xhigh"
        val openRouter = Provider.openRouter.copy(model = "openai/gpt-5.2")
        ReasoningControl.note(ReasoningControl.OpenRouter, openRouter)
        val wire = Wire(); val vm = thinkingHarder(wire, openRouter)
        assertEquals("xhigh", vm.state.value.thinkHarderLevel)
        wire.hold!!.complete(Unit); advanceUntilIdle()
    }

    @Test fun aStopGoesBackToThePick() = runTest(dispatcher) {
        val wire = Wire(); val vm = thinkingHarder(wire)
        vm.stop(); advanceUntilIdle()
        assertNull(vm.state.value.thinkHarderLevel)
    }

    @Test fun aFailureGoesBackToThePick() = runTest(dispatcher) {
        val wire = Wire(); val vm = thinkingHarder(wire)
        wire.fail = true; wire.hold!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.thinkHarderLevel)
    }

    /** A refused level is sent again without it (OpenAiTransport): from that request on the chip claims none. */
    @Test fun aRefusalStopsClaimingALevel() = runTest(dispatcher) {
        val wire = Wire(); val vm = thinkingHarder(wire)
        assertEquals("high", vm.state.value.thinkHarderLevel)
        wire.resent = CompletableDeferred(); wire.hold!!.complete(Unit); advanceUntilIdle()
        assertTrue("still generating, resent", vm.state.value.isStreaming)
        assertNull(vm.state.value.thinkHarderLevel)
        wire.resent!!.complete(Unit); advanceUntilIdle()
    }

    /** Only where a level shows at all (developer mode, a level the model takes): with none, Think Harder adds none. */
    @Test fun theChipShowsTheSentLevelOnlyWhereALevelShows() {
        assertEquals("high", shownChipLevel(whereLevel = "low", thinkHarderLevel = "high"))
        assertEquals("low", shownChipLevel(whereLevel = "low", thinkHarderLevel = null))
        assertNull("developer mode off", shownChipLevel(whereLevel = null, thinkHarderLevel = "high"))
    }
}
