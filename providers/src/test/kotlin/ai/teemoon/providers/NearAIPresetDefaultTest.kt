// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (the near.ai preset default only — unchanged since 5567da4;
//        the other presets' defaults and ModelDefaultRule.strongestOpenWeight are ProviderPresets.kt / ModelDefaultRule.kt)
package ai.teemoon.providers

import ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The near.ai preset must default to a model near.ai STILL SERVES with a live direct host.
 *
 * 5.2 and 5.1 were retired on 2026-09-12 and their direct hosts stopped answering. A preset pointing at one means
 * a fresh setup cannot establish E2EE at all: the model-enclave manifest never loads, key binding fails closed,
 * and the send gate refuses the user's first message with "attestation unreachable". iOS hit this as a live
 * signature test failing for days on "Ed25519 key unavailable"; here it presented as a send that would not go.
 */
class NearAIPresetDefaultTest {

    @Test fun theDefaultIsNotARetiredModel() {
        for (retired in listOf("z-ai/glm-5.2", "zai-org/GLM-5.1-FP8", "z-ai/glm-5.1")) {
            assertNotEquals("the preset points at a retired model — a fresh setup cannot do E2EE",
                retired.lowercase(), Provider.nearAI.model.lowercase())
        }
    }

    /**
     * And it must be own-fleet, because the whole point of the near.ai preset is the E2EE path. A default that
     * classified as proxied would work — and silently be the one thing this provider is chosen to avoid.
     */
    @Test fun theDefaultIsAnOwnFleetModel() {
        assertEquals(TEE_OWN, NearAIModelCatalog.classify(Provider.nearAI.model))
    }

    /** Pinned, so a future edit is a decision: this is what near.ai serves as of 2026-09-22. */
    @Test fun theDefaultIsGlm53Flash() {
        assertEquals("z-ai/glm-5.3-flash", Provider.nearAI.model)
        assertTrue(Provider.nearAI.supportsModelBrowsing)
    }
}
