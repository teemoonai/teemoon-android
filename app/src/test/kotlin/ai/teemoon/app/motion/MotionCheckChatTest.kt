// The motion check on the chat (card motion-pass): the keyboard going up and down under the composer. The composer rides
// the keyboard's top in the same frames, one composer throughout, one way, and nothing moves after the keyboard settles.
package ai.teemoon.app.motion

import ai.teemoon.app.ChatScreen
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WebSearchChipState
import ai.teemoon.app.edgesVm
import ai.teemoon.providers.WhereLocality
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Before
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
class MotionCheckChatTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val names = listOf("chat.composer", "chat.transcript")
    private lateinit var keyboard: ScriptedKeyboard
    private val screenDp get() = rule.activity.resources.configuration.screenHeightDp.toFloat()

    @Before fun chat() {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        keyboard = ScriptedKeyboard(rule.activity)
        rule.setContent { TeemoonTheme {
            ChatScreen(focusOnEmpty = false, vm = remember { edgesVm(short = true) }, title = "glm-5.2 · near.ai", hasProvider = true, onOpenSettings = {},
                webSearchState = WebSearchChipState.OFF, whereModel = "glm-5.2", wherePlace = "near.ai", whereLocality = WhereLocality.CLOUD, onOpenWhere = {},
                pickImages = { _, _ -> emptyList() })
        } }
        rule.mainClock.advanceTimeBy(2_000)   // the open's landing settles
    }

    private fun MotionRecorder.keyboardMoves(show: Boolean) = record(700, onFrame = { keyboard.apply(it) }, extra = { keyboard.shot(it, screenDp) }) {
        if (show) keyboard.show() else keyboard.hide()
    }

    @Test fun theComposerRidesTheKeyboardUp() {
        val rec = MotionRecorder(rule, names)
        val f = rec.keyboardMoves(show = true)
        rec.writeStrip("keyboard-up")
        assertNone(MotionRules.oneInstance(f, names))
        assertNone(MotionRules.monotonic(f, "chat.composer", Edge.BOTTOM))
        assertNone(MotionRules.together(f, "chat.composer" to Edge.BOTTOM, "keyboard" to Edge.TOP))
        assertNone(MotionRules.stillAfter(f, listOf("chat.composer"), MotionRules.span(f, "keyboard", Edge.TOP)!!.second))
    }

    @Test fun theComposerRidesTheKeyboardDown() {
        keyboard.show(atMs = -1_000); keyboard.apply(-1); rule.mainClock.advanceTimeBy(500)   // up and settled before the trigger
        val rec = MotionRecorder(rule, names)
        val f = rec.keyboardMoves(show = false)
        rec.writeStrip("keyboard-down")
        assertNone(MotionRules.oneInstance(f, names))
        assertNone(MotionRules.monotonic(f, "chat.composer", Edge.BOTTOM))
        assertNone(MotionRules.together(f, "chat.composer" to Edge.BOTTOM, "keyboard" to Edge.TOP))
    }
}
