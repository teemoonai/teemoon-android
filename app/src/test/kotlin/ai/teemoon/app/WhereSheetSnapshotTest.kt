// Paparazzi baselines for the Where sheet: the ready/recents/get sections with a warm home machine and a cloud key.
package ai.teemoon.app

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereGetPolicy
import ai.teemoon.providers.WhereRecentEntry
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WhereSheetSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 2200, density = com.android.resources.Density.MEDIUM))

    /** NearAIModelCatalog must initialise OUTSIDE the layoutlib composition: its OkHttp platform probe fails under layoutlib's conscrypt stubs and poisons the class for every later test. */
    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }

    private val mac = Provider(id = "mac", name = "second mac", endpoint = "http://100.100.0.12:11434/v1", model = "gemma4:e2b", requiresAPIKey = false, equippedModels = listOf("qwen3:14b", "gemma4:e2b"))
    private val near = Provider.nearAI.copy(id = "near", equippedModels = listOf(Provider.nearAI.model))
    private val providers = listOf(mac, near)
    private val home = mapOf("mac" to WhereGetPolicy.HomeInfo(LocalServerKind.OLLAMA, 2, setOf("gemma4:e2b")))

    @Test fun whereSheet_readyRecentsGet() {
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = providers, selectedId = "near", problem = null, onSelect = {}, onBack = {},
                hasKey = { it.id == "near" },
                recents = listOf(WhereRecentEntry(mac.copy(model = "gemma4:e2b"), 0L)),
                policyFor = { f -> WhereGetPolicy(f, providers, true, home, { if (it.id == "near") "k" else "" }, { "" }) },
                onRefreshHome = {},
            )
        } } }
    }

    /** The Tensor file landing over the general place: the row is the file's name, the right side just the number. */
    @Test fun whereSheet_tensorDownloadOverTheGeneralPlace() {
        val e2b = LocalModelCatalog.all.first(); val device = LocalModelCatalog.forDevice("Tensor G5")
        val place = Provider.local(e2b).copy(id = "local"); val ps = listOf(place, near)
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = ps, selectedId = "local", problem = null, onSelect = {}, onBack = {}, hasKey = { it.id == "near" },
                installed = { true }, downloads = mapOf(e2b.id to 0.17), downloadNames = mapOf(e2b.id to device.first().displayName),
                policyFor = { f -> WhereGetPolicy(f, ps, true, emptyMap(), { if (it.id == "near") "k" else "" }, { "" }, catalog = device, deviceFileMissing = { true }) },
                onRefreshHome = {},
            )
        } } }
    }

    /** WhereSheetPreviews "where · phone, waiting for wi-fi" (iOS 3b1c494): a parked download says why it isn't moving. */
    @Test fun whereSheet_phone_waitingForWifi() {
        val e2b = LocalModelCatalog.all.first()
        val place = Provider.local(e2b).copy(id = "local"); val ps = listOf(place)
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = ps, selectedId = "local", problem = null, onSelect = {}, onBack = {}, hasKey = { true },
                installed = { false }, downloads = mapOf(e2b.id to 0.42), parkedDownloads = setOf(e2b.id),
                onRefreshHome = {},
            )
        } } }
    }

    /**
     * WhereSheetView's warm/cold for the PHONE row, as iOS shows it: warm while the engine is resident. The policy's
     * isWarm is home-only (iOS's too — the phone's comes from LocalEngineResidency), and Android left the phone row
     * blank (Pixel, 2026-10-04: "why doesnt gemma show warm and cold status like ios?").
     */
    @Test fun whereSheet_phoneWarmAndHomeCold() {
        val e2b = LocalModelCatalog.all.first()
        val place = Provider.local(e2b).copy(id = "local"); val ps = listOf(place, mac)
        val cold = mapOf("mac" to WhereGetPolicy.HomeInfo(LocalServerKind.OLLAMA, 2, emptySet()))
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = ps, selectedId = "local", problem = null, onSelect = {}, onBack = {}, hasKey = { true },
                installed = { true }, phoneWarm = { p -> p.id == "local" },
                policyFor = { f -> WhereGetPolicy(f, ps, true, cold, { "" }, { "" }) },
                onRefreshHome = {},
            )
        } } }
    }

    /** WhereSheetView+FirstRun (iOS cf251b5): the hero's fixed-orange pill with a white label, and the cloud row's six names on one line. */
    @Test fun whereSheet_firstRun() {
        paparazzi.snapshot { TeemoonTheme(dark = true) { Surface {
            WhereSheet(providers = emptyList(), selectedId = null, problem = null, onSelect = {}, onBack = {}, hasKey = { false },
                installed = { false }, onRefreshHome = {})
        } } }
    }

    @Test fun whereSheet_offline_empty() {
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(providers = emptyList(), selectedId = null, problem = null, onSelect = {}, onBack = {},
                policyFor = { f -> WhereGetPolicy(f, emptyList(), false, emptyMap(), { "" }, { "" }) }, networkSatisfied = false, onRefreshHome = {})
        } } }
    }
}
