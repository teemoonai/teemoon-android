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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WhereSheetSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 2200, density = com.android.resources.Density.MEDIUM))

    /** NearAIModelCatalog must initialise OUTSIDE the layoutlib composition: its OkHttp platform probe fails under layoutlib's conscrypt stubs and poisons the class for every later test. */
    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
        // near.ai's tier list seen, as on a phone that loaded it: whether glm-5.3-flash reads "end-to-end encrypted"
        // otherwise depended on which tests ran first, and record and verify ran them differently (S44, 2026-11-11)
        NearAIModelCatalog.tierListSeenOverride = true
    }

    @After fun forgetTierList() { NearAIModelCatalog.tierListSeenOverride = null }

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

    /**
     * IMAGES.md §4e (S44): "reads images" on, as the can't-see routes open the sheet — the count reads "1 of 3", the
     * control is tinted, the mac's text-only model and the recent that can't see are gone, near.ai's row reads "18 of 42"
     * from its saved list, and brave answers hides.
     */
    @Test fun whereSheet_showOnlyReadsImages() {
        paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = providers, selectedId = "near", problem = null, onSelect = {}, onBack = {},
                hasKey = { it.id == "near" }, readsImages = { it.model == "gemma4:e2b" },
                recents = listOf(WhereRecentEntry(mac.copy(model = "qwen3:14b"), 0L)),
                policyFor = { f -> WhereGetPolicy(f, providers, true, home, { if (it.id == "near") "k" else "" }, { "" }) },
                listCount = { p, f -> if (p.id == "near") (if (f.isEmpty()) 42 else 18) else null },
                initialShowOnly = setOf(ai.teemoon.providers.ModelBrowserQuery.Filter.VISION),
                onRefreshHome = {},
            )
        } } }
    }

    /**
     * A near.ai model that isn't end-to-end encrypted (proxied) is an ordinary choice: its caption is muted like every
     * other non-E2EE place, not orange (review item where-near-unencrypted-muted, the maintainer 2026-11-07).
     */
    @Test fun whereSheet_nearProxiedModelIsMuted() {
        val proxied = near.copy(model = "anthropic/claude-opus-4-6", equippedModels = listOf("anthropic/claude-opus-4-6"))
        val ps = listOf(mac, proxied)
        // near.ai's tier list seen, as on a phone that loaded it: the get row's e2ee tag otherwise depended on test order
        NearAIModelCatalog.tierListSeenOverride = true
        try { paparazzi.snapshot { TeemoonTheme { Surface {
            WhereSheet(
                providers = ps, selectedId = "near", problem = null, onSelect = {}, onBack = {},
                hasKey = { it.id == "near" }, recents = emptyList(),
                policyFor = { f -> WhereGetPolicy(f, ps, true, home, { if (it.id == "near") "k" else "" }, { "" }) },
                onRefreshHome = {},
            )
        } } } } finally { NearAIModelCatalog.tierListSeenOverride = null }
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
