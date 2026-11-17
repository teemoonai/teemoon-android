// ports: teemoonTests/NearAIModelCatalogTests.swift @ ea19232 (noIDIsOwnFleetBeforeTheTierListIsSeen, recording marks it seen); teemoonTests/ProviderCatalogValidationTests.swift @ e2935f25 (todaysAttestedThirdPartyIdsAreNeverOwnFleetOffline)
package ai.teemoon.providers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearAITierListSeenTest {
    @After fun follow() { NearAIModelCatalog.tierListSeenOverride = null }

    /** A fresh install, a first setup before /v1/models answers, cleared storage: no guess claims own fleet. */
    @Test fun noIDIsOwnFleetBeforeTheTierListIsSeen() {
        NearAIModelCatalog.tierListSeenOverride = false
        for (id in listOf("z-ai/glm-hosted-elsewhere", "qwen/qwen3-hosted-elsewhere", "deepseek-ai/r9", "openai/gpt-oss-120b")) {
            assertEquals(id, NearAIModelCatalog.Confidentiality.UNKNOWN, NearAIModelCatalog.confidentiality(id))
            // claimed nowhere, yet still attested: the send proves it or asks (iOS cdcae74b)
            assertFalse(id, Provider.nearAI.copy(model = id).claimsEndToEndEncryption)
            assertTrue(id, ProviderCapability.ATTESTATION in Provider.nearAI.copy(model = id).capabilities)
        }
        // the down-veto is unchanged: a proxied id stays proxied
        assertEquals(NearAIModelCatalog.Confidentiality.PROXIED, NearAIModelCatalog.confidentiality("anthropic/claude-sonnet-5"))
    }

    /**
     * iOS 5b40a389 (update_providers.py, 2026-11-14): near.ai serves z-ai/glm-5.3 as attested third-party. Offline, with a
     * tier list seen before it was listed, its `z-ai/` namespace must not file it as own fleet and claim end-to-end encryption.
     */
    @Test fun glm53IsThirdPartyOfflineAndNeverClaimsE2EE() {
        NearAIModelCatalog.tierListSeenOverride = true
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY, NearAIModelCatalog.classify("z-ai/glm-5.3"))
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY, NearAIModelCatalog.confidentiality("z-ai/glm-5.3"))
        assertFalse(Provider.nearAI.copy(model = "z-ai/glm-5.3").claimsEndToEndEncryption)
        // the flash model is still near.ai's own fleet
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.classify("z-ai/glm-5.3-flash"))
    }

    /** iOS e2935f25: near.ai's live attested third-party ids on 2026-11-14; offline, with no list loaded, none reads as own fleet. */
    @Test fun todaysAttestedThirdPartyIdsAreNeverOwnFleetOffline() {
        for (id in listOf("deepseek/deepseek-v3.2", "moonshotai/kimi-k2.6", "moonshotai/kimi-k3", "z-ai/glm-5.3")) {
            assertEquals(id, NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY, NearAIModelCatalog.classify(id))
        }
    }

    @Test fun recordingATierListMarksItSeen() {
        NearAIModelCatalog.recordTiers(mapOf("nearai/marks-seen" to NearAIModelCatalog.Confidentiality.TEE_OWN))
        assertTrue(NearAIModelCatalog.tierListSeen)
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality("z-ai/glm-hosted-elsewhere"))
    }
}
