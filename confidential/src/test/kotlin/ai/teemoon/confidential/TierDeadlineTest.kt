// Verification always ends in a verdict, never "verifying" forever (pm for the maintainer, 2026-11-14; iOS found the same root cause,
// a 1.1.0 regression: near.ai's tier list never loaded and the verdict waited on it). The near.ai tier was the one
// pending input with no deadline: a z-ai/ id reads UNKNOWN until /v1/models has answered once, nothing asked again for
// 24 h after a failure, and a late answer did not reach the screen. Now:
//  - past TIER_DEADLINE_MS (20 s, the maintainer "Yes") an unknown tier settles to the soft degrade, where the send asks;
//  - the tier list is asked for again, keyless, with each attestation while it has never been seen;
//  - a tier list that arrives touches the session, so the screen reads the new state;
//  - reading canSeal does not draw a new E2EE key each time (it did on every recomposition).
package ai.teemoon.confidential

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class TierDeadlineTest {
    @Before fun offline() {
        NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }); NearAIModelCatalog.tierListSeenOverride = false
        ProvenanceService.default = ProvenanceService(http = { _, _ -> throw java.io.IOException("offline") }, sleepMs = {})
    }
    @After fun clean() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.tierListSeenOverride = null }

    private val model = "zai-org/TIER-DEADLINE-PROBE"
    private val record = AttestationRecord.preview.copy(model = model)
    private val near = Provider.nearAI.copy(id = record.providerID, model = model)
    private val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    /** Every verdict in; only the tier unknown. The clock is the test's virtual time from the record's own fetch. */
    private fun TestScope.session(services: (now: () -> Instant) -> ConfidentialSession.Services = { now -> ConfidentialSession.Services(skipLiveAttestation = true, now = now) }): ConfidentialSession {
        val now = { record.fetchedAt.plusMillis(testScheduler.currentTime) }
        val s = ConfidentialSession(backgroundScope, { near }, credentialFor = { "" }, services = services(now))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        s.dcapVerification = RecordDcapVerification(gateway = ok, model = ok)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        s.nearScopeOverride = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked)
        return s
    }

    @Test fun anUnknownTierSettlesToTheSoftDegradeAtTheDeadline() = runTest {
        val s = session()
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        testScheduler.advanceTimeBy(ConfidentialSession.TIER_DEADLINE_MS - 1_000); testScheduler.runCurrent()
        assertEquals("before the deadline the tier still holds it", AttestationState.VERIFYING, s.attestationState)
        val seen = s.changes.value
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertTrue("the deadline touches the session, or the screen never re-reads it", s.changes.value > seen)
        assertEquals("past the deadline: a verdict", AttestationState.DEGRADED, s.attestationState)
        assertFalse("the soft one: the send asks, it is not blocked", s.degradeIsHardFailure)
        assertFalse("the tier no longer holds the send as a verdict still running", s.verdictsPending)
        // iOS VerdictDeadlineTests @ 622e3619: out of the pending ask, and the send asks through the soft degrade
        assertEquals(null, s.pendingKind)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals(ConfidentialSession.TIER_REASON, s.e2eeDegradedReason)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * A given-up tier ends the unpublished-image exemption (TLC: a given-up tier beside an unpublished image was waved
     * through; iOS CouldntCheckAsks). The binding is intact and the degrade soft, so `!tierGaveUp` in
     * [ConfidentialSession.unpublishedOnlyButE2EEIntact] is the only thing that makes this send ask (audit mutation pass
     * on 05c07fa: removing it survived every test).
     */
    @Test fun aGivenUpTierBesideAnUnpublishedImageStillAsks() = runTest {
        val s = session()
        s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)),
            ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
        testScheduler.advanceTimeBy(ConfidentialSession.TIER_DEADLINE_MS + 100); testScheduler.runCurrent()
        assertTrue(s.tierGaveUp)
        assertTrue("the binding itself is intact", s.e2eeBindingIntact)
        assertFalse("the soft degrade, not a hard one", s.degradeIsHardFailure)
        assertFalse("a given-up tier is not covered by the unpublished-image exemption", s.unpublishedOnlyButE2EEIntact)
        assertTrue(s.requiresE2EEConfirmation)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        s.clearDerivedState(resetCounts = true)
    }

    /** A list arriving after the give-up goes degraded → verified in one step, never back through "verifying…" (pm 2026-11-14). */
    @Test fun aListAfterTheGiveUpGoesStraightToVerified() = runTest {
        val s = session()
        testScheduler.advanceTimeBy(ConfidentialSession.TIER_DEADLINE_MS + 100); testScheduler.runCurrent()
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        val seen = mutableListOf<AttestationState>()
        val watch = backgroundScope.launch { s.changes.collect { seen += s.attestationState } }
        testScheduler.runCurrent()
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN)); testScheduler.runCurrent()
        assertEquals(AttestationState.OK, s.attestationState)
        assertFalse("no flash back to verifying: $seen", AttestationState.VERIFYING in seen)
        watch.cancel(); s.clearDerivedState(resetCounts = true)
    }

    /** The tier reason ranks LAST in the sheet's hero: a missing key (or a GPU/TLS hard failure) names itself first (pm). */
    @Test fun theTierReasonRanksLast() = runTest {
        val s = session()
        testScheduler.advanceTimeBy(ConfidentialSession.TIER_DEADLINE_MS + 100); testScheduler.runCurrent()
        s.attestation = record.copy(modelEd25519PubKey = null); testScheduler.runCurrent()
        assertEquals("the model's encryption key is unavailable.", s.e2eeDegradedReason)
        s.clearDerivedState(resetCounts = true)
    }

    @Test fun aTierListArrivingLateTouchesTheSessionAndTheVerdictReadsIt() = runTest {
        val s = session()
        val seen = s.changes.value
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN)); testScheduler.runCurrent()
        assertTrue("recordTiers touches the session", s.changes.value > seen)
        assertEquals(AttestationState.OK, s.attestationState)
        s.clearDerivedState(resetCounts = true)
    }

    @Test fun theTierListIsAskedForAgainWithEachAttestationWhileUnseen() = runTest {
        var asked = 0
        val s = ConfidentialSession(backgroundScope, { near }, credentialFor = { "" }, services = ConfidentialSession.Services(
            directBase = { "https://model.example" }, fetchRecord = { _, _, _, _, _ -> record }, fetchModelLayerManifest = null, verifyManifests = null,
            now = { record.fetchedAt.plusMillis(testScheduler.currentTime) }, fetchTiers = { asked++; false }))
        // iOS VerdictDeadline: a first try and two retries (2 s, 4 s), keyless
        s.refreshAttestation(); testScheduler.advanceTimeBy(7_000); testScheduler.runCurrent()
        assertEquals("asked with the first attestation, and retried twice", 3, asked)
        s.refreshAttestation(); testScheduler.advanceTimeBy(7_000); testScheduler.runCurrent()
        assertEquals("and again with the next, while it has never been seen", 6, asked)
        NearAIModelCatalog.tierListSeenOverride = true
        s.refreshAttestation(); testScheduler.advanceTimeBy(7_000); testScheduler.runCurrent()
        assertEquals("not once it has been seen", 6, asked)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * canSeal answers without making a peer (each one draws a random client key, and the screen asks on every
     * recomposition): it must still agree with whether the send could make one, for a usable key and for one the
     * conversion refuses.
     */
    @Test fun canSealAgreesWithWhetherASendCouldMakeAPeer() = runTest {
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val s = session()
        val key = record.modelEd25519PubKey!!
        assertEquals(runCatching { E2EEPeer(key) }.isSuccess, s.canSeal)
        assertTrue("the preview record's key is usable", s.canSeal)
        assertEquals(s.canSeal, s.currentTeeContext()?.e2eePeer != null)
        val bad = ByteArray(32) { 0xFF.toByte() }
        s.attestation = record.copy(modelEd25519PubKey = bad)
        assertEquals("the same answer as the constructor's conversion", runCatching { E2EEPeer(bad) }.isSuccess, s.canSeal)
        s.clearDerivedState(resetCounts = true)
    }

    /**
     * The bound: every other pending input answers by its own deadline (here the slowest the production fetchers allow,
     * TLS at 20 s — DCAP 30 s and NRAS 60 s run in the same way), and the tier never does. By the latest of those plus a
     * frame the state is a verdict, not VERIFYING.
     */
    @Test fun everyPendingInputEndsInAVerdictWithinABound() = runTest {
        val s = session { now -> ConfidentialSession.Services(skipLiveAttestation = true, now = now, directBase = { "https://model.example" },
            verifyTls = { delay(20_000); TlsAttestation.Verified }) }
        s.tlsAttestation = null; s.verifyTls(near); testScheduler.runCurrent()
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        testScheduler.advanceTimeBy(maxOf(20_000L, ConfidentialSession.TIER_DEADLINE_MS) + 100); testScheduler.runCurrent()
        assertNotEquals("a verdict within the bound", AttestationState.VERIFYING, s.attestationState)
        s.clearDerivedState(resetCounts = true)
    }
}
