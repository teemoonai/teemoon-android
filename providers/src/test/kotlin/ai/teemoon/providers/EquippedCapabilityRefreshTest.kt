// ports: teemoonTests/EquippedCapabilityRefreshTests.swift @ a851981 (3 of 4: the launch pass is AppState.followCapabilities,
//        which needs an Android context — its parts are the store adoption below and LiveCatalogStore.onRecorded)
//
// the maintainer 2026-10-28: "Fireworks 5.3 flash says can't use images" after an upgrade. An equipped model's capabilities were
// copied when it was equipped and never read again, so a model the vendor later lists with image input stayed "can't see
// images" for good. A fresh /models answer now rewrites them.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class EquippedCapabilityRefreshTest {
    @get:Rule val tmp = TemporaryFolder()
    private val flash = "accounts/fireworks/models/glm-5p3-flash"

    private fun row(id: String, caps: Set<ModelCapability>?) = KnownModel(id = id, displayName = id, vendor = "z.ai", price = "", capabilities = caps)

    /** The 1.0.6-era state: GLM 5.3 Flash equipped, saved as tools-only. */
    private fun storeWithStaleFlash(): Pair<ConfigStore, String> {
        val server = Server(id = UUID.randomUUID().toString(), name = "Fireworks", endpoint = Provider.fireworks.endpoint, requiresAPIKey = true, activeModelID = flash)
        val equipped = EquippedModel(serverID = server.id, modelID = flash, capabilities = ModelCapabilities.TOOLS)
        val store = ConfigStore(File(tmp.newFolder(), "teemoon-config.json"))
        store.write(ConfigSnapshot(servers = listOf(server), equipped = listOf(equipped), currentEquippedID = equipped.id))
        return store to server.id
    }

    @Test fun aFreshListThatSaysTheModelTakesImagesMakesItSeeImages() {
        val (store, server) = storeWithStaleFlash()
        val fresh = store.adoptCapabilities(listOf(row(flash, setOf(ModelCapability.TOOLS, ModelCapability.VISION))), server)
        val both = ModelCapabilities.fromCapabilitySet(setOf(ModelCapability.TOOLS, ModelCapability.VISION))
        assertEquals(both, store.capabilities(server, flash))
        assertEquals(both, fresh[flash])
    }

    @Test fun aRowTheListDoesNotDescribeKeepsWhatItKnew() {
        val (store, server) = storeWithStaleFlash()
        store.adoptCapabilities(listOf(row(flash, null), row("other", setOf(ModelCapability.VISION))), server)
        assertEquals(ModelCapabilities.TOOLS, store.capabilities(server, flash))
    }

    @Test fun recordingAListInTheCatalogueStoreReachesTheConfig() {
        val (store, server) = storeWithStaleFlash()
        val catalogue = LiveCatalogStore(tmp.newFolder())
        catalogue.onRecorded = { _, models -> store.adoptCapabilities(models, server) }
        catalogue.record(listOf(row(flash, setOf(ModelCapability.TOOLS, ModelCapability.VISION))), Provider.fireworks, "fw-key")
        assertEquals(ModelCapabilities.fromCapabilitySet(setOf(ModelCapability.TOOLS, ModelCapability.VISION)), store.capabilities(server, flash))
    }
}
