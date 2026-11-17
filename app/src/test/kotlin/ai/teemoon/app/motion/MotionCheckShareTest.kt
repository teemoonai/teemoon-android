// The motion check on share to teemoon (design review of share-in, 2026-11-14): the new chat a share opens has its
// photos in the strip from its first frame. They reached the composer in a LaunchedEffect, a frame after the first
// draw, so the strip played a photo's arrival (the panel grew, the photo sprang in and poked out over the panel's top)
// on a chat that opened with it. The text already avoided that by being the field's first value.
package ai.teemoon.app.motion

import ai.teemoon.app.AppSettings
import ai.teemoon.app.ChatScreen
import ai.teemoon.app.ChatViewModel
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WebSearchChipState
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.app.imaging.ShareIn
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun assertNone(v: List<Violation>) = assertEquals(v.joinToString("\n"), emptyList<Violation>(), v)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class MotionCheckShareTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun photo(argb: Int): ImageDecodeFrame.Decoded {
        val bmp = android.graphics.Bitmap.createBitmap(96, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return ImageDecodeFrame.Decoded("image/png", 600, 400, png, png)
    }

    private val names = listOf("chat.panel", "chat.strip", "chat.image.remove")

    /** The chat a share opens: composed with the draft, as Root keys a new ChatScreen to it; the strip never arrives, it is there. */
    @Test fun aSharesPhotosAreInTheStripFromTheFirstFrame() {
        val draft = ShareIn.draft("what is this?", listOf(photo(0xFF3B82F6.toInt()), photo(0xFFF97316.toInt())))
        var open by mutableStateOf(false)
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }).also { it.restore(emptyList()) }
        rule.setContent { TeemoonTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            if (open) ChatScreen(focusOnEmpty = false, vm = vm, title = "chat", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
                whereModel = "glm-5.3-flash", wherePlace = "fireworks", whereLocality = WhereLocality.CLOUD, onOpenWhere = {}, pickImages = { _, _ -> emptyList() },
                sharedDraft = draft)
        } } }
        rule.mainClock.advanceTimeBy(500)
        val rec = MotionRecorder(rule, names)
        val f = rec.record(800) { open = true }
        rec.writeStrip("share-open")
        assertTrue("both photos are there at the end: ${f.last().shots["chat.image.remove"]?.size}", f.last().shots["chat.image.remove"]?.size == 2)
        assertNone(MotionRules.inPlaceFromTheFirstFrame(f, "chat.strip", Edge.HEIGHT))
        assertNone(MotionRules.inPlaceFromTheFirstFrame(f, "chat.panel", Edge.TOP))
    }
}
