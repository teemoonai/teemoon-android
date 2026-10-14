package ai.teemoon.app

import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * A grouped page scrolled under its pinned header: the rows dissolve into the bar (iOS 26's scroll edge effect)
 * instead of being cut flat at its edge, which read as a row touching the title (where sheet, Pixel, 2026-10-13).
 */
class GroupedPageScrollEdgeSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 1300, density = com.android.resources.Density.create(420)))

    private fun page(scrolledBy: Int) = paparazzi.snapshot { TeemoonTheme { Surface { Box(Modifier.fillMaxSize()) {
        GroupedPage(header = { NavBar("where", leading = { CircleButton(Icons.Filled.Close, "close", {}, tint = TeemoonColors.tint) }) },
            topInset = 11.dp, scrollState = ScrollState(scrolledBy)) {
            SectionLabel("ready now")
            InsetGroup(dividerInset = 52.dp) {
                for ((m, p) in listOf("glm-5.2" to "near.ai · end-to-end encrypted", "gpt-oss-120b" to "near.ai · end-to-end encrypted",
                        "glm-5.3-flash" to "near.ai · end-to-end encrypted", "qwen3.8-27b" to "near.ai · end-to-end encrypted",
                        "kimi-k3" to "near.ai · not end-to-end encrypted", "glm-5p3" to "fireworks · not end-to-end encrypted"))
                    InsetRow(m, p, leading = { LocalityGlyph(WhereLocality.CLOUD, tint = TeemoonColors.tint) }, divider = true)
            }
        }
    } } } }

    /** At the top there is nothing under the bar, so no fade. */
    @Test fun atTheTop_noFade() = page(0)

    @Test fun scrolledUnderTheHeader_fades() = page(290)
}
