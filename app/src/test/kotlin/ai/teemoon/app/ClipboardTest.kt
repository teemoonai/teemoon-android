// ports: teemoonTests/SensitiveClipboardTests.swift @ v1.0.4 — the half a JVM test can hold.
//
// iOS's three cases are: the copy round-trips, it REPLACES rather than accumulates, and the lifetime is short.
// Only the third survives translation as a unit test, and the reasons are worth writing down rather than leaving
// as a shorter file:
//
//   round-trip   needs a real ClipboardManager, so it belongs in androidTest with the key-copy journey.
//   replaces     is structural here, not behavioural. iOS had to test it because `setItems` can APPEND, so a
//                careless call leaves every key ever copied on the pasteboard and the expiry clears only the
//                newest. Android's `setPrimaryClip` replaces the primary clip by definition — there is no
//                accumulating spelling of it to guard against.
//   lifetime     is the one that matters on both platforms, and it is the one someone widens.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTest {

    /**
     * The bound is a real value, not a placeholder someone can quietly widen to a day.
     *
     * An api key on the clipboard is readable by every app that asks, and on Android it also rides the clipboard
     * into whatever the system does with it. Long enough to switch apps and paste; not long enough to still be
     * there after lunch.
     */
    @Test fun theSensitiveLifetimeIsShort() {
        assertTrue("a credential must not sit on the clipboard indefinitely", Clipboard.SENSITIVE_LIFETIME_SECONDS > 0)
        assertTrue("${Clipboard.SENSITIVE_LIFETIME_SECONDS}s is long enough to forget about",
            Clipboard.SENSITIVE_LIFETIME_SECONDS <= 300)
    }

    /**
     * The exact flag string, because the named constant is API 33 and this ships to older releases. A typo here
     * fails silently in the worst way: the copy works, nothing errors, and the key is simply no longer marked
     * sensitive — so the system clipboard preview shows it and clipboard sync may carry it off the device.
     */
    @Test fun theSensitiveFlagIsTheOneAndroidActuallyReads() {
        assertEquals("android.content.extra.IS_SENSITIVE", Clipboard.EXTRA_IS_SENSITIVE)
    }
}
