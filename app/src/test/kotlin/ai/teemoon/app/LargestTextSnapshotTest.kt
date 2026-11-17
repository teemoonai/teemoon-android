// The settings root and places & keys at the phone's largest font size (200%, Android 14+), after iOS's AX fix for
// the "places & keys" row broke it at default size on the maintainer's iPhone (pm, 2026-10-31): every label is still drawn and
// no row balloons, at a Pixel 10 Pro's 412 dp width. At default size both are 3917's pixels (SettingsScreensSnapshotTest, PlacesKeysSnapshotTest).
// The import page's start, file card, progress and done at the same size (pm 2026-11-01).
package ai.teemoon.app

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.PlacesKeysBadge
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LargestTextSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 1400, density = com.android.resources.Density.MEDIUM, fontScale = 2f))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    /** Settings › appearance: color, then the font group (design, width, size), each row's value beside its chevron. */
    @Test fun appearance_largestText() {
        paparazzi.snapshot { TeemoonTheme { Surface { AppearanceSettingsScreen(AppSettings(InMemoryPrefs()), onBack = {}) } } }
    }

    @Test fun settingsRoot_largestText() {
        paparazzi.snapshot { TeemoonTheme { Surface { SettingsRootScreen(managedCount = 2, developerMode = true, onSetDeveloperMode = {}, onOpenAppearance = {}, onOpenChats = {}, onOpenSearch = {}, onOpenPlaces = {}, onOpenCredits = {}, onBack = {}) } } }
    }
    @Test fun placesHub_largestText() {
        paparazzi.snapshot { TeemoonTheme { Surface { PlacesKeysHubScreen(2, PlacesKeysBadge.cloud(keyed = 1, unkeyed = 2), {}, {}, {}) } } }
    }

    /**
     * text-size-system (pm, 2026-11-06: iOS found its chat-list rows on fixed sizes): the chat list — searching, so a
     * title match and a snippet are both drawn — and the chat with its composer, at 200%, to show they grow.
     */
    @Test fun chatsList_largestText() {
        val now = 1_756_900_000_000L
        val saved = java.util.TimeZone.getDefault(); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            val threads = listOf(ai.teemoon.chat.ThreadEntity("t1", "Montréal trip notes from near.ai", now - 60_000),
                ai.teemoon.chat.ThreadEntity("t2", "what is a trusted execution environment?", now - 3_600_000))
            val hit = ai.teemoon.chat.search.ChatSearchResult("m2", "t2", "user", now, "flights to \u0001montreal\u0002 in may?")
            paparazzi.snapshot { TeemoonTheme { ThreadsScreen(threads = threads, onOpen = {}, onNew = {}, onClose = {}, previews = mapOf("t1" to "a mock reply."),
                initialQuery = "montreal", onSearch = { listOf(hit) }, nowMs = { now }) } }
        } finally { java.util.TimeZone.setDefault(saved) }
    }

    /** The composer with a typed question at 200% (the transcript's own scroll settling made a whole-chat render flaky). */
    @Test fun composer_largestText() {
        paparazzi.snapshot { TeemoonTheme { Surface { Composer(value = "so my data never leaves the enclave?", onValueChange = {}, streaming = false, onSend = {}, onStop = {}) } } }
    }

    private fun importPage(state: ChatImportUi) = paparazzi.snapshot { TeemoonTheme { Surface {
        NearImportScreen(state, onChooseFile = {}, onConfirm = {}, onDone = {}, onDismissFailure = {}, onBack = {}) } } }
    private val summary = ai.teemoon.chat.imports.ImportSummary(6, 25, 1, 4, imagesImported = 1)
    private val file = ImportFile("private-chat-export-2026-10-15.json", 24_300, null)
    @Test fun nearImport_start_largestText() = importPage(ChatImportUi.Idle)
    @Test fun nearImport_fileChosen_largestText() = importPage(ChatImportUi.Confirm(summary, file))
    @Test fun nearImport_nothingNew_largestText() = importPage(ChatImportUi.Confirm(ai.teemoon.chat.imports.ImportSummary(chatsAlreadyHere = 6), file))
    @Test fun nearImport_emptyFile_largestText() = importPage(ChatImportUi.Confirm(ai.teemoon.chat.imports.ImportSummary(), file))
    @Test fun nearImport_deleteOffer_largestText() = paparazzi.snapshot { TeemoonTheme { Surface {
        ChatsSettingsScreen(AppSettings(InMemoryPrefs()), onDeleteAllChats = {}, onBack = {})
        ExportDeleteAlert(ExportDelete.Offer(file.name), onDelete = {}, onNotNow = {}, onDismissFailure = {}) } } }
    @Test fun nearImport_importing_largestText() = importPage(ChatImportUi.Importing(read = 4, total = 6))
    @Test fun nearImport_done_largestText() = importPage(ChatImportUi.Finished(summary))
}
