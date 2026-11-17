// The where sheet where it opens, on a whole phone screen (the maintainer 2026-11-13, "Ok make bigger"): two thirds of a
// 411 x 915 dp screen, so what shows under the header without a drag is what the person sees first.
package ai.teemoon.app

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereGetPolicy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WhereSheetDetentSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2402, density = com.android.resources.Density.create(420)))

    // offline, and near.ai drawn with its tier list loaded, as WhereSheetSnapshotTest does
    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
        NearAIModelCatalog.tierListSeenOverride = true
    }
    @After fun forgetTierList() { NearAIModelCatalog.tierListSeenOverride = null }

    private val mac = Provider(id = "mac", name = "second mac", endpoint = "http://100.100.0.12:11434/v1", model = "gemma4:e2b", requiresAPIKey = false, equippedModels = listOf("qwen3:14b", "gemma4:e2b"))
    private val near = Provider.nearAI.copy(id = "near", equippedModels = listOf(Provider.nearAI.model))
    private val fireworks = Provider.fireworks.copy(id = "fw", model = "accounts/fireworks/models/glm-5p3-flash",
        equippedModels = listOf("accounts/fireworks/models/glm-5p3-flash", "accounts/fireworks/models/kimi-k3", "accounts/fireworks/models/qwen3p6-235b"))
    private val providers = listOf(mac, near, fireworks)
    private val home = mapOf("mac" to WhereGetPolicy.HomeInfo(LocalServerKind.OLLAMA, 2, setOf("gemma4:e2b")))

    @Test fun whereSheet_opensAtTwoThirds() {
        val screenDp = 2402 * 160 / 420
        paparazzi.snapshot { TeemoonTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((screenDp * (1 - SheetDetents.WHERE)).dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
                Surface {
                    WhereSheet(providers = providers, selectedId = "near", problem = null, onSelect = {}, onBack = {},
                        hasKey = { it.id != "mac" },
                        policyFor = { f -> WhereGetPolicy(f, providers, true, home, { if (it.id != "mac") "k" else "" }, { "" }) },
                        onRefreshHome = {})
                }
            }
        } } }
    }
}
