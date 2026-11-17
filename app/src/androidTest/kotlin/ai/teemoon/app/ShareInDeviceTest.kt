package ai.teemoon.app

// android-only: share to teemoon on a device (the maintainer 2026-11-14, as other chat apps do it, in 1.1.0): another app's
// share sheet starts teemoon, which opens a NEW chat with the photos in the composer's strip, the text or link in the
// field with the caret after it, and the keyboard up — and never sends. MainActivity is a standard activity, so a
// share always creates one (onCreate): with the process cold, and with teemoon already open behind it.
// The photos come from the app's own FileProvider with a read grant, as a gallery's would.

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextRange
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareInDeviceTest {
    @get:Rule val compose = emptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    /** A last-used place to land on, whatever the device held (a keyless home server; nothing is sent to it). */
    @Before fun seedAPlace() {
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UiTestSeeding.EXTRA_FLAG, true)
            putExtra(UiTestSeeding.EXTRA_LOCAL_ENDPOINT, "http://127.0.0.1:9/v1")
            putExtra(UiTestSeeding.EXTRA_LOCAL_MODEL, "fake-model")
        }).use { compose.waitUntil(20_000) { shown("chat.composer").isNotEmpty() } }
    }

    /** A real PNG where the app's FileProvider serves it (file_paths.xml: cache/camera/), and its content URI. */
    private fun photo(name: String, color: Int): android.net.Uri {
        val f = File(ctx.cacheDir, "camera/$name.png").apply { parentFile!!.mkdirs() }
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(color) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    private fun share(text: String?, vararg uris: android.net.Uri): Intent = Intent(ctx, MainActivity::class.java).apply {
        action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        type = if (uris.isEmpty()) "text/plain" else "image/png"
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
        else uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) }
        // the grant a chooser hands over: on the intent's ClipData, as Android builds it from EXTRA_STREAM
        if (uris.isNotEmpty()) clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f }

    /** The new chat's draft: [photos] in the strip, [text] in the field with the caret after it, the field focused, nothing sent. */
    private fun draftLands(photos: Int, text: String) {
        fun fieldText() = shown("chat.composer").firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
        // the share's own arrival, not the launch's first frame: a text-only share has no photo to wait for
        runCatching { compose.waitUntil(20_000) { shown("chat.image.remove").size == photos && fieldText() == text } }
        val field = shown("chat.composer").first()
        assertEquals("the photos are in the strip", photos, shown("chat.image.remove").size)
        assertEquals("the shared text is the field's text", text, fieldText().orEmpty())
        assertEquals("the caret is after it", TextRange(text.length), field.config.getOrElse(SemanticsProperties.TextSelectionRange) { TextRange.Zero })
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("chat.composer") and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // never sent: no stop button, no message in the new chat, a few seconds on
        Thread.sleep(3_000)
        assertTrue("nothing is generating", shown("chat.stop").isEmpty())
        assertTrue("the new chat has no messages", shown("chat.lastQuestion").isEmpty())
    }

    @Test fun aPhotoAndTextOpenANewChatWithThemAsTheDraft() {
        // "a new chat" is an EMPTY one: newOrCurrentThread reuses the current chat when it has no messages, so the
        // count need not grow; draftLands holds that the chat it lands in has none
        ActivityScenario.launch<MainActivity>(share("what is this?", photo("share-a", Color.rgb(230, 120, 40)))).use {
            draftLands(photos = 1, text = "what is this?")
        }
    }

    @Test fun severalPhotosLandInOrderWithAnEmptyField() {
        ActivityScenario.launch<MainActivity>(share(null, photo("share-b", Color.RED), photo("share-c", Color.BLUE), photo("share-d", Color.GREEN))).use {
            draftLands(photos = 3, text = "")
        }
    }

    @Test fun aLinkIsTheFieldsText() {
        ActivityScenario.launch<MainActivity>(share("https://example.com/tides")).use { draftLands(photos = 0, text = "https://example.com/tides") }
    }

    /** teemoon already open on a chat: the share still lands in a NEW chat with its draft (the open chat is left as it was). */
    @Test fun withTeemoonAlreadyOpenTheShareStillLands() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { shown("chat.composer").isNotEmpty() }
            ctx.startActivity(share("and this?", photo("share-e", Color.MAGENTA)))
            draftLands(photos = 1, text = "and this?")
        }
    }
}
