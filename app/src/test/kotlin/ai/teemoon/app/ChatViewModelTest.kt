// ports: teemoonTests/teemoonTests.swift @ v1.0.4 (the ChatGeneration suite: alreadyRunning, generateStreamsThroughAStubTransport,
//        emptyReplyBecomesAStatedFailure; the two waitUntilStopped tests are Swift-continuation plumbing with no Kotlin counterpart —
//        the coroutine Job is awaited directly)
//        teemoonTests/SendPrepTests.swift @ v1.0.4 (postPrepareRefusalHonoursTheSettledGate); AppStateTests' postPrepareRefusal
//        halves (9f4aa84, 4ea1dda) ride the send-level cases below
package ai.teemoon.app

import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.confidential.TrustSendPolicy
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.ToolCallState
import ai.teemoon.inference.ToolRoundPolicy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain() }

    private class Scripted(private val turns: List<TurnResult>) : GenerationTransport {
        var calls = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            val t = turns[minOf(calls, turns.lastIndex)]; calls++
            if (t.content.isNotEmpty()) onPartial(t.content)
            return t
        }
    }
    private val place = Provider.braveAnswers.copy(endpoint = "https://api.example.com/v1")

    private fun vm(transport: GenerationTransport) = ChatViewModel(provider = { place }, credential = { "sk-test" }, makeTransport = { _, _, _, _ -> transport })

    /**
     * The sources a reply keeps are what it CITES (iOS ChatViewModel: GroundingSource.citedOrAll) — its markdown
     * links, carrying the search snippet where one matched — or every grounding source when it cites none. The port
     * had the function and never called it: Android kept the whole search haul and no link of the reply's own.
     */
    @Test fun aReplyKeepsTheSourcesItCites() = runTest(dispatcher) {
        val vm = ChatViewModel(provider = { Provider.fireworks }, credential = { "sk-test" },
            makeTransport = { _, _, _, _ -> Scripted(listOf(TurnResult(content = "See [the chart](https://example.org/chart) for more."))) })
        vm.send("a link please"); advanceUntilIdle()
        val sources = vm.state.value.messages.last().sources
        assertEquals("the cited link is not the reply's source: $sources", listOf("https://example.org/chart"), sources.map { it.url })
    }

    /**
     * The transport streams from a network thread (OpenAiTransport's IO loop calls onPartial there) while the main
     * thread writes the same state. Every write is an atomic update: a partial from a worker thread lands, and the
     * turn's settle after it is not overwritten by it.
     */
    @Test fun aPartialFromAWorkerThreadLandsAndTheSettleAfterItHolds() = runTest(dispatcher) {
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                val worker = Thread { onPartial("from the wire") }
                worker.start(); worker.join()
                return TurnResult(content = "from the wire")
            }
        }
        val vm = vm(transport)
        vm.send("hello"); advanceUntilIdle()
        val last = vm.state.value.messages.last()
        assertEquals("from the wire", last.text)
        assertTrue("the settle was lost: the reply is still streaming", !last.streaming && !vm.state.value.isStreaming)
    }

    @Test fun alreadyRunning_sendsNothing() = runTest(dispatcher) {
        val transport = Scripted(listOf(TurnResult(content = "hi")))
        val vm = vm(transport)
        vm.generation.beginTurn(0)   // iOS: llm.running = true
        vm.send("hello"); advanceUntilIdle()
        assertEquals(0, transport.calls); assertTrue(vm.state.value.messages.isEmpty())
    }
    @Test fun generateStreamsThroughAStubTransport() = runTest(dispatcher) {
        val vm = vm(Scripted(listOf(TurnResult(content = "hi from the stub"))))
        vm.send("hello"); advanceUntilIdle()
        val s = vm.state.value
        assertEquals("hi from the stub", s.messages.last().text); assertEquals("hi from the stub", vm.generation.output)
        assertNull(s.error); assertNull(vm.generation.lastError); assertTrue(!s.isStreaming); assertTrue(!vm.generation.running)
        assertNotNull("the developer card is seeded and completed", s.lastRequestDebugInfo?.totalDuration)
    }
    /**
     * stop() cancels the turn's job, and the cancellation unwinds through the transport. It used to land in the
     * blanket catch as "An unexpected error occurred … StandaloneCoroutine was cancelled" — an error card for the
     * user's own tap (Pixel 10 Pro, 2026-09-16). A stop keeps what streamed and records no error anywhere.
     */
    @Test fun stoppingMidStreamIsNotAnError() = runTest(dispatcher) {
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                onPartial("Jupiter is the largest"); awaitCancellation()
            }
        }
        val vm = vm(transport)
        vm.send("tell me about jupiter"); advanceUntilIdle()
        assertTrue("the turn should be streaming", vm.state.value.isStreaming)
        vm.stop(); advanceUntilIdle()
        val s = vm.state.value
        assertNull("a stop must not raise an error card: ${s.error}", s.error); assertNull(vm.generation.lastError)
        assertTrue(!s.isStreaming); assertTrue(!vm.generation.running)
        assertEquals("what streamed stays", "Jupiter is the largest", s.messages.last().text)
    }

    @Test fun emptyReplyBecomesAStatedFailure() = runTest(dispatcher) {
        val vm = vm(Scripted(listOf(TurnResult(content = ""))))
        vm.send("hello"); advanceUntilIdle()
        assertTrue(vm.state.value.error?.contains("empty response") == true)
        assertEquals(vm.state.value.error, vm.generation.lastError?.userMessage)
        assertEquals("no reply row without output, as iOS", "user", vm.state.value.messages.last().role)
    }
    /**
     * A failure before the first token takes the placeholder reply row with it: iOS never had one (the message exists
     * only when there is output), and left behind it was padding between the prompt and the card, and "a reply" to
     * the transcript's reserve, so the card was never pinned into view (Pixel 10 Pro, 2026-09-17).
     */
    @Test fun aFailedTurnLeavesNoEmptyReplyRow() = runTest(dispatcher) {
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult =
                throw LlmError(LlmError.Source.BraveGrounding, "Brave web search rejected the API key (HTTP 422).", httpStatus = 422)
        }
        val vm = vm(transport)
        vm.send("hello"); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(422, s.lastError?.httpStatus)
        assertEquals(1, s.messages.size); assertEquals("user", s.messages.last().role)
        assertTrue(!s.isStreaming)
    }
    /**
     * THE TOOL-ROUND CEILING IS THE MODEL'S, NOT THE APP'S. iOS sets it in two places:
     * GenerationEngine.defaultMaxToolRounds = 4 for everything dialled over the wire, and
     * LocalLanguageModel.swift:136 passes 2 — because on the phone each extra round is a
     * full re-prefill plus a serial decode. Android applied the on-device number to every
     * provider, so a hosted model researched two rounds shallower than the same model on
     * iOS and hit "I've used up my searches" early (2026-09 port review, B3).
     *
     * Counting turns through the transport is the observable: a tool call per turn runs
     * until the budget is spent, so the number of turns is the ceiling plus the final answer.
     */
    private class ToolLooper(private val answerAfter: Int) : GenerationTransport {
        var turns = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            turns++
            if (turns > answerAfter) { onPartial("done"); return TurnResult(content = "done") }
            return TurnResult(content = "", toolCalls = mapOf(0 to ToolCallState(id = "c$turns", name = "web_search", arguments = """{"query":"oil"}""")))
        }
    }

    @Test fun hostedProvidersGetTheFullToolRoundBudget() = runTest(dispatcher) {
        val transport = ToolLooper(answerAfter = 99)
        val vm = ChatViewModel(provider = { place }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _ -> transport })
        vm.send("what is the oil price"); advanceUntilIdle()
        assertEquals("a hosted place gets iOS's 4 rounds, not the on-device 2",
            ToolRoundPolicy.DEFAULT_MAX_ROUNDS, transport.turns - 1)
    }

    @Test fun onDeviceKeepsTheNarrowerBudget() = runTest(dispatcher) {
        val transport = ToolLooper(answerAfter = 99)
        val local = Provider.braveAnswers.copy(endpoint = "https://api.example.com/v1", localModelID = "gemma-3n-e2b")
        val vm = ChatViewModel(provider = { local }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _ -> transport })
        vm.send("what is the oil price"); advanceUntilIdle()
        assertEquals("each extra round on the phone is a re-prefill plus a serial decode", 2, transport.turns - 1)
    }

    /**
     * "this model can't search the web — turn off & retry": keyless, the tool still attaches (it is the offer's
     * trigger), so the retry must go out WITHOUT it or the model refuses it again with the same 400.
     */
    @Test fun turnOffSearchAndRetrySendsNoSearchTool() = runTest(dispatcher) {
        val toolsPerCall = ArrayList<Int>()
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                toolsPerCall += tools.size; onPartial("ok"); return TurnResult(content = "ok")
            }
        }
        val vm = ChatViewModel(provider = { Provider.fireworks }, credential = { "sk-test" }, groundingKey = { null }, makeTransport = { _, _, _, _ -> transport })
        vm.send("what is the weather"); advanceUntilIdle()
        vm.disableWebSearchAndRetry(); advanceUntilIdle()
        assertEquals("the first turn should carry the keyless tool, the retry none", listOf(1, 0), toolsPerCall)
    }

    @Test fun theSystemPromptGoesOutFirst() = runTest(dispatcher) {
        var seen: List<Map<String, Any>>? = null
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult { seen = messages; onPartial("ok"); return TurnResult(content = "ok") }
        }
        // Brave Answers omits the system prompt by preset; Fireworks does not.
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { "be brief {{datetime}}" }, makeTransport = { _, _, _, _ -> transport })
        vm.send("hello"); advanceUntilIdle()
        assertEquals("system", seen!!.first()["role"]); assertTrue(!(seen.first()["content"] as String).contains("{{"))
        assertEquals("hello", seen.last()["content"])
    }

    /** The gate re-read after `prepareTurn`: a hard block never proceeds; a soft degrade proceeds only if the user already chose "send anyway"; a verdict still pending is not a pass. */
    @Test fun postPrepareRefusalHonoursTheSettledGate() {
        assertNull(postPrepareRefusal(TrustSendPolicy.ALLOW, acceptedDegrade = false, verdictsPending = false))
        assertNotNull(postPrepareRefusal(TrustSendPolicy.BLOCK, acceptedDegrade = true, verdictsPending = false))
        assertNotNull(postPrepareRefusal(TrustSendPolicy.CONFIRM, acceptedDegrade = false, verdictsPending = false))
        assertNull(postPrepareRefusal(TrustSendPolicy.CONFIRM, acceptedDegrade = true, verdictsPending = false))
        assertNotNull(postPrepareRefusal(TrustSendPolicy.ALLOW, acceptedDegrade = false, verdictsPending = true))
        assertNotNull(postPrepareRefusal(TrustSendPolicy.CONFIRM, acceptedDegrade = true, verdictsPending = true))
    }

    /**
     * The whole path (iOS 9f4aa84): the composer saw ALLOW, prepareTurn waited for a TLS verdict still in flight, it
     * failed, and NOTHING reached the wire — the card reads the refusal, and the session books no unsealed request.
     */
    @Test fun aVerdictThatFailsDuringPrepareSendsNothing() = runTest(dispatcher) {
        val preview = AttestationRecord.preview
        val near = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val session = ConfidentialSession(this, { near }, credentialFor = { "" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" },
                verifyTls = { delay(300); TlsAttestation.Failed("certificate does not match") }))
        session.attestation = preview
        session.lastRequestUsedE2EE = true
        session.verifyTls(near)
        assertEquals("precondition: the composer's read", TrustSendPolicy.ALLOW, session.sendPolicy)
        val transport = Scripted(listOf(TurnResult(content = "must not be asked")))
        val vm = ChatViewModel(provider = { near }, credential = { "sk-test" }, session = session, makeTransport = { _, _, _, _ -> transport })
        vm.send("hello"); advanceUntilIdle()
        assertEquals("nothing reached the wire", 0, transport.calls)
        assertEquals(postPrepareRefusal(TrustSendPolicy.BLOCK, false, false), vm.state.value.error)
        assertNull("a refusal is abandoned, not booked as an unsealed request", session.lastRequestUsedE2EE)
    }

    /** A plain-TLS provider never waits on verdicts it does not have (iOS 4ea1dda, NVIDIA): the send goes out. */
    @Test fun aPlainTlsSendIsNotHeldByAnotherSessionsVerifier() = runTest(dispatcher) {
        val preview = AttestationRecord.preview
        var active: Provider = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val session = ConfidentialSession(this, { active }, credentialFor = { "" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" },
                verifyTls = { awaitCancellation() }))
        session.attestation = preview
        session.verifyTls(active)
        active = Provider.nvidia.copy(endpoint = "https://api.example.com/v1")
        val transport = Scripted(listOf(TurnResult(content = "hi from nvidia")))
        val vm = ChatViewModel(provider = { active }, credential = { "sk-test" }, session = session, makeTransport = { _, _, _, _ -> transport })
        vm.send("hello"); advanceUntilIdle()
        assertEquals(1, transport.calls)
        assertNull(vm.state.value.error)
        session.clearDerivedState(resetCounts = true)
    }

    /**
     * WITH NO PLACE, NOTHING GOES OUT. The state used to carry a compiled default endpoint (the emulator's fake server,
     * http://10.0.2.2:8765) that send() fell back to; the composer's SendPrep gate stopped a first send, but the error
     * card's retries — search off & retry, accept a search key, retry — call send() directly and went to it in clear.
     * Flagged by the local audit (a LOW since 3910, 2026-10-13).
     */
    @Test fun withNoPlaceTheRetriesSendNothing() = runTest(dispatcher) {
        var current: Provider? = Provider.fireworks
        val endpoints = mutableListOf<String>()
        val transport = Scripted(listOf(TurnResult(content = "an answer")))
        val vm = ChatViewModel(provider = { current }, credential = { "sk-test" }, groundingKey = { "brave-key" },
            makeTransport = { endpoint, _, _, _ -> endpoints += endpoint; transport })
        vm.send("a question"); advanceUntilIdle()
        assertEquals(1, transport.calls)
        current = null
        vm.retryLastTurn(); advanceUntilIdle()
        vm.disableWebSearchAndRetry(); advanceUntilIdle()
        vm.acceptSearchKey("brave-key"); advanceUntilIdle()
        vm.send("another question"); advanceUntilIdle()
        assertEquals("a turn went out with no place: $endpoints", 1, transport.calls)
        assertTrue("an endpoint nobody chose: $endpoints", endpoints.none { "10.0.2.2" in it })
    }

    /**
     * A RETRY WITH NO PLACE KEEPS THE QUESTION. retry() dropped the question (state and Room) before send() found no
     * place and returned, so the question simply vanished (the local audit's re-run, 2026-10-13). It now stays, and
     * the screen is asked to say why — the composer's "no provider configured" alert.
     */
    @Test fun aRetryWithNoPlaceKeepsTheQuestionAndAsksForOne() = runTest(dispatcher) {
        var current: Provider? = Provider.fireworks
        val transport = Scripted(listOf(TurnResult(content = "an answer")))
        val vm = ChatViewModel(provider = { current }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _ -> transport })
        vm.send("a question"); advanceUntilIdle()
        val before = vm.state.value.messages.map { it.role to it.text }
        current = null
        for (retry in listOf<() -> Unit>({ vm.retryLastTurn() }, { vm.disableWebSearchAndRetry() }, { vm.acceptSearchKey("brave-key") })) {
            retry(); advanceUntilIdle()
            assertEquals("the question went missing", before, vm.state.value.messages.map { it.role to it.text })
            assertTrue("nothing told the user why the retry did nothing", vm.state.value.needsPlace)
            vm.placeRequestShown()
            assertTrue(!vm.state.value.needsPlace)
        }
        assertEquals(1, transport.calls)
    }
}
