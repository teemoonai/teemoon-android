// The image note's place in the composer (the maintainer 2026-11-14, "Makr ur recommended change for android spackng"; iOS
// COMPOSER_PANEL.md): alone it sits 17 dp under the panel's top edge, as the strip's top and the first text line do;
// under photos it sits 6 dp below the strip. It was a flat 8 dp either way — tight alone, loose under a strip.
package ai.teemoon.app.motion

import ai.teemoon.app.Composer
import ai.teemoon.app.TeemoonTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class ComposerNoteSpacingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun thumb() = androidx.compose.ui.graphics.ImageBitmap(64, 64)

    private fun composer(images: Int) = rule.setContent { TeemoonTheme {
        Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {}, onAttach = {},
            images = List(images) { thumb() }, note = "teemoon couldn't read what was shared.")
    } }

    @Test fun aNoteAloneSits17dpUnderThePanelsTop() {
        composer(images = 0)
        val panel = rule.onNodeWithTag("chat.composerPanel", useUnmergedTree = true).getBoundsInRoot()
        val note = rule.onNodeWithTag("chat.imageNote", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(17f, (note.top - panel.top).value, 0.5f)
    }

    @Test fun underPhotosTheNoteSits6dpBelowTheStrip() {
        composer(images = 1)
        rule.mainClock.advanceTimeBy(1_000)
        val strip = rule.onNodeWithTag("chat.strip", useUnmergedTree = true).getBoundsInRoot()
        val note = rule.onNodeWithTag("chat.imageNote", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(6f, (note.top - strip.bottom).value, 0.5f)
    }
}
