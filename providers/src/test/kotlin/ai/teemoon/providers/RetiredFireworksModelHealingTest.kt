// ports: teemoonTests/ProviderTests.swift @ ffcfc00 (RetiredFireworksModelHealingTests)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** nemotron-3-super 404s on NVIDIA (2026-10-22); the maintainer picked lightning as the default (iOS ffcfc00). */
    @Test fun anNvidiaSetupOnSuperMovesToLightningAndSaysSo() {
        val superId = "nvidia/nemotron-3-super-120b-a12b"; val lightning = "nvidia/nemotron-3.5-lightning-30b-a3b"
        val p = setup(Provider.nvidia, superId, listOf(superId, "nvidia/nemotron-3-ultra-550b-a55b"))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertEquals(listOf(p.id), heal.moved.map { it.id })
        val healed = heal.providers.single()
        assertEquals(lightning, healed.model)
        assertEquals(listOf(lightning, "nvidia/nemotron-3-ultra-550b-a55b"), healed.equippedModels)
        assertEquals(listOf("nemotron 3 super is no longer on nvidia, so your nvidia setup now uses nemotron 3.5 lightning."), heal.notices)
        assertEquals("the preset's default is the successor", lightning, Provider.nvidia.model)
    }

    @Test fun theNvidiaIdOnAnotherProviderIsUntouched() {
        val superId = "nvidia/nemotron-3-super-120b-a12b"
        val p = setup(Provider.openRouter, superId, listOf(superId))
        val heal = ProviderHealing.healRetiredFireworksModels(listOf(p))
        assertTrue(heal.moved.isEmpty())
        assertEquals(superId, heal.providers.single().model)
    }

    /** The successor is priced and served today; the retired id is gone from the table (since 3917.8's catalog sync). */
    @Test fun everySuccessorIsPricedAndServedTodayInTheTable() {
        for (r in ProviderHealing.fireworksRetirements.filter { it.source == EndpointModelCatalog.Source.FIREWORKS }) {
            assertNotNull("${r.successor} has no price", FireworksAdapter.fireworksPrices[r.successor])
            assertNull("${r.retired} is still in the table", FireworksAdapter.fireworksPrices[r.retired])
        }
    }
}
