// Paparazzi baselines: the settings root and the web-search setup with no key yet.
package ai.teemoon.app

import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class SettingsScreensSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 900, density = com.android.resources.Density.MEDIUM))

    @Test fun settingsRoot() {
        paparazzi.snapshot { TeemoonTheme { Surface { SettingsRootScreen(managedCount = 2, developerMode = true, onSetDeveloperMode = {}, onOpenAppearance = {}, onOpenChats = {}, onOpenSearch = {}, onOpenPlaces = {}, onOpenCredits = {}, onBack = {}) } } }
    }
    @Test fun searchSettings_noKeyYet() {
        paparazzi.snapshot { TeemoonTheme { Surface { SearchSettingsScreen(activeProviderHasBuiltInGrounding = false, storedKey = null, groundingEnabled = false, onSaveKey = { null }, onSetEnabled = {}, onCopySensitive = {}, onBack = {}) } } }
    }
}
