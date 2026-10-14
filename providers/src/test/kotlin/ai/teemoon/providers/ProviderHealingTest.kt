// ports: teemoonTests/DuplicateMachineTests.swift @ v1.0.1 (9 of 10 — healingOnlyTouchesRecordsWithNoStoredKey is over
//        the Keychain; its rule is pinned with a credential seam), teemoonTests/EquippedModelsTests.swift @ v1.0.1
//        (the 6 Provider-level tests; the Codable and store tests are ConfigStore's and the app's).
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProviderHealingTest {
    private fun ollama(endpoint: String, model: String, models: List<String>? = null, id: String = UUID.randomUUID().toString(), key: Boolean = false) =
        Provider(id = id, name = "ollama", endpoint = endpoint, model = model, requiresAPIKey = key, equippedModels = models)

    // MARK: - Duplicate self-hosted machines

    @Test fun sameEndpointTypedTwoWays_isOneMachine() {
        val a = ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:latest"); val b = ollama("https://RingZero.tailnet-name.ts.net:11434/v1/", "qwen3.5:4b")
        assertNotNull(a.machineIdentity); assertEquals(a.machineIdentity, b.machineIdentity)
    }
    @Test fun differentPortsOnOneHost_areDifferentMachines() {
        assertNotEquals(ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "a").machineIdentity, ollama("https://ringzero.tailnet-name.ts.net:8080/v1", "b").machineIdentity)
    }
    @Test fun cloudProvidersHaveNoMachineIdentity() = assertNull(Provider.nearAI.machineIdentity)
    @Test fun aSelfHostedEndpointThatNeedsAKeyHasNoMachineIdentity() = assertNull(ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "a", key = true).machineIdentity)

    @Test fun addingTheSameMachineTwice_leavesOneRecord() {
        val models = listOf("gemma4:latest", "gemma4:e2b", "qwen3.5:4b")
        val m = ProviderHealing.mergeDuplicateMachines(listOf(ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:latest", models), ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:latest", models)), null)
        assertEquals(1, m.providers.size); assertEquals(3, m.providers[0].equipped.size); assertEquals(1, m.dropped.size)
    }
    @Test fun mergeKeepsModelsOnlyTheDuplicateHad() {
        val m = ProviderHealing.mergeDuplicateMachines(listOf(ollama("https://box.ts.net:11434/v1", "a", listOf("a")), ollama("https://box.ts.net:11434/v1", "b", listOf("b", "c"))), null)
        assertEquals(1, m.providers.size); assertEquals(setOf("a", "b", "c"), m.providers[0].equipped.toSet())
    }
    @Test fun mergingTheACTIVErecord_keepsWhatWasRunning() {
        val first = ollama("https://box.ts.net:11434/v1", "a", listOf("a")); val second = ollama("https://box.ts.net:11434/v1", "c", listOf("c"))
        val m = ProviderHealing.mergeDuplicateMachines(listOf(first, second), currentProviderID = second.id)
        assertEquals(1, m.providers.size); assertEquals(first.id, m.newCurrentProviderID); assertEquals("c", m.providers[0].model)
        assertTrue(m.notice!!.contains("the same computer"))
    }
    /** The device's two ringzero records: keyed flags healed (no stored key), then folded into one. */
    @Test fun theDevicesTwoRingzeroRecords_collapseToOne() {
        val models = listOf("gemma4:latest", "gemma4:e2b-it-qat", "qwen3.5:4b")
        val two = listOf(ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:latest", models, key = true), ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "qwen3.5:4b", models, key = true))
        val healed = ProviderHealing.healSelfHostedKeyFlags(two) { "" }
        assertTrue(healed.providers.all { !it.requiresAPIKey })
        val m = ProviderHealing.mergeDuplicateMachines(healed.providers, null)
        assertEquals(1, m.providers.size); assertEquals(models.toSet(), m.providers[0].equipped.toSet())
    }
    /** Healing only touches records with NO stored key: a real secret keeps requiresAPIKey, and the record stays outside machine identity. */
    @Test fun healingOnlyTouchesRecordsWithNoStoredKey() {
        val keyed = ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:latest", listOf("gemma4:latest"), key = true)
        val healed = ProviderHealing.healSelfHostedKeyFlags(listOf(keyed)) { "a-real-secret" }
        assertTrue(healed.providers[0].requiresAPIKey); assertNull(healed.providers[0].machineIdentity); assertTrue(healed.renamed.isEmpty())
    }
    @Test fun healingLeavesCloudProvidersAlone() {
        val healed = ProviderHealing.healSelfHostedKeyFlags(listOf(Provider.nearAI)) { "" }
        assertTrue(healed.providers[0].requiresAPIKey); assertNull(healed.providers[0].machineIdentity)
    }
    @Test fun distinctMachinesAreLeftAlone() {
        val m = ProviderHealing.mergeDuplicateMachines(listOf(ollama("https://box-one.ts.net:11434/v1", "a"), ollama("https://box-two.ts.net:11434/v1", "a"), Provider.nearAI), null)
        assertEquals(3, m.providers.size); assertTrue(m.dropped.isEmpty())
    }

    /** Duplicate endpoints: the current record wins, else the keyed one; the active model follows the record that was running; the loser's key is safe to delete only when the survivor has one. */
    @Test fun mergeDuplicateEndpoints_currentThenKeyedWins() {
        val a = Provider(id = "a", name = "grok", endpoint = Provider.grok.endpoint, model = "grok-4", equippedModels = listOf("grok-4"))
        val b = Provider(id = "b", name = "grok 2", endpoint = Provider.grok.endpoint + "/", model = "grok-3", equippedModels = listOf("grok-3"))
        val keyedB = ProviderHealing.mergeDuplicateEndpoints(listOf(a, b), currentProviderID = null) { it.id == "b" }
        assertEquals(listOf("b"), keyedB.providers.map { it.id }); assertEquals(setOf("grok-3", "grok-4"), keyedB.providers[0].equipped.toSet()); assertEquals(listOf("a"), keyedB.keysSafeToDelete.map { it.id })   // the survivor has a key, so the loser's slot may go
        val currentA = ProviderHealing.mergeDuplicateEndpoints(listOf(a, b), currentProviderID = "a") { it.id == "b" }
        assertEquals(listOf("a"), currentA.providers.map { it.id }); assertNull(currentA.newCurrentProviderID)
        val currentB = ProviderHealing.mergeDuplicateEndpoints(listOf(a, b), currentProviderID = "b") { true }
        assertEquals(listOf("b"), currentB.providers.map { it.id }); assertEquals("grok-3", currentB.providers[0].model); assertEquals(listOf("a"), currentB.keysSafeToDelete.map { it.id })
    }

    /** Auto labels: "<place> <model>" is teemoon's own shape and is healed to the place; a user's label is not. */
    @Test fun healAutoLabels_renamesOnlyGeneratedLabels() {
        val box = ollama("https://ringzero.tailnet-name.ts.net:11434/v1", "gemma4:e4b").copy(name = "ringzero gemma4:e4b")
        val mine = box.copy(name = "the fast box")
        // The catalogue's pretty name ("GLM 5.2") comes from what near.ai last answered with (c3fb76d), not a shipped table.
        val saved = { endpoint: String -> if (endpoint == Provider.nearAI.endpoint) listOf(KnownModel("z-ai/glm-5.2", "GLM 5.2", "Z.ai", "")) else emptyList() }
        val healed = ProviderHealing.healAutoLabels(listOf(box, mine, Provider.nearAI.copy(name = "near.ai GLM 5.2")), saved)
        assertEquals(listOf("ringzero", "the fast box", "near.ai"), healed.providers.map { it.name }); assertEquals(2, healed.renamed.size)
        // With nothing saved the words are unknown, so the user's label is left alone rather than guessed at.
        assertEquals("near.ai GLM 5.2", ProviderHealing.healAutoLabels(listOf(Provider.nearAI.copy(name = "near.ai GLM 5.2"))).providers.single().name)
        assertEquals("k26", ProviderHealing.fuzzyModelKey("kimi-k2p6").removePrefix("kimi")); assertTrue(ProviderHealing.looksLikeAModelRef("qwen3.5:4b")); assertFalse(ProviderHealing.looksLikeAModelRef("my box"))
    }

    // MARK: - Equipped models (Provider level)

    private fun p(model: String) = Provider(id = "p", name = "near.ai", endpoint = "https://x/v1", model = model)
    @Test fun nilEquippedReadsAsTheActiveModel() { assertNull(p("glm-5.2").equippedModels); assertEquals(listOf("glm-5.2"), p("glm-5.2").equipped) }
    @Test fun activeModelIsAlwaysEquippedEvenIfTheStoredSetDisagrees() { val x = p("running-this").copy(equippedModels = listOf("something-else")); assertEquals("running-this", x.equipped.first()) }
    @Test fun equippingAppendsActivatesAndIsIdempotent() {
        assertEquals(listOf("glm-5.2", "qwen3-235b"), p("glm-5.2").equipping("qwen3-235b").equipped); assertEquals("qwen3-235b", p("glm-5.2").equipping("qwen3-235b").model)
        assertEquals(listOf("a", "b", "c"), p("a").equipping("b").equipping("c").equipped); assertEquals(listOf("a", "b"), p("a").equipping("b").equipping("b").equipped)
    }
    @Test fun unequippingTheActiveModelMovesActiveToASurvivor() { val after = p("a").equipping("b").unequipping("b"); assertEquals(listOf("a"), after.equipped); assertEquals("a", after.model) }
    @Test fun unequippingANonActiveModelLeavesActiveAlone() { val after = p("a").equipping("b").equipping("a").unequipping("b"); assertEquals(listOf("a"), after.equipped); assertEquals("a", after.model) }
    @Test fun unequippingTheLastModelEmptiesTheServerInsteadOfDeletingIt() {
        val only = p("only"); val after = only.unequipping("only")
        assertTrue(after.equipped.isEmpty()); assertEquals("", after.model); assertEquals(only.id, after.id); assertEquals(only.endpoint, after.endpoint)
    }
    @Test fun localProviderHasExactlyOneEquippedModel() { val l = Provider.local(LocalModelCatalog.all[0]); assertEquals(listOf(l.model), l.equipped) }
}
