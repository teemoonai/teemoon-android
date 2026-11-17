// The motion check on a photo on its way (the maintainer 2026-11-14, the pending-photo prototype approved with the photo fading
// in): its placeholder tile is in the strip from the frame the pick returns, send is off while it waits, and the photo
// then takes the tile's place by fading in — the strip neither grows nor jumps, the panel's top stays, the photo does
// not scale from 0.6 as a new photo does.
package ai.teemoon.app.motion

import ai.teemoon.app.AppSettings
import ai.teemoon.app.ChatScreen
import ai.teemoon.app.ChatViewModel
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WebSearchChipState
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
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
class MotionCheckPendingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun photo(): ImageDecodeFrame.Decoded {
        val bmp = android.graphics.Bitmap.createBitmap(96, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B82F6.toInt()) }
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return ImageDecodeFrame.Decoded("image/png", 600, 400, png, png)
    }

    private fun chat(): ChatViewModel {
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }).also { it.restore(emptyList()) }
        rule.setContent { TeemoonTheme {
            ChatScreen(focusOnEmpty = false, vm = vm, title = "chat", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
                whereModel = "glm-5.3-flash", wherePlace = "fireworks", whereLocality = WhereLocality.CLOUD, onOpenWhere = {}, pickImages = { _, _ -> emptyList() })
        } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onAllNodesWithTag("chat.composer", useUnmergedTree = true)[0].performTextInput("what is this?")
        rule.mainClock.advanceTimeBy(500)
        return vm
    }

    private fun sendEnabled() = rule.onAllNodesWithTag("chat.send", useUnmergedTree = true).fetchSemanticsNodes().first().config.contains(SemanticsProperties.Disabled).not()

    @Test fun aTileIsThereAtOnceAndSendWaits() {
        val vm = chat()
        assertTrue("text typed: send is on", sendEnabled())
        val rec = MotionRecorder(rule, listOf("chat.image.pending", "chat.strip"), pictures = false)
        val f = rec.record(400) { vm.pendingStarted(1) }
        val first = f.first { it.ms >= 0 }
        assertTrue("the tile is in the strip on the frame the pick returns: ${first.shots["chat.image.pending"]}", first.shots["chat.image.pending"]?.size == 1)
        // it fades in, as iOS's does (design review of d64e430f): a start under 0.9 that only rises to 1
        val tileAlphas = f.filter { it.ms >= 0 }.mapNotNull { it.one("chat.image.pending")?.alpha }
        assertTrue("the tile fades in: $tileAlphas", tileAlphas.first() < 0.9f && tileAlphas.last() > 0.99f)
        // TalkBack names it with iOS's words (ChatComposer.swift:492)
        val label = rule.onAllNodesWithTag("chat.image.pending", useUnmergedTree = true).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("loading image"), label)
        assertTrue("send is off while a photo is on its way", !sendEnabled())
        vm.pendingLanded(1, listOf(photo()), limit = 10)
        rule.mainClock.advanceTimeBy(600)
        assertTrue("send is on once it has landed", sendEnabled())
    }

    @Test fun thePhotoFadesInWhereTheTileWas() {
        val vm = chat()
        vm.pendingStarted(1)
        rule.mainClock.advanceTimeBy(1_000)
        val names = listOf("chat.panel", "chat.strip", "chat.image", "chat.image.pending", "chat.image.remove")
        val rec = MotionRecorder(rule, names)
        val f = rec.record(600) { vm.pendingLanded(1, listOf(photo()), limit = 10) }
        rec.writeStrip("pending-land")
        assertNone(MotionRules.inPlaceFromTheFirstFrame(f, "chat.strip", Edge.HEIGHT))
        assertNone(MotionRules.inPlaceFromTheFirstFrame(f, "chat.panel", Edge.TOP))
        assertNone(MotionRules.inPlaceFromTheFirstFrame(f, "chat.image", Edge.LEFT))
        val widths = f.filter { it.ms >= 0 }.mapNotNull { it.one("chat.image")?.let { s -> s.right - s.left } }
        assertTrue("full size from its first frame, no scale-in: $widths", widths.isNotEmpty() && widths.all { kotlin.math.abs(it - 64f) <= 0.5f })
        val alphas = f.filter { it.ms >= 0 }.mapNotNull { it.one("chat.image")?.alpha }
        // Motion's short fade decelerates, so the first frame is already past half: a fade is a start under 0.9 that only rises to 1
        assertTrue("it fades in: $alphas", alphas.first() < 0.9f && alphas.last() > 0.99f && alphas.zipWithNext().all { (x, y) -> y >= x - 0.001f })
        assertTrue("the tile is gone", f.last().shots["chat.image.pending"].isNullOrEmpty())
        // the photo's x fades with it, not drawn at full strength over a bare tile (design review of d64e430f; iOS fades the cell)
        val xAlphas = f.filter { it.ms >= 0 }.mapNotNull { it.one("chat.image.remove")?.alpha }
        assertTrue("the x fades in with the photo: $xAlphas", xAlphas.first() < 0.9f && xAlphas.last() > 0.99f)
    }

    /** A read that fails takes its tile away by fading it, not a cut, and leaves the note. */
    @Test fun aFailedReadFadesItsTileOut() {
        val vm = chat()
        vm.pendingStarted(1)
        rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, listOf("chat.image.pending"), pictures = false)
        val f = rec.record(500) { vm.pendingLanded(1, listOf(ImageDecodeFrame.Rejected(ai.teemoon.app.imaging.ImageRejection.UNREADABLE)), limit = 10) }
        val alphas = f.filter { it.ms >= 0 }.mapNotNull { it.one("chat.image.pending")?.alpha }
        assertTrue("it fades out: $alphas", alphas.isNotEmpty() && alphas.first() > 0.1f && alphas.zipWithNext().all { (x, y) -> y <= x + 0.001f })
        assertTrue("then it is gone", f.last().shots["chat.image.pending"].isNullOrEmpty())
        assertEquals(ai.teemoon.app.imaging.ImageCopy.UNREADABLE, vm.draftImageNote)
    }
}
