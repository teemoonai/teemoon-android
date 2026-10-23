// The card's cache figure on a PLAIN send, not only on a retry. iOS build 51 drops it there: turnOrigin is set in
// retry() only, so a normal send has no phases and the card has no "cache N%" (pm, 2026-10-21). Android's phases come
// from GenerationEngine on every turn; this pins that, through the view model as a send reaches it.
// + the re-ask rule of iOS cf57d0c (CardPhasesOnSendTests.aReaskOfTheFirstQuestionIsNotAFirstMessage).
package ai.teemoon.app

import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnReport
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CacheLineOnSendTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    /** Fireworks' usage as it arrives: no cached figure on a miss, the figure on a hit. */
    private class Fireworks(val cached: List<Int?>) : GenerationTransport {
        var n = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            val c = cached[n++]
            onPartial("answer $n")
            return TurnResult(content = "answer $n", report = TurnReport(url = Provider.fireworks.endpoint, responseAt = Instant.now(),
                promptTokens = 1_000, cachedPromptTokens = c))
        }
    }

    @Test fun aPlainSendShowsFirstMessageThenTheFigure() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val wire = Fireworks(listOf(null, 900))
        val vm = ChatViewModel(provider = { Provider.fireworks }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> wire })
        vm.send("first"); advanceUntilIdle()
        assertEquals("cache — first message", vm.generation.lastRequestDebugInfo?.cacheLine)
        assertTrue(vm.generation.lastRequestDebugInfo?.cardTimingLine.orEmpty().endsWith("cache — first message"))
        clock += 60_000
        vm.send("second"); advanceUntilIdle()
        assertEquals("cache 90%", vm.generation.lastRequestDebugInfo?.cacheLine)
        assertTrue(vm.generation.lastRequestDebugInfo?.cardTimingLine.orEmpty().endsWith("cache 90%"))
    }

    /**
     * Port of CardPhasesOnSendTests.aReaskOfTheFirstQuestionIsNotAFirstMessage (iOS cf57d0c; the maintainer, 2026-10-21,
     * cache-first-message-retry): only a chat's first plain send says "first message" — the provider just saw that
     * prompt, so a re-ask with 0 cached is a miss. Think Harder, a retry and a later send all read "cache 0%".
     */
    @Test fun aReaskOfTheFirstQuestionIsNotAFirstMessage() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val wire = Fireworks(listOf(0, 0, 0, 0))
        val vm = ChatViewModel(provider = { Provider.fireworks }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> wire })
        vm.send("hello"); advanceUntilIdle()
        assertEquals("cache — first message", vm.generation.lastRequestDebugInfo?.cacheLine)
        clock += 60_000
        vm.thinkHarder(vm.state.value.messages.last().id); advanceUntilIdle()
        assertEquals("Think Harder", "cache 0%", vm.generation.lastRequestDebugInfo?.cacheLine)
        clock += 60_000
        vm.retryLastTurn(); advanceUntilIdle()
        assertEquals("a retry", "cache 0%", vm.generation.lastRequestDebugInfo?.cacheLine)
        clock += 60_000
        vm.send("and another"); advanceUntilIdle()
        assertEquals("a later send", "cache 0%", vm.generation.lastRequestDebugInfo?.cacheLine)
        assertEquals(4, wire.n)
    }
}
