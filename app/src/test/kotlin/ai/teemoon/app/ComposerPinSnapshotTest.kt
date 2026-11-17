// android-only: the composer's layout pinned before it moves to BasicTextField(TextFieldState) for image paste (pm for the
// maintainer, 2026-11-07: "pin today's behaviour first … the AX5/200% layout"). Recorded on the String field; the
// migration must leave every golden unchanged — at default size on a 320 dp phone, and at 200% on a 412 dp one.
package ai.teemoon.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

abstract class ComposerPinShots(fontScale: Float, width: Int) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = width, screenHeight = 900, density = com.android.resources.Density.MEDIUM, fontScale = fontScale))

    private fun swatch(argb: Int) = android.graphics.Bitmap.createBitmap(56, 56, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }.asImageBitmap()
    private val eightLines = (1..8).joinToString("\n") { "line $it of a question that keeps going" }

    /** The placeholder, one line, the field at its six-line cap, and a streaming reply's stop square. */
    @Test fun composer_text() = paparazzi.snapshot { TeemoonTheme { Surface {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {})
            Composer(value = "what does the quote prove?", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {})
            Composer(value = eightLines, onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {})
            Composer(value = "", onValueChange = {}, streaming = true, onSend = {}, onStop = {})
        }
    } } }

    /** Picked images above the field, with and without text. */
    @Test fun composer_images() = paparazzi.snapshot { TeemoonTheme { Surface {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val three = listOf(swatch(0xFF3A6EA5.toInt()), swatch(0xFFC2763A.toInt()), swatch(0xFF5A9A5A.toInt()))
            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, images = three, canSend = true)
            Composer(value = "compare these", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, images = three)
        }
    } } }
}

class ComposerPinSnapshotTest : ComposerPinShots(1f, 320)
class ComposerPinLargestTextSnapshotTest : ComposerPinShots(2f, 412)
