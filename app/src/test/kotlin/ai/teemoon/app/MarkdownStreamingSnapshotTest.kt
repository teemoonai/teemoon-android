// Android-only (no Swift source): Paparazzi baselines of the assistant markdown body at points of a long streamed reply —
// recorded BEFORE MarkdownBody rendered its top-level blocks itself, so a pixel of drift from that change fails here.
package ai.teemoon.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class MarkdownStreamingSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 5200, density = com.android.resources.Density.MEDIUM))

    private val md = StreamingReplyFixture.MARKDOWN
    private fun body(text: String, streaming: Boolean) =
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) { MarkdownBody(text, streaming = streaming) } } } }

    /** The whole reply, settled: headings, nested lists, the code block, the table, quotes. */
    @Test fun wholeReplySettled() = body(md, streaming = false)

    /** Mid-stream, cut inside the code block (an unclosed fence in the tail). */
    @Test fun streamingInsideTheCodeBlock() = body(md.substring(0, md.indexOf("require(quote.mrConfigId")), streaming = true)

    /** Mid-stream, cut inside the table's rows (a half-typed row is held). */
    @Test fun streamingInsideTheTable() = body(md.substring(0, md.indexOf("| field-3 |") + 6), streaming = true)
}
