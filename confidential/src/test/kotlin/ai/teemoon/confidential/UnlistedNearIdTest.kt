// ports: teemoonTests/NearAIModelCatalogTests.swift @ 9862a35 (anUnlistedIDIsNeverOwnFleetUntilTheCatalogueSaysSo)
//
// Audit gardener #7 (the maintainer 2026-10-25): classify fell through to own fleet, so a new near.ai model wore "end-to-end
// encrypted" — and had the attestation and E2EE capabilities — until the catalogue's owned_by arrived. An id no rule
// names claims nothing, still degrades a session that finds no direct host (never "ordinary"), and the catalogue
// settles it. It lives here, beside ConfidentialSession, because the missing-host outcome is part of the rule.
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UnlistedNearIdTest {
    @After fun reset() = NearAIModelCatalog.resetTierCache()

    @Test fun anUnlistedIDIsNeverOwnFleetUntilTheCatalogueSaysSo() {
        NearAIModelCatalog.resetTierCache()
        for (id in listOf("acme/brand-new-model-1", "brand-new-model-2", "mistralai/mistral-large-4")) {
            assertEquals(id, "UNKNOWN", NearAIModelCatalog.classify(id).name)
            assertNotEquals(id, NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality(id))
            assertFalse(id, NearAIModelCatalog.isAttestable(id))
            assertEquals("$id must not read as ordinary", ConfidentialSession.MissingHostOutcome.DEGRADED, ConfidentialSession.outcomeWhenNoDirectHost(id))
        }
        NearAIModelCatalog.recordTiers(mapOf("acme/brand-new-model-1" to NearAIModelCatalog.Confidentiality.TEE_OWN))
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality("acme/brand-new-model-1"))
    }

    /** The namespaces near.ai hosts itself are still guessed own fleet offline: no regression for today's models. */
    @Test fun nearsOwnNamespacesStayOwnFleetOffline() {
        NearAIModelCatalog.resetTierCache()
        for (id in listOf("z-ai/glm-5.3-flash", "zai-org/GLM-5-FP8", "deepseek-ai/DeepSeek-V4-Flash", "Qwen/Qwen3.6-27B-FP8", "meta-llama/Llama-4", "nearai/x", "openai/gpt-oss-120b", "google/gemma-4"))
            assertEquals(id, NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.classify(id))
    }
}
