// Paparazzi baselines: the places hub and the cloud-keys manage list.
package ai.teemoon.app

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.PlacesKeysBadge
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlacesKeysSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 640, density = com.android.resources.Density.MEDIUM))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    /** iOS preview: near.ai keyed, grok + brave answers without → "1 · 2 need keys". */
    @Test fun placesHub() { paparazzi.snapshot { TeemoonTheme { Surface { PlacesKeysHubScreen(2, PlacesKeysBadge.cloud(keyed = 1, unkeyed = 2), {}, {}, {}) } } } }
    /** A short value ends at the chevron, under home machines' count — it sat mid-row on the Pixel (2026-10-04). */
    @Test fun placesHubShortBadge() { paparazzi.snapshot { TeemoonTheme { Surface { PlacesKeysHubScreen(2, PlacesKeysBadge.cloud(keyed = 3, unkeyed = 0), {}, {}, {}) } } } }
    @Test fun cloudKeys() {
        val near = Provider.nearAI.copy(id = "n", name = "near.ai glm 5.2", equippedModels = listOf("z-ai/glm-5.2", "z-ai/glm-5.1"))
        val grok = Provider.grok.copy(id = "g", model = "grok-4.5", equippedModels = listOf("grok-4.5"))
        paparazzi.snapshot { TeemoonTheme { Surface { PlacesManageScreen(WhereLocality.CLOUD, listOf(near, grok), subtitle = { placeManageSubtitle(it, null, it.id == "n") }, onAdd = {}, onEdit = {}, onDelete = {}, onBack = {}) } } }
    }
}
