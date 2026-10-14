// ports: teemoon/Views/Chat/TranscriptViewport.swift @ v1.0.1 (TranscriptAnchorGeometry, TranscriptViewport.resolve),
//        teemoon/Views/Chat/TranscriptHandoffSizing.swift @ v1.0.1, teemoon/Views/Chat/TranscriptRowHeightCache.swift @ v1.0.1,
//        teemoon/Views/Chat/TranscriptApplyPolicy.swift @ v1.0.1, teemoon/Views/Chat/FreshStartRule.swift @ v1.0.1 (FreshStart),
//        teemoon/Views/Chat/FollowGlide.swift @ v1.0.1
//
// The transcript CONTRACT (docs/UIKIT_TRANSCRIPT.md): the formulas the
// UIKit transcript obeys, as pure values. The Compose transcript is a human
// rebuild; these are what it has to satisfy, and what its tests will read.
package ai.teemoon.app

/** The anchor band: what is reserved below a user turn so the reply's end lands where the prompt at the top would be. */
data class TranscriptAnchorGeometry(val userHeight: Double, val visibleHeight: Double, val topFade: Double, val chrome: Double, val inkBelow: Double) {
    val usableHeight: Double get() = maxOf(0.0, visibleHeight - topFade - chrome)
    val placeholder: Double get() = maxOf(0.0, usableHeight - userHeight - inkBelow)
}

/**
 * What `inkBelow` is on iOS (`TranscriptCollectionView.anchorGeometry()`): `contentSize.height - frame.maxY` — every row
 * drawn after the prompt, whatever it is: the reply, the debug card under it, an error card, a sources chip — re-read on
 * every layout pass. A lazy list measures only what is composed, so the same quantity from what is on screen: the end
 * of the last visible row before the slack spacer, minus the prompt's bottom. Rows that exist past the viewport make
 * the ink unbounded — there is nothing left to reserve. (2026-09-17: measured from the reply row alone, and only when
 * its text changed, the reserve stood under a developer card as a screen of nothing.)
 */
object TranscriptInk {
    fun below(promptBottom: Double, lastVisibleTailEnd: Double?, tailContinuesPastViewport: Boolean): Double {
        if (lastVisibleTailEnd != null && tailContinuesPastViewport) return Double.POSITIVE_INFINITY
        return maxOf(0.0, (lastVisibleTailEnd ?: promptBottom) - promptBottom)
    }
}

enum class PinMode { OFF, THROTTLED, IMMEDIATE }

/** Where the viewport is bound this frame: a hold outranks everything; a live follow is throttled; a settling handoff pins immediately. */
sealed interface TranscriptViewport {
    data object Free : TranscriptViewport
    data class PinToEnd(val mode: PinMode) : TranscriptViewport
    data class Hold(val y: Double) : TranscriptViewport

    companion object {
        fun resolve(generating: Boolean, interrupted: Boolean, now: Double, settleUntil: Double, holdY: Double?, holdUntil: Double): TranscriptViewport {
            if (holdY != null && now < holdUntil) return Hold(holdY)
            if (generating && !interrupted) return PinToEnd(PinMode.THROTTLED)
            if (now < settleUntil) return PinToEnd(PinMode.IMMEDIATE)
            return Free
        }
    }
}

object TranscriptHandoffSizing {
    /** A fitted height only replaces the handoff seed when it is plausibly a real measurement, not an unfitted first paint. */
    fun floor(seed: Double, fitted: Double): Double {
        if (fitted > 200) { if (seed > 2000 && fitted < 1000) return seed; return fitted }
        return seed
    }
}

/** A transcript row identity for height caching and the debug-card policy. */
sealed interface TranscriptItem {
    data class Message(val id: String) : TranscriptItem
    data class DebugInfo(val turn: Int) : TranscriptItem
    data object Pending : TranscriptItem
    val isDebugInfo: Boolean get() = this is DebugInfo
}

/** Heights only grow: a first paint must not shrink a handoff seed. */
object TranscriptRowHeightCache {
    private val heights = HashMap<TranscriptItem, Double>()
    @Synchronized fun height(item: TranscriptItem): Double? = heights[item]
    @Synchronized fun store(height: Double, item: TranscriptItem) { val existing = heights[item]; if (existing != null && existing > height) return; heights[item] = height }
    @Synchronized fun forget(item: TranscriptItem) { heights.remove(item) }
}

object TranscriptApplyPolicy {
    /** A debug card appearing under an unchanged transcript is a reveal (animate in place), not a handoff. */
    fun isDebugCardReveal(previousTranscript: List<TranscriptItem>, previousTail: List<TranscriptItem>, transcript: List<TranscriptItem>, tail: List<TranscriptItem>): Boolean {
        if (previousTranscript != transcript) return false
        return tail.any { it.isDebugInfo } && !previousTail.any { it.isDebugInfo }
    }
    fun handoffTail(tail: List<TranscriptItem>): List<TranscriptItem> = tail.filter { !it.isDebugInfo }
    fun hasDebugCard(tail: List<TranscriptItem>): Boolean = tail.any { it.isDebugInfo }
}

/** Where the "starts fresh" rule goes: above every user turn after the first, for a provider that answers one question at a time. */
object FreshStart {
    const val LABEL = "starts fresh"
    const val ACCESSIBILITY = "New session. This provider answers one question at a time and does not see the conversation above."
    fun indices(roles: List<String>, singleTurn: Boolean): Set<Int> {
        if (!singleTurn) return emptySet()
        val result = HashSet<Int>(); var seenUserTurn = false
        for ((i, role) in roles.withIndex()) if (role == "user") { if (seenUserTurn) result.add(i); seenUserTurn = true }
        return result
    }
}

/** A short eased glide that follows a wrapped line instead of jumping; a burst re-targets, noise does not. */
data class FollowGlide(val from: Double, val to: Double, val start: Double, val duration: Double, val eased: Boolean = false) {
    fun progress(now: Double): Double = if (duration <= 0) 1.0 else minOf(1.0, maxOf(0.0, (now - start) / duration))
    fun offset(now: Double): Double { val t = progress(now); val shaped = if (eased) t * t * (3 - 2 * t) else t; return from + (to - from) * shaped }
    fun isFinished(now: Double): Boolean = progress(now) >= 1

    companion object {
        const val START_THRESHOLD = 8.0
        const val RETARGET_THRESHOLD = 28.0
        const val FOLLOW_GLIDE_DURATION = 0.12
        fun shouldStart(delta: Double, mode: PinMode) = mode == PinMode.THROTTLED && delta > START_THRESHOLD
        fun shouldRetarget(delta: Double) = delta > RETARGET_THRESHOLD
    }
}
