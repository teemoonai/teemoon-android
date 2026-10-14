package ai.teemoon.app

import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereGetPolicy
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * The where sheet at the Pixel 10 Pro's width (1080 px at 420 dpi = 411 dp): the fit that WhereSheetSnapshotTest's
 * deliberately narrow 320 dp cannot show — "gemma 4 e2b · tensor" beside "17%" and "cancel" on one line.
 */
class WhereSheetPhoneWidthSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 1400, density = com.android.resources.Density.create(420)))

    /** WhereChip.swift's three equipped states: place; "downloading 61%" in the place's slot; "not downloaded" in red. */
    @Test fun whereChip_states_phoneWidth() {
        paparazzi.snapshot { TeemoonTheme { Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WhereChip(model = "gemma 4 e2b · tensor", place = "on this device", locality = WhereLocality.PHONE, onClick = {})
                WhereChip(model = "gemma 4 e2b · tensor", place = "on this device", locality = WhereLocality.PHONE, onClick = {}, progress = 0.61)
                // parked on mobile data (iOS 3b1c494): the number is not moving, so the chip says why
                WhereChip(model = "gemma 4 e2b", place = "on this device", locality = WhereLocality.PHONE, onClick = {}, progress = 0.42, progressLabel = "waiting for wi-fi")
                WhereChip(model = "gemma 4 e2b", place = "on this device", locality = WhereLocality.PHONE, onClick = {}, needsDownload = true)
                WhereChip(model = null, place = null, locality = null, onClick = {})
            }
        } } }
    }

    @Test fun whereSheet_tensorDownload_phoneWidth() {
        val e2b = LocalModelCatalog.all.first(); val device = LocalModelCatalog.forDevice("Tensor G5")
        val place = Provider.local(e2b).copy(id = "local"); val near = Provider.nearAI.copy(id = "near", equippedModels = listOf(Provider.nearAI.model)); val ps = listOf(place, near)
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = ps, selectedId = "local", problem = null, onSelect = {}, onBack = {}, hasKey = { it.id == "near" },
                installed = { true }, downloads = mapOf(e2b.id to 0.17), downloadNames = mapOf(e2b.id to device.first().displayName),
                policyFor = { f -> WhereGetPolicy(f, ps, true, emptyMap(), { if (it.id == "near") "k" else "" }, { "" }, catalog = device, deviceFileMissing = { true }) },
                onRefreshHome = {},
            )
        } } }
    }
}
