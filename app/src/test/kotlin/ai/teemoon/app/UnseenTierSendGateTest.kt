// ports: teemoonTests/NearAIModelCatalogTests.swift @ cdcae74b (anUnseenOwnFleetIDStillGoesThroughTheAttestedGate)
//
// pm SECURITY 2026-10-31 (iOS found it on a fresh install; near-import only, 3917 asked): with no saved tier list an
// own-fleet near.ai id read UNKNOWN, got no attestation capability, and the send went to cloud-api.near.ai as plain
// TLS with no E2EE question. Not knowing the tier must not mean "nothing to encrypt": the send is attested or the gate
// asks, and no screen claims e2ee until near.ai vouches. Starts with NO saved list.
package ai.teemoon.app

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnseenTierSendGateTest {
    @After fun follow() { NearAIModelCatalog.tierListSeenOverride = null }

    @Test fun beforeTheTierListASendIsAttestedOrAsked() {
        NearAIModelCatalog.tierListSeenOverride = false
        for (id in listOf("z-ai/glm-5.2", "z-ai/glm-5.3-flash", "deepseek-ai/DeepSeek-V9")) {
            val p = Provider.nearAI.copy(model = id)
            assertTrue("$id: sent as plain TLS without asking", ProviderCapability.ATTESTATION in p.capabilities)
            // no peer yet (degraded or not attested yet): the turn is refused into the E2EE question, never sent plain
            assertTrue("$id would send unsealed without the question", mustRefuseUnsealedSend(p, null))
            assertFalse("$id: claimed e2ee before near.ai vouched for it", p.claimsEndToEndEncryption)
        }
        NearAIModelCatalog.tierListSeenOverride = true
        assertTrue("the claim returns once the list is seen", Provider.nearAI.copy(model = "deepseek-ai/DeepSeek-V9").claimsEndToEndEncryption)
    }
}
