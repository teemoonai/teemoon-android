// The JVM has no keyboard: the motion check plays one. Its height follows the curve measured on the AVD (SendMotionTest,
// 2026-11-13: ~345 ms, decelerating), delivered to the window as the system delivers it — an ime inset on the decor view.
package ai.teemoon.app.motion

import android.app.Activity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.pow

class ScriptedKeyboard(private val activity: Activity, private val heightDp: Float = 300f, private val durationMs: Long = 345) {
    private val density get() = activity.resources.displayMetrics.density
    private var from = 0f; private var to = 0f; private var startMs = 0L
    /** The height now, dp: ease-out cubic from where it was to where it goes. */
    fun heightAt(ms: Long): Float {
        val f = ((ms - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * (1 - (1 - f).pow(3))
    }
    fun show(atMs: Long = 0) { from = heightAt(atMs); to = heightDp; startMs = atMs }
    fun hide(atMs: Long = 0) { from = heightAt(atMs); to = 0f; startMs = atMs }
    /** Sets the window's ime inset to the height at [ms]. */
    fun apply(ms: Long) {
        val px = (heightAt(ms) * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.ime(), px > 0).build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
    }
    /** The keyboard as a frame entry: its top edge in the root's dp. */
    fun shot(ms: Long, screenHeightDp: Float): Map<String, List<Shot>> {
        val h = heightAt(ms)
        return if (h < 0.5f) emptyMap() else mapOf("keyboard" to listOf(Shot(0f, screenHeightDp - h, 411f, screenHeightDp)))
    }
}
