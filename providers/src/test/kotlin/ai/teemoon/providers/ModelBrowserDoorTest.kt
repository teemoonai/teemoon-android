// ports: teemoonTests/WherePresentationTests.swift @ v1.0.4 (ModelBrowserDoorTests, iOS a0276dd); the store is injected
//        here rather than reached for as a singleton, so the encrypted-picker case drives a real one.
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelBrowserDoorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        EndpointDirectory.resetPersisted()
    }

    private fun row(id: String, host: String? = null) =
        KnownModel(id = id, displayName = id, vendor = "v", price = "", directBaseURL = host)

    /**
     * The trust ladder offers what teemoon can SEAL: own-fleet models with a
     * published confidential host. Attested third-party hardware passes
     * isAttestable and has no confidential endpoint — offering it is the
     * overclaim the screen exists to avoid.
     */
    @Test fun theEncryptedPickerOffersOnlySealableModels() {
        NearAIModelCatalog.recordTiers(mapOf(
            "z-ai/glm-own" to NearAIModelCatalog.Confidentiality.TEE_OWN,
            "moonshotai/kimi-3p" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY,
            "anthropic/claude-proxied" to NearAIModelCatalog.Confidentiality.PROXIED))
        val store = LiveCatalogStore(tmp.newFolder())
        store.record(listOf(
            row("z-ai/glm-own", "https://glm.completions.near.ai/v1"),
            row("moonshotai/kimi-3p", "https://kimi.completions.near.ai/v1"),
            row("anthropic/claude-proxied"),
        ), Provider.nearAI, "")

        val door = ModelBrowserDoor.encryptedPicker(Provider.nearAI, "", store)
        assertEquals(listOf("z-ai/glm-own"), door.models.map { it.id })
        assertTrue("the picker always tags the tier", door.showsConfidentialityTags)
        assertTrue("an unknown id must not be offerable here", !door.allowsCustomID)
    }

    /**
     * An empty refresh is null, not an empty list: the caller keeps what is on
     * screen rather than replacing a usable picker with nothing.
     */
    @Test fun anEmptyRefreshKeepsWhatIsOnScreen() = runTest {
        val store = LiveCatalogStore(tmp.newFolder())
        val door = ModelBrowserDoor.encryptedPicker(Provider.nearAI, "", store) { emptyList() }
        assertNull(door.liveLoader!!.invoke())
    }

    @Test fun aRefreshIsFilteredTheSameWay() = runTest {
        NearAIModelCatalog.recordTiers(mapOf(
            "z-ai/glm-own" to NearAIModelCatalog.Confidentiality.TEE_OWN,
            "moonshotai/kimi-3p" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
        val store = LiveCatalogStore(tmp.newFolder())
        val door = ModelBrowserDoor.encryptedPicker(Provider.nearAI, "", store) {
            listOf(row("z-ai/glm-own", "https://glm.completions.near.ai/v1"), row("moonshotai/kimi-3p"))
        }
        assertEquals(listOf("z-ai/glm-own"), door.liveLoader!!.invoke()!!.map { it.id })
    }

    @Test fun nearAIIsTieredAndRefusesTypedIDs() {
        val door = ModelBrowserDoor.browsing(Provider.nearAI, "", null, LiveCatalogStore(tmp.newFolder()))
        assertTrue(door.showsConfidentialityTags)
        assertTrue("an unknown id would claim e2ee", !door.allowsCustomID)
        assertNull("near.ai publishes no routes", door.offersLoader)
        assertTrue(door.liveLoader != null)
    }

    @Test fun openRouterPublishesOffersAndTakesTypedIDs() {
        val door = ModelBrowserDoor.browsing(Provider.openRouter, "", null, LiveCatalogStore(tmp.newFolder()))
        assertTrue(door.offersLoader != null)
        assertTrue(door.allowsCustomID)
        assertTrue(!door.showsConfidentialityTags)
    }

    @Test fun onDeviceHasNothingLiveBehindIt() {
        val phone = Provider.local(LocalModelCatalog.all[0])
        val door = ModelBrowserDoor.browsing(phone, "", null, LiveCatalogStore(tmp.newFolder()))
        assertTrue(door.models.isEmpty())
        assertNull(door.liveLoader)
        assertNull(door.offersLoader)
    }
}
