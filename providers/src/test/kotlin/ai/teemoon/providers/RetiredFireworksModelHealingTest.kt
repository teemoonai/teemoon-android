// ports: teemoonTests/ProviderTests.swift @ 5d2fb66 (RetiredFireworksModelHealingTests)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Fireworks took kimi-k2p6 off serverless on 2026-10-15 while it was the shipped default; the 1.0.5 preset change moved
 * only new setups, so every saved Fireworks setup on it failed its next send. The user chose "switch and tell": move it
 * to kimi-k3 at launch and say so once, naming the price change — the same string as iOS.
 */
class RetiredFireworksModelHealingTest {
    private val k2p6 = "accounts/fireworks/models/kimi-k2p6"
    private val k3 = "accounts/fireworks/models/kimi-k3"
    private val glm = "accounts/fireworks/models/glm-5p3"
    private fun setup(base: Provider, model: String, equipped: List<String>?) =
        base.copy(id = UUID.randomUUID().toString(), equippedModels = equipped).withModel(model)

    @Test fun aSetupOnTheRetiredModelMovesToItsSuccessorAndSaysSo() {
        val p = setup(Provider.fireworks, k2p6, listOf(k2p6, glm, k3))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertEquals(listOf(p.id), heal.moved.map { it.id })
        val healed = heal.providers.single()
        assertEquals(k3, healed.model)
        assertEquals("replaced in place and de-duplicated: k3 was already equipped", listOf(k3, glm), healed.equippedModels)
        assertEquals(listOf("kimi k2.6 is no longer on fireworks, so your fireworks setup now uses kimi k3 (\$3.00/\$15.00 per 1M tokens, was \$0.95/\$4.00)."), heal.notices)
    }

    @Test fun onlyTheEquippedListCanBeHealedToo() {
        val p = setup(Provider.fireworks, k3, listOf(k2p6))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertEquals(listOf(p.id), heal.moved.map { it.id })
        assertEquals(listOf(k3), heal.providers.single().equippedModels)
        assertEquals(1, heal.notices.size)
    }

    @Test fun aSetupOnAnotherModelIsUntouchedAndSaysNothing() {
        val p = setup(Provider.fireworks, glm, listOf(glm))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertTrue(heal.moved.isEmpty()); assertTrue(heal.notices.isEmpty())
        assertEquals(p, heal.providers.single())
    }

    @Test fun theSameIdOnAnotherProviderIsUntouched() {
        val p = setup(Provider.openRouter, k2p6, listOf(k2p6))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertTrue(heal.moved.isEmpty()); assertTrue(heal.notices.isEmpty())
        assertEquals(k2p6, heal.providers.single().model)
    }

    /**
     * iOS also asserts the retired id is gone from the price table. Android keeps the seven retired rows until the
     * conformance kit's next sync (its Fireworks vectors still carry them; they never render, the list being Fireworks'
     * live one), so only the successor half is pinned here.
     */
    @Test fun everySuccessorIsPriced() {
        for (r in ProviderHealing.fireworksRetirements) assertNotNull("${r.successor} has no price", FireworksAdapter.fireworksPrices[r.successor])
    }
}
