// spec: teemoon-ios docs/COMPOSER_PANEL.md @ 85c544c6 — every state of the one-panel composer for the review card
// `composer-one-panel` (the maintainer 2026-11-12, "One panel, in 1.1.0"): default, choose where, photos with a note and
// three lines, a long model name, sending (stop), downloading, a model that can't take tools, not downloaded, the line
// cap; at the smallest width
// (360 dp), at 2.0 font scale, and in light. One golden per state, at 2x and cropped to the panel: the one-sheet
// version came out 171 px wide (Paparazzi caps a golden at 1000 px), which could neither show the spacing on the card
// nor catch a 1dp change (the design review of e51604dd).
package ai.teemoon.app

import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

abstract class ComposerPanelShots(private val fontScale: Float, width: Int, private val light: Boolean = false) {
    private val config = DeviceConfig.PIXEL_5.copy(screenWidth = width * 2, screenHeight = width * 2,
        density = com.android.resources.Density.XHIGH, fontScale = fontScale,
        nightMode = if (light) com.android.resources.NightMode.NOTNIGHT else com.android.resources.NightMode.NIGHT)
    @get:Rule val paparazzi = Paparazzi(deviceConfig = config)

    private fun swatch(argb: Int) = android.graphics.Bitmap.createBitmap(56, 56, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }.asImageBitmap()
    private fun three() = listOf(swatch(0xFF3B82F6.toInt()), swatch(0xFFF97316.toInt()), swatch(0xFF22C55E.toInt()))

    private fun where(model: String?, place: String? = "on this device", locality: WhereLocality? = WhereLocality.PHONE, progress: Double? = null): @Composable (Boolean) -> Unit =
        { iconOnly -> WhereChip(model, place, locality, onClick = {}, progress = progress, iconOnly = iconOnly) }
    private fun web(state: WebSearchChipState): @Composable (Boolean) -> Unit = { iconOnly -> WebSearchChip(state, onClick = {}, iconOnly = iconOnly) }

    /** Each state on a screen its own height (dp, at font 1.0), so the golden is the panel at 2x and not a sheet scaled down. */
    private fun shot(height: Int = 136, composer: @Composable () -> Unit) {
        // DeviceConfig's sides are pixels (2 per dp at XHIGH); a screen shorter than wide must say landscape, or layoutlib
        // swaps the sides
        val h = (height * 2 * (if (fontScale > 1f) 1.3f else 1f)).toInt()
        paparazzi.unsafeUpdateConfig(deviceConfig = config.copy(screenHeight = h,
            orientation = if (h < config.screenWidth) com.android.resources.ScreenOrientation.LANDSCAPE else com.android.resources.ScreenOrientation.PORTRAIT))
        snap(composer)
    }

    private fun snap(composer: @Composable () -> Unit) = paparazzi.snapshot { TeemoonTheme(dark = !light) { Surface {
        Column(Modifier.padding(16.dp)) { composer() }
    } } }

    /** default: a phone model equipped, web off */
    @Test fun state1_default() = shot { Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.OFF)) }

    /** nothing equipped: "choose where" keeps its accent fill; web disabled (dimmed) */
    @Test fun state2_chooseWhere() = shot { Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, whereChip = where(null, null, null), webChip = web(WebSearchChipState.DISABLED)) }

    /** photos, the note under them, the text, web on. One sentence with no breaks, so it wraps on its own: the text runs
     *  to the 16 inset on BOTH sides (send sits in the row now; nothing in the field keeps space for it). */
    @Test fun state3_photosAndText() = shot(height = 300) {
        Composer(value = "compare these three photos and tell me which one is the brightest, in a single sentence please", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            images = three(), canSend = true, note = "up to 10 images per message", whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.ON))
    }

    /** a photo in, another on its way: its placeholder tile (64 dp, a small grey spinner) and send dimmed until it lands
     *  (the maintainer 2026-11-14, the approved pending-photo prototype) */
    @Test fun state11_photoOnItsWay() = shot(height = 200) {
        Composer(value = "what is this?", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            images = three().take(1), pendingImages = 1, canSend = false, whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.ON))
    }

    /** a photo on its way and nothing else in: the lone tile, the note 6 dp under it (the strip's spacing, not 17) */
    @Test fun state12_pendingOnly() = shot(height = 236) {
        Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            pendingImages = 1, canSend = false, note = "this image couldn't be read", whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.ON))
    }

    /** a long model name on a cloud place, as the app labels it (WhereProviderPresentation.modelLabel, never the raw id):
     *  the where chip truncates; "+", web and send never shrink */
    @Test fun state4_longCloudName() = shot {
        Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            whereChip = where(ai.teemoon.providers.WhereProviderPresentation.modelLabel(ai.teemoon.providers.Provider(id = "f", name = "Fireworks", endpoint = "https://api.fireworks.ai/inference/v1", model = "accounts/fireworks/models/deepseek-v4.1-flash-1017")), "fireworks", WhereLocality.CLOUD), webChip = web(WebSearchChipState.UNCONFIGURED))
    }

    /** sending: the stop square; a model that can't take tools: no web chip, the row closes the gap */
    @Test fun state5_sending() = shot { Composer(value = "", onValueChange = {}, streaming = true, onSend = {}, onStop = {}, onAttach = {}, whereChip = where("gemma 4 e2b"), webChip = null) }

    /** the model downloading */
    @Test fun state6_downloading() = shot { Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, whereChip = where("gemma 4 e4b", progress = 0.61), webChip = web(WebSearchChipState.OFF), whereStatus = true) }

    /** a download parked for wi-fi: the status in words, no glyph */
    @Test fun state7_waitingForWifi() = shot {
        Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            whereChip = { iconOnly -> WhereChip("gemma 4 e4b", "on this device", WhereLocality.PHONE, onClick = {}, progress = 0.2, progressLabel = "waiting for wi-fi", iconOnly = iconOnly) },
            webChip = web(WebSearchChipState.OFF), whereStatus = true)
    }

    /** equipped but not downloaded: the red badge */
    @Test fun state8_notDownloaded() = shot { Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, whereChip = { iconOnly -> WhereChip("gemma 4 e4b", "on this device", WhereLocality.PHONE, onClick = {}, needsDownload = true, iconOnly = iconOnly) }, webChip = web(WebSearchChipState.OFF), whereStatus = true) }

    /** eight lines: the text stops at its cap (6, or 4 at accessibility sizes) and scrolls */
    @Test fun state9_lineCap() = shot(height = 236) { Composer(value = (1..8).joinToString("\n") { "line $it of a long question" }, onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, canSend = true, whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.OFF)) }

    /** the same eight lines left scrolled part-way (half a line and a bit): the text must not stop at a hard line 17
     *  inside the panel, cut through a glyph — it runs on under the panel's edge (pm, iOS's check-7 notes) */
    @Test fun state10_scrolledText() = shot(height = 236) {
        Composer(value = (1..8).joinToString("\n") { "line $it of a long question" }, onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {}, canSend = true,
            whereChip = where("gemma 4 e2b"), webChip = web(WebSearchChipState.OFF), textScroll = androidx.compose.foundation.ScrollState(initial = 52))
    }
}

class ComposerPanelSnapshotTest : ComposerPanelShots(fontScale = 1f, width = 412)
class ComposerPanelSmallestWidthSnapshotTest : ComposerPanelShots(fontScale = 1f, width = 360)
/** font 1.3: the largest size where the chips still show their words; the row must keep one height (iOS's chips grew
 *  taller than its 34 circles at large sizes). */
class ComposerPanelFontThirteenSnapshotTest : ComposerPanelShots(fontScale = 1.3f, width = 412)
class ComposerPanelLargestTextSnapshotTest : ComposerPanelShots(fontScale = 2f, width = 412)
class ComposerPanelLightSnapshotTest : ComposerPanelShots(fontScale = 1f, width = 412, light = true)
