// images-model-marker (iOS b8457654, pm evidence 1.1-ui/ios/images-model-marker): the paperclip's note naming a model
// that reads images, the one with none, the phone chat that would leave the phone, the send-refused alert's switch, the
// browser's photo marker under its "reads images" filter, and "· images" on the where sheet — at default size on a
// 320 dp phone, and the note and the browser again at the largest text size (200%) on a 412 dp one.
package ai.teemoon.app

import androidx.compose.foundation.layout.width
import ai.teemoon.app.imaging.ImageCopy
import ai.teemoon.app.imaging.ImageModelChoice
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.ModelBrowserDoor
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.ModelCapability
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereGetPolicy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

abstract class ImageModelMarkerShots(fontScale: Float, width: Int) {
    private val largest = fontScale > 1f
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = width, screenHeight = 900, density = com.android.resources.Density.MEDIUM, fontScale = fontScale))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    /** The "+" (IMAGES.md §4d): tint on every model; it always opens the attach sheet. */
    @Test fun attach_states() = paparazzi.snapshot { TeemoonTheme { Surface {
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {})
        }
    } } }

    /** The attach sheet (IMAGES.md §4d): take photo and choose photos side by side, the footnote under them. */
    @Test fun attach_sheet() = paparazzi.snapshot { TeemoonTheme { Surface(color = TeemoonColors.groupedBackground) {
        AttachSheet(AttachSheetRequest(ImageCopy.attachSheet(hasCamera = true), null, act = {}), onDismiss = {})
    } } }

    /** On a model that can't see images (5722f76d): the line and "choose an image-capable model" above the two buttons. */
    @Test fun attach_sheetCantSee() = paparazzi.snapshot { TeemoonTheme { Surface(color = TeemoonColors.groupedBackground) {
        AttachSheet(AttachSheetRequest(ImageCopy.attachSheet(hasCamera = true), null, {}, cantSee = ai.teemoon.app.imaging.CantSeeRoute.sheetLine("glm 5.3")), onDismiss = {})
    } } }

    /** Full: "+" still opens the sheet, and the limit's note shows there instead of the buttons acting. */
    @Test fun attach_sheetFull() = paparazzi.snapshot { TeemoonTheme { Surface(color = TeemoonColors.groupedBackground) {
        AttachSheet(AttachSheetRequest(ImageCopy.attachSheet(hasCamera = true), ImageCopy.upTo(10), act = {}), onDismiss = {})
    } } }

    /** The send refused (IMAGES.md §4d): "choose an image-capable model" above "ok"; it opens the filtered where sheet. */
    @Test fun imageModel_sendRefused() = paparazzi.snapshot { TeemoonTheme { Surface {
        IosAlert(ai.teemoon.app.imaging.CantSeeRoute.alertTitle("glm-5p3"), ai.teemoon.app.imaging.CantSeeRoute.alertMessage(hasText = false),
            ai.teemoon.app.imaging.CantSeeRoute.alertButtons(hasText = false).map { it to {} }, onDismiss = {}) } } }

    /** "find a model that reads images": the server's browser opened with the filter on; a photo after each reader's name. */
    @Test fun imageModel_browserFiltered() {
        val vision = setOf(ModelCapability.VISION)
        val rows = listOf(
            KnownModel("accounts/fireworks/models/glm-5p3-flash", "GLM 5.3 Flash", "z-ai", "\$0.30/\$1.20", contextWindow = "200K", capabilities = vision),
            KnownModel("accounts/fireworks/models/kimi-k3", "Kimi K3", "Moonshot", "\$0.60/\$2.50", contextWindow = "256K", capabilities = vision, isNew = true),
            KnownModel("accounts/fireworks/models/qwen3p6-vl-235b-a22b-instruct", "Qwen3.6 VL 235B A22B Instruct", "Qwen", "\$0.22/\$0.88", contextWindow = "256K", capabilities = vision),
            KnownModel("accounts/fireworks/models/glm-5p3", "GLM 5.3", "z-ai", "\$0.55/\$2.19", contextWindow = "200K"),
        )
        paparazzi.snapshot { TeemoonTheme { Surface { ModelBrowserScreen(ModelBrowserDoor(models = rows), onSelect = {}, onCancel = {},
            selectedModel = "accounts/fireworks/models/glm-5p3", initialQuery = ModelBrowserQuery(filters = setOf(ModelBrowserQuery.Filter.VISION))) } } }
    }

    /**
     * "· images" ends the caption of the model that reads them; the one that can't has none. Only "ready now" is drawn:
     * near.ai's e2ee tag under "get" follows tier state other suites leave behind, and is not this shot's subject.
     */
    @Test fun imageModel_whereCaption() {
        val fw = Provider.fireworks.copy(model = "accounts/fireworks/models/glm-5p3", equippedModels = listOf("accounts/fireworks/models/glm-5p3", "accounts/fireworks/models/glm-5p3-flash"))
        paparazzi.snapshot { TeemoonTheme { Surface(Modifier.height(if (largest) 520.dp else 320.dp)) {
            WhereSheet(providers = listOf(fw), selectedId = fw.id, problem = null, onSelect = {}, onBack = {}, hasKey = { true },
                readsImages = { it.model.endsWith("flash") },
                policyFor = { f -> WhereGetPolicy(f, listOf(fw), true, emptyMap(), { "k" }, { "" }) }, onRefreshHome = {})
        } } }
    }
}

class ImageModelMarkerSnapshotTest : ImageModelMarkerShots(fontScale = 1f, width = 320)
class ImageModelMarkerLargestTextSnapshotTest : ImageModelMarkerShots(fontScale = 2f, width = 412)
