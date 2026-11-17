// The motion check on the composer (card motion-pass): a photo arriving in the strip and leaving by its x, and a text
// send in a chat that already has turns. One panel, one strip, the panel's edge one way; after a send the new question
// travels up to its place in one eased glide, no frame over 96 dp (item 2: it went 102 dp a frame, linear), and stays.
package ai.teemoon.app.motion

import ai.teemoon.app.AppSettings
import ai.teemoon.app.ChatScreen
import ai.teemoon.app.ChatViewModel
import ai.teemoon.chat.MessageEntity
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WebSearchChipState
import ai.teemoon.app.imaging.ImageDecodeFrame
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.Tool
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
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
class MotionCheckComposerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** A short reply streamed over ~0.6 s, as a provider sends one: the send's glide runs while it arrives. */
    private object Wire : GenerationTransport {
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            var text = ""
            for (w in "yes: the operator sees only ciphertext, and the attestation is what lets the phone check that.".split(" ")) {
                kotlinx.coroutines.delay(40); text += (if (text.isEmpty()) "" else " ") + w; onPartial(text)
            }
            return TurnResult(content = text)
        }
    }

    private fun vm(): ChatViewModel {
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }, makeTransport = { _, _, _, _, _, _ -> Wire })
        val now = 1_756_900_000_000L
        val reply = List(5) { "A trusted execution environment keeps the model's work sealed inside the processor, so the operator sees ciphertext going in and out." }.joinToString("\n\n")
        vm.restore((0 until 3).flatMap { i -> listOf(MessageEntity("u$i", "t1", "user", "question ${i + 1}: is it private?", now - 60_000L + i * 2_000), MessageEntity("a$i", "t1", "assistant", reply, now - 59_000L + i * 2_000)) })
        return vm
    }

    private fun chat(vm: ChatViewModel) {
        rule.setContent { TeemoonTheme {
            ChatScreen(focusOnEmpty = false, vm = vm, title = "glm-5.3-flash · fireworks", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
                whereModel = "glm-5.3-flash", wherePlace = "fireworks", whereLocality = WhereLocality.CLOUD, onOpenWhere = {}, pickImages = { _, _ -> emptyList() })
        } }
        rule.mainClock.advanceTimeBy(2_000)
    }

    /** A 600x400 photo's 64 px thumbnail, as the decoder hands it over. */
    private fun photo(): ImageDecodeFrame.Decoded {
        val bmp = android.graphics.Bitmap.createBitmap(96, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE67828.toInt()) }
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return ImageDecodeFrame.Decoded("image/png", 600, 400, png, png)
    }

    private val names = listOf("chat.panel", "chat.strip", "chat.composer", "chat.lastQuestion")

    @Test fun aPhotoArrivesInOneMotion() {
        val vm = vm(); chat(vm)
        val rec = MotionRecorder(rule, names)
        val f = rec.record(700) { vm.draftImagesState.value = listOf(photo()) }
        rec.writeStrip("photo-add")
        assertNone(MotionRules.oneInstance(f, names))
        assertNone(MotionRules.monotonic(f, "chat.panel", Edge.TOP))
        assertNone(MotionRules.monotonic(f, "chat.panel", Edge.HEIGHT))
    }

    @Test fun aPhotoLeavesByItsXInOneMotion() {
        val vm = vm(); chat(vm)
        rule.runOnIdle { vm.draftImagesState.value = listOf(photo()) }; rule.mainClock.advanceTimeBy(1_000)
        val rec = MotionRecorder(rule, names)
        val f = rec.record(700) { rule.onAllNodesWithTag("chat.image.remove", useUnmergedTree = true)[0].performClick() }
        rec.writeStrip("photo-remove")
        assertNone(MotionRules.oneInstance(f, names))
        assertNone(MotionRules.monotonic(f, "chat.panel", Edge.TOP))
        assertNone(MotionRules.monotonic(f, "chat.panel", Edge.HEIGHT))
    }

    @Test fun aSendInAChatWithTurnsGoesUpOnceAndStays() {
        val vm = vm(); chat(vm)
        rule.onAllNodesWithTag("chat.composer", useUnmergedTree = true)[0].performTextInput("and the operator?")
        rule.mainClock.advanceTimeBy(500)
        val rec = MotionRecorder(rule, names)
        val f = rec.record(1_500) { rule.onAllNodesWithTag("chat.send", useUnmergedTree = true)[0].performClick() }
        rec.writeStrip("send-existing", count = 48)
        assertNone(MotionRules.oneInstance(f, names))
        // the name passes from the old question to the new one on the tap's frame: the new one is judged from there
        val asked = f.filter { it.ms >= 0 }
        assertNone(MotionRules.monotonic(asked, "chat.lastQuestion", Edge.TOP))
        val end = MotionRules.span(asked, "chat.lastQuestion", Edge.TOP)?.second ?: 0
        assertNone(MotionRules.stillAfter(asked, listOf("chat.lastQuestion", "chat.panel"), end))
    }
}
