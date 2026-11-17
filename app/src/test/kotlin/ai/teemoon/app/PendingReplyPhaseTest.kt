// ports: teemoonTests/PendingReplyPhaseTests.swift @ 59b9280e
//
// S32 (the maintainer 2026-11-07, seen on this Pixel): since S30 prepareTurn can hold a send up to 15 s, and the reply
// placeholder read "thinking…" the whole time although nothing had been sent — the log showed the tap at 28.796, the
// last check at 30.626 and the request's first bytes at 30.791. While the gate holds the send it reads "waiting for
// verification…"; once the request leaves, "thinking…" as before. A plain-TLS or on-device turn never waits at the gate
// and never shows the waiting label. An engine load line keeps priority. formal/ ThinkingOnlyAfterSend,
// WaitingOnlyWhenTheGateCanHold.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.confidential.ManifestProvenance
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.RecordDcapVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingReplyPhaseTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain(); NearAIModelCatalog.resetTierCache() }

    // the rule

    @Test fun aHeldGateReadsWaitingForVerification() {
        assertEquals(PendingReplyPhase.WAITING_FOR_VERIFICATION, PendingReplyPhase.of(gateWaiting = true, loadLine = null, thinkingHarder = false))
        assertEquals("a think harder waits at the gate like any send", PendingReplyPhase.WAITING_FOR_VERIFICATION,
            PendingReplyPhase.of(gateWaiting = true, loadLine = null, thinkingHarder = true))
    }

    @Test fun afterTheGateItIsTodaysThinking() {
        assertEquals(PendingReplyPhase.THINKING, PendingReplyPhase.of(gateWaiting = false, loadLine = null, thinkingHarder = false))
        assertEquals(PendingReplyPhase.THINKING_HARDER, PendingReplyPhase.of(gateWaiting = false, loadLine = null, thinkingHarder = true))
    }

    @Test fun aModelLoadLineWins() {
        assertEquals(PendingReplyPhase.LOADING_MODEL, PendingReplyPhase.of(gateWaiting = false, loadLine = "loading", thinkingHarder = false))
        assertEquals(PendingReplyPhase.LOADING_MODEL, PendingReplyPhase.of(gateWaiting = true, loadLine = "loading", thinkingHarder = true))
        assertEquals(ActivityState.LoadingModel("loading"), ActivityState.of(false, 0, loadLine = "loading", gateWaiting = true))
    }

    /** The approved words (the maintainer 2026-11-07T22:37:08Z, the ellipsis 22:46Z), with U+2026. No clock. */
    @Test fun theWaitingLabelSaysExactlyThis() {
        assertEquals("waiting for verification…", activityLabel(ActivityState.WaitingForVerification, null))
        assertEquals("waiting for verification…", activityLabel(ActivityState.WaitingForVerification, 9.0))
        assertEquals(ActivityState.WaitingForVerification, ActivityState.of(false, 0, gateWaiting = true))
        assertEquals(ActivityState.Thinking, ActivityState.of(false, 0, gateWaiting = false))
    }

    // through the chat

    private class Recorder(val onRun: () -> Unit) : GenerationTransport {
        var calls = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            onRun(); calls++; onPartial("an answer"); return TurnResult(content = "an answer")
        }
    }

    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    /** near.ai, every verdict in and verified except TLS, which answers when [tls] completes: prepareTurn holds the send. */
    private fun kotlinx.coroutines.test.TestScope.held(tls: CompletableDeferred<TlsAttestation>, atSend: MutableList<Boolean>): Pair<ChatViewModel, Recorder> {
        val model = "zai-org/S32-HELD-PROBE"
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val record = AttestationRecord.preview.copy(model = model)
        val near = Provider.nearAI.copy(id = record.providerID, model = model)
        val session = ConfidentialSession(this, { near }, credentialFor = { "" }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://model.example" }, verifyTls = { tls.await() }, now = { record.fetchedAt }))
        session.attestation = record; session.lastRequestUsedE2EE = true
        session.imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList())
        session.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate)
        session.gpuAttestation = NrasVerification.Verified
        session.verifyTls(near)
        lateinit var vm: ChatViewModel
        val transport = Recorder { atSend += vm.state.value.waitingForVerification }
        vm = ChatViewModel(provider = { near }, credential = { "sk-test" }, session = session, makeTransport = { _, _, _, _, _, _ -> transport })
        return vm to transport
    }

    /** The bug: during a held prepareTurn the placeholder read "thinking…". From the tap on it reads waiting; once the request leaves, thinking. */
    @Test fun aHeldGateReadsWaitingFromTheTapAndThinkingOnceTheRequestLeaves() = runTest(dispatcher) {
        val tls = CompletableDeferred<TlsAttestation>()
        val atSend = mutableListOf<Boolean>()
        val (vm, transport) = held(tls, atSend)
        vm.send("hello")
        assertTrue("the tap's own frame read thinking", vm.state.value.waitingForVerification)
        dispatcher.scheduler.advanceTimeBy(2_000); runCurrent()
        assertEquals("precondition: the gate holds the send", 0, transport.calls)
        assertTrue("a held gate read thinking", vm.state.value.waitingForVerification)
        assertTrue("the placeholder is the streaming row, never a didn't-finish one", vm.state.value.messages.last().let { it.role == "assistant" && it.streaming && it.marker == null })
        tls.complete(TlsAttestation.Verified); advanceUntilIdle()
        assertEquals(1, transport.calls)
        assertEquals("the request left under the waiting label", listOf(false), atSend)
        assertFalse(vm.state.value.waitingForVerification)
    }

    /** A plain-TLS provider's gate never waits: the label never shows, not for a frame. */
    @Test fun aPlainTlsProviderNeverShowsTheWaitingLabel() = runTest(dispatcher) {
        val atSend = mutableListOf<Boolean>()
        lateinit var vm: ChatViewModel
        val transport = Recorder { atSend += vm.state.value.waitingForVerification }
        val fireworks = Provider.fireworks.copy(endpoint = "https://api.example.com/v1")
        vm = ChatViewModel(provider = { fireworks }, credential = { "sk-test" }, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("hello")
        assertFalse("a plain-TLS send showed the waiting label", vm.state.value.waitingForVerification)
        advanceUntilIdle()
        assertEquals(1, transport.calls); assertEquals(listOf(false), atSend)
    }

    /** An on-device turn never waits at the gate: no waiting label. */
    @Test fun anOnDeviceTurnNeverShowsTheWaitingLabel() = runTest(dispatcher) {
        val atSend = mutableListOf<Boolean>()
        lateinit var vm: ChatViewModel
        val transport = Recorder { atSend += vm.state.value.waitingForVerification }
        val phone = Provider.fireworks.copy(id = "on-device-probe", localModelID = "gemma-probe")
        assertTrue("precondition: on-device", phone.isLocal)
        vm = ChatViewModel(provider = { phone }, credential = { "" }, makeTransport = { _, _, _, _, _, _ -> transport })
        vm.send("hello")
        assertFalse("an on-device send showed the waiting label", vm.state.value.waitingForVerification)
        advanceUntilIdle()
        assertEquals(listOf(false), atSend)
    }
}
