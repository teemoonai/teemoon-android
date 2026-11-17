// Android-only: Paparazzi of a long code line wrapping inside its block (the maintainer 2026-10-25, via pm; iOS wraps too).
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

class CodeWrapSnapshotTest {
    /** A long line in a code block wraps inside the block (the maintainer 2026-10-25, as iOS) — not scrolled off the edge. */
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 360, screenHeight = 360, density = com.android.resources.Density.MEDIUM))
    @Test fun aLongCodeLineWraps() = paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) {
        MarkdownBody("Here is a small function:\n\n```kotlin\nfun main() {\n    val counts = wordCounts(\"the cat and the hat sat on the mat\") // {the=2, cat=1, and=1, hat=1}\n}\n```\n\nIt counts each word.", streaming = false)
    } } } }
}
