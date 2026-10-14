// ports: teemoonTests/AppStateTests.swift @ v1.0.4 (the "ConfidentialSession" suite)
//        teemoonTests/E2EESecurityExploitTests.swift @ v1.0.1 (the "Send-policy choke-point" suite)
//        teemoonTests/LocalProviderTests.swift @ v1.0.1 (registeringAndActivatingALocalProviderIsSafe)
//        teemoonTests/AppStateTests.swift @ v1.0.4 (prepareTurnWaitsForAnInFlightVerdictBeforeTheGateIsRead,
//        prepareTurnReportsAVerdictStillPendingAtTheDeadline, aRefusalBeforeTheWireDoesNotReadAsAnUnsealedRequest —
//        their ChatViewModel.postPrepareRefusal halves are ChatViewModelTest's, the function lives in app/)
//        teemoonTests/AppStateTests.swift @ v1.0.4 (aPlainTLSProviderNeverWaitsOnAnotherSessionsVerifiers,
//        aSwitchDropsCancelledVerifierHandles)
//        teemoonTests/AppStateTests.swift @ v1.0.4 (MissingDirectHostReasonTests: theReasonNamesTheDirectoryNotTheServer,
//        aFailedFetchIsRetriedAfterTheInterval)
//        ConfidentialSession+Verifiers.swift @ v1.0.4 has no iOS test for the kept verdict; aSealedSessionIsVerifyingUntilProvenanceSettles pins it
//        NOT ported: liveFetchSkipsPreviewAndDesignTourNotOrdinaryLaunches (Xcode process flags),
//        canSeal_tracksTheDerivedPeer (TEEContext / E2EEPeer — the E2EE row), chatViewModelDelegatesToTheSession
//        (the app's ViewModel is a human row).
package ai.teemoon.confidential

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

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
        // The session's provenance seams default to the real service; no test here may reach GitHub.
        ProvenanceService.default = ProvenanceService(http = { _, _ -> throw IOException("offline test") }, upgradeAttempted = HashSet(), sleepMs = {})
    }

    /** No fetch: the state tests read verdicts over seeded records, as the iOS suite does. */
    private val offlineServices = ConfidentialSession.Services(skipLiveAttestation = true)

    private fun session(scope: CoroutineScope, store: Store, services: ConfidentialSession.Services = offlineServices, warn: (String) -> Unit = {}) =
        ConfidentialSession(scope, store.source, credentialFor = { "" }, services = services, warn = warn)

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
        assertEquals("Could not reach the attestation server.", s.e2eeDegradedReason)
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

    /** Every mutation bumps `changes`, so a collector re-reads the snapshot exactly when the state moved — no polling. */
    @Test fun changes_bumpOnEveryMutation() = runTest {
        val s = session(this, storeMatchingPreviewRecord())
        val before = s.changes.value
        s.attestation = AttestationRecord.preview; assertEquals(before + 1, s.changes.value)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.NotPerformed; assertEquals(before + 3, s.changes.value)
        s.beginRequest(expectingE2EE = true); assertTrue(s.changes.value >= before + 4)
        s.finishTurn(isE2EEActive = true, error = null, verification = ResponseVerification.Verified("0xabc")); assertTrue(s.changes.value >= before + 6)
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

        answer.complete(ManifestProvenance.AllVerified(emptyList(), emptyList()))
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
        second.complete(ManifestProvenance.AllVerified(emptyList(), emptyList())); advanceUntilIdle()
        assertTrue(s.provenanceSettled)
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
        assertEquals("The model's encryption key is unavailable.", s.e2eeDegradedReason)
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
        assertEquals("Could not reach the attestation server.", s.e2eeDegradedReason)
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
            verifyManifests = { ManifestProvenance.AllVerified(emptyList(), emptyList()) },
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
        // comes back was fetched FOR 5.2 and must not show for 5.3 — the read
        // gate refuses it even though no refresh has re-stamped the context yet.
        var fetchedFor: String? = null
        store.update(nearProvider("z-ai/glm-5.2"))
        val slow = ConfidentialSession.Services(directBase = { "https://x/v1" }, fetchRecord = { _, _, model, providerID, _ ->
            fetchedFor = model; store.update(store.active!!.copy(model = "z-ai/glm-5.3")); preview.copy(providerID = providerID, model = model)
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

    @Test fun requestOutcomeRecordsE2eeAndTheFailureReason() = runTest {
        val s = session(this, Store(null))
        s.beginRequest(expectingE2EE = true)
        assertEquals(true, s.lastRequestUsedE2EE)
        s.recordRequestOutcome(isE2EEActive = true, error = null)
        assertEquals(2, s.e2eeMessageCount)
        s.recordRequestOutcome(isE2EEActive = false, error = ConfidentialSession.RequestError(421, "rejected"))
        assertEquals("Server rejected request (HTTP 421)", s.lastE2EEFailReason)
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
}
