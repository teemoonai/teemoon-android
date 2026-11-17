// The chat's two edges (docs/ANDROID_NATIVE.md, "Transcript edges"): nothing of the transcript may show inside the title
// band, or beside or under the composer panel. Rendered twice, the second time with every "e" of the messages an "o": the
// chat lands where it always does (the last question anchored half under the title, its reply running under the panel),
// so every pixel that changes is transcript, and none of those may fall in either band.
package ai.teemoon.app

import ai.teemoon.chat.MessageEntity
import ai.teemoon.providers.Provider
import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import java.awt.image.BufferedImage
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * One long reply after each of three questions; [alt] swaps the "e"s of every message for "o"s: the layout stays, the ink
 * changes. [short]: one question and a one-line reply, nothing to scroll.
 */
internal fun edgesVm(alt: Boolean = false, short: Boolean = false): ChatViewModel {
    val p = Provider(id = "n", name = "near.ai", endpoint = "https://cloud-api.near.ai/v1/chat/completions", model = "z-ai/glm-5.2")
    val vm = ChatViewModel(provider = { p })
    val now = 1_756_900_000_000L
    val reply = List(6) { "A trusted execution environment keeps the model's work sealed inside the processor, so the operator of the server sees ciphertext going in and ciphertext coming out, and the attestation report is what lets the phone check that claim before it sends a word." }.joinToString("\n\n")
    fun t(s: String) = if (alt) s.replace('e', 'o') else s
    if (short) { vm.restore(listOf(MessageEntity("u0", "t1", "user", "is it private?", now - 2_000), MessageEntity("a0", "t1", "assistant", "yes: the text is sealed inside the enclave.", now - 1_000))); return vm }
    vm.restore((0 until 3).flatMap { i -> listOf(
        MessageEntity("u$i", "t1", "user", t("question ${i + 1}: how does the enclave keep my text private?"), now - 60_000L + i * 2_000),
        MessageEntity("a$i", "t1", "assistant", t(reply), now - 59_000L + i * 2_000),
    ) })
    return vm
}

/**
 * The chat as the app sets it for near.ai; [keyboard] stands in for the IME. The frame is taken before the open's one-second
 * landing settles (the last question anchored near the top), which is what puts reply text against both edges.
 */
@Composable
internal fun EdgesChat(alt: Boolean = false, dark: Boolean = true, keyboard: Boolean = false, short: Boolean = false, onEdges: ((Int, Int) -> Unit)? = null) {
    TeemoonTheme(dark = dark) {
        Column {
            Box(Modifier.weight(1f)) {
                ChatScreen(focusOnEmpty = false, vm = remember { edgesVm(alt, short) }, title = "glm-5.2 · near.ai", hasProvider = true, onOpenSettings = {}, webSearchState = WebSearchChipState.OFF,
                    whereModel = "glm-5.2", wherePlace = "near.ai", whereLocality = WhereLocality.CLOUD, onOpenWhere = {}, pickImages = { _, _ -> emptyList() },
                    onEdges = onEdges)
            }
            if (keyboard) Box(Modifier.fillMaxWidth().height(KEYBOARD_DP.dp).background(if (dark) Color(0xFF2B2B2E) else Color(0xFFD1D3D9)))
        }
    }
}

private const val KEYBOARD_DP = 300

abstract class ChatEdgesCase(widthDp: Int, private val fontScale: Float = 1f, private val dark: Boolean = true, private val keyboard: Boolean = false) {
    private val frames = mutableListOf<BufferedImage>()
    private val capture = object : SnapshotHandler {
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) { frames += image }
            override fun close() = Unit
        }
        override fun close() = Unit
    }
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = widthDp * 2, screenHeight = SCREEN_PX, density = Density.XHIGH, fontScale = fontScale),
        snapshotHandler = capture)

    // the same near.ai flag DesignSnapshotTest pins: the chip draws the same either way here, but the flag is process-wide
    @Before fun tierListSeen() { ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = true }
    @After fun tierListFollows() { ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = null }

    /** Two renders and the edges, in the frame's pixels: [Pair.first] the title band's last row, [Pair.second] the panel's first. */
    private fun renders(name: String, content: @Composable (Boolean, (Int, Int) -> Unit) -> Unit): Triple<BufferedImage, BufferedImage, Pair<Int, Int>> {
        var title = 0; var panel = 0
        for (variant in listOf(false, true)) paparazzi.snapshot { content(variant) { t, p -> title = t; panel = p } }
        val (a, b) = frames.takeLast(2)
        assertEquals(a.height, b.height)
        // the card's renders (the review page "android-chat-edges"), beside the test results
        java.io.File("build/chat-edges-card").apply { mkdirs() }.let { d -> javax.imageio.ImageIO.write(a, "png", java.io.File(d, "${javaClass.simpleName}-$name.png")) }
        assertTrue("edges measured: title $title, panel $panel", title in 1 until panel)
        // Paparazzi hands over the frame scaled to 1000 px tall; the edges are measured in the screen's pixels. The scaling
        // blends each edge's row with the transcript beside it, so the bands stop one scaled row (~2 px) short of the edge.
        val k = a.height.toDouble() / SCREEN_PX
        return Triple(a, b, (title * k).toInt() - 1 to Math.ceil(panel * k).toInt() + 1)
    }

    @Test fun noTranscriptInsideTheTitleBandOrAroundThePanel() {
        val (a, b, edges) = renders("edges") { alt, onEdges -> EdgesChat(alt, dark = dark, keyboard = keyboard, onEdges = onEdges) }
        val (title, panel) = edges
        fun changed(rows: IntRange): Int = rows.sumOf { y -> (0 until a.width).count { x -> differs(a.getRGB(x, y), b.getRGB(x, y)) } }
        val k = a.height.toDouble() / SCREEN_PX
        val fade = Math.ceil(16 * 2 * k).toInt()   // at most 16 dp above the panel may fade
        // transcript right up to both edges, or the bands are empty for the wrong reason
        val line = (48 * k).toInt()
        assertTrue("reply text just under the title", changed(title until title + 3 * line) > 0)
        assertTrue("reply text just above the panel", changed(panel - fade - 3 * line until panel - fade) > 0)
        assertEquals("transcript pixels inside the title band / beside or under the panel", "0 / 0", "${changed(0 until title)} / ${changed(panel until a.height)}")
    }

    /**
     * No hairline, at rest or scrolled (the maintainer 2026-11-14, "try the default android"; it was the lifted bar's line, which
     * he saw as "a faint line below titlebar"): the bar's last rows are the page's black either way.
     */
    @Test fun noHairlineAtRestOrScrolled() {
        val (resting, lifted, edges) = renders("unlifted") { long, onEdges -> EdgesChat(dark = dark, keyboard = keyboard, short = !long, onEdges = onEdges) }
        val title = edges.first + 1   // the bar's last row, where a hairline would be drawn
        val ground = resting.getRGB(1, title + 4)   // the screen's background, left of the transcript
        // the middle half of the width: the round buttons at either side reach the bar's last rows
        fun inked(img: BufferedImage) = (title - 1..title).sumOf { y -> (img.width / 4 until img.width * 3 / 4).count { x -> differs(img.getRGB(x, y), ground) } }
        assertEquals("hairline pixels on a chat with nothing under the title", 0, inked(resting))
        assertEquals("hairline pixels on a scrolled chat", 0, inked(lifted))
    }

    private companion object { const val SCREEN_PX = 1830 }

    // any channel more than 4/255 apart: a 6% dim of white text over black is ~15 apart, so a dim alone fails
    private fun differs(p: Int, q: Int) = (0..2).any { abs(((p shr (it * 8)) and 0xFF) - ((q shr (it * 8)) and 0xFF)) > 4 }
}

class ChatEdgesTest : ChatEdgesCase(412)
class ChatEdgesSmallestWidthTest : ChatEdgesCase(360)
class ChatEdgesLightTest : ChatEdgesCase(412, dark = false)
class ChatEdgesLargestTextTest : ChatEdgesCase(412, fontScale = 2f)
class ChatEdgesKeyboardTest : ChatEdgesCase(412, keyboard = true)

/** A reply pinned to the end stops at the fade's top: the list's gap above the composer IS the fade, keyboard up or down. */
class ComposerEdgeTest {
    @Test fun theFadeIsTheRestingGap() {
        assertEquals(ComposerEdge.BLOCK_TOP + ComposerEdge.LIST_GAP, ComposerEdge.FADE)
        assertTrue(ComposerEdge.FADE <= 16.dp)
    }
}
