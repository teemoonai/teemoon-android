// android-only: step goldens for the user journeys — what the user SEES at each step of a common flow, compared on
// pixels against a recorded baseline (Paparazzi's idea, on the device, across a whole flow). No iOS source: the iOS
// capture tests produce screenshots for review, never a pass/fail.
package ai.teemoon.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import kotlin.math.abs

/**
 * `golden("connect.where-sheet")` in a journey screenshots the device and compares it with
 * `androidTest/assets/goldens/<width>x<height>/<name>.png` — one set per screen profile. Mismatches are collected and the journey fails at its END, so one drifted
 * step does not hide the steps after it; the actual and a diff image land under `files/journeys/goldens/` for review.
 *
 * Recording: `tools/journey_goldens.sh record` runs the journeys with the runner argument `goldens=record`, which
 * writes every capture instead of comparing, then pulls them into the assets. Baselines are per AVD: the same
 * density, size and keyboard (`tools/parity_capture_compose.sh` documents the AVD).
 *
 * Tolerance: the status bar (the clock) is not compared; a pixel differs when any channel moves more than
 * [CHANNEL_TOLERANCE]; the step fails when more than [PIXEL_BUDGET] of the compared pixels differ. That budget is far
 * above a row's timestamp, a cursor blink or a "thought for 4s" chip, and far below a wrong colour, a missing row, a
 * misplaced sheet or a keyboard that should not be there.
 */
class GoldenRule : TestRule {
    private val mismatches = ArrayList<String>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val record get() = InstrumentationRegistry.getArguments().getString("goldens") == "record"
    private val outDir get() = File(inst.targetContext.getExternalFilesDir(null), "journeys/goldens").apply { mkdirs() }

    fun capture(name: String) {
        val actual = inst.uiAutomation.takeScreenshot() ?: run { mismatches.add("$name: no screenshot"); return }
        // One baseline set PER SCREEN: a golden is pixels, and a Pixel 10 Pro (1080x2410) is not the AVD (1080x2400).
        // Baselines live under goldens/<width>x<height>/, recorded on that screen, and a screen with no set of its
        // own says so rather than failing every step on a size line.
        val profile = "${actual.width}x${actual.height}"
        val out = File(outDir, profile).apply { mkdirs() }
        if (record) { save(actual, File(out, "$name.png")); return }
        val expected = runCatching { inst.context.assets.open("goldens/$profile/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (expected == null) { save(actual, File(out, "$name.png")); mismatches.add("$name: no golden recorded for a $profile screen (tools/journey_goldens.sh record, on this device)"); return }
        val verdict = compare(expected, actual, File(out, "$name.diff.png"))
        if (verdict != null) { save(actual, File(out, "$name.png")); mismatches.add("$name: $verdict") }
    }

    /** Null when within tolerance; otherwise why, with the diff image written. */
    private fun compare(expected: Bitmap, actual: Bitmap, diffOut: File): String? {
        if (expected.width != actual.width || expected.height != actual.height) return "size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height} (a different AVD?)"
        val w = expected.width; val h = expected.height
        val e = IntArray(w * h).also { expected.getPixels(it, 0, w, 0, 0, w, h) }
        val a = IntArray(w * h).also { actual.getPixels(it, 0, w, 0, 0, w, h) }
        val diff = IntArray(w * h)
        var differing = 0; var compared = 0
        for (y in STATUS_BAR_PX until h) for (x in 0 until w) {
            val i = y * w + x; compared++
            val p = e[i]; val q = a[i]
            val d = maxOf(abs(((p shr 16) and 255) - ((q shr 16) and 255)), abs(((p shr 8) and 255) - ((q shr 8) and 255)), abs((p and 255) - (q and 255)))
            if (d > CHANNEL_TOLERANCE) { differing++; diff[i] = 0xFFFF2D55.toInt() } else diff[i] = (0xFF000000.toInt()) or ((((p shr 16) and 255) / 4) shl 16) or ((((p shr 8) and 255) / 4) shl 8) or ((p and 255) / 4)
        }
        val fraction = differing.toDouble() / compared
        if (fraction <= PIXEL_BUDGET) return null
        save(Bitmap.createBitmap(diff, w, h, Bitmap.Config.ARGB_8888), diffOut)
        return "%.2f%% of pixels differ (budget %.2f%%) — see journeys/goldens/".format(fraction * 100, PIXEL_BUDGET * 100)
    }

    private fun save(b: Bitmap, f: File) { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            mismatches.clear()
            base.evaluate()
            if (mismatches.isNotEmpty()) throw AssertionError("golden steps drifted:\n  " + mismatches.joinToString("\n  "))
        }
    }

    companion object {
        /** The status bar carries the clock; nothing under test lives there. */
        const val STATUS_BAR_PX = 90
        const val CHANNEL_TOLERANCE = 32
        const val PIXEL_BUDGET = 0.004
    }
}
