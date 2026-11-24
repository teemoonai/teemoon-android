// ports: teemoonTests/TierDowngradeTests.swift @ 6c364e33 (the context carries SealedModels only for near.ai, so the chat, a retry and every
//        other send that goes prepareTurn -> currentTeeContext arm alike; Android has no Shortcuts intent path)
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class SealedModelsContextTest {
    private fun session(provider: Provider, sealed: SealedModels): ConfidentialSession {
        NearAIModelCatalog.resetTierCache()
        val record = AttestationRecord.preview.copy(providerID = provider.id, model = provider.model)
        return ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { provider }, credentialFor = { "k" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt }), sealedModels = sealed)
            .also { it.attestation = record }
    }

    @Test fun nearAIsContextCarriesTheSessionsStore() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        val ctx = session(Provider.nearAI, sealed).currentTeeContext()
        assertNotNull("precondition: an attested near.ai context", ctx)
        assertSame(sealed, ctx!!.sealedModels)
    }
}
