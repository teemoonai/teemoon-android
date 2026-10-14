// ports: teemoonTests/EncryptedModelCTATests.swift @ v1.0.4 (all 4)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedModelCTATest {

    @Test fun aKeyedNearAISetupOffersTheSwitch() {
        val cta = EncryptedModelCTA.resolve(Provider.nearAI, hasKey = true)
        assertEquals(EncryptedModelCTA.SwitchToNearAI(Provider.nearAI), cta)
        assertTrue(cta.opensPicker)
        assertTrue(cta.title.startsWith("switch to"))
    }

    @Test fun noNearAIAtAllOffersTheKeyForm() {
        val cta = EncryptedModelCTA.resolve(null, hasKey = false)
        assertEquals(EncryptedModelCTA.AddNearAIKey(null), cta)
        assertFalse(cta.opensPicker)
    }

    /**
     * A setup that exists WITHOUT a key (legacy, or a lost key) must not open
     * the picker — the pick would die at send with "no api key".
     */
    @Test fun aKeylessNearAISetupOffersTheKeyFormForThatSetup() {
        val cta = EncryptedModelCTA.resolve(Provider.nearAI, hasKey = false)
        assertEquals(EncryptedModelCTA.AddNearAIKey(Provider.nearAI), cta)
        assertFalse(cta.opensPicker)
    }

    /** The whole complaint: the provider was never named. */
    @Test fun everyStateNamesNearAIAndTheKey() {
        for (cta in listOf(
            EncryptedModelCTA.resolve(Provider.nearAI, hasKey = true),
            EncryptedModelCTA.resolve(null, hasKey = false),
        )) {
            assertTrue("$cta", cta.title.contains("near.ai"))
            assertTrue("$cta", cta.detail.contains("near.ai key"))
        }
    }
}
