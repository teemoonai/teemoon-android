// Paparazzi baselines: the settings root, the web-search setup with no key yet, the chats page with its import row,
// and the import page at each step.
package ai.teemoon.app

import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class SettingsScreensSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 900, density = com.android.resources.Density.MEDIUM))

    /** Settings › appearance: color, then the font group (design, width, size), each row's value beside its chevron. */
    @Test fun appearance() {
        paparazzi.snapshot { TeemoonTheme { Surface { AppearanceSettingsScreen(AppSettings(InMemoryPrefs()), onBack = {}) } } }
    }

    @Test fun settingsRoot() {
        paparazzi.snapshot { TeemoonTheme { Surface { SettingsRootScreen(managedCount = 2, developerMode = true, onSetDeveloperMode = {}, onOpenAppearance = {}, onOpenChats = {}, onOpenSearch = {}, onOpenPlaces = {}, onOpenCredits = {}, onBack = {}) } } }
    }
    /** IMPORT_NEAR.md §5/§8: "import chats" and its footer between haptics and delete all chats. */
    @Test fun chatsSettings_importRow() {
        paparazzi.snapshot { TeemoonTheme { Surface { ChatsSettingsScreen(AppSettings(InMemoryPrefs()), onDeleteAllChats = {}, onBack = {}) } } }
    }
    /** The import page (iOS NearImportView, cdea210c) at each step, with its placeholder words. */
    private fun importPage(state: ChatImportUi) = paparazzi.snapshot { TeemoonTheme { Surface {
        NearImportScreen(state, onChooseFile = {}, onConfirm = {}, onDone = {}, onDismissFailure = {}, onBack = {}) } } }
    // The Safari-style import page's six states (pm canvas, 2026-11-01), plus the dry run reading the file.
    private val file = ImportFile("private-chat-export-2026-10-15.json", 24_300, null)
    private val summary = ai.teemoon.chat.imports.ImportSummary(6, 25, 1, 4, imagesImported = 1)
    @Test fun nearImport_start() = importPage(ChatImportUi.Idle)
    @Test fun nearImport_checking() = importPage(ChatImportUi.Checking(file))
    @Test fun nearImport_fileChosen() = importPage(ChatImportUi.Confirm(summary, file))
    @Test fun nearImport_nothingNew() = importPage(ChatImportUi.Confirm(ai.teemoon.chat.imports.ImportSummary(chatsAlreadyHere = 6), file))
    @Test fun nearImport_emptyFile() = importPage(ChatImportUi.Confirm(ai.teemoon.chat.imports.ImportSummary(), file))
    @Test fun nearImport_importing() = importPage(ChatImportUi.Importing(read = 4, total = 6))
    @Test fun nearImport_done() = importPage(ChatImportUi.Finished(summary))
    @Test fun nearImport_notAnExport() = importPage(ChatImportUi.Failed(ChatImportUi.Failed.Reason.WRONG_FILE))
    // the offer to delete the export over the done step, and its failure (pm 2026-11-01, iOS 478b3bc3)
    // the offer to delete the export, asked once the page has closed to the chats settings (v9, D4), and its failure
    @Test fun nearImport_deleteOffer() = paparazzi.snapshot { TeemoonTheme { Surface {
        ChatsSettingsScreen(AppSettings(InMemoryPrefs()), onDeleteAllChats = {}, onBack = {})
        ExportDeleteAlert(ExportDelete.Offer(file.name), onDelete = {}, onNotNow = {}, onDismissFailure = {}) } } }
    @Test fun nearImport_deleteFailed() = paparazzi.snapshot { TeemoonTheme { Surface {
        ChatsSettingsScreen(AppSettings(InMemoryPrefs()), onDeleteAllChats = {}, onBack = {})
        ExportDeleteAlert(ExportDelete.Failed, onDelete = {}, onNotNow = {}, onDismissFailure = {}) } } }
    /** UI audit #10: the system prompt's own screen, edited (so "reset to default" is live). */
    @Test fun systemPrompt_edited() {
        val settings = AppSettings(InMemoryPrefs()).apply { systemPrompt = "You are terse. Today's date is {{date}}." }
        paparazzi.snapshot { TeemoonTheme { Surface { SystemPromptScreen(settings, onBack = {}) } } }
    }
    @Test fun searchSettings_noKeyYet() {
        paparazzi.snapshot { TeemoonTheme { Surface { SearchSettingsScreen(activeProviderHasBuiltInGrounding = false, storedKey = null, groundingEnabled = false, onSaveKey = { null }, onSetEnabled = {}, onCopySensitive = {}, onBack = {}) } } }
    }
}
