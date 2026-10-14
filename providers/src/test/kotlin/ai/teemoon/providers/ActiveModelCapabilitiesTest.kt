// ports: teemoonTests/EquippedModelsTests.swift @ v1.0.4 (ActiveModelCapabilitiesTests, iOS 9e47a63)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `modelCapabilities` describes the ACTIVE model. Switching models has to re-read it, or the tools gate answers for the model that just left. */
class ActiveModelCapabilitiesTest {
    private val server = Provider(id = "s", name = "server", endpoint = "http://box.local/v1/chat/completions", model = "with-tools", requiresAPIKey = false)
    private val none = ModelCapabilities(0)

    /** Save, move, refill — what AppState.activate / persist do. */
    private fun ConfigStore.put(p: Provider) = save(listOf(p), p.id)

    @Test fun aMoveDropsTheOldModelsCapabilities() {
        val p = server.copy(modelCapabilities = ModelCapabilities.TOOLS)
        val moved = p.withModel("no-tools")
        assertNull("unknown, not inherited", moved.modelCapabilities)
        assertEquals("the same model is not a move", ModelCapabilities.TOOLS, p.withModel("with-tools").modelCapabilities)
        assertNull(p.equipping("no-tools").modelCapabilities)
    }

    @Test fun activateTakesTheNewModelsOwnCapabilities() {
        val store = ConfigStore.inMemory()
        store.put(server.copy(equippedModels = listOf("with-tools"), modelCapabilities = ModelCapabilities.TOOLS))
        // a second model whose catalogue row says it cannot call tools
        store.put(store.refilled(server.copy(modelCapabilities = ModelCapabilities.TOOLS).equipping("no-tools")).copy(modelCapabilities = none))
        // back to the first: its own row said tools, and that is what must return
        val back = store.refilled(store.loadedProvider().equipping("with-tools"))
        assertEquals("with-tools", back.model); assertTrue(back.modelSupportsTools)
    }

    /** The failing direction: a model known NOT to call tools must not inherit the previous model's tools bit. */
    @Test fun aNoToolsModelDoesNotInheritTools() {
        val store = ConfigStore.inMemory()
        store.put(server.copy(model = "no-tools", equippedModels = listOf("no-tools"), modelCapabilities = none))
        store.put(store.loadedProvider().equipping("with-tools").copy(modelCapabilities = ModelCapabilities.TOOLS))
        assertTrue(store.loadedProvider().modelSupportsTools)
        val back = store.refilled(store.loadedProvider().equipping("no-tools"))
        assertEquals("no-tools", back.model); assertFalse(back.modelSupportsTools)
        // neither move overwrote the other row
        store.put(back)
        assertEquals(ModelCapabilities.TOOLS, store.capabilities("s", "with-tools"))
        assertEquals(none, store.capabilities("s", "no-tools"))
    }

    private fun ConfigStore.loadedProvider(): Provider = ProviderConfigProjection.providers(snapshot).providers.single()
}
