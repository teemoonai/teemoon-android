// ports: teemoonTests/EnclaveListAskTests.swift @ beb43b71 (reverifyAsksNearAIForTheListAgain)
//
// Audit 1.1.1 A8: re-verify (AppState.reverify = refreshAttestation(keepExisting = true), what "retry verification" runs)
// fetches the record again, and the record fetch asks near.ai for the list again: counted on the wire.
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnclaveListReverifyTest {
    @After fun down() { NearAIModelCatalog.tierListSeenOverride = null; NearAIModelCatalog.resetTierCache() }

    @Test fun reverifyAsksNearAIForTheListAgain() = runTest {
        val live = LiveCandidates.load()
        val base = "https://cloud-api.near.ai/v1"
        val record0 = AttestationRecord.preview
        val listRequests = ArrayList<String>()
        // the list's own request, counted; the first answer is a 404 (an outage that is final: no retries), the second the live list
        val http: AttestationHttp = { url, _ ->
            synchronized(listRequests) { listRequests += url }
            if (listRequests.size == 1) 404 to ByteArray(0) else 200 to live.body
        }
        val near = Provider.nearAI.copy(id = record0.providerID, model = record0.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(this, { near }, credentialFor = { "" }, services = ConfidentialSession.Services(
            directBase = { "https://model.example" }, verifyManifests = null, fetchModelLayerManifest = null, verifyDcap = null, verifyNras = null, verifyTls = null,
            // AttestationService.fetch's list step, through the same calls
            fetchRecord = { b, key, model, _, _ ->
                var record = record0
                val sealed = record.modelEd25519PubKey ?: ByteArray(32)
                val adopted = AttestationService.fetchCandidateList(b, model, key, http)?.let { (body, nonce) -> AttestationService.adoptCandidates(body, nonce, sealed) }
                record = record.copy(modelCandidates = adopted, modelCandidatesListFailed = adopted == null)
                record
            }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.refreshAttestation(keepExisting = true); advanceUntilIdle()
        assertTrue("the first fetch's list failed", s.attestation?.modelCandidatesListFailed == true)
        assertEquals(1, listRequests.size)

        s.refreshAttestation(keepExisting = true); advanceUntilIdle()
        assertFalse("the second fetch loaded the list", s.attestation?.modelCandidatesListFailed == true)
        assertEquals("re-verify did not ask for the list again", 2, listRequests.size)
    }
}
