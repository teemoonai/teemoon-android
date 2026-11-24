// ports: teemoonTests/AppStateTests.swift @ ebc9bdb (the "ConfidentialSession" suite)
//        teemoonTests/E2EESecurityExploitTests.swift @ v1.0.1 (the "Send-policy choke-point" suite)
//        teemoonTests/LocalProviderTests.swift @ v1.0.1 (registeringAndActivatingALocalProviderIsSafe)
//        teemoonTests/AppStateTests.swift @ ebc9bdb (prepareTurnWaitsForAnInFlightVerdictBeforeTheGateIsRead,
//        prepareTurnReportsAVerdictStillPendingAtTheDeadline, aRefusalBeforeTheWireDoesNotReadAsAnUnsealedRequest —
//        their ChatViewModel.postPrepareRefusal halves are ChatViewModelTest's, the function lives in app/)
//        teemoonTests/AppStateTests.swift @ ebc9bdb (aPlainTLSProviderNeverWaitsOnAnotherSessionsVerifiers,
//        aSwitchDropsCancelledVerifierHandles)
//        teemoonTests/AppStateTests.swift @ ebc9bdb (MissingDirectHostReasonTests: theReasonNamesTheDirectoryNotTheServer,
//        aFailedFetchIsRetriedAfterTheInterval)
//        ConfidentialSession+Verifiers.swift @ v1.1.0 has no iOS test for the kept verdict; aSealedSessionIsVerifyingUntilProvenanceSettles pins it
//        NOT ported: liveFetchSkipsPreviewAndDesignTourNotOrdinaryLaunches (Xcode process flags),
//        canSeal_tracksTheDerivedPeer (TEEContext / E2EEPeer — the E2EE row), chatViewModelDelegatesToTheSession
//        (the app's ViewModel is a human row).
package ai.teemoon.confidential

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import ai.teemoon.confidential.AttestationFixtures.verification
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import ai.teemoon.providers.EndpointDirectory
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent

/*
 * Sessions are given the test scope itself: refresh jobs then run under
 * advanceUntilIdle, and runTest waits for them (they all complete).
 */

/**
 * A stand-in for ProviderStore's active selection. In-memory, per test, so
 * nothing races the verdict (the iOS suite learned this from a flake).
 */
private class Store(var active: Provider? = null) {
    val source: () -> Provider? = { active }
    fun update(p: Provider) { active = p }
}

class ConfidentialSessionTest {
    /** Models near.ai set up with its list loaded: see [ai.teemoon.providers.NearAIModelCatalog.tierListSeen] (iOS ea19232). */
    init { ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf("nearai/tier-list-seen-by-tests" to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN)) }


    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
        // The session's provenance seams default to the real service; no test here may reach GitHub.
        ProvenanceService.default = ProvenanceService(http = { _, _ -> throw IOException("offline test") }, upgradeAttempted = HashSet(), sleepMs = {})
    }

    /** No fetch: the state tests read verdicts over seeded records, as the iOS suite does. */
    private val offlineServices = ConfidentialSession.Services(skipLiveAttestation = true)

    private fun session(scope: CoroutineScope, store: Store, services: ConfidentialSession.Services = offlineServices, warn: (String) -> Unit = {}) =
        ConfidentialSession(scope, store.source, credentialFor = { "" }, services = services, warn = warn, sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))

    /**
     * A store whose active provider is the one AttestationRecord.preview was
     * stamped for — so a seeded record survives the READ GATE. Do not inline
     * Provider.nearAI here: its model is the current flagship and moves, while
     * the preview stays stamped for GLM-5.1-FP8. Deriving the provider from the
     * record means the next flagship bump cannot fail five policy tests with no
     * hint as to why.
     */
    private fun storeMatchingPreviewRecord(): Store {
        val preview = AttestationRecord.preview
        return Store(Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model))
    }

    /** A real pass names at least one image: a pass over none reads as incomplete (aPassOverNoImagesIsNotAPass). */
    private val oneImageVerified = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())

    private fun nearProvider(model: String = "zai-org/GLM-5.1-FP8") =
        Provider(id = "p-near", name = "near.ai", endpoint = "https://completions.near.ai/v1", model = model)

    /** Guards the pairing above directly, so the *cause* is what fails rather than five unrelated policy assertions. */
    @Test fun previewRecordSurvivesTheReadGateOnItsOwnProvider() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        assertNotNull(s.attestation)
    }

    /** prepareTurn derives the context from the gated record and opens the turn with the peer's presence; no provider → no context, turn opened unsealed. */
    @Test fun prepareTurn_derivesTheContextAndOpensTheTurn() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        val ctx = s.prepareTurn()
        assertNotNull(ctx); assertEquals(AttestationRecord.preview.modelEd25519PubKey != null, ctx!!.e2eePeer != null)
        assertEquals(ctx.e2eePeer != null, s.lastRequestUsedE2EE); assertEquals(ctx.e2eePeer != null, s.canSeal)
        val none = session(this, Store(null))
        assertNull(none.prepareTurn()); assertEquals(false, none.lastRequestUsedE2EE); assertFalse(none.canSeal)
    }

    /** A TLS verifier that answers [result] after [delayMs] of (virtual) time — the in-flight verdict the gate must wait for. */
    private fun slowTls(delayMs: Long, result: TlsAttestation = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint")) =
        ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" },
            verifyTls = { delay(delayMs); result },
            verifyDcap = { awaitCancellation() })

    /**
     * A verdict still in flight is neutral in `attestationState`, so a send in that window rode an unsettled gate: on
     * the phone, every other message to a host whose TLS check fails (iOS, 2026-09-09). `prepareTurn` now waits for the
     * hard-block-capable verdicts before the caller re-reads the policy.
     */
    /**
     * THE GATE MATRIX (AppStateTests @ v1.0.4, iOS e017577): every kind of provider crossed with every prior state the
     * session can be in when a send starts. A gate change that widens — like build 34's pending-verdict refusal, which
     * reached plain-TLS providers through a cancelled near.ai probe — has to fail a cell here before it reaches a phone.
     * The refusal column is ChatViewModel's (app/), pinned over the same outcomes in ChatViewModelTest.
     */
    @Test fun theSendGateAnswersEveryProviderKindInEveryPriorState() = runTest {
        val kinds = mapOf(
            "attested" to storeMatchingPreviewRecord(),
            "plainCloud" to Store(Provider.nvidia),
            "selfHosted" to Store(Provider(id = "box", name = "box", endpoint = "http://box.local:11434/v1", model = "gemma4:e4b", requiresAPIKey = false)),
            "onDevice" to Store(Provider.local(LocalModelCatalog.all[0])),
        )
        for ((kind, store) in kinds) for (prior in listOf("fresh", "staleHandleNoReset", "afterSwitchReset", "hardFailure")) {
            val s = session(this, store, services = slowTls(60_000))
            val cell = "kind=$kind prior=$prior"
            fun seedRecord() { if (kind == "attested") { s.attestation = AttestationRecord.preview; s.lastRequestUsedE2EE = true } }
            seedRecord()
            when (prior) {
                "staleHandleNoReset" -> s.verifyTls(store.active!!)
                "afterSwitchReset" -> { s.verifyTls(store.active!!); s.clearDerivedState(resetCounts = true); seedRecord() }   // the new session's own record
                "hardFailure" -> s.tlsAttestation = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint")
            }
            when {
                kind == "attested" && prior == "staleHandleNoReset" -> assertTrue("$cell: a real verdict is in flight", s.verdictsPending)
                kind == "attested" && prior == "hardFailure" -> assertEquals(cell, TrustSendPolicy.BLOCK, s.sendPolicy)
                kind == "attested" -> { assertFalse(cell, s.verdictsPending); assertEquals(cell, TrustSendPolicy.ALLOW, s.sendPolicy) }
                // No verdicts exist for these providers: nothing pending, nothing blocked, whatever a previous session left.
                else -> { assertFalse(cell, s.verdictsPending); assertEquals(cell, TrustSendPolicy.ALLOW, s.sendPolicy) }
            }
            s.clearDerivedState(resetCounts = true)   // a 60 s verifier would otherwise hold runTest open
        }
    }

    @Test fun prepareTurnWaitsForAnInFlightVerdictBeforeTheGateIsRead() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, services = slowTls(300))
        s.attestation = AttestationRecord.preview
        s.lastRequestUsedE2EE = true
        assertEquals("precondition: the preview record verifies", TrustSendPolicy.ALLOW, s.sendPolicy)
        s.verifyTls(store.active!!)
        assertTrue(s.verdictsPending)
        assertEquals("the unsettled gate still reads allow — the hole", TrustSendPolicy.ALLOW, s.sendPolicy)

        s.prepareTurn()

        assertFalse(s.verdictsPending)
        assertEquals(true, s.tlsAttestation?.isHardFailure)
    }

    /** A verdict that never lands is not a pass: the wait is bounded and the caller learns it is still pending. */
    @Test fun prepareTurnReportsAVerdictStillPendingAtTheDeadline() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, services = slowTls(30_000))
        s.attestation = AttestationRecord.preview
        s.lastRequestUsedE2EE = true
        s.verifierSettleTimeoutMs = 250
        s.verifyTls(store.active!!)
        val started = testScheduler.currentTime
        s.prepareTurn()
        assertTrue("bounded by the settle timeout, not the verifier", testScheduler.currentTime - started < 5_000)
        assertTrue(s.verdictsPending)
        s.clearDerivedState(resetCounts = true)   // the 30 s verifier would otherwise hold runTest open
    }

    // MARK: - Regression guards for the near.ai SDK comparison's refuted findings (1.0.6, audit). Negative claims,
    // re-confirmed: a send never rides an unsettled DCAP verdict, and a Revoked TCB blocks.

    /**
     * The trust scope over the GPU node's report (NearTrustScope): audit's live report binds and changes nothing; its
     * action log bent by one byte is a hard break that blocks, like a DCAP hard failure; no action log is soft and
     * never degrades.
     */
    @Test fun aBentActionLogBlocksAndAMissingOneDoesNot() = runTest {
        fun res(n: String) = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$n")!!.readText()).jsonObject
        val report = res("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
        val nonce = res("expected.json").getValue("request_nonce_sent").jsonPrimitive.content
        val cm = report.getValue("compose_manager_attestation").jsonObject
        val manager = RecordDcapVerification(manager = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", ""))
        fun record(r: kotlinx.serialization.json.JsonObject) = AttestationRecord.preview.copy(gpuNodeReport = r, gpuNonce = nonce, gpuIntelQuote = report.getValue("intel_quote").jsonPrimitive.content)
        fun sessionOver(r: kotlinx.serialization.json.JsonObject) = session(this, storeMatchingPreviewRecord()).also { it.attestation = record(r); it.dcapVerification = manager }

        val clean = session(this, storeMatchingPreviewRecord()).also { it.attestation = AttestationRecord.preview }
        val live = sessionOver(report)
        assertEquals(NearTrustScope.Severity.NONE, live.nearScope?.severity)
        assertEquals(clean.attestationState, live.attestationState)

        val actions = cm.getValue("actions").jsonArray
        val first = actions[0].jsonObject
        val bent = kotlinx.serialization.json.JsonObject(first + ("timestamp" to kotlinx.serialization.json.JsonPrimitive(first.getValue("timestamp").jsonPrimitive.content.replaceFirst('2', '3'))))
        val broken = sessionOver(kotlinx.serialization.json.JsonObject(report + ("compose_manager_attestation" to kotlinx.serialization.json.JsonObject(cm + ("actions" to kotlinx.serialization.json.JsonArray(listOf(bent) + actions.drop(1)))))))
        assertEquals(AttestationState.DEGRADED, broken.attestationState)
        assertTrue(broken.degradeIsHardFailure)
        assertFalse(broken.e2eeBindingIntact)
        assertEquals(NearTrustScope.ACTION_LOG_BROKEN, broken.e2eeDegradedReason)

        val missing = sessionOver(kotlinx.serialization.json.JsonObject(report - "compose_manager_attestation"))
        assertEquals(NearTrustScope.Severity.SOFT, missing.nearScope?.severity)
        assertEquals(clean.attestationState, missing.attestationState)
    }

    /**
     * (c) through the session: once the manager quote's DCAP verdict binds the log, each running (commit, file) is
     * fetched; a file that hashes to anything else than the log says is the recipe line, hard. The fake serves "x" for
     * every file, so every fetched pair mismatches.
     */
    @Test fun aRunningComposeFileThatDoesNotMatchItsLogBlocks() = runTest {
        fun res(n: String) = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$n")!!.readText()).jsonObject
        val report = res("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
        val nonce = res("expected.json").getValue("request_nonce_sent").jsonPrimitive.content
        val asked = ArrayList<String>()
        // the compose-file work runs off the main thread (ProvenanceOffMainTest): here on the test's own scheduler
        ProvenanceService.default = ProvenanceService(http = { url, _ -> asked += url; ProvenanceResponse(200, emptyMap(), "x".toByteArray()) }, upgradeAttempted = HashSet(), sleepMs = {},
            io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val record = AttestationRecord.preview.copy(gpuNodeReport = report, gpuNonce = nonce, gpuIntelQuote = report.getValue("intel_quote").jsonPrimitive.content)
        val manager = RecordDcapVerification(manager = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", ""))
        val s = session(this, storeMatchingPreviewRecord(), services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { manager }))
        s.attestation = record
        s.verifyDcap(record)
        advanceUntilIdle()
        assertTrue("only near.ai's compose-files repo is asked", asked.isNotEmpty() && asked.all { it.startsWith("https://raw.githubusercontent.com/nearai/cvm-compose-files/") })
        // rule A: every newest whole file first, then the reported pairs up to the cap — each asked once. With bodies
        // that don't hash, every whole file is services unknown: 16 rows, 4 of them unknown (as iOS's offline run).
        val scope = s.nearScope!!
        assertEquals(16, scope.runningServices.size); assertEquals(4, scope.runningServices.count { it.service == "" })
        val want = (scope.wholeFileSources.map { it.commit to it.file } + ComposeFiles.toFetch(scope.runningServices)).toSet()
        assertEquals(want.map { ComposeFiles.url(it.first, it.second) }.toSet(), asked.toSet())
        assertEquals("each asked once", asked.toSet().size, asked.size)
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertTrue(s.degradeIsHardFailure)
        assertEquals(NearTrustScope.RECIPE_UNVERIFIED, s.e2eeDegradedReason)
    }

    /**
     * Trust scope (c)'s compose files are a check still running (iOS: TrustScope.run awaits the pairs and their bodies, and
     * verdictsPending counts trustScopeTask until then): a send in that window waits or asks, and a mark made in it does
     * not settle. Android did not count them — no ask, no mark, before a mismatch turned the scope hard.
     */
    @Test fun runningComposeFilesArePending() = runTest {
        fun res(n: String) = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/$n")!!.readText()).jsonObject
        val report = res("glm-5-3-flash-gpu04-5f7ebd85-2026-10-30T2241Z.json")
        val nonce = res("expected.json").getValue("request_nonce_sent").jsonPrimitive.content
        val github = kotlinx.coroutines.CompletableDeferred<Unit>()
        ProvenanceService.default = ProvenanceService(http = { _, _ -> github.await(); ProvenanceResponse(200, emptyMap(), "x".toByteArray()) },
            upgradeAttempted = HashSet(), sleepMs = {}, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val record = AttestationRecord.preview.copy(gpuNodeReport = report, gpuNonce = nonce, gpuIntelQuote = report.getValue("intel_quote").jsonPrimitive.content)
        val manager = RecordDcapVerification(manager = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", ""))
        val s = session(this, storeMatchingPreviewRecord(), services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { manager }))
        s.attestation = record
        s.verifyDcap(record)
        advanceUntilIdle()
        assertTrue("the compose files are still being fetched: a check still running", s.verdictsPending)
        github.complete(Unit); advanceUntilIdle()
        assertFalse(s.verdictsPending)
        assertTrue("the mismatch they found blocks", s.degradeIsHardFailure)
    }

    /** DCAP in flight holds the send; when it lands Revoked, the gate the caller re-reads is BLOCK. */
    @Test fun aDcapVerdictInFlightHoldsTheSend_andRevokedBlocks() = runTest {
        val store = storeMatchingPreviewRecord()
        val revoked = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.REVOKED, "01", ""))
        val s = session(this, store, services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { delay(300); revoked }))
        s.attestation = AttestationRecord.preview
        s.lastRequestUsedE2EE = true
        s.verifyDcap(AttestationRecord.preview)
        assertTrue("a launched DCAP check is pending", s.verdictsPending)
        s.prepareTurn()
        assertFalse(s.verdictsPending)
        assertEquals(true, s.dcapVerification?.hasHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
    }

    /**
     * The pre-launch window: a record is adopted and its verifiers launched in ONE continuation (refreshAttestation:
     * `attestation = record` then verifyDcap/verifyGpu/verifyTls, no suspension between), so no observer sees the
     * record without its pending verdicts. Before the record lands there is nothing to seal with: prepareTurn's
     * context has no peer, which ChatViewModel's mustRefuseUnsealedSend refuses (TrustPresentationTest) — and the
     * wait for it is bounded (below).
     */
    @Test fun noObserverSeesARecordBeforeItsVerifiersAreInFlight() = runTest {
        val store = Store(nearProvider())
        val release = CompletableDeferred<Unit>()
        val services = ConfidentialSession.Services(
            directBase = { "https://glm-5-1.completions.near.ai/v1" },
            fetchRecord = { _, _, model, providerID, _ -> release.await(); AttestationRecord.preview.copy(providerID = providerID, model = model, nvidiaPayload = "{}") },
            verifyDcap = { awaitCancellation() }, verifyNras = { _, _ -> awaitCancellation() }, verifyTls = { awaitCancellation() },
        )
        val s = session(this, store, services)
        runCurrent()
        assertNull("the fetch is in flight", s.attestation)
        assertFalse("nothing launched yet, so nothing pending — the no-record state the view model refuses", s.verdictsPending)
        s.verifierSettleTimeoutMs = 100
        assertNull("no record, no peer", s.prepareTurn()?.e2eePeer)
        release.complete(Unit)
        runCurrent()
        assertNotNull(s.attestation)
        assertTrue("the record arrived with its verdicts already pending", s.verdictsPending)
        s.clearDerivedState(resetCounts = true)   // the never-landing verifiers would otherwise hold runTest open
    }

    /** A hung attestation fetch used to hold the send past the 15 s wait: the wait joined the fetch before its deadline loop (iOS ebc9bdb). */
    @Test fun aHungAttestationFetchDoesNotOutlastTheKeyWait() = runTest {
        val store = Store(nearProvider())
        // backgroundScope: the fetch never answers, and runTest cancels it there when the body ends.
        val s = session(backgroundScope, store, ConfidentialSession.Services(directBase = { "https://glm-5-1.completions.near.ai/v1" },
            fetchRecord = { _, _, _, _, _ -> awaitCancellation() }))
        runCurrent()
        // The deadline runs on services.now() — the wall clock — so the bound is measured on it, as iOS does.
        val started = System.nanoTime()
        assertNull(s.e2eeKey(waitingUpToMs = 300))
        s.attestationOutcome(waitingUpToMs = 300)
        assertTrue("the wait followed the hung fetch, not its deadline", System.nanoTime() - started < 3_000_000_000L)
    }

    /** iOS adoptingARecordLeavesNoUnlaunchedCheck: adopt() sets the record and launches its verifiers in one step. */
    @Test fun adoptingARecordLeavesNoUnlaunchedCheck() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" },
            verifyDcap = { awaitCancellation() }, verifyTls = { awaitCancellation() }))
        assertFalse("precondition: nothing launched, nothing adopted", s.verdictsPending)
        s.adopt(AttestationRecord.preview, store.active!!)
        assertNotNull(s.attestation)
        assertTrue("an adopted record must hold the send until its checks answer", s.verdictsPending)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * A refusal before the wire sent nothing. It used to be booked as an unsealed request, and once the key was
     * present that read as plaintext-went-out: a hard block only a provider switch cleared.
     */
    @Test fun aRefusalBeforeTheWireDoesNotReadAsAnUnsealedRequest() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        // The old bookkeeping, for contrast: this is the poison.
        s.beginRequest(expectingE2EE = false)
        s.finishTurn(isE2EEActive = null, error = null, verification = null)
        assertTrue("precondition: the old shape reads as a hard break", s.degradeIsHardFailure)

        s.abandonTurn(reason = "nothing was sent")
        assertNull(s.lastRequestUsedE2EE)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicy)
        assertEquals("nothing was sent", s.lastE2EEFailReason)
    }

    /**
     * NVIDIA (iOS, 2026-09-12): every send was refused with "Verification is still running, so nothing was sent". The
     * near.ai session before it had its TLS probe cancelled by the switch; the handle stayed, no verdict ever came, and
     * the chat path asked the pending predicate for a provider that has no verdicts at all.
     */
    @Test fun aPlainTLSProviderNeverWaitsOnAnotherSessionsVerifiers() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, services = slowTls(30_000))
        s.attestation = AttestationRecord.preview
        s.verifyTls(store.active!!)
        assertTrue("precondition: the near.ai probe is in flight", s.verdictsPending)
        // The stale handle is still there; only the active provider changed.
        store.update(Provider.nvidia)
        assertFalse(ProviderCapability.ATTESTATION in Provider.nvidia.capabilities)
        assertFalse(s.verdictsPending)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * A cancelled verifier never answers, so a switch must drop its handle — otherwise the next near.ai session
     * inherits a verdict that is "pending" forever, and even its own sends wait the full settle timeout.
     */
    @Test fun aSwitchDropsCancelledVerifierHandles() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, services = slowTls(30_000))
        s.attestation = AttestationRecord.preview
        s.verifyTls(store.active!!); s.verifyDcap(AttestationRecord.preview)
        assertTrue(s.verdictsPending)

        s.clearDerivedState(resetCounts = true)
        // The handles are private; what they decide is public. Re-attach the same record the way a re-verify would, and
        // the gate must not find the cancelled verifiers still pending.
        s.attestation = AttestationRecord.preview
        assertFalse(s.verdictsPending)
    }

    /**
     * A missing directory host used to read "Could not reach the attestation server" although the server answered, and
     * stayed that way until a provider switch (iOS b2aeee9). It names itself now — here through a real refresh: the
     * directory has no host for an own-fleet model.
     */
    @Test fun theReasonNamesTheDirectoryNotTheServer() = runTest {
        val store = Store(nearProvider("zai-org/GLM-5.1-FP8"))
        val s = session(this, store, services = ConfidentialSession.Services(directBase = { null }, fetchRecord = { _, _, _, _, _ -> error("not reached") }))
        s.refreshAttestation(); advanceUntilIdle()
        assertTrue(s.attestationFetchFailed); assertTrue(s.directHostMissing)
        assertEquals("near.ai lists no confidential host for this model yet.", s.e2eeDegradedReason)
        s.directHostMissing = false
        assertEquals("couldn't reach the attestation server.", s.e2eeDegradedReason)
    }

    /** The pre-send staleness check retries a failed fetch — at most once a minute. */
    @Test fun aFailedFetchIsRetriedAfterTheInterval() = runTest {
        var now = Instant.parse("2026-09-23T12:00:00Z")
        var asked = 0
        val s = session(this, Store(nearProvider()), services = ConfidentialSession.Services(now = { now },
            directBase = { asked++; null }, fetchRecord = { _, _, _, _, _ -> error("not reached") }))
        s.refreshAttestation(); advanceUntilIdle()
        assertEquals(1, asked); assertTrue(s.attestationFetchFailed)
        // Too soon after the last try: left alone.
        now = now.plusSeconds(5)
        s.refreshAttestationIfStale(); advanceUntilIdle()
        assertEquals(1, asked)
        // Past the interval: tried again, and the clock restamped.
        now = now.plusSeconds(ConfidentialSession.ATTESTATION_RETRY_INTERVAL_SECONDS)
        s.refreshAttestationIfStale(); advanceUntilIdle()
        assertEquals(2, asked)
        assertEquals(now, s.attestationAttemptedAt)
    }

    /**
     * T5 (pm sweep 2026-11-06): "no confidential endpoint" is one refresh's answer, not the session's. It was cleared
     * only on a switch, so a later refresh of the same model that FAILED (here the directory lookup throws) still read
     * NONE — "ordinary model, nothing to verify" — where DEGRADED was meant.
     */
    @Test fun aFailedRefreshAfterANoEndpointAnswerDegrades() = runTest {
        val s = session(this, Store(nearProvider()), services = ConfidentialSession.Services(
            directBase = { throw IOException("directory unreachable") }, fetchRecord = { _, _, _, _, _ -> error("not reached") }))
        advanceUntilIdle()
        // An earlier refresh's answer, as the tier race leaves it (noDirectHostDegradesOwnFleet…: the only way in).
        s.noConfidentialEndpoint = true; s.attestationFetchFailed = false
        assertEquals(AttestationState.NONE, s.attestationState)
        s.refreshAttestation(); advanceUntilIdle()
        assertTrue(s.attestationFetchFailed)
        assertEquals(AttestationState.DEGRADED, s.attestationState)
    }

    /**
     * pm sweep 2026-11-06 (Android-only): a verifier that throws must answer. With no catch the job ended with no
     * verdict, so the check read pending forever (sends refused as "still running") — or the throw reached the scope.
     * DCAP answers Failed (fail-closed, as an unreachable collateral already does); NRAS and TLS answer Inconclusive.
     */
    @Test fun aVerifierThatThrowsStillAnswers() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://x/v1" },
            verifyDcap = { error("parser bug") }, verifyNras = { _, _ -> error("parser bug") }, verifyTls = { error("parser bug") }))
        val record = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        s.lastRequestUsedE2EE = true
        s.adopt(record, store.active!!)
        advanceUntilIdle()
        assertFalse(s.verdictsPending)
        assertTrue(s.gpuAttestation is NrasVerification.Inconclusive)
        assertTrue(s.tlsAttestation is TlsAttestation.Inconclusive)
        // near-send-gate-rule (the maintainer 2026-11-06) reverses trust-verifier-error's "DCAP hard": our bug is not evidence, so
        // every throw is couldn't-check and the send asks; one fixed reason, the raw message log-only
        assertTrue(s.dcapVerification?.hasUnchecked == true && s.dcapVerification?.hasHardFailure == false)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals(ConfidentialSession.VERIFIER_ERROR, (s.gpuAttestation as NrasVerification.Inconclusive).reason)
        assertEquals(ConfidentialSession.VERIFIER_ERROR, (s.tlsAttestation as TlsAttestation.Inconclusive).reason)
        assertFalse(s.dcapVerification.toString().contains("parser bug"))
        // trust-hardware-fail-lines 1 (the maintainer 2026-11-06): our bug is not the hardware's — the hero's cause says so
        assertEquals(ConfidentialSession.VERIFIER_BROKE, s.e2eeDegradedReason)
        assertEquals("a hardware check broke inside teemoon.", ConfidentialSession.VERIFIER_BROKE)
    }

    /**
     * 1.1.1 audit integ read, item 5 (iOS ConfidentialSession+Verifiers.swift @ 01a337a4: DCAPService.verify never throws, each quote that
     * can't be checked is unchecked): a verifier that throws leaves every listed quote unchecked at once, so the listed state reads
     * "couldn't finish" and not "checking" until the verdict deadline.
     */
    @Test fun aVerifierThatThrowsMakesTheListedStateCouldntFinishAtOnce() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://x/v1" }, verifyDcap = { error("parser bug") }))
        val record = AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean)))
        s.adopt(record, store.active!!)
        advanceUntilIdle()
        assertEquals(2, s.dcapVerification?.listed?.size)
        assertTrue(s.dcapVerification?.listed?.all { it.isUnchecked } == true)
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(record, s.dcapVerification, NrasVerification.Verified, s.listedNearScopes))
    }

    /**
     * trust-stale-record (the maintainer 2026-11-06): a record past ATTESTATION_MAX_AGE_SECONDS whose refetch failed is no longer
     * vouched for — SOFT degraded with the existing "couldn't reach the attestation server.", never green forever.
     * Younger, or with the refetch still to come, it stands.
     */
    @Test fun aStaleRecordWhoseRefetchFailedIsSoftDegraded() = runTest {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, ConfidentialSession.Services(skipLiveAttestation = true, now = { now }))
        s.lastRequestUsedE2EE = true
        s.attestation = AttestationRecord.preview.copy(fetchedAt = now.minusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1))
        assertEquals("the refetch is still to come", AttestationState.OK, s.attestationState)
        s.attestationFetchFailed = true
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals("couldn't reach the attestation server.", s.e2eeDegradedReason)
        s.attestation = AttestationRecord.preview.copy(fetchedAt = now.minusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS - 1))
        assertEquals("young enough: a failed refetch is not news yet", AttestationState.OK, s.attestationState)
    }

    /**
     * 1.1.1 audit integ read item 8 (iOS ConfidentialSession+Verdict.swift @ 01a337a4 e2eeDegradedReason): a stale record whose refetch failed is
     * named right after the missing-attestation guard, before every other cause, so a key fault beside it still reads "couldn't reach the attestation
     * server." (iOS recordIsStaleAndUnrefreshable). The confirmation key's reason half is that text; attestationState and sendPolicy do not move.
     */
    @Test fun aStaleUnrefreshableRecordIsNamedBeforeAKeyFault() = runTest {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, ConfidentialSession.Services(skipLiveAttestation = true, now = { now }))
        s.lastRequestUsedE2EE = true
        s.attestation = AttestationRecord.preview.copy(modelEd25519PubKey = null, fetchedAt = now.minusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1))
        s.attestationFetchFailed = true
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals("couldn't reach the attestation server.", s.e2eeDegradedReason)
        assertTrue(s.confirmationKey(pending = false)!!.endsWith("|couldn't reach the attestation server."))
    }

    /** Every mutation bumps `changes`, so a collector re-reads the snapshot exactly when the state moved — no polling. */
    @Test fun changes_bumpOnEveryMutation() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        val before = s.changes.value
        s.attestation = AttestationRecord.preview; assertEquals(before + 1, s.changes.value)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.NotPerformed; assertEquals(before + 3, s.changes.value)
        s.beginRequest(expectingE2EE = true); assertTrue(s.changes.value >= before + 4)
        s.finishTurn(isE2EEActive = true, error = null, verification = ResponseVerification.Verified("0xabc")); assertTrue(s.changes.value >= before + 6)
        // pm sweep 2026-11-06: a failed fetch is a mutation too — the chip moves to DEGRADED on it, and with no bump
        // the screen kept the last state until something else changed.
        val atFail = s.changes.value
        s.attestationFetchFailed = true; assertEquals(atFail + 1, s.changes.value)
    }

    /** The UITEST_SEED_ATTESTATION door answers the verdict itself, and clearing it gives the real computation back. */
    @Test fun aSeededVerdictAnswersAndClearingItRestoresTheRealOne() = runTest {
        val s = session(this, Store(null))
        s.uiTestSeed = ConfidentialSession.UiTestSeed.of("hardFailure")
        assertEquals(AttestationState.DEGRADED, s.attestationState); assertEquals(true, s.degradeIsHardFailure)
        s.uiTestSeed = ConfidentialSession.UiTestSeed.of("mismatch")
        assertEquals(AttestationState.OK, s.attestationState); assertEquals(1, s.effectiveMismatchCount)
        s.uiTestReset()
        assertEquals("a reset session still answers the seed", AttestationState.NONE, s.attestationState)
        assertEquals(0, s.effectiveMismatchCount)
        assertEquals(null, ConfidentialSession.UiTestSeed.of("anything-else"))
    }

    @Test fun attestationState_noneWhenNoProvider() = runTest {
        assertEquals(AttestationState.NONE, session(this, Store(null)).attestationState)
    }

    @Test fun attestationState_noneForNonAttestationProvider() = runTest {
        val store = Store(Provider(id = "o", name = "OpenAI", endpoint = "https://api.openai.com/v1", model = "gpt-4"))
        assertEquals(AttestationState.NONE, session(this, store).attestationState)
    }

    /**
     * A near.ai model that is classified attestable but has no confidential
     * endpoint (e.g. a retired model) must resolve to NONE — a plain
     * non-attestable model — not stall on VERIFYING then fail closed.
     */
    /** awaitsFirstRecord (iOS dfc528b): only VERIFYING without a record; a provenance sweep over a record is not it. */
    @Test fun awaitsFirstRecord_onlyBeforeTheFirstRecord() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        assertTrue("no record yet: a silent verifier is news", s.awaitsFirstRecord)
        s.attestation = AttestationRecord.preview
        assertFalse("a record is in", s.awaitsFirstRecord)
    }

    @Test fun attestationState_noneWhenNoConfidentialEndpoint() = runTest {
        val store = Store(nearProvider())
        val s = session(this, store)
        // Precondition: this provider IS attestation-capable, so a NONE verdict can
        // only come from the no-confidential-endpoint short-circuit.
        assertTrue(ProviderCapability.ATTESTATION in store.active!!.capabilities)
        assertEquals(AttestationState.VERIFYING, s.attestationState)   // no record yet, flag off
        s.noConfidentialEndpoint = true
        assertEquals(AttestationState.NONE, s.attestationState)        // non-attestable, not a stall
    }

    /**
     * Regression (observed on device): switching GLM-5.1 → 5.2 kept showing 5.1.
     * The first refresh after a model switch came from a keepExisting caller,
     * which recorded the new context WITHOUT clearing — so no later refresh
     * ever saw a change. A context change must clear even on a keepExisting refresh.
     */
    @Test fun modelSwitch_clearsDerivedState_evenOnKeepExistingRefresh() = runTest {
        val store = Store(nearProvider())
        // Live refresh path (not skipped), but every network seam answers nothing.
        val s = session(this, store, ConfidentialSession.Services(directBase = { null }))
        s.modelArtifact = ModelArtifact("QuantTrio/GLM-5.1-AWQ", null, "zai-org/GLM-5.1-FP8", "AWQ")
        store.update(store.active!!.copy(model = "z-ai/glm-5.2"))
        s.refreshAttestation(keepExisting = true)
        // The 5.1 artifact must be gone — the switch invalidates it even though the
        // caller asked to keep existing state for a same-context refresh.
        assertNull(s.modelArtifact)
        // And a same-context keepExisting refresh must NOT clear: re-seed and refresh.
        s.modelArtifact = ModelArtifact("PhalaCloud/GLM-5.2-W4AFP8", null, "z-ai/glm-5.2", "W4AFP8")
        s.refreshAttestation(keepExisting = true)
        assertNotNull(s.modelArtifact)
    }

    /** The provenance-blocked-but-E2EE-intact discriminator drives the honest send alert. */
    @Test fun provenanceBlockDiscriminator_tracksE2EEIntactness() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        // No attestation at all → false.
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), emptyList(), emptyList())
        assertFalse(s.provenanceBlockedButE2EEIntact)
        // Bound-verified record + provenance failure → true.
        s.attestation = AttestationRecord.preview
        assertEquals(32, s.attestation?.modelEd25519PubKey?.size)
        assertTrue(s.provenanceBlockedButE2EEIntact)
        // Provenance fine → false.
        s.imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList())
        assertFalse(s.provenanceBlockedButE2EEIntact)
    }

    /**
     * Fail-loud: an inner-compose hash mismatch is an ADVERSARIAL integrity
     * break — fail closed, HARD (red hero), gate the send, name the recipe. A
     * TRANSIENT fetch failure must do none of that.
     */
    @Test fun recipeHashMismatch_failsClosedAndHard_fetchFailureDoesNot() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        assertEquals(AttestationState.OK, s.attestationState)
        assertFalse(s.requiresE2EEConfirmation)

        s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
        assertTrue(s.modelComposeIntegrityFailed)
        assertEquals(AttestationState.DEGRADED, s.attestationState)   // fail-closed
        assertTrue(s.degradeIsHardFailure)                            // RED, not advisory
        assertTrue(s.requiresE2EEConfirmation)                        // send actually gated
        assertTrue(s.e2eeDegradedReason!!.contains("recipe"))

        s.modelLayerVerification = ModelLayerVerification.FETCH_FAILED
        assertFalse(s.modelComposeIntegrityFailed)
        assertEquals(AttestationState.OK, s.attestationState)
        assertFalse(s.requiresE2EEConfirmation)
    }

    /**
     * The simulator, 2026-09-12 (1102bc8): "end-to-end encrypted" at 1.4 s, "image unpublished" at 4.5 s. A sealed
     * session read green while its image provenance was still being fetched; now it reads verifying until the pass
     * writes its result, and the send gate does not change.
     */
    @Test fun aSealedSessionIsVerifyingUntilProvenanceSettles() = runTest {
        var answer = CompletableDeferred<ManifestProvenance>()
        val s = session(this, storeMatchingPreviewRecord(),
            ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null, verifyManifests = { answer.await() }))
        s.attestation = AttestationRecord.preview
        assertEquals("precondition: the preview record verifies", AttestationState.OK, s.attestationState)
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicy)

        val record = AttestationRecord.preview.copy(composeManifest = "image: nearaidev/cloud-api@sha256:${"a".repeat(64)}")
        s.verifyImageProvenance(record)
        yield()
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        assertEquals("in-flight provenance never gates a send", TrustSendPolicy.ALLOW, s.sendPolicy)

        answer.complete(oneImageVerified)
        advanceUntilIdle()
        assertTrue(s.provenanceSettled)
        assertEquals(AttestationState.OK, s.attestationState)

        // An inconclusive pass settles — not verifying forever — and keeps the last verdict rather than blanking it
        // (iOS fbedb79: a rate-limited re-sweep dropped a verified sheet back to pending).
        answer = CompletableDeferred()
        s.verifyImageProvenance(record)
        answer.complete(ManifestProvenance.Incomplete(emptyList(),
            listOf(ProvenanceFailure(ImageRef("nearaidev/cloud-api", "a".repeat(64)), ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient("HTTP 403")))), emptyList()))
        advanceUntilIdle()
        assertTrue("the verified verdict survives a transient re-sweep", s.imageProvenance is ManifestProvenance.AllVerified)
        assertTrue(s.provenanceSettled)
        assertEquals(AttestationState.OK, s.attestationState)
    }

    /**
     * T1 (pm sweep 2026-11-06): a hard-block-capable verdict that has not answered is not a pass. With a sealed record
     * and DCAP, NRAS or TLS still in flight, the chip is VERIFYING, not OK — OK said "end-to-end encrypted" and
     * "confirmed by Intel and NVIDIA" before either had answered. The send gate is unchanged: prepareTurn settles.
     */
    @Test fun aSealedSessionIsVerifyingWhileAHardVerdictIsInFlight() = runTest {
        val dcap = CompletableDeferred<RecordDcapVerification>()
        val s = session(this, storeMatchingPreviewRecord(),
            ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null, verifyDcap = { dcap.await() }))
        s.attestation = AttestationRecord.preview
        assertEquals("precondition: the preview record verifies", AttestationState.OK, s.attestationState)
        s.verifyDcap(AttestationRecord.preview)
        yield()
        assertTrue(s.verdictsPending)
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        dcap.complete(RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "00", "")))
        advanceUntilIdle()
        assertFalse(s.verdictsPending)
        assertEquals(AttestationState.OK, s.attestationState)
    }

    /**
     * T2 (pm sweep 2026-11-06; iOS provenanceKey, with its T2 commit): an inconclusive re-check keeps the last verdict
     * only for the same record. A re-verify that lands on another node must not show the old node's digests.
     */
    @Test fun anInconclusiveRecheckOnAnotherNodeKeepsNothing() = runTest {
        var answer = CompletableDeferred<ManifestProvenance>()
        val s = session(this, storeMatchingPreviewRecord(),
            ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null, verifyManifests = { answer.await() }))
        val nodeA = AttestationRecord.preview.copy(composeManifest = "image: nearaidev/cloud-api@sha256:${"a".repeat(64)}")
        s.attestation = nodeA
        s.verifyImageProvenance(nodeA)
        answer.complete(ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList()))
        advanceUntilIdle()
        assertTrue(s.imageProvenance is ManifestProvenance.AllVerified)

        val transient = ManifestProvenance.Incomplete(emptyList(),
            listOf(ProvenanceFailure(ImageRef("nearaidev/cloud-api", "b".repeat(64)), ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient("HTTP 403")))), emptyList())
        val nodeB = AttestationRecord.preview.copy(composeManifest = "image: nearaidev/cloud-api@sha256:${"b".repeat(64)}")
        answer = CompletableDeferred()
        s.attestation = nodeB
        s.verifyImageProvenance(nodeB)
        answer.complete(transient)
        advanceUntilIdle()
        assertNull("node A's verdict is not node B's", s.imageProvenance)
        assertTrue(s.provenanceSettled)

        // the same record again: an inconclusive pass still keeps its own last verdict (iOS fbedb79)
        answer = CompletableDeferred(); s.verifyImageProvenance(nodeB)
        answer.complete(ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "b".repeat(64))), emptyList())); advanceUntilIdle()
        answer = CompletableDeferred(); s.verifyImageProvenance(nodeB); answer.complete(transient); advanceUntilIdle()
        assertTrue(s.imageProvenance is ManifestProvenance.AllVerified)
    }

    /**
     * T2 (pm 2026-11-06): a pass over zero images vouches for nothing. ProvenanceService never returns one (no near.ai
     * image is Incomplete, fail-closed), but whatever reaches the session reads it the same way, never as a pass.
     */
    @Test fun aPassOverNoImagesIsNotAPass() = runTest {
        val s = session(this, storeMatchingPreviewRecord(), ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null,
            verifyManifests = { ManifestProvenance.AllVerified(emptyList(), listOf(ImageRef("redis", "c".repeat(64)))) }))
        val record = AttestationRecord.preview.copy(composeManifest = "image: redis@sha256:${"c".repeat(64)}")
        s.attestation = record
        s.verifyImageProvenance(record)
        advanceUntilIdle()
        assertEquals(ManifestProvenance.Incomplete(emptyList(), emptyList(), listOf(ImageRef("redis", "c".repeat(64)))), s.imageProvenance)
    }

    /**
     * T2b (iOS ProvenanceCarryOverTests.aRateLimitedFirstPassDoesNotReadVerified): the FIRST provenance pass on a
     * record is inconclusive (GitHub 403/429, the network). There is no earlier verdict to keep, so provenance stays
     * null but settled, and the state fell through to OK — a green hero while no image was checked. Approved fix
     * (trust-provenance-unchecked, the maintainer 2026-11-06): the session says so; the chip is unchanged, and since S30 the
     * send asks "couldn't confirm near.ai's software".
     */
    @Test fun aRateLimitedFirstPassDoesNotReadVerified() = runTest {
        val s = session(this, storeMatchingPreviewRecord(), ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null,
            verifyManifests = { ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/cloud-api", "a".repeat(64)),
                ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient("HTTP 429")))), emptyList()) }))
        val record = AttestationRecord.preview   // its own manifest, so the code identity holds: only provenance is in question
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", ""))
        s.verifyImageProvenance(record); advanceUntilIdle()
        assertTrue("precondition: a pass ran, settled with no verdict", s.provenanceSettled && s.imageProvenance == null)
        assertNotEquals("precondition: the code identity holds", false, record.codeIdentityVerified)
        // trust-provenance-unchecked (the maintainer 2026-11-06): the seal is fine, so the chip and the send are unchanged; the
        // session says no image was checked, and the trust screen shows it (TrustPresentationTest).
        assertTrue("no image was checked: said so", s.provenanceUnchecked)
        // S30 (the maintainer 2026-11-07, "Have that case also ask the question"; iOS 38647a07): the send now asks first
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        // a second pass that GitHub also refuses keeps saying so
        s.verifyImageProvenance(record); advanceUntilIdle()
        assertTrue(s.provenanceUnchecked)
    }

    /** A cancelled pass finishing late must not mark a newer one settled. */
    @Test fun aCancelledPassCannotSettleANewerOne() = runTest {
        val first = CompletableDeferred<ManifestProvenance>()
        val second = CompletableDeferred<ManifestProvenance>()
        var calls = 0
        val s = session(this, storeMatchingPreviewRecord(),
            ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null,
                verifyManifests = { if (calls++ == 0) first.await() else second.await() }))
        s.attestation = AttestationRecord.preview
        val record = AttestationRecord.preview.copy(composeManifest = "image: nearaidev/cloud-api@sha256:${"a".repeat(64)}")
        s.verifyImageProvenance(record); yield()
        s.verifyImageProvenance(record); yield()   // cancels the first
        advanceUntilIdle()
        assertFalse("the second pass is still running", s.provenanceSettled)
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        second.complete(oneImageVerified); advanceUntilIdle()
        assertTrue(s.provenanceSettled)
    }

    /**
     * pm's ruling on the NVIDIA/TLS split (2026-11-06, honesty, no new words): a check that stopped on teemoon's own
     * fault never names an outage as the reason — the hero says the approved fault line, as the row does. An outage
     * keeps its own words.
     */
    @Test fun aTeemoonFaultIsNeverCalledAnOutage() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.gpuAttestation = NrasVerification.Inconclusive(NrasVerification.TEEMOON_FAULT)
        assertEquals(ConfidentialSession.VERIFIER_ERROR, s.e2eeDegradedReason)
        s.gpuAttestation = NrasVerification.Inconclusive("NVIDIA attestation service unreachable")
        assertEquals("nvidia gpu attestation couldn't be completed (service unreachable).", s.e2eeDegradedReason)
        s.gpuAttestation = NrasVerification.Verified
        s.tlsAttestation = TlsAttestation.Inconclusive(NrasVerification.TEEMOON_FAULT)
        assertEquals(ConfidentialSession.VERIFIER_ERROR, s.e2eeDegradedReason)
        s.tlsAttestation = TlsAttestation.Inconclusive("TLS attestation request failed")
        assertEquals("teemoon couldn't check the model node's tls certificate (unreachable).", s.e2eeDegradedReason)
    }

    /**
     * F2, found by iOS's TLA+ trust model (2026-11-06): a record with no GPU evidence after one with it cancelled the
     * NVIDIA check but kept its handle, so the cancelled job — which never answers — read as a verdict still pending,
     * and the chat said "verifying" forever.
     */
    @Test fun aRecordWithoutGpuEvidenceEndsTheNvidiaWait() = runTest {
        val nras = CompletableDeferred<NrasVerification>()
        val s = session(this, storeMatchingPreviewRecord(), ConfidentialSession.Services(skipLiveAttestation = true, verifyNras = { _, _ -> nras.await() }))
        s.attestation = AttestationRecord.preview
        s.verifyGpu(AttestationRecord.preview.copy(nvidiaPayload = "{\"evidence\":1}")); yield()
        assertTrue("the first record's check is running", s.verdictsPending)
        s.verifyGpu(AttestationRecord.preview.copy(nvidiaPayload = null)); advanceUntilIdle()
        assertFalse("a record with no GPU evidence still waits on NVIDIA", s.verdictsPending)
    }

    /**
     * Shipped 1.0.6 let this through (audit's F1 addendum, 2026-11-06): an unpublished-only image skipped the ask while the
     * binding ignored a model-recipe hash mismatch — sendPolicy ALLOW under a HARD_BLOCK chip. 1.1.0 must block it.
     */
    @Test fun anUnpublishedImageBesideARecipeHashMismatchBlocks() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
        assertTrue(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
    }

    /**
     * sentBeforeConfirmed, the trust side (reply-machine.json sentBeforeConfirmedResolution): a record a reply went out on
     * while its hard verdicts were pending reports how they settled — pass, hardFail, or unsettled for an outage — and a
     * held E2EE count is added only on a pass.
     */
    @Test fun aRecordSentOnWhilePendingSaysHowItSettled() = runTest {
        for ((verdict, want) in listOf<Pair<TlsAttestation, String>>(TlsAttestation.Verified to "pass", TlsAttestation.Failed("fingerprint mismatch") to "hardFail",
            TlsAttestation.Inconclusive("probe unreachable") to "unsettled")) {
            val tls = CompletableDeferred<TlsAttestation>()
            val s = session(this, storeMatchingPreviewRecord(), ConfidentialSession.Services(skipLiveAttestation = true, directBase = { "https://model.example" }, verifyTls = { tls.await() }))
            s.attestation = AttestationRecord.preview
            s.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", ""))
            s.gpuAttestation = NrasVerification.Verified
            s.verifyTls(Provider.nearAI.copy(id = AttestationRecord.preview.providerID, model = AttestationRecord.preview.model ?: Provider.nearAI.model)); yield()
            val settled = ArrayList<Pair<String, String>>(); s.onRecordSettled = { k, o -> settled += k to o }
            val mark = s.sentBeforeConfirmedMark(sealed = true)!!
            val before = s.e2eeMessageCount
            s.recordRequestOutcome(isE2EEActive = true, error = null)
            assertEquals("held while pending", before, s.e2eeMessageCount)
            tls.complete(verdict); advanceUntilIdle()
            assertEquals(listOf(mark.removePrefix("pending|") to want), settled)
            assertEquals("counted only on a pass", if (want == "pass") before + 2 else before, s.e2eeMessageCount)
        }
    }

    /** A transient outage of the GPU/TLS verifier degrades fail-closed but is NOT a hard break; a genuine negative verdict is. */
    @Test fun transientVerifierOutage_isSoft_genuineVerdictIsHard() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview

        s.gpuAttestation = NrasVerification.Inconclusive("NVIDIA attestation service unreachable")
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertFalse(s.degradeIsHardFailure)
        s.gpuAttestation = NrasVerification.Failed("NVIDIA attestation verdict: FAIL")
        assertTrue(s.degradeIsHardFailure)

        s.gpuAttestation = NrasVerification.Verified
        s.tlsAttestation = TlsAttestation.Inconclusive("TLS attestation request failed")
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertFalse(s.degradeIsHardFailure)
        s.tlsAttestation = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint")
        assertTrue(s.degradeIsHardFailure)
    }

    /** A SOFT degrade (provenance untraced, E2EE intact) is confirm-to-proceed — gated, but NOT a hard block. */
    @Test fun softDegrade_provenanceGap_isConfirmNotHardBlock() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), emptyList(), emptyList())
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertTrue(s.requiresE2EEConfirmation)
        assertFalse(s.degradeIsHardFailure)
        assertTrue(s.provenanceBlockedButE2EEIntact)
        assertFalse(s.unpublishedOnlyButE2EEIntact)
    }

    /** A clean GitHub 404 must not gate send or claim "unencrypted" when the key is bound — and must not read as fully verified. */
    @Test fun unpublishedImage_doesNotGateSealedSend() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        val fail = ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(fail), emptyList())
        assertTrue(s.unpublishedOnlyButE2EEIntact)
        assertEquals(AttestationState.DEGRADED, s.attestationState)   // not fully verified
        assertFalse(s.requiresE2EEConfirmation)                       // no modal
        assertFalse(s.degradeIsHardFailure)
        assertTrue(s.e2eeDegradedReason!!.contains("still sealed"))
    }

    /**
     * The audit's F1 pair has two guards, and each must hold alone (audit mutation pass on 05c07fa: either could be
     * deleted with every test green, because the other caught the same send). Guard one: a recipe-hash mismatch is never
     * an intact binding, whatever the provenance — so the unpublished-image exemption cannot start from it.
     */
    @Test fun aRecipeHashMismatchIsNeverAnIntactBinding_evenBesideAnUnpublishedImage() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
        assertFalse("a hash mismatch binds nothing", s.e2eeBindingIntact)
        assertFalse(s.unpublishedOnlyButE2EEIntact)
        assertTrue(s.degradeIsHardFailure)
        assertTrue(s.requiresE2EEConfirmation)
    }

    /**
     * Guard two: a hard failure the binding check does not read — plaintext already went out ([lastRequestUsedE2EE]
     * false) — still asks beside an unpublished image. The binding stays intact here, so only the gate's own
     * `!degradeIsHardFailure` clause stands between this state and an unasked send.
     */
    @Test fun aHardFailureTheBindingDoesNotSeeStillAsksBesideAnUnpublishedImage() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        s.lastRequestUsedE2EE = false
        assertTrue("the binding check does not read lastRequestUsedE2EE", s.unpublishedOnlyButE2EEIntact)
        assertTrue(s.degradeIsHardFailure)
        assertTrue("a hard failure asks, unpublished image or not", s.requiresE2EEConfirmation)
    }

    /**
     * iOS d3ca003b: with an unpublished-only image (which asks nothing at the gate) the hero read "encrypted — one image
     * unpublished" while DCAP was still running. The pending check comes first: verifying until the hard verdicts
     * answer, then the unpublished state as before.
     */
    @Test fun anUnpublishedImageStillWaitsForTheHardVerdicts() = runTest {
        val dcap = CompletableDeferred<RecordDcapVerification>()
        val s = session(this, storeMatchingPreviewRecord(), ConfidentialSession.Services(skipLiveAttestation = true, fetchModelLayerManifest = null, verifyDcap = { dcap.await() }))
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        s.verifyDcap(AttestationRecord.preview); yield()
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        dcap.complete(RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", ""))); advanceUntilIdle()
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertTrue(s.unpublishedOnlyButE2EEIntact)
    }

    /** A bad Sigstore / identity failure is not "unpublished" — still confirm. */
    @Test fun signatureInvalidProvenance_stillConfirms() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        val fail = ProvenanceFailure(ImageRef("nearaidev/compose-manager", "e".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.SignatureInvalid))
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(fail), emptyList())
        assertFalse(s.unpublishedOnlyButE2EEIntact)
        assertTrue(s.requiresE2EEConfirmation)
        assertEquals(AttestationState.DEGRADED, s.attestationState)
    }

    /** A reply-signature mismatch is loud in the run but must NOT falsely claim "sending paused" — it is not an attestation degrade. */
    @Test fun signatureMismatch_doesNotFalselyGateSending() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.mismatchedResponseCount = 1
        assertEquals(AttestationState.OK, s.attestationState)
        assertFalse(s.requiresE2EEConfirmation)
        assertFalse(s.degradeIsHardFailure)
    }

    /**
     * THE structural guarantee after three stale-display bugs: the session never
     * exposes a record fetched for a different model than the active one.
     */
    @Test fun attestationReadGate_blocksMismatchedModel() = runTest {
        val store = Store(nearProvider())
        val s = session(this, store)
        val rec = AttestationFixtures.clone(AttestationRecord.preview).copy(providerID = store.active!!.id, model = "zai-org/GLM-5.1-FP8")
        s.attestation = rec
        assertNotNull(s.attestation)
        // The user switches the model: the same stored record must vanish from every reader instantly — no refresh required.
        store.update(store.active!!.copy(model = "z-ai/glm-5.2"))
        assertNull(s.attestation)
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        // Switching back restores visibility (the record was for 5.1).
        store.update(store.active!!.copy(model = "zai-org/GLM-5.1-FP8"))
        assertNotNull(s.attestation)
    }

    @Test fun recordVerification_countsHonestly() = runTest {
        val s = session(this, Store(null))
        s.recordVerification(ResponseVerification.Verified("0xA"))
        s.recordVerification(ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.GatewayTrustOnly("0xB")))
        s.recordVerification(ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureMismatch("0xA", "0xC")))
        s.recordVerification(ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureUnavailable))
        assertEquals(1, s.verifiedResponseCount)
        assertEquals(1, s.gatewayTrustResponseCount)
        assertEquals(1, s.mismatchedResponseCount)
    }

    // MARK: Send-policy choke-point (E2EESecurityExploitTests)

    @Test fun verifiedSession_allows() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        assertEquals(AttestationState.OK, s.attestationState)
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicy)
    }

    @Test fun softDegrade_confirms() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), emptyList(), emptyList())
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
    }

    @Test fun hardFailure_blocks() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        s.attestation = AttestationRecord.preview
        s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
        assertTrue(s.degradeIsHardFailure)
    }

    /**
     * Hardening: a present-but-wrong-length Ed25519 key must degrade, never
     * read OK. Before the fix, a 31-byte key alongside a VERIFIED model quote
     * yielded a green chip: e2eeKeyBoundToModelTEE returns null (not false)
     * when the length is wrong, so no degrade gate fired.
     */
    @Test fun malformedE2EEKey_neverReadsVerified() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store)
        val provider = store.active!!
        val record = AttestationRecord(
            composeHash = "abc", mrtd = "def", osImageHash = "ghi", intelQuote = "",
            composeManifest = null, gpuArch = null, gpuNodeComposeHash = null, modelFileHash = null,
            signingAddress = null, gpuSigningAddress = null,
            modelEd25519PubKey = ByteArray(31) { 0xAB.toByte() },   // wrong length
            quoteVerification = null, gpuQuoteVerification = null,
            modelQuoteVerification = verification(ByteArray(64)),
            fetchedAt = Instant.now(), providerID = provider.id, model = provider.model)
        s.attestation = record
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertEquals("the model's encryption key is unavailable.", s.e2eeDegradedReason)
        assertNotEquals(TrustSendPolicy.ALLOW, s.sendPolicy)
    }

    // MARK: Local provider (LocalProviderTests)

    /** Register a local model and make it active: nothing to attest, so no record may be invented. */
    @Test fun registeringAndActivatingALocalProviderIsSafe() = runTest {
        val store = Store(null)
        val s = session(this, store, ConfidentialSession.Services(directBase = { null }))
        val provider = Provider.local(LocalModelCatalog.all[0])
        store.update(provider)
        s.refreshAttestation()
        advanceUntilIdle()
        assertTrue(store.active!!.isLocal)
        assertNull(s.attestation)
        assertEquals(AttestationState.NONE, s.attestationState)
    }

    // MARK: Android-only — the refresh path over the seams

    /** No AttestationService yet: a refresh for an attestable model FAILS CLOSED (degraded), never invents a record. */
    @Test fun refreshWithoutAFetcherFailsClosed() = runTest {
        val store = Store(nearProvider())
        val warnings = ArrayList<String>()
        // The seam defaults to AttestationService; a caller that nulls it must still fail closed, never adopt a record.
        val s = session(this, store, ConfidentialSession.Services(directBase = { "https://glm-5-1.completions.near.ai/v1" }, fetchRecord = null), warn = { warnings.add(it) })
        advanceUntilIdle()
        assertNull(s.attestation)
        assertTrue(s.attestationFetchFailed)
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertEquals("couldn't reach the attestation server.", s.e2eeDegradedReason)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertTrue(warnings.any { it.contains("not ported") })
    }

    /**
     * No direct host: the MODEL'S TIER decides, not the directory's mood.
     *
     * This used to ask `hasAuthoritativeDirectory()` — a cold miss failed closed,
     * and an authoritative "no host" read as an ordinary model. c93914c replaced
     * that: a cold directory, a failed fetch and a genuine gap in the published
     * map are all "couldn't check", and they are indistinguishable from the app's
     * side, so the question became what the model CLAIMS. Own fleet (and any id
     * the guess does not know) degrades, so the send asks; proxied and attested
     * third-party stay ordinary, because neither ever had an enclave.
     */
    @Test fun noDirectHostDegradesOwnFleetAndLeavesOtherTiersOrdinary() = runTest {
        assertNull(nearProvider("z-ai/glm-5.3-preview").directGPUNodeUrl)
        val own = Store(nearProvider("z-ai/glm-5.3-preview"))
        val sOwn = session(this, own, ConfidentialSession.Services(directBase = { null }))
        advanceUntilIdle()
        assertTrue(sOwn.attestationFetchFailed)
        assertFalse(sOwn.noConfidentialEndpoint)
        assertEquals(AttestationState.DEGRADED, sOwn.attestationState)

        // A proxied model never reaches that branch: `Provider.capabilities` grants
        // ATTESTATION only for TEE_OWN, so refreshAttestation returns before the
        // host lookup and the verdict is NONE by capability, not by outcome. The
        // ORDINARY arm exists for the narrow race where the live `owned_by` tier
        // loads mid-refresh and demotes a model that was capable a moment ago —
        // which is why the tier, not the directory, has to be what decides.
        val proxied = Store(nearProvider("anthropic/claude-sonnet-5"))
        val sProxied = session(this, proxied, ConfidentialSession.Services(directBase = { null }))
        advanceUntilIdle()
        assertFalse(ProviderCapability.ATTESTATION in proxied.active!!.capabilities)
        assertFalse(sProxied.attestationFetchFailed)
        assertEquals(AttestationState.NONE, sProxied.attestationState)
        assertEquals(ConfidentialSession.MissingHostOutcome.ORDINARY,
            ConfidentialSession.outcomeWhenNoDirectHost("anthropic/claude-sonnet-5"))
    }

    /** An adopted record fans out to the verifiers, and a record from a switched-away context is discarded. */
    @Test fun adoptedRecordRunsVerifiersAndStaleFetchIsDiscarded() = runTest {
        val store = Store(nearProvider())
        val preview = AttestationRecord.preview
        var dcapCalls = 0; var tlsCalls = 0
        val services = ConfidentialSession.Services(
            directBase = { "https://glm-5-1.completions.near.ai/v1" },
            fetchRecord = { _, _, model, providerID, _ -> preview.copy(providerID = providerID, model = model, nvidiaPayload = "{}") },
            verifyDcap = { dcapCalls++; RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", "")) },
            verifyNras = { _, _ -> NrasVerification.Verified },
            verifyTls = { tlsCalls++; TlsAttestation.Verified },
            verifyManifests = { oneImageVerified },
            fetchModelLayerManifest = null,   // the layer fetch is exercised by ProvenanceServiceTest; here the seam is absent
        )
        val s = session(this, store, services)
        advanceUntilIdle()
        assertNotNull(s.attestation)
        assertEquals(AttestationState.OK, s.attestationState)
        assertEquals(1, dcapCalls); assertEquals(1, tlsCalls)
        assertEquals(NrasVerification.Verified, s.gpuAttestation)
        assertEquals(TlsAttestation.Verified, s.tlsAttestation)
        assertTrue(s.imageProvenance is ManifestProvenance.AllVerified)

        // The user switches models while a fetch is in flight: the record that
        // comes back was fetched FOR 5.2 and must not show for 5.3 Flash — the read
        // gate refuses it even though no refresh has re-stamped the context yet.
        // (Both own fleet: z-ai/glm-5.3 is attested third-party since iOS 5b40a389.)
        var fetchedFor: String? = null
        store.update(nearProvider("z-ai/glm-5.2"))
        val slow = ConfidentialSession.Services(directBase = { "https://x/v1" }, fetchRecord = { _, _, model, providerID, _ ->
            fetchedFor = model; store.update(store.active!!.copy(model = "z-ai/glm-5.3-flash")); preview.copy(providerID = providerID, model = model)
        })
        val s2 = session(this, store, slow)
        advanceUntilIdle()
        assertEquals("z-ai/glm-5.2", fetchedFor)
        assertNull(s2.attestation)
        assertEquals(AttestationState.VERIFYING, s2.attestationState)
        // Switching back makes the 5.2 record visible again.
        store.update(store.active!!.copy(model = "z-ai/glm-5.2"))
        assertNotNull(s2.attestation)
    }

    /**
     * T4 (pm sweep 2026-11-06): the TLS check asked the directory a second time. A lookup that answered for the fetch
     * and not for the check wrote NotPerformed ("no direct host — not applicable"): the TLS section vanished and the
     * chip went green with the connection never checked. The check now probes the host the record was fetched from.
     */
    @Test fun theTlsCheckProbesTheHostTheRecordCameFrom() = runTest {
        val host = "https://glm-5-1.completions.near.ai/v1"
        var lookups = 0
        val probed = ArrayList<String>()
        val services = ConfidentialSession.Services(
            directBase = { if (lookups++ == 0) host else null },
            fetchRecord = { _, _, model, providerID, _ -> AttestationRecord.preview.copy(providerID = providerID, model = model) },
            verifyDcap = { RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", "")) },
            verifyTls = { probed += it; TlsAttestation.Verified },
            verifyManifests = { oneImageVerified },
            fetchModelLayerManifest = null,
        )
        val s = session(this, Store(nearProvider()), services)
        advanceUntilIdle()
        assertEquals(listOf(host), probed)
        assertEquals(TlsAttestation.Verified, s.tlsAttestation)
    }

    /** T4: a cancelled TLS job must not write NotPerformed over the next one's verdict. */
    @Test fun aCancelledTlsCheckWritesNothing() = runTest {
        val gate = CompletableDeferred<String?>()
        var first = true
        val services = ConfidentialSession.Services(skipLiveAttestation = true,
            // a lookup that does not check for cancellation (the directory's own fetch), so it returns after the cancel
            directBase = { if (first) { first = false; kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() } } else "https://x/v1" },
            verifyTls = { TlsAttestation.Verified })
        val store = storeMatchingPreviewRecord()
        val s = session(this, store, services)
        s.verifyTls(store.active!!)
        runCurrent()
        s.verifyTls(store.active!!)   // replaces the first, which is parked in its lookup
        advanceUntilIdle()
        assertEquals(TlsAttestation.Verified, s.tlsAttestation)
        gate.complete(null)           // the cancelled lookup returns "no host" late
        advanceUntilIdle()
        assertEquals(TlsAttestation.Verified, s.tlsAttestation)
    }

    /**
     * T3 (pm sweep 2026-11-06): a turn's outcome belongs to the place it was sent to. Switching mid-stream from A to B
     * gave B "E2EE used", its counts and its verified responses — evidence B never earned.
     */
    @Test fun aTurnFinishingAfterASwitchCreditsNothingToTheNewProvider() = runTest {
        val a = nearProvider()
        val store = Store(a)
        val s = session(this, store)
        val b = nearProvider("z-ai/glm-5.3")
        store.update(b)
        s.finishTurn(isE2EEActive = true, error = null, verification = ResponseVerification.Verified("0xA"), place = a)
        assertNull("B never sent anything", s.lastRequestUsedE2EE)
        assertEquals(0, s.e2eeMessageCount)
        assertEquals(0, s.verifiedResponseCount)
        // the same provider still selected: booked as before
        store.update(a)
        s.finishTurn(isE2EEActive = true, error = null, verification = ResponseVerification.Verified("0xA"), place = a)
        assertEquals(true, s.lastRequestUsedE2EE); assertEquals(1, s.verifiedResponseCount)
    }

    /** T3: prepareTurn for a place that is no longer selected opens nothing on the new one (and gives no context to seal with). */
    @Test fun prepareTurnForAPlaceNoLongerSelectedOpensNothing() = runTest {
        val store = storeMatchingPreviewRecord()
        val s = session(this, store)
        s.attestation = AttestationRecord.preview
        val sentTo = store.active!!
        store.update(Provider.nearAI.copy(id = "other", model = "z-ai/glm-5.3"))
        assertNull(s.prepareTurn(sentTo))
        assertNull("no request opened on the provider now selected", s.lastRequestUsedE2EE)
    }

    @Test fun requestOutcomeRecordsE2eeAndTheFailureReason() = runTest {
        val s = session(this, Store(null))
        s.beginRequest(expectingE2EE = true)
        assertEquals(true, s.lastRequestUsedE2EE)
        s.recordRequestOutcome(isE2EEActive = true, error = null)
        assertEquals(2, s.e2eeMessageCount)
        s.recordRequestOutcome(isE2EEActive = false, error = ConfidentialSession.RequestError(421, "rejected"))
        assertEquals("the server rejected the request (http 421).", s.lastE2EEFailReason)
        s.recordRequestOutcome(isE2EEActive = null, error = ConfidentialSession.RequestError(null, "network down"))
        assertEquals("network down", s.lastE2EEFailReason)
        s.finishTurn(isE2EEActive = true, error = null, verification = ResponseVerification.Verified("0xA"))
        assertEquals(4, s.e2eeMessageCount); assertEquals(1, s.verifiedResponseCount)
    }

    // MARK: - No direct host (c93914c)

    /**
     * The behaviour that mattered in c93914c. A model with no direct host used
     * to read as NONE — "ordinary model, nothing to verify" — as long as the
     * directory had any data at all. For an OWN-FLEET model that is wrong in
     * the unsafe direction: the row says end-to-end encrypted and the send goes
     * out in the clear with no prompt.
     */
    @Test fun anOwnFleetModelWithNoHostDegrades() {
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN,
            NearAIModelCatalog.confidentiality("z-ai/glm-5.3-flash"))
        assertEquals(ConfidentialSession.MissingHostOutcome.DEGRADED,
            ConfidentialSession.outcomeWhenNoDirectHost("z-ai/glm-5.3-flash"))
    }

    /**
     * An id the guess has never seen also classifies own-fleet, so it degrades
     * too. That is the fail-closed direction: the send asks.
     */
    @Test fun anUnknownIdDegrades() {
        assertEquals(ConfidentialSession.MissingHostOutcome.DEGRADED,
            ConfidentialSession.outcomeWhenNoDirectHost("z-ai/glm-6-preview"))
    }

    /**
     * Tiers that never had a confidential endpoint stay ordinary: no lock, no
     * spinner, no block.
     */
    @Test fun proxiedAndThirdPartyModelsAreOrdinary() {
        assertEquals(ConfidentialSession.MissingHostOutcome.ORDINARY,
            ConfidentialSession.outcomeWhenNoDirectHost("anthropic/claude-sonnet-5"))
        assertEquals(ConfidentialSession.MissingHostOutcome.ORDINARY,
            ConfidentialSession.outcomeWhenNoDirectHost("moonshotai/kimi-k3"))
    }

    // --- 1.1.1 audit cut review item 3 (iOS RunningPairBudgetTests, 9d21baba) ---

    private fun sha256Hex(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun composeBodyAt(path: String) = "# $path\nservices:\n  svc:\n    image: nearaidev/x@sha256:abc\n".toByteArray()

    /** Every (commit, file) the log names served, its pinned hash rewritten to match: the healthy cold run (iOS `served`). */
    private fun served(actions: List<kotlinx.serialization.json.JsonObject>): List<kotlinx.serialization.json.JsonObject> = actions.map { a ->
        val commit = a["commit"]?.jsonPrimitive?.content; val file = a["file"]?.jsonPrimitive?.content
        if (commit == null || file == null || a["file_sha256"] == null) a
        else kotlinx.serialization.json.JsonObject(a + ("file_sha256" to kotlinx.serialization.json.JsonPrimitive(sha256Hex(composeBodyAt(ComposeFiles.url(commit, file).substringAfter("githubusercontent.com"))))))
    }

    /**
     * The live list's entry [i] with its compose manager's action log replaced by [actions], the manager quote's report data re-signed over
     * them (bytes 0..32 = SHA-256 of the canonical actions; 32..64 still the list's nonce): (a) binds it as it binds a live log, so the
     * session builds this listed enclave's scope itself (ModelCandidates.trustScopes). DCAP is stubbed, so the quote's signature is never read.
     */
    private fun entryWithLog(c: LiveCandidates.Capture, i: Int, actions: List<kotlinx.serialization.json.JsonObject>): kotlinx.serialization.json.JsonObject {
        val entry = c.list[i]
        val cm = entry.getValue("compose_manager_attestation").jsonObject
        val arr = kotlinx.serialization.json.JsonArray(actions)
        val quoteHex = cm.getValue("quote").jsonPrimitive.content
        val quote = quoteHex.removePrefix("0x").hexToBytes()
        val reportDataAt = QuoteParser.HEADER_SIZE + 520   // TD report body: 520 bytes of measurements before its 64-byte report data
        java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encode(arr).toByteArray(Charsets.UTF_8)).copyInto(quote, reportDataAt)
        val resigned = (if (quoteHex.startsWith("0x")) "0x" else "") + quote.toHex()
        val edited = kotlinx.serialization.json.JsonObject(cm + mapOf("actions" to arr, "quote" to kotlinx.serialization.json.JsonPrimitive(resigned)) - "actions_hash")
        return kotlinx.serialization.json.JsonObject(entry + ("compose_manager_attestation" to edited))
    }

    private fun runUp(i: Int) = kotlinx.serialization.json.buildJsonObject {
        put("action", kotlinx.serialization.json.JsonPrimitive("compose_up")); put("commit", kotlinx.serialization.json.JsonPrimitive("c$i"))
        put("file", kotlinx.serialization.json.JsonPrimitive("prod/f$i.yaml")); put("file_sha256", kotlinx.serialization.json.JsonPrimitive("x"))
        put("timestamp", kotlinx.serialization.json.JsonPrimitive("2026-11-20T%05d".format(i)))
    }

    private fun liveLogs(): List<List<kotlinx.serialization.json.JsonObject>> =
        kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResource("/fixtures/nearai-trust/nearai_live_actionlogs_20261120.json")!!.readText())
            .jsonObject.getValue("logs").jsonArray.map { log -> log.jsonArray.map { it.jsonObject } }

    /** Runs the (c) fetch over [logs] (one listed enclave each); the URL paths asked and the scopes it ends with. */
    private suspend fun kotlinx.coroutines.test.TestScope.fetchLogs(logs: List<List<kotlinx.serialization.json.JsonObject>>, status: Int): Pair<List<String>, List<NearTrustScope>> {
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val bodies = HashMap<String, ByteArray>()
        logs.flatten().forEach { a ->
            val c = a["commit"]?.jsonPrimitive?.content; val f = a["file"]?.jsonPrimitive?.content
            if (c != null && f != null) ComposeFiles.url(c, f).substringAfter("githubusercontent.com").let { bodies[it] = composeBodyAt(it) }
        }
        ProvenanceService.default = ProvenanceService(http = { url, _ ->
            val path = java.net.URI(url).path; asked += path
            if (status == 200) ProvenanceResponse(if (path in bodies) 200 else 404, emptyMap(), bodies[path] ?: ByteArray(0)) else ProvenanceResponse(status, emptyMap(), ByteArray(0))
        }, cache = InMemoryProvenanceCacheStore(), upgradeAttempted = HashSet(), sleepMs = {}, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        // The listed scopes come from a real record: one live list entry per log, each entry's manager log replaced by it (no test seam in the gate).
        val c = LiveCandidates.load()
        require(logs.size <= c.list.size) { "the live list has ${c.list.size} entries" }
        val listed = ListedNearScopeTest.listed(logs.mapIndexed { i, log -> entryWithLog(c, i, log) }, c.nonce, c.key)
        val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")
        val verdict = RecordDcapVerification(manager = ok, listed = List(logs.size) { ok }, listedManagers = List(logs.size) { ok })
        val s = session(this, storeMatchingPreviewRecord(), services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { verdict }))
        val record = AttestationRecord.preview.copy(modelCandidates = listed)
        s.attestation = record
        s.verifyDcap(record)
        advanceUntilIdle()
        val scopes = s.listedNearScopes.orEmpty()
        assertEquals("every log is a listed enclave the session bound", List(logs.size) { DeployLogBinding.Bound }, scopes.map { it.deployLogBinding })
        return asked.toList() to scopes
    }

    /** A log naming far more files than the budget: it stops at the budget, and nothing past it reads as checked. */
    @Test fun aRunawayLogStopsAtTheBudgetAsCouldntCheck() = runTest {
        val (asked, scopes) = fetchLogs(listOf(served((0 until ComposeFiles.FETCH_BUDGET * 5).map(::runUp))), 200)
        assertEquals("one log made ${asked.size} GitHub fetches", ComposeFiles.FETCH_BUDGET, asked.size)
        val unread = scopes.single().composeFiles.filter { ComposeFiles.url(it.commit, it.file).substringAfter("githubusercontent.com") !in asked }
        assertTrue(unread.isNotEmpty())
        for (p in unread) assertTrue("an unread file read as ${p.check}", p.check == ComposeFileCheck.Inconclusive || p.check == ComposeFileCheck.NotFetched)
    }

    /** Each listed enclave's log has its own budget: two runaway logs make two budgets' worth, not one shared and not unbounded. */
    @Test fun eachLogHasItsOwnBudget() = runTest {
        val log = { tag: String -> served((0 until ComposeFiles.FETCH_BUDGET * 2).map { i -> kotlinx.serialization.json.JsonObject(runUp(i) + ("commit" to kotlinx.serialization.json.JsonPrimitive("$tag$i"))) }) }
        val (asked, _) = fetchLogs(listOf(log("a"), log("b")), 200)
        assertEquals(2 * ComposeFiles.FETCH_BUDGET, asked.size)
    }

    /** The 2026-11-20 capture's three logs, cold, every file served and matching: each fits with room to spare. Also all 404 and all 429: the worst a real log costs. */
    @Test fun theLiveCaptureLogsFit() = runTest {
        for (status in listOf(200, 404, 429)) for ((i, log) in liveLogs().withIndex()) {
            val (asked, scopes) = fetchLogs(listOf(served(log)), status)
            println("budget-measure status=$status log=$i actions=${log.size} fetches=${asked.size} distinct=${asked.toSet().size}")
            assertTrue("log $i made ${asked.size} fetches at $status", asked.size < ComposeFiles.FETCH_BUDGET)
            if (status == 200) {
                assertTrue("the budget is about twice a healthy cold run", asked.size * 2 <= ComposeFiles.FETCH_BUDGET + 2)
                assertFalse("a healthy live log ran out of budget", scopes.single().composeFiles.any { it.check == ComposeFileCheck.Inconclusive })
            }
        }
    }
}
