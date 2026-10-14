// android-only: the copied-key clear that outlives the process. See Clipboard.copySensitive.
package ai.teemoon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivered by a system alarm at a copied key's deadline. The in-process timer clears the key while teemoon lives; if the
 * process died inside the lifetime (swiped away, a low-memory kill) nothing else would — the key stayed indefinitely on
 * Android 10–12 (the local audit of c0ed24c, 2026-10-13). A fresh process cannot read the clipboard without focus, so
 * it cannot check the key is still there: it clears if the clear is still pending, i.e. nothing this app saw replaced
 * the key. Not exported: only the alarm, and the app itself, reach it.
 */
class ClipClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Clipboard.clearIfPending(context.applicationContext)
    }

    companion object {
        const val ACTION = "ai.teemoon.app.CLEAR_COPIED_KEY"
    }
}
