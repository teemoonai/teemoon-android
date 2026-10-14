// android-only: no Swift counterpart, and it could not have one — iOS suspends a backgrounded app and cannot hold
// a streaming connection, so a turn that outlives the foreground is a thing only this platform can do. See
// docs/ANDROID_NATIVE.md.
//
// WHY THIS EXISTS. A cloud turn can take minutes. Nothing in teemoon cancels one when the app is backgrounded —
// the turn runs in `viewModelScope`, which is cleared on ViewModel destruction, not on stop — but the PROCESS is
// another matter: measured on a Pixel 10 Pro on 2026-09-21, backgrounding drives `oom_score_adj` from 700 to 900
// within a minute, which is the top of the kill list. It survived five minutes there because nothing else wanted
// the memory; open a camera and it is gone, the generation with it, from a server teemoon does not own and which
// bills for it anyway. A foreground service pins the process near 200 instead. The notification is not a tax on
// the feature — it IS the mechanism.
package ai.teemoon.app

/**
 * What the turn notification says: that an answer is ready, and nothing of the chat.
 *
 * THE EXPOSURE, A PRODUCT DECISION. Anything in a notification leaves everything `PlaintextExposure` enumerates and
 * attests: notification history (a log the app neither controls nor can purge), the lock screen, paired devices, and
 * **any app holding `BIND_NOTIFICATION_LISTENER_SERVICE`** — a watch companion, a desktop bridge, an automation app
 * granted access years ago. From 2026-09-21 the notification carried the reply (up to 380 characters) and the
 * thread's title, on the reasoning that this was the user's own device ecosystem. Reversed 2026-10-13, after the
 * local audit rated it a medium risk: the listener apps are not "the user's own devices", and the title is the
 * user's first message. Now it says "your answer is ready" and nothing else, everywhere — the tap opens the thread,
 * whose id rides in the PendingIntent, not in anything shown. A failure says only that it failed: a provider's error
 * body echoes the prompt back often enough to matter.
 */
sealed interface TurnNotification {
    /** Nothing shown. */
    data object None : TurnNotification

    /** A turn is in flight. It names no provider: it shows at every send, lock screen and listener apps included. */
    data object Working : TurnNotification {
        const val title = "thinking…"
        const val body = "answering. you can leave the app."
    }

    /** The turn finished while the user was elsewhere. Only that — see the exposure note above. */
    data object Ready : TurnNotification {
        const val title = "your answer is ready"
    }

    /** It failed while they were away. That it failed, never the provider's error body or the thread. */
    data object Failed : TurnNotification {
        const val title = "that turn didn't finish"
        const val body = "open to see why"
    }
}

/**
 * When the service runs and what it shows. Pure, so the rules are testable without a device — the plumbing around
 * it is not, and this is the half that decides anything.
 */
object TurnNotificationPolicy {

    /**
     * The service runs for as long as a turn is in flight, foreground or not.
     *
     * NOT "only once backgrounded", which is the tempting version: priority has to be raised BEFORE the app loses
     * the foreground, because the window between `onStop` and the kill is not something the app gets to schedule
     * work in. Starting it at send costs an ongoing notification during a turn the user is watching, and Android
     * suppresses that for the first ten seconds, which covers most turns that finish quickly.
     */
    fun serviceRuns(turnInFlight: Boolean): Boolean = turnInFlight

    /**
     * What to show. [foreground] is whether the user is looking at the app right now.
     *
     * A completed turn notifies only if they left — telling someone an answer arrived on the screen they are
     * reading it on is noise, and noise in a notification is how an app loses the permission it just asked for.
     */
    fun notification(turnInFlight: Boolean, foreground: Boolean, outcome: TurnOutcome?): TurnNotification = when {
        turnInFlight -> TurnNotification.Working
        foreground -> TurnNotification.None
        outcome == TurnOutcome.SUCCEEDED -> TurnNotification.Ready
        outcome == TurnOutcome.FAILED -> TurnNotification.Failed
        // Stopped by the user: they asked for it to end, and an app that notifies about obeying is an app that
        // notifies about nothing. (iOS's abandon path has the same rule — a turn the user stopped is not resumed.)
        else -> TurnNotification.None
    }
}

enum class TurnOutcome { SUCCEEDED, FAILED, STOPPED }

/**
 * The two notification channels, one per notification. They used to share one LOW channel, `turns`, and on
 * 2026-10-13 a finished answer arrived silent with no status-bar icon: Android files LOW under Silent, shade only,
 * and a phone that hides silent notifications from the lock screen shows nothing at all. Decided that day:
 *
 *  - [WORKING] (the foreground-service notification) is DEFAULT with sound and vibration off: an icon in the status
 *    bar, no noise. It starts at every send, so a sound here would ding every turn.
 *  - [ANSWERS] (Ready / Failed) is HIGH with the default sound: the result arrives when the user is elsewhere, so it
 *    drops a heads-up banner. It was DEFAULT for an hour on 2026-10-13: the chime played, nothing appeared on
 *    screen, and the status-bar icon was the same one the working notification had shown — the answer sat unseen
 *    in the shade. Its id is `replies` for that reason; `answers` was the DEFAULT one.
 *
 * The ids are NEW, not `turns` raised: Android never raises an installed channel's importance (the user owns it once
 * created), so a changed importance on an old id reaches fresh installs only. [RETIRED] is deleted so the old
 * channels do not linger in the app's notification settings. Never reuse a retired id.
 */
data class TurnChannel(val id: String, val name: String, val description: String,
                       val visibleInStatusBar: Boolean, val audible: Boolean, val headsUp: Boolean)

object TurnChannels {
    val WORKING = TurnChannel("working", "working", "while a reply is being generated", visibleInStatusBar = true, audible = false, headsUp = false)
    val ANSWERS = TurnChannel("replies", "answers", "when a reply arrives while you are away", visibleInStatusBar = true, audible = true, headsUp = true)
    val ALL = listOf(WORKING, ANSWERS)
    val RETIRED = listOf("turns", "answers")
}
