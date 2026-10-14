// ports: teemoonTests/ProviderTests.swift @ v1.0.4 (NearAIHostIdentityTests: the host rule behind every E2EE claim)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `endpoint.contains("near.ai")` was true for hosts near.ai does not run. */
class NearAIHostIdentityTest {
    private fun provider(endpoint: String) = Provider(id = "p", name = "p", endpoint = endpoint, model = "z-ai/glm-5.3-flash")

    @Test fun realNearAIHostsAreNearAI() {
        assertTrue(provider("https://cloud-api.near.ai/v1/chat/completions").isNearAI)
        assertTrue(provider("https://glm-5-3-flash.completions.near.ai/v1").isNearAI)
        assertTrue(Provider.nearAI.isNearAI)
    }

    @Test fun lookAlikeHostsAreNot() {
        assertFalse(provider("https://api.linear.ai/v1/chat/completions").isNearAI)
        assertFalse(provider("https://near.ai.evil.com/v1/chat/completions").isNearAI)
        assertFalse(provider("https://nearsai.example/v1").isNearAI)
    }

    /** A look-alike must not collect the attestation capabilities either — the tier guess answers TEE_OWN for ids it does not know. */
    @Test fun aLookAlikeHostClaimsNoEncryption() {
        val fake = provider("https://api.linear.ai/v1/chat/completions")
        assertFalse(ProviderCapability.END_TO_END_ENCRYPTION in fake.capabilities)
        assertFalse(ProviderCapability.ATTESTATION in fake.capabilities)
        // and the presentation never offers near.ai's tags or warning for it
        assertFalse(WhereProviderPresentation.showsConfidentialityTags(fake))
    }

    /** Saving mints a fresh id, so preset facts have to resolve by endpoint — trailing slash included. */
    @Test fun aSavedSetupKeepsItsPresetRuleAndKeyCheck() {
        val saved = Provider.openRouter.copy(id = "fresh", endpoint = Provider.openRouter.endpoint + "/")
        assertEquals(ModelDefaultRule.StrongestOpenWeight, saved.defaultModelRule)
        assertEquals(ProviderKeyValidator.Endpoint.OPEN_ROUTER, saved.keyValidationEndpoint)
    }
}
