// IMAGES.md §4d (the 1.1.0 image flow): a message's tiles by count — one 240 × 180, two 136 squares, three or more 100
// squares, two to a row, at most four with "+N" on the fourth (the maintainer 2026-11-14; iOS MessageImageGrid @ 760ba58f).
package ai.teemoon.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class MessageImageGridSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 1900, density = com.android.resources.Density.MEDIUM))

    /** A photo per hue, so the still shows which image each tile is. */
    private fun photos(n: Int) = (1..n).map { i ->
        val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.HSVToColor(floatArrayOf(i * 36f, 0.55f, 0.85f))) }
        val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        ChatImage("img-$i", "image/jpeg", 64, 48, "attachments/img-$i.jpg", jpeg)
    }

    /** As sent: 1, 2, 3, 4, 5 and 10 photos, in the user's bubble, against its trailing edge. */
    @Test fun tiles_byCount() = paparazzi.snapshot { TeemoonTheme { Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            for (n in listOf(1, 2, 3, 4, 5, 10)) UserBubble("$n photos", images = photos(n))
        }
    } } }
}
