// Records a transition for MotionRules: Compose's clock stepped by hand, 16 ms a frame, and in each frame the root
// bounds (dp) and opacity (MotionAlpha) of every named node, plus a picture of the screen for the frame strip a person reviews.
// Runs under Robolectric (test only; the maintainer "Yes", 2026-11-14) with native graphics, so the pictures are real renders.
package ai.teemoon.app.motion

import ai.teemoon.app.MotionAlpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import java.io.File

class MotionRecorder(private val rule: ComposeContentTestRule, private val names: List<String>, private val pictures: Boolean = true) {
    val frames = ArrayList<Frame>()
    private val shots = ArrayList<Pair<Long, ImageBitmap>>()

    init { rule.mainClock.autoAdvance = false }

    /**
     * One frame on both clocks: Compose's, and the main looper's (Robolectric's SystemClock and Dispatchers.Main, where the
     * view model's turn and the transcript's settle timers run). Stepping Compose's alone froze everything that waits on
     * the other: a send never scrolled.
     */
    fun frame() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        rule.mainClock.advanceTimeByFrame()
    }

    private fun capture(ms: Long, extra: Map<String, List<Shot>> = emptyMap()) {
        frames += Frame(ms, extra + names.associateWith { n ->
            rule.onAllNodesWithTag(n, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                // unclipped: a page sliding in from the left edge is off screen, not at 0 (boundsInRoot clips to the root)
                val c = node.layoutInfo.coordinates
                val b = c.findRootCoordinates().localBoundingBoxOf(c, clipBounds = false); val d = rule.density.density
                Shot(b.left / d, b.top / d, b.right / d, b.bottom / d, node.config.getOrNull(MotionAlpha) ?: 1f)
            }
        }.filterValues { it.isNotEmpty() })
        if (pictures) shots += ms to rule.onRoot().captureToImage()
    }

    /**
     * The frame before [trigger], the trigger's own frame (ms 0) and every 16 ms after it for [forMs]. [onFrame] runs
     * before each frame from the trigger on (the scripted keyboard sets its height there); [extra] adds what is not a node
     * (that keyboard's own box) to the frame.
     */
    fun record(forMs: Long = 1_000, onFrame: (Long) -> Unit = {}, extra: (Long) -> Map<String, List<Shot>> = { emptyMap() }, trigger: () -> Unit): List<Frame> {
        frame(); capture(-16, extra(-16))
        rule.runOnIdle { trigger() }
        var t = 0L
        while (t <= forMs) { rule.runOnIdle { onFrame(t) }; frame(); capture(t, extra(t)); t += 16 }
        return frames
    }

    /**
     * build/motion/<name>.png — a strip of the first [count] frames from the one before the trigger, six a row, scaled to
     * [cellWidth] — and <name>.tsv, every frame's numbers. For review only: never compared, never committed.
     */
    fun writeStrip(name: String, count: Int = 36, cellWidth: Int = 180) {
        val dir = File("build/motion").apply { mkdirs() }
        File(dir, "$name.tsv").writeText("ms\tname\tleft\ttop\tright\tbottom\talpha\n" + frames.joinToString("") { f ->
            f.shots.entries.joinToString("") { (n, ss) -> ss.joinToString("") { s -> "${f.ms}\t$n\t${s.left}\t${s.top}\t${s.right}\t${s.bottom}\t${s.alpha}\n" } }
        })
        if (shots.isEmpty()) return
        val pick = shots.take(count)
        val first = pick.first().second.asAndroidBitmap()
        val cellH = first.height * cellWidth / first.width
        val cols = 6; val rows = (pick.size + cols - 1) / cols
        val out = java.awt.image.BufferedImage(cols * (cellWidth + 4), rows * (cellH + 18), java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = java.awt.Color.GRAY; g.fillRect(0, 0, out.width, out.height)
        pick.forEachIndexed { i, (ms, img) ->
            val bmp = img.asAndroidBitmap()
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val src = java.awt.image.BufferedImage(bmp.width, bmp.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, bmp.width, bmp.height, px, 0, bmp.width) }
            val x = (i % cols) * (cellWidth + 4); val y = (i / cols) * (cellH + 18)
            g.drawImage(src, x, y + 16, cellWidth, cellH, null)
            g.color = java.awt.Color.WHITE; g.drawString("${ms} ms", x + 4, y + 12)
        }
        g.dispose()
        javax.imageio.ImageIO.write(out, "png", File(dir, "$name.png"))
    }
}
