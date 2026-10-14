// ports: teemoon/Providers/ProviderStore.swift @ v1.0.4 (addProvider: the fold, its on-device exemption, the union).
//        Android-only tests: the defect was Android's (2026-10-05) — the iOS rule was not carried into AppState.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddProviderTest {
    private val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }
    private val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }

    /**
     * Every phone model shares the endpoint "on-device". Folding on it replaced the phone's first model with the second
     * and returned an id that was in no list: downloading E4B on a phone that held E2B left the chip on "choose where"
     * and E2B's place gone (Pixel 10 Pro, 2026-10-05).
     */
    @Test fun aSecondPhoneModelIsItsOwnPlace() {
        val first = Provider.local(e2b)
        val added = ProviderHealing.addProvider(listOf(first), Provider.local(e4b), currentProviderID = first.id)
        assertEquals(2, added.providers.size)
        assertTrue("the first phone model's place is gone", added.providers.any { it.id == first.id && it.localModelID == e2b.id })
        assertTrue("the id returned is not in the list", added.providers.any { it.id == added.id && it.localModelID == e4b.id })
    }

    @Test fun aSecondRecordForACloudEndpointFoldsIntoTheFirstAndUnionsItsModels() {
        val first = Provider.fireworks.copy(id = "a", model = "m1", equippedModels = listOf("m1", "m2"))
        val second = Provider.fireworks.copy(id = "b", model = "m3", equippedModels = listOf("m2", "m3"))
        val added = ProviderHealing.addProvider(listOf(first), second, currentProviderID = "a")
        assertEquals(1, added.providers.size)
        assertEquals("the existing id is kept (a rotated key lands in the same slot)", "a", added.id)
        assertEquals(listOf("m1", "m2", "m3"), added.providers.single().equipped)
    }

    @Test fun aNewEndpointIsAppendedUnderItsOwnId() {
        val first = Provider.fireworks.copy(id = "a")
        val added = ProviderHealing.addProvider(listOf(first), Provider.nearAI.copy(id = "n"), currentProviderID = "a")
        assertEquals(listOf("a", "n"), added.providers.map { it.id }); assertEquals("n", added.id)
    }
}
