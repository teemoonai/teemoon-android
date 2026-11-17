// the paste rule shared with iOS (pm for the maintainer, 2026-11-07): the composer's own paste — the field's paste action,
// as the long-press menu and the keyboard's clipboard use — hands an image clip to the composer and keeps a text one.
package ai.teemoon.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerPasteDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val field get() = compose.onNodeWithTag("chat.composer")
    private var pasted: Pair<List<String>, List<String?>>? = null

    private var dataUrls = mutableListOf<String>()
    private fun host(initial: String = "") = compose.setContent {
        var prompt by remember { mutableStateOf(initial) }
        TeemoonTheme { Composer(value = prompt, onValueChange = { prompt = it }, streaming = false, onSend = {}, onStop = {},
            onPasteImages = { types, items, dataUrl -> pasted = types to items.map { it.uri }; dataUrl?.let { dataUrls += it } },
            onPasteDataUrl = { dataUrls += it }) }
    }
    private fun clip(c: ClipData) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(c)
    }
    private fun imageUri(): android.net.Uri {
        val f = java.io.File(context.filesDir, "attachments").apply { mkdirs() }.let { java.io.File(it, "paste-test.png") }
        f.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    @Test fun anImageClipGoesToTheComposerAndLeavesTheFieldEmpty() {
        val uri = imageUri()
        clip(ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(uri)))
        host(); field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        compose.runOnIdle { assertEquals(listOf("image/png") to listOf(uri.toString()), pasted) }
        // the field's own text, not the placeholder the decorator shows beside it
        field.assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun aTextClipIsTheFieldsAsBefore() {
        clip(ClipData.newPlainText("t", "just words"))
        host(); field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        field.assertTextEquals("just words")
        compose.runOnIdle { assertEquals(null, pasted) }
    }

    /** Words and an image in one paste are text — the words go in, no URI as text, nothing attached. */
    @Test fun aClipWithTextAndAnImageGivesTheFieldOnlyItsText() {
        val uri = imageUri()
        clip(ClipData(ClipDescription("mixed", arrayOf("text/plain", "image/png")), ClipData.Item("look at this")).apply { addItem(ClipData.Item(uri)) })
        host(); field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        field.assertTextEquals("look at this")
        compose.runOnIdle { assertEquals(null, pasted) }
    }

    // IMAGES.md §4c, a data: URL as text (iOS ec43cc58; the Android cases ImagePasteTests pins)

    private val jpegUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3), android.util.Base64.NO_WRAP)
    private fun editable() = field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
    private fun pasteText(text: String) { clip(ClipData.newPlainText("t", text)); field.performSemanticsAction(SemanticsActions.PasteText) }

    /** Cases 1 and 2: an empty composer, the URL (blanks around it too) → handed over, the composer still empty. */
    @Test fun aPastedDataUrlIsAnImageNotText() {
        host(); field.performClick()
        pasteText("  \n$jpegUrl\n ")
        compose.runOnIdle { assertEquals(listOf(jpegUrl), dataUrls) }
        assertEquals("", editable())
    }

    /** Cases 3 and 4: pasted at a caret inside text (even beside a `d`) → handed over, the text unchanged. */
    @Test fun aDataUrlPastedInsideTextLeavesTheTextAsItWas() {
        host("hello world"); field.performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(6))
        pasteText(jpegUrl)
        compose.runOnIdle { assertEquals(listOf(jpegUrl), dataUrls) }
        assertEquals("hello world", editable())
    }

    @Test fun aDataUrlPastedBetweenAAndDLeavesAd() {
        host("ad"); field.performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        pasteText(jpegUrl)
        compose.runOnIdle { assertEquals(listOf(jpegUrl), dataUrls) }
        assertEquals("ad", editable())
    }

    /** Case 5: pasted over a selection → handed over, the old text back as it was. */
    @Test fun aDataUrlPastedOverASelectionKeepsTheOldText() {
        host("replace me"); field.performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(0, 10))
        pasteText(jpegUrl)
        compose.runOnIdle { assertEquals(listOf(jpegUrl), dataUrls) }
        assertEquals("replace me", editable())
    }

    /** Case 10, as one item: the URL as an image's text → the image item's rule runs first (§4c), the image attaches. */
    @Test fun anImageItemCarryingTheDataUrlIsTheImage() {
        val uri = imageUri()
        clip(ClipData(ClipDescription("copy", arrayOf("image/jpeg", "text/plain")), ClipData.Item(jpegUrl, null, uri)))
        host(); field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        compose.runOnIdle { assertEquals(listOf("image/jpeg", "text/plain") to listOf(uri.toString()), pasted); assertEquals(emptyList<String>(), dataUrls) }
        assertEquals("", editable())
    }

    /** Case 8: a URL among other words is text, as before. */
    @Test fun aDataUrlAmongWordsIsText() {
        host(); field.performClick()
        pasteText("look $jpegUrl")
        compose.runOnIdle { assertEquals(emptyList<String>(), dataUrls) }
        assertEquals("look $jpegUrl", editable())
    }

    // IMAGES.md §4c, an image item's URL is not text (the maintainer 2026-11-08, "behave like Messages does"; iOS 1af3cd79)

    private val page = "https://example.com/photos/cat.jpg"
    private fun pasteClip(c: ClipData) { clip(c); field.performSemanticsAction(SemanticsActions.PasteText) }
    private fun browserCopy(uri: android.net.Uri) = ClipData(ClipDescription("copy", arrayOf("image/jpeg", "text/plain")), ClipData.Item(page, null, uri))

    /** Case 1: a browser's image copy, one item {JPEG, its https URL} → the image, the composer empty. */
    @Test fun aBrowsersImageCopyAttachesTheImageAndNoText() {
        val uri = imageUri()
        host(); field.performClick()
        pasteClip(browserCopy(uri))
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second) }
        assertEquals("", editable())
    }

    /** Case 3: an image item whose text is words → the words, nothing attached. */
    @Test fun anImageItemWithWordsPastesTheWords() {
        host(); field.performClick()
        pasteClip(ClipData(ClipDescription("copy", arrayOf("image/png", "text/plain")), ClipData.Item("a caption the copy carried", null, imageUri())))
        assertEquals("a caption the copy carried", editable())
        compose.runOnIdle { assertEquals(null, pasted) }
    }

    /** Case 4: mid-text and over a selection → the image, the old text kept. */
    @Test fun aBrowsersImageCopyMidTextKeepsTheText() {
        val uri = imageUri()
        host("hello world"); field.performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(6))
        pasteClip(browserCopy(uri))
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second) }
        assertEquals("hello world", editable())
        pasted = null
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(0, 11))
        pasteClip(browserCopy(uri))
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second) }
        assertEquals("hello world", editable())
    }

    /** Case 5: [{JPEG, URL}, {PNG only}] → both, in order, the composer empty. */
    @Test fun twoImageItemsAttachBoth() {
        val uri = imageUri()
        val second = imageUri().buildUpon().appendQueryParameter("n", "2").build()
        host(); field.performClick()
        pasteClip(browserCopy(uri).apply { addItem(ClipData.Item(second)) })
        compose.runOnIdle { assertEquals(listOf(uri.toString(), second.toString()), pasted?.second) }
        assertEquals("", editable())
    }

    /** Case 6: [{image only}, {URL only}] → the image to the composer, and no text (the maintainer 2026-11-09, like Messages). */
    @Test fun anImageBesideAUrlOnlyItemAttachesOnlyTheImage() {
        val uri = imageUri()
        host(); field.performClick()
        pasteClip(ClipData(ClipDescription("mixed", arrayOf("image/heic", "text/plain")), ClipData.Item(uri)).apply { addItem(ClipData.Item(page)) })
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second?.filterNotNull()) }
        assertEquals("", editable())
    }

    /** A URI under a generic type ("any images copied should work", 2026-11-09): handed to the composer, whose bytes decide. */
    @Test fun aUriUnderAGenericTypeGoesToTheComposer() {
        val uri = imageUri()
        host("hello"); field.performClick()
        pasteClip(ClipData(ClipDescription("file", arrayOf("application/octet-stream")), ClipData.Item(uri)))
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second) }
        assertEquals("hello", editable())
    }

    /** A data: URL text item beside an image item (ios 2026-11-08): both attach, image first, the composer unchanged. */
    @Test fun aDataUrlBesideAnImageItemAttachesBoth() {
        val uri = imageUri()
        host("hello world"); field.performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(6))
        pasteClip(ClipData(ClipDescription("mixed", arrayOf("image/png", "text/plain")), ClipData.Item(uri)).apply { addItem(ClipData.Item(jpegUrl)) })
        compose.runOnIdle { assertEquals(listOf(uri.toString(), null), pasted?.second); assertEquals(listOf(jpegUrl), dataUrls) }
        assertEquals("hello world", editable())
    }

    /** iOS S42, trip.com's shape: an image under the app's own type beside its page URL → the image, and no text. */
    @Test fun anImageUnderAnAppTypeBesideItsUrlAttaches() {
        val uri = imageUri()
        host(); field.performClick()
        pasteClip(ClipData(ClipDescription("app", arrayOf("application/x-app-image", "text/plain")), ClipData.Item(uri)).apply { addItem(ClipData.Item(page)) })
        compose.runOnIdle { assertEquals(listOf(uri.toString()), pasted?.second?.filterNotNull()) }
        assertEquals("", editable())
    }
}
