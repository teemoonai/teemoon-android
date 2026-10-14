// Kotlin-self pins for WhereSheetView+FirstRun.downloadEstimate and the size label (no Swift tests exist).
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadCopyTest {
    @Test fun estimateNeverSaysZeroMinutes() { assertEquals("about a minute on wi-fi", downloadEstimate(300)); assertEquals("about 3 minutes on wi-fi", downloadEstimate(2468)); assertEquals("about 5 minutes on wi-fi", downloadEstimate(3490)) }
    @Test fun sizeLabelIsOneDecimalGb() { assertEquals("2.4 gb", downloadSizeLabel(2468)); assertEquals("3.4 gb", downloadSizeLabel(3490)) }
}
