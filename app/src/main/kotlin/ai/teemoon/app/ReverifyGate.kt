// android-only: the half of iOS ChatView.retryE2EEAndGenerate that lives in the screen — what the send alert does once a
// Re-verify's wait is over — as a state holder, so it is tested on the JVM (ChatScreen holds one per chat).
package ai.teemoon.app

/**
 * The gates the user raises (a send, retry or think harder the gate stopped) and what a Re-verify does when its wait
 * ends. It acts only for the gate it was tapped on: a newer gate raised meanwhile decides, or its message would go
 * twice — the re-verify sending the composer, then the newer alert's "send anyway" (iOS's 1.0.6 finding, pm 2026-11-07).
 */
class ReverifyGate {
    private var epoch = 0

    /** The user raised a new gate. */
    fun raised() { epoch++ }

    /** Re-verify was tapped: the ticket its wait carries. */
    fun tapped(): Int = epoch

    enum class Then { SHOW_BLOCKED, SHOW_ASK, RESEND_RETRY, SEND_COMPOSER, NOTHING }

    /** What to do with [after] (ChatViewModel.afterReverify) for the gate [tappedOn]; [gatedRetry]: a retry is kept. */
    fun then(after: ChatViewModel.AfterReverify?, tappedOn: Int, gatedRetry: Boolean): Then {
        if (epoch != tappedOn) return Then.NOTHING
        return when (after) {
            ChatViewModel.AfterReverify.BLOCKED -> Then.SHOW_BLOCKED
            ChatViewModel.AfterReverify.ASK -> Then.SHOW_ASK
            ChatViewModel.AfterReverify.GO -> if (gatedRetry) Then.RESEND_RETRY else Then.SEND_COMPOSER
            null -> Then.NOTHING
        }
    }
}
