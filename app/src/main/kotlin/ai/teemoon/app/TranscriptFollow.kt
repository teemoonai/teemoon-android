// ports: teemoon/Views/Chat/TranscriptCollectionView.swift @ v1.0.4 (the follow contract: the 50 pt interruption rule, the 40 ms
//        pin floor, armSettle / armHold, the drag callbacks) and docs/TRANSCRIPT_ANCHOR.md (phase A slack / phase B pin) — as a
//        pure policy the Compose transcript drives; the formulas it consumes are Transcript.kt
package ai.teemoon.app

/**
 * What the transcript should do with its scroll offset this frame, and the
 * state that decides it. Pure and stepped by the screen: it never touches a
 * list. The follow contract, each rule with its receipt on iOS:
 *  - a real drag >50 pt away from the end stops the follow for the turn; a
 *    tap does not; a sub-50 pt nudge does not; the next send re-arms it;
 *    scrolling back to within 50 pt of the end resumes it.
 *  - pins are coalesced in REAL TIME (≥40 ms): chained immediate updates
 *    never yield the run loop.
 *  - a turn start arms a 0.6 s settle that lands in phase A (the prompt near
 *    the top, slack below); a turn end arms 1.0 s of settle, or — for a
 *    reader who left — a 1.0 s HOLD of the absolute offset so the hand-off
 *    cannot move them.
 */
class TranscriptFollow(private val now: () -> Double) {
    var generating = false; private set
    var interrupted = false; private set
    private var settleUntil = 0.0
    private var holdY: Double? = null
    private var holdUntil = 0.0
    private var dragAnchorY = 0.0
    private var isUserDriving = false
    private var lastPinAt = -1.0

    /** ChatGeneration.scrollInterrupted, mirrored out so the chip and the composer can read it. */
    var onInterruptionChanged: ((Boolean) -> Unit)? = null

    fun update(generating: Boolean) {
        val turnStarted = generating && !this.generating
        val turnEnded = !generating && this.generating
        this.generating = generating
        // A new turn supersedes the previous hand-off's hold: the send is the user's own decision to return to the end.
        // ChatGeneration resets scrollInterrupted when a generation starts — scrolling away kills the follow FOR THAT TURN,
        // and a send from up in the history re-arms it (LongThreadStreamingUITests.testTheFollowReArmsOnTheNextSend).
        if (turnStarted) { releaseHold(); setInterrupted(false); armSettle(SETTLE_AT_START); lastPinAt = -1.0 }
        if (turnEnded) { if (interrupted) armHold(HOLD_AT_END) else armSettle(SETTLE_AT_END) }
    }

    private fun armSettle(seconds: Double) { settleUntil = now() + seconds }
    private var currentOffset = 0.0
    private fun armHold(seconds: Double) { holdY = currentOffset; holdUntil = now() + seconds }
    private fun releaseHold() { holdY = null; holdUntil = 0.0 }

    /** The intent this frame — TranscriptViewport.resolve over the arms above. */
    fun intent(): TranscriptViewport = TranscriptViewport.resolve(generating, interrupted, now(), settleUntil, holdY, holdUntil)

    /** Whether a pin may fire now (the 40 ms floor); records the pin when it may. */
    fun mayPin(mode: PinMode): Boolean {
        if (mode == PinMode.IMMEDIATE) { lastPinAt = now(); return true }
        val waited = now() - lastPinAt
        if (lastPinAt >= 0 && waited < MIN_PIN_INTERVAL) return false
        lastPinAt = now(); return true
    }

    // MARK: - the scroll callbacks (UIScrollViewDelegate, in order)

    fun onDragBegan(offsetY: Double) { isUserDriving = true; dragAnchorY = offsetY; settleUntil = 0.0; releaseHold() }

    /** Every offset change; [distanceFromEnd] is how far the viewport's bottom sits above the content's end. */
    fun onScrolled(offsetY: Double, distanceFromEnd: Double, userDriving: Boolean) {
        currentOffset = offsetY
        if (distanceFromEnd <= INTERRUPTION_THRESHOLD) { setInterrupted(false); if (!userDriving) isUserDriving = false; return }
        if (!userDriving) return
        // Away from the end and travelled far enough: a decision, not a nudge.
        if (dragAnchorY - offsetY > INTERRUPTION_THRESHOLD) setInterrupted(true)
    }

    fun onDragEnded() { isUserDriving = false }

    private fun setInterrupted(value: Boolean) { if (interrupted == value) return; interrupted = value; onInterruptionChanged?.invoke(value) }

    companion object {
        const val INTERRUPTION_THRESHOLD = 50.0
        const val MIN_PIN_INTERVAL = 0.040
        const val SETTLE_AT_START = 0.6
        const val SETTLE_AT_END = 1.0
        const val HOLD_AT_END = 1.0
    }
}
