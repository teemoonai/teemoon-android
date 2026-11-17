// The composer while a Think Harder turn generates, developer mode on (the maintainer 2026-11-14, card
// chip-level-during-think-harder): the chip reads the level the request sent, "high", where at rest it reads the pick.
package ai.teemoon.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.teemoon.providers.WhereLocality
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ThinkHarderChipSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 700, density = com.android.resources.Density.create(420)))

    @Composable private fun chip(level: String?) = WhereChip(model = "glm-5.3-flash", place = "fireworks", locality = WhereLocality.CLOUD, onClick = {}, level = level)

    /** At rest (the pick, low), then generating a Think Harder reply (the stop square; the chip reads high). */
    @Test fun composer_thinkHarderGenerating() = paparazzi.snapshot { TeemoonTheme { Surface {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
                whereChip = { chip(shownChipLevel(whereLevel = "low", thinkHarderLevel = null)) }, whereStatus = true)
            // the + stays while a reply generates, as in the app (ChatScreen passes onAttach whether or not it streams); this
            // render left it out and showed a parity gap there is none of (pm 2026-11-14)
            Composer(value = "", onValueChange = {}, streaming = true, onSend = {}, onStop = {}, onAttach = {},
                whereChip = { chip(shownChipLevel(whereLevel = "low", thinkHarderLevel = "high")) }, whereStatus = true)
        }
    } } }
}
