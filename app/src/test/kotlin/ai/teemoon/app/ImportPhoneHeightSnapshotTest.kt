package ai.teemoon.app

// android-only: the import page at the height its sheet has on a phone (411×914 dp less the status bar, the sheet top
// and the gesture bar: ~760 dp) and the largest text. LargestTextSnapshotTest draws it 1400 dp
// tall, where nothing crowds; on the emulator run (411×914 dp, text at 200%, 2026-11-06) images.json's file card grew
// until "choose another file…" touched the pinned "import" button (pm: keep space between them).

import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ImportPhoneHeightSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 411, screenHeight = 760, density = com.android.resources.Density.MEDIUM, fontScale = 2f))

    /** images.json's card, as the run showed it: 23 chats, 42 messages and 20 images. */
    @Test fun nearImport_imagesCard_phoneHeight_largestText() = paparazzi.snapshot { TeemoonTheme { Surface {
        NearImportScreen(ChatImportUi.Confirm(ai.teemoon.chat.imports.ImportSummary(23, 42, imagesImported = 20, imagesSkipped = 11), ImportFile("images.json", 190_579, null)),
            onChooseFile = {}, onConfirm = {}, onDone = {}, onDismissFailure = {}, onBack = {}) } } }
}
