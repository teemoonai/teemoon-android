// ports: teemoonTests/teemoonTests.swift @ v1.0.4 (the ChatGeneration suite: alreadyRunning, generateStreamsThroughAStubTransport,
//        emptyReplyBecomesAStatedFailure; the two waitUntilStopped tests are Swift-continuation plumbing with no Kotlin counterpart —
//        the coroutine Job is awaited directly)
//        teemoonTests/SendPrepTests.swift @ v1.0.4 (postPrepareRefusalHonoursTheSettledGate); AppStateTests' postPrepareRefusal
//        halves (9f4aa84, 4ea1dda) ride the send-level cases below
//        teemoonTests/ConfirmPendingSendAnywayTests.swift @ b05f4d25 (aConfirmationDoesNotCarryOverToADifferentReason) and
//        @ 535dfae6 (a hard verdict while asked sends nothing)
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
import org.junit.Assert.assertFalse
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    /** These sessions read the send gate while a near.ai tier is still unknown, run to idle: no tier deadline inside them. */
    private val TIER_WAITS: Long? = null
    // NO saved tier list (pm 2026-10-31): a list recorded "seen" here hid a fresh install's plain-TLS near.ai send
    // (UnseenTierSendGateTest). The gate must hold from the first launch, so every case starts where a new user does.

    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher); ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = false }
    @After fun down() { Dispatchers.resetMain(); ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = null }

    private class Scripted(private val turns: List<TurnResult>) : GenerationTransport {
        var calls = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            val t = turns[minOf(calls, turns.lastIndex)]; calls++
            if (t.content.isNotEmpty()) onPartial(t.content)
            return t
        }
    }
    private val place = Provider.braveAnswers.copy(endpoint = "https://api.example.com/v1")

    private fun vm(transport: GenerationTransport) = ChatViewModel(provider = { place }, credential = { "sk-test" }, makeTransport = { _, _, _, _, _, _ -> transport })

    /**
     * The sources a reply keeps are what it CITES (iOS ChatViewModel: GroundingSource.citedOrAll) — its markdown
     * links, carrying the search snippet where one matched — or every grounding source when it cites none. The port
     * had the function and never called it: Android kept the whole search haul and no link of the reply's own.
     */
    @Test fun aReplyKeepsTheSourcesItCites() = runTest(dispatcher) {
        val vm = ChatViewModel(provider = { Provider.fireworks }, credential = { "sk-test" },
            makeTransport = { _, _, _, _, _, _ -> Scripted(listOf(TurnResult(content = "See [the chart](https://example.org/chart) for more."))) })
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

    /** A transport whose first turn holds its read past a stop (a blocked read, an on-device step), then answers. */
    private class OutlivesTheStop : GenerationTransport {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            if (calls++ == 0) { onPartial("partial"); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }; return TurnResult(content = "partial") }
            onPartial("the second answer"); return TurnResult(content = "the second answer")
        }
    }

    /**
     * A2 (pm sweep 2026-11-06): stop() settles the screen at once (isStreaming false) while the stopped turn's job
     * runs until its read returns. A send in that window passed the screen's check, the composer cleared, and the
     * turn refused to begin: the message was lost. It now waits for the stopped turn, then goes.
     */
    @Test fun aSendWhileTheStoppedTurnWindsDownIsNotLost() = runTest(dispatcher) {
        val transport = OutlivesTheStop()
        val vm = vm(transport)
        vm.send("first"); advanceUntilIdle()
        vm.stop(); advanceUntilIdle()
        assertTrue("the stop window: settled on screen, the turn still running", !vm.state.value.isStreaming && vm.generation.running)
        vm.send("second"); advanceUntilIdle()
        transport.release.complete(Unit); advanceUntilIdle()
        assertEquals(2, transport.calls)
        assertEquals(listOf("user" to "first", "assistant" to "partial", "user" to "second", "assistant" to "the second answer"),
            vm.state.value.messages.map { it.role to it.text })
    }

    /** A2: retry in the same window deleted the question, then its send refused to begin — the question was gone. */
    @Test fun aRetryWhileTheStoppedTurnWindsDownKeepsAndAsksTheQuestion() = runTest(dispatcher) {
        val transport = OutlivesTheStop()
        val vm = vm(transport)
        vm.send("first"); advanceUntilIdle()
        vm.stop(); advanceUntilIdle()
        vm.retryLastTurn(); advanceUntilIdle()
        transport.release.complete(Unit); advanceUntilIdle()
        assertEquals(2, transport.calls)
        assertEquals(listOf("user" to "first", "assistant" to "the second answer"), vm.state.value.messages.map { it.role to it.text })
    }

    @Test fun emptyReplyBecomesAStatedFailure() = runTest(dispatcher) {
        val vm = vm(Scripted(listOf(TurnResult(content = ""))))
        vm.send("hello"); advanceUntilIdle()
        assertTrue(vm.state.value.error?.contains("empty answer") == true)
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
        val vm = ChatViewModel(provider = { place }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("what is the oil price"); advanceUntilIdle()
        assertEquals("a hosted place gets iOS's 4 rounds, not the on-device 2",
            ToolRoundPolicy.DEFAULT_MAX_ROUNDS, transport.turns - 1)
    }

    @Test fun onDeviceKeepsTheNarrowerBudget() = runTest(dispatcher) {
        val transport = ToolLooper(answerAfter = 99)
        val local = Provider.braveAnswers.copy(endpoint = "https://api.example.com/v1", localModelID = "gemma-3n-e2b")
        val vm = ChatViewModel(provider = { local }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _, _, _ -> transport })
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
        val vm = ChatViewModel(provider = { Provider.fireworks }, credential = { "sk-test" }, groundingKey = { null }, makeTransport = { _, _, _, _, _, _ -> transport })
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
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { "be brief {{datetime}}" }, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("hello"); advanceUntilIdle()
        assertEquals("system", seen!!.first()["role"]); assertTrue(!(seen.first()["content"] as String).contains("{{"))
        // The question's own time rides on the wire (iOS 0802a0f); the stored message is what was typed.
        val sent = seen.last()["content"] as String
        assertTrue(sent, sent.startsWith("hello\n\n[Sent at ") && sent.endsWith("]"))
        assertEquals("hello", vm.state.value.messages.first { it.role == "user" }.text)
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
            services = ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" }, tierDeadlineMs = TIER_WAITS,
                verifyTls = { delay(300); TlsAttestation.Failed("certificate does not match") }))
        session.attestation = preview
        session.lastRequestUsedE2EE = true
        session.verifyTls(near)
        assertEquals("precondition: the composer's read", TrustSendPolicy.ALLOW, session.sendPolicy)
        val transport = Scripted(listOf(TurnResult(content = "must not be asked")))
        val vm = ChatViewModel(provider = { near }, credential = { "sk-test" }, session = session, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("hello"); advanceUntilIdle()
        assertEquals("nothing reached the wire", 0, transport.calls)
        assertEquals(postPrepareRefusal(TrustSendPolicy.BLOCK, false, false), vm.state.value.error)
        assertNull("a refusal is abandoned, not booked as an unsealed request", session.lastRequestUsedE2EE)
    }

    /**
     * The still-checking question is answered on the SAME reply (reply-machine.json: awaitingConfirm → confirm → requesting;
     * ios, 2026-11-06): "send anyway" sends the waiting turn — its row, first written "interrupted", ends complete and
     * carries the pending sent-before-confirmed mark. It used to be a retry: a new reply, and the mark lost.
     */
    @Test fun theStillCheckingQuestionIsAnsweredOnTheSameReply() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        vm.send("hello"); advanceUntilIdle()
        assertTrue("the question is asked", vm.state.value.hardwareConfirmRequested)
        assertEquals("nothing sent while it waits", 0, transport.calls)
        val waiting = dao.messages.single { it.role == "assistant" }
        assertEquals("interrupted", waiting.status)
        vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertEquals(1, transport.calls)
        val reply = dao.messages.single { it.role == "assistant" }
        assertEquals("the same reply", waiting.id, reply.id)
        assertEquals("complete", reply.status)
        assertTrue("marked sent before confirmed: ${reply.sentBeforeConfirmed}", reply.sentBeforeConfirmed?.startsWith("pending|") == true)
        stillCheckingSession?.clearDerivedState(resetCounts = true)   // the TLS check that never answers
    }

    /**
     * "don't send" on a fresh send (reply-machine.json 0.6.1 "returned"; the maintainer's rule via pm, 2026-11-06): the
     * question leaves the chat and the store, its text goes back to the composer, nothing is sent, no marker shows.
     */
    @Test fun dontSendReturnsTheQuestionToTheComposer() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        vm.send("hello"); advanceUntilIdle()
        vm.answerStillChecking(sendIt = false); advanceUntilIdle()
        assertEquals(0, transport.calls)
        assertTrue("nothing left in the store: ${dao.messages}", dao.messages.none { it.threadId == "t" })
        assertTrue("nothing left in the chat", vm.state.value.messages.isEmpty())
        assertEquals("hello", vm.state.value.returnedQuestion?.text)
        assertNull("a decline shows no card: the user chose not to send (iOS returnGatedQuestion)", vm.state.value.error)
        vm.returnedQuestionTaken()
        assertNull(vm.state.value.returnedQuestion)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /**
     * "Never destroy a reply the user had" (reply-machine.json 0.6.2; the maintainer's rule via pm, 2026-11-06): a retry
     * changes nothing on disk until its request goes out. Declined at the still-checking question, the reply the user
     * already had stands as it was — in the chat and in the store, no marker — and nothing is sent.
     */
    @Test fun aDeclinedRetryLeavesTheReplyTheUserHad() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        val had = listOf(ai.teemoon.chat.MessageEntity("m-1", "t", "user", "hello", 1),
            ai.teemoon.chat.MessageEntity("a-2", "t", "assistant", "Old answer", 2, status = "complete"))
        dao.messages += had
        vm.restore(had)
        vm.retryLastTurn(); advanceUntilIdle()
        assertTrue("the question is asked", vm.state.value.hardwareConfirmRequested)
        assertEquals("nothing written while it waits", had, dao.messages.sortedBy { it.timestamp })
        vm.answerStillChecking(sendIt = false); advanceUntilIdle()
        assertEquals(0, transport.calls)
        assertEquals("the store as it was", had, dao.messages.sortedBy { it.timestamp })
        assertEquals(listOf("m-1" to "hello", "a-2" to "Old answer"), vm.state.value.messages.map { it.id to it.text })
        assertNull("no marker", vm.state.value.messages.last().marker)
        assertFalse(vm.state.value.isStreaming)
        assertNull("a decline shows no card: the user chose not to send (iOS returnGatedQuestion)", vm.state.value.error)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /**
     * A hard verdict that lands while the still-checking question is up (reply-machine.json 0.6.4
     * lifecycle/hard-verdict-while-awaiting-confirm-sends-nothing): "send" re-reads the gate. Under BLOCK nothing goes
     * out, the question goes back to the composer (awaitingConfirm × gateRefused) and the existing hard refusal shows.
     * It used to send: no mark (the verdict was no longer pending), to a host that had just failed its TLS check.
     */
    @Test fun aSendAnswerUnderAHardVerdictSendsNothing() = runTest(dispatcher) {
        val tls = kotlinx.coroutines.CompletableDeferred<TlsAttestation>()
        val (vm, dao, transport) = stillChecking(tls)
        vm.send("hello"); advanceUntilIdle()
        assertTrue("the question is asked", vm.state.value.hardwareConfirmRequested)
        tls.complete(TlsAttestation.Failed("certificate does not match")); advanceUntilIdle()
        assertEquals("precondition: the verdict is hard", TrustSendPolicy.BLOCK, stillCheckingSession?.sendPolicy)
        vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertEquals("nothing reached the wire", 0, transport.calls)
        assertTrue("no reply row is left", dao.messages.none { it.role == "assistant" })
        assertEquals("the question returns to the composer", "hello", vm.state.value.returnedQuestion?.text)
        assertFalse(vm.state.value.hardwareConfirmRequested)
        assertFalse(vm.state.value.isStreaming)
        assertEquals("the existing hard refusal shows", postPrepareRefusal(TrustSendPolicy.BLOCK, false, false), vm.state.value.error)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /**
     * F4 (sendgate.json 0.5.14 confirmScope; iOS ConfirmPendingSendAnywayTests.aConfirmationDoesNotCarryOverToADifferentReason):
     * the still-checking answer is for (this record, pending). TLS came back inconclusive while it was up — a different,
     * soft reason with its own question — and the answer must not carry the send past it.
     */
    @Test fun aStillCheckingAnswerDoesNotCarryOverToADifferentReason() = runTest(dispatcher) {
        val tls = kotlinx.coroutines.CompletableDeferred<TlsAttestation>()
        // a known tier: since S30 an unknown one is itself pending, and the soft TLS reason would never be the only thing left
        val model = "zai-org/F4-DIFFERENT-REASON-PROBE"
        ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf(model to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN))
        val (vm, dao, transport) = stillChecking(tls, model = model)
        vm.send("hello"); advanceUntilIdle()
        assertTrue("the question is asked", vm.state.value.hardwareConfirmRequested)
        tls.complete(TlsAttestation.Inconclusive("probe unreachable")); advanceUntilIdle()
        val s = stillCheckingSession!!
        assertTrue("precondition: a different, soft reason", !s.verdictsPending && s.sendPolicy == TrustSendPolicy.CONFIRM)
        vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertEquals("the still-checking answer carried the send past an unconfirmed reason", 0, transport.calls)
        assertTrue("no reply row is left", dao.messages.none { it.role == "assistant" })
        assertEquals("the question returns to the composer", "hello", vm.state.value.returnedQuestion?.text)
        s.clearDerivedState(resetCounts = true)
    }

    /** The question's offered choices follow the live policy, not the gate its screen captured. */
    @Test fun theStillCheckingQuestionGivesWayToAHardBlock() {
        assertTrue(asksStillChecking(pendingAsk = true, policy = TrustSendPolicy.CONFIRM))
        assertTrue(asksStillChecking(pendingAsk = true, policy = TrustSendPolicy.ALLOW))
        assertFalse("a hard verdict landed: the hard choices", asksStillChecking(pendingAsk = true, policy = TrustSendPolicy.BLOCK))
        assertFalse(asksStillChecking(pendingAsk = false, policy = TrustSendPolicy.CONFIRM))
    }

    /**
     * reply-machine.json 0.6.5 lifecycle/reverify-on-a-retry-keeps-it-pending (iOS 5638261c; the maintainer 2026-11-07, "1.1
     * should match"): Re-verify on a held retry declines it — the reply the user had stands, nothing is sent — and keeps
     * the retry pending, so the re-shown alert's "send anyway" resends it; only then is the old reply replaced.
     */
    @Test fun aReverifiedRetryStaysPendingAndSendAnywayResendsIt() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        val had = listOf(ai.teemoon.chat.MessageEntity("m-1", "t", "user", "hello", 1),
            ai.teemoon.chat.MessageEntity("a-2", "t", "assistant", "Old answer", 2, status = "complete"))
        dao.messages += had
        vm.restore(had)
        vm.retryLastTurn(); advanceUntilIdle()
        assertTrue("the question is asked", vm.state.value.hardwareConfirmRequested)
        val kept = vm.reverifyHeldTurn(); advanceUntilIdle()
        assertEquals("the retry is kept, at its effort", ai.teemoon.inference.ReasoningEffort.CHAT_DEFAULT, kept)
        assertEquals(0, transport.calls)
        assertEquals("the reply the user had stands", had, dao.messages.sortedBy { it.timestamp })
        assertNull("a retry's question does not go to the composer", vm.state.value.returnedQuestion)
        // the re-shown alert, still checking: "send sealed unconfirmed" resends the kept retry, without asking again
        vm.confirmStillChecking()
        vm.retryLastTurn(acceptedDegrade = true, effort = kept!!); advanceUntilIdle()
        assertFalse("asked twice for one answer", vm.state.value.hardwareConfirmRequested)
        assertEquals("the kept retry went out", 1, transport.calls)
        assertEquals(listOf("hello", "a sealed answer"), vm.state.value.messages.map { it.text })
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /** The same Re-verify on a fresh send: its question goes back to the composer and nothing is kept. */
    @Test fun aReverifiedSendReturnsItsQuestion() = runTest(dispatcher) {
        val (vm, _, transport) = stillChecking()
        vm.send("hello"); advanceUntilIdle()
        assertNull(vm.reverifyHeldTurn()); advanceUntilIdle()
        assertEquals(0, transport.calls)
        assertEquals("hello", vm.state.value.returnedQuestion?.text)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /** After the re-verify: a hard verdict re-shows the block; a newer turn meanwhile means nothing runs (G6). */
    @Test fun afterAReverifyTheAlertComesBackOrNothingRuns() = runTest(dispatcher) {
        val tls = kotlinx.coroutines.CompletableDeferred<TlsAttestation>()
        val (vm, _, _) = stillChecking(tls)
        tls.complete(TlsAttestation.Failed("certificate does not match")); advanceUntilIdle()
        assertEquals(ChatViewModel.AfterReverify.BLOCKED, vm.afterReverify(wasHardBlock = false, captured = vm.reverifyCapture()))
        val captured = vm.reverifyCapture()
        vm.send("a newer question"); advanceUntilIdle()
        assertNull("a newer turn started", vm.afterReverify(wasHardBlock = true, captured = captured))
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /** C2 (iOS retryE2EEAndGenerate): after a hard block nothing is sent on its own, even once the state reads allow — the alert comes back. */
    @Test fun afterAHardBlockTheAlertComesBackEvenWhenItNowAllows() = runTest(dispatcher) {
        val tls = kotlinx.coroutines.CompletableDeferred<TlsAttestation>()
        val (vm, _, _) = stillChecking(tls)
        tls.complete(TlsAttestation.Verified); advanceUntilIdle()
        assertEquals("precondition", TrustSendPolicy.ALLOW, stillCheckingSession?.sendPolicy)
        assertEquals(ChatViewModel.AfterReverify.ASK, vm.afterReverify(wasHardBlock = true, captured = vm.reverifyCapture()))
        assertEquals(ChatViewModel.AfterReverify.GO, vm.afterReverify(wasHardBlock = false, captured = vm.reverifyCapture()))
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /**
     * F4 strict (sendgate.json 0.5.15; the maintainer 2026-11-07; iOS ConfirmPendingSendAnywayTests @ b1d6ad56): Intel's
     * couldn't-check is already known while TLS still runs. The ask answers that reason — one answer covers the record —
     * and the send goes out.
     */
    @Test fun aKnownCouldntCheckWhileChecksRunAsksItsOwnQuestion() = runTest(dispatcher) {
        val (vm, _, transport) = stillChecking()
        val s = stillCheckingSession!!
        s.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(model = ai.teemoon.confidential.DcapVerification.Unchecked("collateral: pcs unreachable"))
        assertTrue("precondition: couldn't-check known, checks running", s.sendPolicy == TrustSendPolicy.CONFIRM && s.verdictsPending)
        assertEquals("the ask answers 'still checking', not the known couldn't-check", s.confirmationKey(pending = false), s.confirmationKey(pending = true))
        vm.send("hello"); advanceUntilIdle()
        assertTrue("precondition: the gate asked", vm.state.value.hardwareConfirmRequested)
        vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertTrue("the answer did not cover the record's couldn't-check", s.hasConfirmed(pending = false))
        assertEquals("the answered send did not go out", 1, transport.calls)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * Window B (reply-machine.json 0.6.6 lifecycle/confirm-for-a-sealed-send-never-goes-plain; iOS @ 6e3e1d13): "send" to
     * the still-checking question for a sealed send, but near.ai's tier resolved to third-party meanwhile. Nothing goes
     * out; the held turn ends like a decline and the question comes back.
     */
    @Test fun aConfirmForASealedSendNeverGoesPlain() = runTest(dispatcher) {
        val model = "zai-org/S1-SEALED-CONFIRM-PROBE"
        try {
            val (vm, dao, transport) = stillChecking(model = model)
            vm.send("hello"); advanceUntilIdle()
            assertTrue("precondition: the still-checking ask for a sealed send", vm.state.value.hardwareConfirmRequested)
            ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf(model to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
            vm.answerStillChecking(sendIt = true); advanceUntilIdle()
            assertEquals("the confirmed sealed send went out", 0, transport.calls)
            assertFalse(vm.state.value.isStreaming)
            assertEquals("the question did not come back to the composer", "hello", vm.state.value.returnedQuestion?.text)
            assertTrue(dao.messages.isEmpty())
            assertNull("a decline, not a card", vm.state.value.error)
        } finally { stillCheckingSession?.clearDerivedState(resetCounts = true); ai.teemoon.providers.NearAIModelCatalog.resetTierCache() }
    }

    /**
     * Window A (0.6.6 lifecycle/a-turn-started-attestable-never-goes-plain; iOS @ ad43bd60): the turn starts attestable
     * (unknown tier) and the tier resolves to third-party while prepareTurn waits, before the gate, with no ask. It must
     * not leave unsealed: nothing is sent, the question comes back.
     */
    @Test fun aTurnStartedAttestableNeverGoesPlain() = runTest(dispatcher) {
        val model = "zai-org/S1-WINDOW-A-PROBE"
        try {
            val (vm, dao, transport) = stillChecking(model = model)
            assertEquals("precondition: the tier is unknown", ai.teemoon.providers.NearAIModelCatalog.Confidentiality.UNKNOWN,
                ai.teemoon.providers.NearAIModelCatalog.confidentiality(model))
            launch { delay(150); ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf(model to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY)) }
            vm.send("hello"); advanceUntilIdle()
            assertEquals("a turn that started attestable went out unsealed", 0, transport.calls)
            assertEquals("the question did not come back to the composer", "hello", vm.state.value.returnedQuestion?.text)
            assertTrue(dao.messages.isEmpty())
        } finally { stillCheckingSession?.clearDerivedState(resetCounts = true); ai.teemoon.providers.NearAIModelCatalog.resetTierCache() }
    }

    /** A switch to another model of the same setup while the question is up: the turn is no longer the selected place's (ownsTurn), so nothing goes out. */
    @Test fun aConfirmAfterAModelSwitchSendsNothing() = runTest(dispatcher) {
        try {
            val (vm, _, transport) = stillChecking()
            vm.send("hello"); advanceUntilIdle()
            assertTrue("precondition: asked", vm.state.value.hardwareConfirmRequested)
            val asked = stillCheckingSession!!.activeProvider!!
            activeOverride = asked.copy(model = "zai-org/another-model")
            vm.answerStillChecking(sendIt = true); advanceUntilIdle()
            assertEquals("sent for a model no longer selected", 0, transport.calls)
            assertEquals("hello", vm.state.value.returnedQuestion?.text)
        } finally { activeOverride = null; stillCheckingSession?.clearDerivedState(resetCounts = true) }
    }

    /** A stop before the request goes out leaves it too. */
    @Test fun aRetryStoppedBeforeItsRequestLeavesTheReplyTheUserHad() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        val had = listOf(ai.teemoon.chat.MessageEntity("m-1", "t", "user", "hello", 1),
            ai.teemoon.chat.MessageEntity("a-2", "t", "assistant", "Old answer", 2, status = "stopped"))
        dao.messages += had
        vm.restore(had)
        vm.retryLastTurn(); advanceUntilIdle()
        vm.stop(); advanceUntilIdle()
        assertEquals(0, transport.calls)
        assertEquals("the store as it was", had, dao.messages.sortedBy { it.timestamp })
        assertEquals(listOf("m-1" to "hello", "a-2" to "Old answer"), vm.state.value.messages.map { it.id to it.text })
        assertEquals("its own marker, as saved", "marker.stopped", vm.state.value.messages.last().marker)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /** Nothing to restore (the reply had failed): the decline ends the retry stopped, and the failed row goes. */
    @Test fun aDeclinedRetryOfAFailedReplyEndsStopped() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        val had = listOf(ai.teemoon.chat.MessageEntity("m-1", "t", "user", "hello", 1),
            ai.teemoon.chat.MessageEntity("a-2", "t", "assistant", "", 2, status = "failed"))
        dao.messages += had
        vm.restore(had)
        vm.retryLastTurn(); advanceUntilIdle()
        vm.answerStillChecking(sendIt = false); advanceUntilIdle()
        assertEquals(0, transport.calls)
        assertTrue("the failed row went: ${dao.messages}", dao.messages.none { it.id == "a-2" })
        val questions = dao.messages.filter { it.role == "user" }
        assertEquals("the question once, at its own time", listOf("hello" to 1L), questions.map { it.content to it.timestamp })
        assertEquals("stopped", dao.messages.single { it.role == "assistant" }.status)
        assertEquals("marker.stopped", vm.state.value.messages.last().marker)
        assertNull("a decline shows no card: the user chose not to send (iOS returnGatedQuestion)", vm.state.value.error)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    /** "send anyway" on a retry: the request goes out, and only then do the old turn's rows go. */
    @Test fun aRetrySentAnywayReplacesTheReplyWhenItGoesOut() = runTest(dispatcher) {
        val (vm, dao, transport) = stillChecking()
        val had = listOf(ai.teemoon.chat.MessageEntity("m-1", "t", "user", "hello", 1),
            ai.teemoon.chat.MessageEntity("a-2", "t", "assistant", "Old answer", 2, status = "complete"))
        dao.messages += had
        vm.restore(had)
        vm.retryLastTurn(); advanceUntilIdle()
        vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertEquals(1, transport.calls)
        assertTrue("the old reply went: ${dao.messages}", dao.messages.none { it.id == "a-2" || it.id == "m-1" })
        assertEquals(listOf("user" to "hello", "assistant" to "a sealed answer"), dao.messages.sortedBy { it.timestamp }.map { it.role to it.content })
        assertEquals("complete", dao.messages.single { it.role == "assistant" }.status)
        stillCheckingSession?.clearDerivedState(resetCounts = true)
    }

    private var stillCheckingSession: ConfidentialSession? = null
    /** The selected provider, once a test switches it; null = the rig's near.ai setup. */
    private var activeOverride: Provider? = null
    /** [tls] is the TLS check's answer; by default it never comes, so the verdict stays pending. */
    /** [model]: stamp the record for a model id no other test uses, so its near.ai tier can be moved safely. */
    private fun kotlinx.coroutines.test.TestScope.stillChecking(tls: kotlinx.coroutines.Deferred<TlsAttestation>? = null, model: String? = null): Triple<ChatViewModel, FakeChatDao, Scripted> {
        val preview = AttestationRecord.preview.let { if (model != null) it.copy(model = model) else it }
        val near = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val session = ConfidentialSession(this, { activeOverride ?: near }, credentialFor = { "" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" }, tierDeadlineMs = TIER_WAITS,
                verifyTls = { tls?.await() ?: awaitCancellation() }))
        session.attestation = preview
        session.lastRequestUsedE2EE = true
        session.verifyTls(near)
        stillCheckingSession = session
        val dao = FakeChatDao(); dao.threads += ai.teemoon.chat.ThreadEntity(id = "t", title = null, timestamp = 0)
        val transport = Scripted(listOf(TurnResult(content = "a sealed answer")))
        val vm = ChatViewModel(repo = ai.teemoon.chat.ChatRepository(dao), threadId = "t", provider = { activeOverride ?: near }, credential = { "sk-test" }, session = session,
            makeTransport = { _, _, _, _, _, _ -> transport })
        return Triple(vm, dao, transport)
    }

    /** A plain-TLS provider never waits on verdicts it does not have (iOS 4ea1dda, NVIDIA): the send goes out. */
    @Test fun aPlainTlsSendIsNotHeldByAnotherSessionsVerifier() = runTest(dispatcher) {
        val preview = AttestationRecord.preview
        var active: Provider = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val session = ConfidentialSession(this, { active }, credentialFor = { "" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" }, tierDeadlineMs = TIER_WAITS,
                verifyTls = { awaitCancellation() }))
        session.attestation = preview
        session.verifyTls(active)
        active = Provider.nvidia.copy(endpoint = "https://api.example.com/v1")
        val transport = Scripted(listOf(TurnResult(content = "hi from nvidia")))
        val vm = ChatViewModel(provider = { active }, credential = { "sk-test" }, session = session, makeTransport = { _, _, _, _, _, _ -> transport })
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
            makeTransport = { endpoint, _, _, _, _, _ -> endpoints += endpoint; transport })
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
        val vm = ChatViewModel(provider = { current }, credential = { "sk-test" }, groundingKey = { "brave-key" }, makeTransport = { _, _, _, _, _, _ -> transport })
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

    /**
     * Found on the maintainer's Pixel (2026-11-12, the device check): changing the font size rebuilt the screen and the
     * composer's attached photos were gone — the text survived (rememberSaveable), the photos were a plain remember. The
     * activity handles rotation and dark mode itself, not font scale. The draft's photos and their note live on the chat's
     * view model, which a rebuild keeps.
     */
    @Test fun theComposersPhotosLiveOnTheViewModelAndOutliveARebuild() {
        val vm = vm(object : GenerationTransport { override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<ai.teemoon.inference.Tool>, onPartial: (String) -> Unit) = error("no send") })
        assertEquals(emptyList<ai.teemoon.app.imaging.ImageDecodeFrame.Decoded>(), vm.draftImages)
        val photo = ai.teemoon.app.imaging.ImageDecodeFrame.Decoded("image/jpeg", 64, 48, byteArrayOf(1), byteArrayOf(2))
        vm.draftImages = listOf(photo); vm.draftImageNote = "note"
        assertEquals("what a rebuilt composer reads back", listOf(photo), vm.draftImages)
        assertEquals("note", vm.draftImageNote)
    }
}
