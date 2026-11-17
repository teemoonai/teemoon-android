// The image viewer under the motion check (card motion-pass item 7): a double tap zooms the photo to 2.5x and back
// with motion, one way each, where it cut from one size to the other in a frame.
package ai.teemoon.app.motion

import ai.teemoon.app.ChatImage
import ai.teemoon.app.ImageViewer
import ai.teemoon.app.TeemoonTheme
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
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
class MotionCheckViewerTest {
    @get:Rule val rule = createComposeRule()

    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(600, 400, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE67828.toInt()) }
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun aDoubleTapZoomsWithMotionBothWays() {
        val img = ChatImage("p#0", "image/png", 600, 400, "", png())
        rule.setContent { TeemoonTheme { ImageViewer(listOf(img), 0, bytes = { png() }, onShare = null, onDone = {}) } }
        rule.mainClock.advanceTimeBy(500)
        // the viewer is a dialog: a window of its own, so no single root to picture — the numbers only
        val photo = { rule.onAllNodesWithTag("image.photo", useUnmergedTree = true)[0] }
        val names = listOf("image.photo")
        val inn = MotionRecorder(rule, names, pictures = false).record(600) { photo().performTouchInput { doubleClick() } }
        val w = inn.mapNotNull { it.one("image.photo")?.let { s -> s.right - s.left } }
        assertTrue("it zooms in to 2.5x: ${w.first()} → ${w.last()}", w.last() > w.first() * 2.4f)
        assertNone(MotionRules.monotonic(inn, "image.photo", Edge.LEFT))   // from the frame before the tap: a cut is a jump
        rule.mainClock.advanceTimeBy(500)
        val out = MotionRecorder(rule, names, pictures = false).record(600) { photo().performTouchInput { doubleClick() } }
        val w2 = out.mapNotNull { it.one("image.photo")?.let { s -> s.right - s.left } }
        assertTrue("and back to 1x: ${w2.first()} → ${w2.last()}", w2.last() < w2.first() / 2.4f)
        assertNone(MotionRules.monotonic(out, "image.photo", Edge.LEFT))
    }
}
