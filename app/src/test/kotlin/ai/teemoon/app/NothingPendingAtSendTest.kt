// ports: teemoonTests/NothingPendingAtSendTests.swift @ 38647a07 (all 7)
//
// S30 (the maintainer 2026-11-07): "the user feels like the e2ee is theater if status show verifying and the message still
// goes out". Nothing leaves while anything is pending: a hard verdict, a stale record's refetch, image provenance, the near.ai
// tier. At the settle timeout the send asks; provenance alone asks its own question. An inconclusive provenance lookup (T2b)
// asks too. formal/ NothingPendingAtSend, ChipNeverVerifyingAtSend.
//
// The Pixel log behind it (2026-11-07 15:07:30): a record 82 min past attestationMaxAge sent at once, sealed to the old key,
// while its refetch ran; the new record landed ~0.7 s later and the chip read "verifying" for the rest of the reply.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.AttestationState
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.confidential.ManifestProvenance
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.RecordDcapVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.confidential.TrustSendPolicy
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class NothingPendingAtSendTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() { Dispatchers.setMain(dispatcher) }
    @After fun down() { Dispatchers.resetMain(); NearAIModelCatalog.tierListSeenOverride = null; NearAIModelCatalog.resetTierCache() }

    private class Recorder : GenerationTransport {
        var calls = 0
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            calls++; onPartial("an answer"); return TurnResult(content = "an answer")
        }
    }

    /** What the request carried as it left: the record it was sealed to, and what the chip read then. */
    private data class Sent(val nonce: String?, val sealed: Boolean, val chip: AttestationState, val hardwareUnconfirmed: Boolean)

    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    private class Chat(val vm: ChatViewModel, val session: ConfidentialSession, val transport: Recorder, val sent: MutableList<Sent>)

    /**
     * The preview record for [model] with every verdict in and verified, the tier known own-fleet unless [knownTier] is
     * false. [services] replaces the session's (a test that holds a check passes its own).
     */
    private fun TestScope.chat(model: String, knownTier: Boolean = true, services: ConfidentialSession.Services? = null,
                               settleMs: Long = 10_000, setUp: (ConfidentialSession) -> Unit = {}): Chat {
        if (knownTier) NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val record = AttestationRecord.preview.copy(model = model)
        val near = Provider.nearAI.copy(id = record.providerID, model = model)
        val session = ConfidentialSession(this, { near }, credentialFor = { "" },
            services = services ?: ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true, now = { record.fetchedAt }))
        session.verifierSettleTimeoutMs = settleMs
        session.attestation = record
        session.lastRequestUsedE2EE = true
        session.imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList())

        session.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate)
        session.gpuAttestation = NrasVerification.Verified
        session.tlsAttestation = TlsAttestation.Verified
        setUp(session)
        runCurrent()
        val transport = Recorder()
        val sent = mutableListOf<Sent>()
        val dao = FakeChatDao(); dao.threads += ai.teemoon.chat.ThreadEntity(id = "t", title = null, timestamp = 0)
        val vm = ChatViewModel(repo = ai.teemoon.chat.ChatRepository(dao), threadId = "t", provider = { near }, credential = { "sk-test" }, session = session,
            makeTransport = { _, _, tee, _, _, _ -> sent += Sent(tee?.attestation?.gpuNonce, tee?.e2eePeer != null, session.chipState, session.sealedButHardwareUnconfirmed); transport })
        return Chat(vm, session, transport, sent)
    }

    /** (a) A record past its age: the send waits for the refetch AND the new record's checks, then goes to the new record's key. */
    @Test fun aStaleRecordSendsOnlyToItsRefetchedRecordOnceItsChecksSettle() = runTest(dispatcher) {
        val fetchGate = CompletableDeferred<Unit>(); val dcapGate = CompletableDeferred<RecordDcapVerification>()
        val record = AttestationRecord.preview.copy(model = "zai-org/S30-STALE-PROBE")
        val fresh = record.copy(gpuNonce = "s30-fresh")
        var fetches = 0; var dcaps = 0
        var started = false
        // the first fetch is the session's own (the record it holds); the second is the send's refetch, held
        val c = chat("zai-org/S30-STALE-PROBE", services = ConfidentialSession.Services(
            directBase = { "https://model.example" },
            fetchRecord = { _, _, _, _, _ -> if (++fetches == 1) record else { started = true; fetchGate.await(); fresh } },
            verifyManifests = null, fetchModelLayerManifest = null,
            verifyDcap = { if (++dcaps == 1) RecordDcapVerification(gateway = upToDate, model = upToDate) else dcapGate.await() },
            verifyNras = null, verifyTls = { TlsAttestation.Verified },
            now = { record.fetchedAt.plusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1) })) { s -> s.refreshAttestation() }
        c.vm.send("hello"); runCurrent()
        assertTrue("no refetch started for a record past attestationMaxAge", started)
        dispatcher.scheduler.advanceTimeBy(300); runCurrent()
        assertEquals("sent to the stale record while its refetch ran", 0, c.transport.calls)
        fetchGate.complete(Unit); runCurrent()
        assertEquals("the refetch was not adopted", "s30-fresh", c.session.attestation?.gpuNonce)
        dispatcher.scheduler.advanceTimeBy(300); runCurrent()
        assertEquals("sent before the new record's DCAP answered", 0, c.transport.calls)
        dcapGate.complete(RecordDcapVerification(gateway = upToDate, model = upToDate)); advanceUntilIdle()
        assertEquals("refused: ${c.vm.state.value.error}; ${c.session.sendPolicy} ${c.session.attestationState}", 1, c.transport.calls)
        assertFalse("nothing was left pending; no question", c.vm.state.value.hardwareConfirmRequested)
        assertEquals("sealed to the wrong record", listOf("s30-fresh"), c.sent.map { it.nonce })
        assertTrue(c.sent.all { it.sealed })
        assertTrue("the chip read verifying as the request left: ${c.sent}", c.sent.none { it.chip == AttestationState.VERIFYING })
    }

    /** (a) A stale record whose refetch fails: nothing goes out; the record soft-degrades and the send asks. */
    @Test fun aStaleRecordWhoseRefetchFailsSendsNothingAndAsks() = runTest(dispatcher) {
        val record = AttestationRecord.preview.copy(model = "zai-org/S30-STALE-FAIL-PROBE")
        var fetches = 0
        val c = chat("zai-org/S30-STALE-FAIL-PROBE", services = ConfidentialSession.Services(
            directBase = { "https://model.example" },
            fetchRecord = { _, _, _, _, _ -> if (++fetches == 1) record else { kotlinx.coroutines.delay(200); throw java.io.IOException("not connected") } },
            verifyManifests = null, fetchModelLayerManifest = null,
            verifyDcap = { RecordDcapVerification(gateway = upToDate, model = upToDate) }, verifyTls = { TlsAttestation.Verified },
            now = { record.fetchedAt.plusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1) })) { s -> s.refreshAttestation() }
        c.vm.send("hello"); advanceUntilIdle()
        assertEquals("sent to a stale record whose refetch failed", 0, c.transport.calls)
        assertEquals(TrustSendPolicy.CONFIRM, c.session.sendPolicy)
        assertEquals("couldn't reach the attestation server.", c.session.e2eeDegradedReason)
    }

    /** (b) Provenance alone still running at the timeout: nothing goes out; its own question; "send anyway" sends sealed, the chip off verifying. */
    @Test fun provenanceInFlightAtTheTimeoutAsksItsOwnQuestion() = runTest(dispatcher) {
        val c = chat("zai-org/S30-PROVENANCE-PROBE", settleMs = 300, services = ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true,
            verifyManifests = { awaitCancellation() }, fetchModelLayerManifest = null, now = { AttestationRecord.preview.fetchedAt })) { s ->
            s.verifyImageProvenance(s.attestation!!)
        }
        try {
            c.vm.send("hello"); advanceUntilIdle()
            assertEquals("sent while provenance was in flight", 0, c.transport.calls)
            assertTrue(c.vm.state.value.hardwareConfirmRequested)
            assertEquals(ConfidentialSession.PendingKind.PROVENANCE, c.vm.state.value.pendingAskKind)
            val ask = E2EEAsk.make(hardFailure = false, pendingAsk = c.vm.state.value.pendingAskKind, provenanceUnchecked = c.session.provenanceUncheckedAsks,
                softButSealed = c.session.softButSealed, provenanceBlocked = c.session.provenanceBlockedButE2EEIntact, cause = null)
            assertEquals(E2EEAsk.PROVENANCE_PENDING_TITLE, ask.title)
            c.vm.answerStillChecking(sendIt = true); advanceUntilIdle()
            assertEquals(1, c.transport.calls)
            assertEquals(listOf(true), c.sent.map { it.sealed })
            assertTrue("the chip read verifying as the request left: ${c.sent}", c.sent.none { it.chip == AttestationState.VERIFYING })
            assertTrue("the answer is remembered for this record", c.session.hasConfirmed(pending = true))
        } finally { c.session.clearDerivedState(resetCounts = true) }
    }

    /** (b) Provenance answering inside the wait: the send goes once it has, with no question and a verified chip. */
    @Test fun provenanceAnsweringInsideTheWaitSendsWithoutAsking() = runTest(dispatcher) {
        val c = chat("zai-org/S30-PROVENANCE-QUICK-PROBE", services = ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true,
            verifyManifests = { kotlinx.coroutines.delay(300); ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList()) },
            fetchModelLayerManifest = null, now = { AttestationRecord.preview.fetchedAt })) { s -> s.verifyImageProvenance(s.attestation!!) }
        c.vm.send("hello"); advanceUntilIdle()
        assertEquals(1, c.transport.calls)
        assertFalse(c.vm.state.value.hardwareConfirmRequested)
        assertEquals("the chip as the request left", listOf(AttestationState.OK), c.sent.map { it.chip })
    }

    /** (c) The near.ai tier still unknown: nothing goes out until it resolves; at the timeout the hardware ask; "send anyway" leaves as "encrypted · hardware not confirmed". */
    @Test fun anUnknownTierHoldsTheSendAndAsksAtTheTimeout() = runTest(dispatcher) {
        val model = "zai-org/S30-UNKNOWN-TIER-PROBE"
        NearAIModelCatalog.tierListSeenOverride = false
        val c = chat(model, knownTier = false, settleMs = 300)
        assertEquals("precondition: the tier is unknown", NearAIModelCatalog.Confidentiality.UNKNOWN, NearAIModelCatalog.confidentiality(model))
        c.vm.send("hello"); advanceUntilIdle()
        assertEquals("sent while the tier was unknown", 0, c.transport.calls)
        assertTrue(c.vm.state.value.hardwareConfirmRequested)
        assertEquals(ConfidentialSession.PendingKind.HARDWARE, c.vm.state.value.pendingAskKind)
        c.vm.answerStillChecking(sendIt = true); advanceUntilIdle()
        assertEquals(1, c.transport.calls)
        assertEquals("the chip as the request left", listOf(AttestationState.DEGRADED), c.sent.map { it.chip })
        assertEquals("the answered look is 'encrypted · hardware not confirmed'", listOf(true), c.sent.map { it.hardwareUnconfirmed })
    }

    /** A pending hardware verdict answered with "send anyway": the request leaves under the answered look. */
    @Test fun anAnsweredHardwareAskLeavesWithTheChipOffVerifying() = runTest(dispatcher) {
        val c = chat("zai-org/S30-TLS-PROBE", settleMs = 300, services = ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true,
            directBase = { "https://model.example" }, verifyTls = { awaitCancellation() }, now = { AttestationRecord.preview.fetchedAt })) { s ->
            s.tlsAttestation = null
            s.verifyTls(s.activeProvider!!)
        }
        try {
            c.vm.send("hello"); advanceUntilIdle()
            assertTrue(c.vm.state.value.hardwareConfirmRequested)
            assertEquals(ConfidentialSession.PendingKind.HARDWARE, c.vm.state.value.pendingAskKind)
            c.vm.answerStillChecking(sendIt = true); advanceUntilIdle()
            assertEquals(1, c.transport.calls)
            assertEquals("the chip as the request left", listOf(AttestationState.DEGRADED), c.sent.map { it.chip })
        } finally { c.session.clearDerivedState(resetCounts = true) }
    }

    /**
     * T2b now asks: an inconclusive provenance lookup keeps the chip's look and asks "couldn't confirm near.ai's software"
     * with the provenance-only choices. Once per (record, reason): a new record asks again.
     */
    @Test fun anInconclusiveProvenanceLookupAsksOncePerRecord() = runTest(dispatcher) {
        val c = chat("zai-org/S30-T2B-PROBE") { s -> s.imageProvenance = null; s.provenanceUnchecked = true }
        val s = c.session
        assertEquals("T2b keeps its approved look", AttestationState.OK, s.chipState)
        assertEquals("an inconclusive lookup sent with no question", TrustSendPolicy.CONFIRM, s.sendPolicy)
        val ask = E2EEAsk.make(hardFailure = false, pendingAsk = null, provenanceUnchecked = s.provenanceUncheckedAsks,
            softButSealed = s.softButSealed, provenanceBlocked = s.provenanceBlockedButE2EEIntact, cause = s.e2eeDegradedReason)
        assertEquals(E2EEAsk(E2EEAsk.PROVENANCE_UNCHECKED_TITLE, E2EEAsk.PROVENANCE_UNCHECKED_MESSAGE,
            E2EESendChoice.choices(hardFailure = false, provenanceOnly = true)), ask)

        c.vm.send("unanswered"); advanceUntilIdle()
        assertEquals("the gate sent past an unanswered question", 0, c.transport.calls)
        // the composer's "send anyway (still encrypted)": the degrade accepted, then the send
        c.vm.send("hello", acceptedDegrade = true); advanceUntilIdle()
        assertEquals(1, c.transport.calls)
        s.lastRequestUsedE2EE = true   // the test transport reports no seal on the wire
        assertEquals("the same record asked twice", TrustSendPolicy.ALLOW, s.sendPolicyAfterConfirmations)
        c.vm.send("again"); advanceUntilIdle()
        assertEquals("a confirmed record's next send went nowhere", 2, c.transport.calls)
        s.lastRequestUsedE2EE = true
        s.attestation = s.attestation!!.copy(gpuNonce = "s30-next-record", fetchedAt = s.attestation!!.fetchedAt.plusSeconds(1))
        assertEquals("a new record did not ask again", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
    }
}
