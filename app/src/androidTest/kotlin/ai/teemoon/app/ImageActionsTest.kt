// iOS S42 (c14aaa4e; the maintainer 2026-11-11, "operate like messages app"): a chat image's long press opens copy / save /
// share and never the viewer; a tap still opens the viewer. The composer's remove control is a 48dp target around a
// 24dp circle riding past the thumbnail's corner (ChatImageActionsUITests' Android half).
package ai.teemoon.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageActionsTest {
    @get:Rule val compose = createComposeRule()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val image = ChatImage("img-1", "image/jpeg", 64, 48, "attachments/img-1.jpg", png)
    private val done = mutableListOf<String>()
    private var opened = 0

    private fun thumbs() = compose.setContent {
        TeemoonTheme {
            Box(Modifier.padding(16.dp)) {
                ImageThumbRow(listOf(image), { opened++ }, actions = ImageActions({ done += "copy:${it.id}" }, { done += "save:${it.id}" }, { done += "share:${it.id}" }))
            }
        }
    }

    @Test fun aLongPressOpensTheMenuAndNotTheViewer() {
        thumbs()
        compose.onNodeWithTag("chat.image").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("a long press opened the viewer", 0, opened)
        for (t in listOf("copy", "save", "share")) compose.onNodeWithTag("chat.imageMenu.$t").assertExists()
        compose.onNodeWithTag("chat.imageMenu.save").performClick()
        compose.runOnIdle { assertEquals(listOf("save:img-1"), done) }
        compose.onAllNodesWithTag("chat.imageMenu").assertCountEquals(0)
    }

    @Test fun eachMenuRowActsOnTheImage() {
        thumbs()
        for (t in listOf("copy", "share")) {
            compose.onNodeWithTag("chat.image").performTouchInput { longClick() }
            compose.onNodeWithTag("chat.imageMenu.$t").performClick()
        }
        compose.runOnIdle { assertEquals(listOf("copy:img-1", "share:img-1"), done) }
    }

    @Test fun aTapStillOpensTheViewerWithNoMenu() {
        thumbs()
        compose.onNodeWithTag("chat.image").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onAllNodesWithTag("chat.imageMenu").assertCountEquals(0)
    }

    private var removed = mutableListOf<Int>()

    private fun composer() = compose.setContent {
        TeemoonTheme {
            Composer(value = "", onValueChange = {}, streaming = false, onSend = {}, onStop = {},
                images = listOf(ImageBitmap(56, 56), ImageBitmap(56, 56)), onRemoveImage = { removed += it })
        }
    }

    /** 48dp, and a touch near the target's outer corner — outside the 24dp circle — still removes. */
    @Test fun theRemoveTargetIs48dpAndReachesPastTheCircle() {
        composer()
        val x = compose.onAllNodesWithTag("chat.image.remove")[1]
        x.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        x.performTouchInput { click(androidx.compose.ui.geometry.Offset(4.dp.toPx(), height - 4.dp.toPx())) }
        compose.runOnIdle { assertEquals(listOf(1), removed) }
    }
}
