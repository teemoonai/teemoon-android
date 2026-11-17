// The app's motion, by job (card motion-pass item 9; pm/features/motion-audit.md §3, the maintainer 2026-11-14 "do all for
// 1.1.0"). Every animation in app/ takes one of these; none is tuned at its call site (MotionTokensTest holds that). The
// values are Material 3's own tokens, not tuned by eye, and "Remove animations" scales every one of them to 0 through
// Compose's MotionDurationScale — nothing here reads the setting itself.
//
// iOS keeps the same jobs in Views/Motion.swift with the platform's own values; a job, not a number, is what the two apps
// share.
package ai.teemoon.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object Motion {
    /** M3's emphasized decelerate (enter) and accelerate (exit) curves (MotionTokens.EasingEmphasized*). */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** M3's standard curve, for a change that neither enters nor leaves (a dim, a level). */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 durations (MotionTokens): Short3 150, Short4 200, Medium2 300. */
    const val SHORT3_MS = 150
    const val SHORT4_MS = 200
    const val MEDIUM2_MS = 300

    /** Something appears in place (a card, the retry button, a chip, a paragraph): M3's fade in. */
    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(SHORT4_MS, easing = EmphasizedDecelerate)
    /** Something leaves in place: M3's fade out, quicker than the way in. */
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(SHORT3_MS, easing = EmphasizedAccelerate)
    /** A paragraph of a streaming reply arriving: a longer fade, so reading isn't a flicker (StreamReveal). */
    fun <T> paragraph(): FiniteAnimationSpec<T> = tween(MEDIUM2_MS, easing = EmphasizedDecelerate)
    /** One thing replaced by another in its place (think harder's reply, a glyph, a label): fade through, each half. */
    fun <T> swap(): FiniteAnimationSpec<T> = tween(SHORT3_MS, easing = Standard)
    /** A value that changes in place without coming or going (the superseded reply's dim). */
    fun <T> change(): FiniteAnimationSpec<T> = tween(SHORT4_MS, easing = Standard)

    /** Something moves or resizes (a sheet, a page, the strip, the panel): no bounce, M3's default spatial spring. */
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    /** Only a thumbnail's arrival and leaving: SwiftUI's `.spring(duration: 0.35, bounce: 0.3)` (IMAGES.md §4d). */
    fun <T> playful(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = THUMB_STIFFNESS)

    /** The transcript following a streaming reply (FollowGlide, 0.12 s linear: a constant speed reads as following). */
    fun <T> follow(): FiniteAnimationSpec<T> = tween((FollowGlide.FOLLOW_GLIDE_DURATION * 1000).toInt(), easing = LinearEasing)
    /** Material's shared axis X between pages: the 30 dp move (Medium2), the old page out in the first 35%, the new in after. */
    fun <T> axisSlide(): FiniteAnimationSpec<T> = tween(MEDIUM2_MS, easing = Standard)
    fun <T> axisFadeOut(): FiniteAnimationSpec<T> = tween(MEDIUM2_MS * 35 / 100, easing = EmphasizedAccelerate)
    fun <T> axisFadeIn(): FiniteAnimationSpec<T> = tween(MEDIUM2_MS * 65 / 100, delayMillis = MEDIUM2_MS * 35 / 100, easing = EmphasizedDecelerate)
    /** A page popped by a back gesture, seeked by the finger: linear, so the page stays under it (PredictiveBack). */
    fun <T> backSeek(): FiniteAnimationSpec<T> = tween(MEDIUM2_MS, easing = LinearEasing)
    /**
     * A reserve-sized move of the transcript (the send's prompt travelling to the top): FollowGlide's jump, 0.25 s
     * smoothstep, eased both ends — iOS's same shape, where a linear 120 ms ran 100 dp a frame.
     */
    fun <T> jump(): FiniteAnimationSpec<T> = tween((FollowGlide.JUMP_GLIDE_DURATION * 1000).toInt(), easing = Easing { t -> t * t * (3 - 2 * t) })
    /** A row put back after a swipe that didn't reach its action. */
    fun <T> settle(): FiniteAnimationSpec<T> = tween(SHORT4_MS, easing = Standard)
    /** The "this needs fixing" shake of a field (iOS: linear 0.4). */
    fun <T> shake(): FiniteAnimationSpec<T> = tween(400, easing = LinearEasing)

    /** The import's spoke wheel turning: eight steps a turn, 0.8 s, at a constant speed (SpokeSpinner). */
    fun <T> spin(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(800, easing = LinearEasing))

    /** One half of a breath (in or out), for a breath that loops while a state lasts and ends with it (VerifyingLock). */
    fun <T> breathHalf(): FiniteAnimationSpec<T> = tween(850, easing = Standard)
    /** A slow breath on something waiting (the activity chip, a warm model's dot): ease both ways, forever. */
    fun <T> breath(periodMs: Int = 850): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(periodMs, easing = Standard), RepeatMode.Reverse)
}

/** IMAGES.md §4d's thumbnail spring: SwiftUI's `.spring(duration: 0.35, bounce: 0.3)` is damping 0.7 at (2π/0.35)². */
private const val THUMB_STIFFNESS = 322.3f
