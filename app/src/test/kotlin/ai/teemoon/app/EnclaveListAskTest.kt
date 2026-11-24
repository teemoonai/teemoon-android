// ports: teemoonTests/EnclaveListAskTests.swift @ 997bc7bf (A8 body says retry verification)
// ports: teemoonTests/EnclaveListAskTests.swift @ beb43b71 (the ask selection and its copy; the wire half is confidential's EnclaveListReverifyTest)
//
// Audit 1.1.1 A8 (card trust-copy-sweep-111 v4): when near.ai's list of enclaves failed and nothing is in flight, the send
// asks to re-verify the list — not "still checking … wait", which waited on nothing.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.confidential.ManifestProvenance
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.RecordDcapVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.confidential.TrustSendPolicy
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnclaveListAskTest {
    @After fun down() { NearAIModelCatalog.tierListSeenOverride = null; NearAIModelCatalog.resetTierCache() }

    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    /** The ask exactly as TrustPresentation builds it, with no settle-timeout ask up. */
    private fun ask(s: ConfidentialSession) = E2EEAsk.make(hardFailure = s.degradeIsHardFailure, pendingAsk = null,
        provenanceUnchecked = s.provenanceUncheckedAsks, softButSealed = s.softButSealed, provenanceBlocked = s.provenanceBlockedButE2EEIntact,
        cause = s.e2eeDegradedReason, tierGaveUp = s.tierGaveUp, enclaveListFailed = s.enclaveListFailedAsks)

    /** A verified session on a model of its own whose near.ai tier is known (an unknown tier is itself a check in flight), the list [listFailed]. */
    private fun TestScope.session(listFailed: Boolean = true): ConfidentialSession {
        val model = "zai-org/A8-LIST-PROBE"
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val record = AttestationRecord.preview.copy(model = model, modelCandidates = null, modelCandidatesListFailed = listFailed)
        val near = Provider.nearAI.copy(id = record.providerID, model = model)
        val s = ConfidentialSession(this, { near }, credentialFor = { "" },
            services = ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true, now = { record.fetchedAt }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record
        s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList())
        s.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate)
        s.gpuAttestation = NrasVerification.Verified
        s.tlsAttestation = TlsAttestation.Verified
        runCurrent()
        return s
    }

    @Test fun aFailedListAsksToReverifyWithTheExistingButtons() = runTest {
        val s = session()
        assertFalse(s.verdictsPending)
        assertEquals("teemoon couldn't load near.ai's list of enclaves for this model.", s.e2eeDegradedReason)
        assertTrue("sealed: ${s.softButSealed}", s.enclaveListFailedAsks)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        val ask = ask(s)
        assertEquals("couldn't load near.ai's list of enclaves", ask.title)
        assertEquals("your message would still be sealed to the model's key, but teemoon couldn't check the other enclaves near.ai lists for this model, which hold the same key. retry verification to ask near.ai for the list again.", ask.message)
        assertEquals(listOf(E2EESendChoice.RetryVerification, E2EESendChoice.SendSealedUnconfirmed, E2EESendChoice.DontSend), ask.choices)
        assertEquals(listOf("retry verification", "send anyway", "don't send"), ask.choices.map { it.label })
    }

    /** Something still in flight, or a worse soft cause named first: the existing asks, unchanged. */
    @Test fun otherwiseTheExistingAsksStand() = runTest {
        // an NVIDIA check that has not answered: the verdict is pending, so there is something to wait for
        val gate = CompletableDeferred<NrasVerification>()
        val held = ConfidentialSession(this, { Provider.nearAI.copy(id = AttestationRecord.preview.providerID, model = "zai-org/A8-LIST-PROBE") }, credentialFor = { "" },
            services = ConfidentialSession.Services(tierDeadlineMs = null, skipLiveAttestation = true, verifyNras = { _, _ -> gate.await() }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        NearAIModelCatalog.recordTiers(mapOf("zai-org/A8-LIST-PROBE" to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val rec = AttestationRecord.preview.copy(model = "zai-org/A8-LIST-PROBE", nvidiaPayload = "{}", modelCandidatesListFailed = true)
        held.attestation = rec; held.lastRequestUsedE2EE = true
        held.imageProvenance = ManifestProvenance.AllVerified(emptyList(), emptyList())
        held.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate); held.tlsAttestation = TlsAttestation.Verified
        held.verifyGpu(rec); runCurrent()
        assertTrue(held.verdictsPending)
        assertFalse(held.enclaveListFailedAsks)
        assertEquals(E2EESendChoice.STILL_CHECKING_TITLE, ask(held).title)
        gate.complete(NrasVerification.Verified); runCurrent()

        val intel = session()
        intel.dcapVerification = RecordDcapVerification(gateway = upToDate, model = DcapVerification.Unchecked("collateral: pcs unreachable"))
        assertFalse(intel.enclaveListFailedAsks)
        assertEquals(E2EESendChoice.STILL_CHECKING_TITLE, ask(intel).title)

        val clean = session(listFailed = false)
        assertFalse(clean.enclaveListFailedAsks)
    }

    /** A hard verdict is never softened into a list ask, and a settle-timeout ask still names what was pending. */
    @Test fun theNewAskNeverOutranksAHardBreakOrAPendingAsk() {
        val soft = E2EEAsk.make(hardFailure = true, pendingAsk = null, provenanceUnchecked = false, softButSealed = false, provenanceBlocked = false,
            cause = "x", enclaveListFailed = true)
        assertEquals("verification failed — sending blocked", soft.title)
        val pendingAsk = E2EEAsk.make(hardFailure = false, pendingAsk = ConfidentialSession.PendingKind.HARDWARE, provenanceUnchecked = false,
            softButSealed = true, provenanceBlocked = false, cause = null, enclaveListFailed = true)
        assertEquals(E2EESendChoice.STILL_CHECKING_TITLE, pendingAsk.title)
    }

    /** iOS e9e2e747: the expert "bound" node and the everyday sealed row follow the listed state, so no green mark beside "couldn't load near.ai's list". */
    @Test fun theSealedAndBoundMarksFollowAFailedList() = runTest {
        val failed = session()
        val near = Provider.nearAI.copy(id = AttestationRecord.preview.providerID, model = "zai-org/A8-LIST-PROBE")
        val snap = trustSnapshot(failed, near)
        assertEquals(ai.teemoon.confidential.TrustClaim.Status.ALERT, snap.verdict.everydayClaims.first { it.id == "sealed" }.status)
        assertEquals(ChainNode.Status.ALERT, snap.expertNodes.first { it.id == "bound" }.status)
    }
}
