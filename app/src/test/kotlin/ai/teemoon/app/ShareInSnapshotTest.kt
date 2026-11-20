// spec: teemoon-ios docs/SHARE_IN.md @ 1f60fe9c — the new chat a share opens, for the review card share-to-teemoon
// (the maintainer 2026-11-12, "I want in 1.1.0"): photos and a link, text alone, over a cap, one photo unreadable.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ShareIn
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ShareInSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 860, density = com.android.resources.Density.MEDIUM))

    /** A shared photo as the intake leaves it: a JPEG and its thumbnail (made inside the snapshot, once layoutlib is up). */
    private fun photo(argb: Int): ImageDecodeFrame.Decoded {
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return ImageDecodeFrame.Decoded("image/jpeg", 96, 96, bytes, bytes)
    }

    // The empty chat draws the moon: pinned to the first quarter the golden images show, not today's phase.
    private fun newChat(draft: () -> ShareIn.Draft) = paparazzi.snapshot { TeemoonTheme { androidx.compose.runtime.CompositionLocalProvider(LocalMoonPhase provides 2) {
        val d = androidx.compose.runtime.remember { draft() }
        // the strip as it stands once the share has landed (on the phone it arrives on its spring a frame after the
        // first draw, which a single snapshot would catch at height 0)
        val vm = androidx.compose.runtime.remember { ChatViewModel(provider = { Provider.fireworks }).also { it.restore(emptyList()); it.draftImages = d.images; it.draftImageNote = d.note } }
        ChatScreen(focusOnEmpty = false, vm = vm, title = "new chat", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
            whereModel = "gemma 4 e2b", wherePlace = "on this device", whereLocality = WhereLocality.PHONE, onOpenWhere = {}, pickImages = { _, _ -> emptyList() },
            sharedDraft = d)
    } } }

    /** A TikTok photo post: three photos and its link, as the draft — nothing sent. */
    @Test fun share_photosAndLink() = newChat { ShareIn.draft("https://www.tiktok.com/@someone/photo/7311", listOf(photo(0xFF3B82F6.toInt()), photo(0xFFF97316.toInt()), photo(0xFF22C55E.toInt()))) }

    /** Text alone (a link shared from a browser is text too). */
    @Test fun share_textOnly() = newChat { ShareIn.draft("worth reading: https://example.com/article", emptyList()) }

    /** Over a cap: nothing kept, the note says why. */
    @Test fun share_overCap() = newChat { ShareIn.overCapDraft() }

    /** The chat a share opens while its two photos are still being read: a tile each, the link in the field, send dimmed. */
    @Test fun share_pending() = newChat { ShareIn.Draft("https://www.tiktok.com/@someone/photo/7311", emptyList(), null, pending = 2, later = kotlinx.coroutines.CompletableDeferred()) }

    /** Over the text cap alone: the note names the text cap (pm 2026-11-14, DRAFT). */
    @Test fun share_textOverCap() = newChat { ShareIn.overCapDraft(ShareIn.TEXT_OVER_CAP) }

    /** One of two photos couldn't be read: the other lands, and the note says so. */
    @Test fun share_oneUnreadable() = newChat { ShareIn.draft("", listOf(photo(0xFF8B5CF6.toInt()), ImageDecodeFrame.Rejected(ai.teemoon.app.imaging.ImageRejection.UNREADABLE))) }
}
