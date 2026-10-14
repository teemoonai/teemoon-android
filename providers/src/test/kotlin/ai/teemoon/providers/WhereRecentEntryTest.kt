// ports: teemoonTests/WherePresentationTests.swift @ v1.0.1 ("Recently used": onFirstTokenIsWhatRecords,
//        aUsedModelListsWhileItIsStillEquipped, aUsedModelSurvivesBeingUnequipped),
//        teemoonTests/ConfigStoreTests.swift @ v1.0.1 ("Deleting a model forgets it": unequippingKeepsIt, deletingRemovesIt)
//        + android-only: server gone, the limit, the home/phone runnability rules.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WhereRecentEntryTest {
    private fun cloud(name: String, endpoint: String, model: String, models: List<String> = listOf(model)) =
        Provider(id = UUID.randomUUID().toString(), name = name, endpoint = endpoint, model = model, equippedModels = models)
    private fun machine(models: List<String> = listOf("gemma4", "qwen3.5:4b")) =
        Provider(id = UUID.randomUUID().toString(), name = "box", endpoint = "http://100.100.0.12:11434/v1", model = "gemma4", requiresAPIKey = false, equippedModels = models)

    /** Both models have answered on a probed machine. */
    private fun usedMachine(): Pair<ConfigStore, Provider> {
        val s = ConfigStore.inMemory(); val p = machine()
        s.save(listOf(p), p.id)
        s.recordProbe(p.id, ServerKind.OLLAMA, listOf("gemma4", "qwen3.5:4b"))
        s.stampUse(p.id, "gemma4", atMs = 1_000); s.stampUse(p.id, "qwen3.5:4b", atMs = 2_000)
        return s to p
    }

    /** Recents are "recently USED", not "recently selected": picking records nothing; output does. */
    @Test fun onFirstTokenIsWhatRecords() {
        val s = ConfigStore.inMemory(); val near = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1")
        s.save(listOf(near), near.id)   // selecting
        assertTrue(ProviderRecents.recentlyUsed(s, listOf(near)).isEmpty())
        ProviderRecents.recordUse(s, near)   // a generation producing output
        val rows = ProviderRecents.recentlyUsed(s, listOf(near))
        assertEquals(1, rows.size); assertEquals(near.id, rows.first().providerID); assertEquals(WhereLocality.CLOUD, rows.first().locality)
    }

    /** A used model shows up even though it is still equipped — the model you just used is equipped by definition. */
    @Test fun aUsedModelListsWhileItIsStillEquipped() {
        val s = ConfigStore.inMemory(); val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1"); val b = cloud("grok", "https://api.x.ai/v1", "grok-4")
        s.save(listOf(a, b), a.id); ProviderRecents.recordUse(s, a); ProviderRecents.recordUse(s, b)
        assertEquals(2, ProviderRecents.recentlyUsed(s, listOf(a, b)).size)
    }

    /** §2.6's load-bearing invariant: the row OUTLIVES the unequip. */
    @Test fun aUsedModelSurvivesBeingUnequipped() {
        val s = ConfigStore.inMemory(); val near = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", listOf("glm-5.1", "glm-5-air"))
        s.save(listOf(near), near.id); s.stampUse(near.id, "glm-5-air")
        val unequipped = near.copy(equippedModels = listOf("glm-5.1")); s.save(listOf(unequipped), near.id)
        val rows = ProviderRecents.recentlyUsed(s, listOf(unequipped))
        assertTrue(rows.any { it.modelID == "glm-5-air" }); assertEquals("glm-5-air", rows.first { it.modelID == "glm-5-air" }.provider.model)
    }

    /** Unequipping KEEPS the history — the model still exists and can come back. */
    @Test fun unequippingKeepsItInRecentlyUsed() {
        val (s, p) = usedMachine()
        val unequipped = p.copy(equippedModels = listOf("gemma4")); s.save(listOf(unequipped), p.id)
        assertTrue(ProviderRecents.recentlyUsed(s, listOf(unequipped), limit = 9).any { it.modelID == "qwen3.5:4b" })
    }

    /** Deleting does NOT: the weights are gone, and the machine must stop claiming to serve it. */
    @Test fun deletingRemovesItFromRecentlyUsed() {
        val (s, p) = usedMachine()
        ProviderRecents.forgetModel(s, "qwen3.5:4b", p)
        val rows = ProviderRecents.recentlyUsed(s, listOf(p), limit = 9)
        assertFalse(rows.any { it.modelID == "qwen3.5:4b" }); assertEquals(listOf("gemma4"), s.servedModels(p.id)); assertTrue(rows.any { it.modelID == "gemma4" })
    }

    /** Newest first, capped, and a row whose server is gone is dropped BEFORE the limit. */
    @Test fun newestFirstCappedAndOrphansDropped() {
        val s = ConfigStore.inMemory(); val ps = (1..4).map { cloud("p$it", "https://p$it.example/v1", "m$it") }
        s.save(ps, ps[0].id); ps.forEachIndexed { i, p -> s.stampUse(p.id, p.model, atMs = 100L + i) }
        val rows = ProviderRecents.recentlyUsed(s, ps.drop(1))   // p1's server is gone
        assertEquals(listOf("m4", "m3", "m2"), rows.map { it.modelID })
    }

    /** HOME needs the machine to still serve it — unless it was never probed this launch; PHONE needs the weights on disk. */
    @Test fun runnabilityFollowsTheTier() {
        val home = machine()
        assertTrue(ProviderRecents.stillRunnable("gemma4", home, served = null))
        assertTrue(ProviderRecents.stillRunnable("gemma4", home, served = listOf("gemma4")))
        assertFalse(ProviderRecents.stillRunnable("gemma4", home, served = listOf("other")))
        val phone = Provider(id = "ph", name = "gemma", endpoint = "on-device", model = "gemma", requiresAPIKey = false, localModelID = LocalModelCatalog.all.first().id)
        assertFalse(ProviderRecents.stillRunnable("gemma", phone, served = null))
        assertTrue(ProviderRecents.stillRunnable("gemma", phone, served = null, installed = { true }))
        assertTrue(ProviderRecents.stillRunnable("anything", cloud("c", "https://c.example/v1", "x"), served = null))
    }
}
