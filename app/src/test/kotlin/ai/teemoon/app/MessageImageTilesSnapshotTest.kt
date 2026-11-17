// The image viewer's bar and an image's menu (IMAGES.md §4d, the 1.1.0 image flow). A message's tiles by count are
// MessageImageGridSnapshotTest's, on a canvas tall enough for six messages.
package ai.teemoon.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class MessageImageTilesSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 1100, density = com.android.resources.Density.MEDIUM))

    private fun images(n: Int) = (1..n).map { ChatImage("img-$it", "image/jpeg", 64, 48, "attachments/img-$it.jpg", ByteArray(0)) }
    /** The viewer's bar (the maintainer 2026-11-12, iOS's toolbar): "done" at the leading edge, the share glyph at the trailing edge. */
    @Test fun viewer_bar() = paparazzi.snapshot { TeemoonTheme { androidx.compose.foundation.layout.Box(Modifier.background(androidx.compose.ui.graphics.Color.Black).padding(vertical = 8.dp)) {
        ViewerTopBar(onDone = {}, onShare = {})
    } } }

    /**
     * An image's long-press menu (the maintainer 2026-11-12, "Material menu"): Material 3's menu surface, 48dp rows,
     * copy / save / share with their icons leading. Drawn as the popup draws it, since Paparazzi renders no popups.
     */
    @Test fun image_menu() = paparazzi.snapshot { TeemoonTheme { Surface { androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        Surface(shape = androidx.compose.material3.MenuDefaults.shape, color = androidx.compose.material3.MenuDefaults.containerColor,
            tonalElevation = androidx.compose.material3.MenuDefaults.TonalElevation, shadowElevation = androidx.compose.material3.MenuDefaults.ShadowElevation) {
            Column(Modifier.padding(vertical = 8.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                ImageMenuItems(images(1)[0], ImageActions(copy = {}, save = {}, share = {}), onDismiss = {})
            }
        }
    } } } }
}
