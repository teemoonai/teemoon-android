// The chat's title bar (the maintainer 2026-11-14): Material's default — no hairline under it, its solid black the page's own,
// and that ground reaching 8 dp below the bottom of its round buttons, so text scrolled up disappears cleanly below
// them instead of running into them ("the chat text touches the round buttons"; "you can see a faint line below
// titlebar" — the lifted bar's hairline).
package ai.teemoon.app.motion

import ai.teemoon.app.AppSettings
import ai.teemoon.app.ChatScreen
import ai.teemoon.app.ChatViewModel
import ai.teemoon.app.TeemoonTheme
import ai.teemoon.app.WebSearchChipState
import ai.teemoon.chat.MessageEntity
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class TitleGroundTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** A long chat scrolled so text sits under the bar. */
    private fun scrolledChat() {
        val vm = ChatViewModel(provider = { Provider.fireworks }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT })
        val now = 1_756_900_000_000L
        val para = List(6) { "A trusted execution environment keeps the model's work sealed inside the processor, so the operator sees ciphertext." }.joinToString(" ")
        vm.restore((0 until 12).flatMap { i -> listOf(MessageEntity("u$i", "t1", "user", "question $i", now + i * 2_000), MessageEntity("a$i", "t1", "assistant", para, now + i * 2_000 + 1_000)) })
        rule.setContent { TeemoonTheme {
            ChatScreen(focusOnEmpty = false, vm = vm, title = "a long chat", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
                whereModel = "glm-5.3-flash", wherePlace = "fireworks", whereLocality = WhereLocality.CLOUD, onOpenWhere = {}, pickImages = { _, _ -> emptyList() })
        } }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag("chat.transcript", useUnmergedTree = true).performScrollToIndex(8)
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test fun theBarsGroundReaches8dpBelowItsButtons() {
        scrolledChat()
        val bar = rule.onNodeWithTag("chat.titleBar", useUnmergedTree = true).getBoundsInRoot()
        // the merged node is the 44 dp clickable circle (the unmerged one is its 22 dp icon)
        val button = rule.onNodeWithContentDescription("settings").getBoundsInRoot()
        assertTrue("the ground ends ${(bar.bottom - button.bottom).value} dp below the button; 8 wanted", bar.bottom.value >= button.bottom.value + 8f - 0.5f)
    }

    @Test fun noHairlineUnderTheBarWhileScrolled() {
        scrolledChat()
        val bar = rule.onNodeWithTag("chat.titleBar", useUnmergedTree = true).getBoundsInRoot()
        val density = rule.activity.resources.displayMetrics.density
        val img = rule.onRoot().captureToImage().toPixelMap()
        val row = (bar.bottom.value * density).toInt() - 1
        val lit = (0 until img.width).count { x -> val c = img[x, row]; c.red > 0.02f || c.green > 0.02f || c.blue > 0.02f }
        assertTrue("the bar's last row is page black, no hairline: $lit of ${img.width} px lit", lit == 0)
    }
}
