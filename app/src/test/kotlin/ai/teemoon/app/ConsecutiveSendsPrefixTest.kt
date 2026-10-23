// android-only: the conformance rule (iOS bbfeb0f, conformance/requests.json) through the app layer — iOS 58 read 0%
// cache on Fireworks sends while its transport alone held the prefix (release, 2026-10-22). Two normal sends in one
// chat: what ChatViewModel → GenerationEngine hand the transport must extend turn to turn — send 2's messages begin
// with send 1's, entry for entry, and the tools and the effort are the same. The serializer's half (extending messages
// give extending bytes, keys sorted) is ConformanceRequestsTest's, on the plain JVM; here OkHttp would pick its Android
// platform once Paparazzi has run in the same JVM. A guard only: the live three-send cache check is the gate.
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsecutiveSendsPrefixTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() = Dispatchers.setMain(dispatcher)
    @After fun down() = Dispatchers.resetMain()

    private class Turn(val messages: List<Map<String, Any>>, val includeTools: Boolean, val tools: List<Tool>, val effort: ReasoningEffort?)
    private class Wire(val effort: () -> ReasoningEffort?) : GenerationTransport {
        val turns = ArrayList<Turn>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            turns += Turn(messages, includeTools, tools, effort()); onPartial("Rayleigh scattering."); return TurnResult(content = "Rayleigh scattering.")
        }
    }

    @Test fun theSecondSendExtendsTheFirstsRequest() = runTest(dispatcher) {
        val place = Provider.fireworks.withModel("accounts/fireworks/models/glm-5p3-flash")
        var clock = 1_770_000_000_000L
        var lastEffort: ReasoningEffort? = null
        val wire = Wire { lastEffort }
        val vm = ChatViewModel(provider = { place }, credential = { "k" }, groundingKey = { "b" }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT },
            now = { clock }, makeTransport = { _, _, _, _, effort, _ -> lastEffort = effort; wire })
        vm.send("why is the sky blue?"); advanceUntilIdle(); clock += 60_000
        vm.send("and sunsets?"); advanceUntilIdle()
        assertEquals(2, wire.turns.size)
        val (one, two) = wire.turns
        assertTrue("search is offered on both", one.includeTools && two.includeTools && one.tools.isNotEmpty())
        assertEquals("send 2's messages begin with send 1's, entry for entry", one.messages, two.messages.take(one.messages.size))
        assertTrue("send 2 adds the reply and the new question", two.messages.size > one.messages.size)
        assertEquals("the same tools, in order", one.tools.map { Triple(it.name, it.description, it.parametersSchema) }, two.tools.map { Triple(it.name, it.description, it.parametersSchema) })
        assertEquals("the same level", ReasoningEffort.FAST, one.effort); assertEquals(one.effort, two.effort)
    }
}
