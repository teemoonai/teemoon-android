package ai.teemoon.app

// android-only: the iOS 26 sheet, measured on the tag's captures rather than Material's fixed half detent.
//   floating: 8pt side and bottom insets, ~48pt corners, the medium detent puts the sheet top at 47.5% of the
//   screen and the large at 8.3%; the bottom edge stays put while the top moves. Material's ModalBottomSheet
//   offers only "50% of the container" and no custom detent, which is why "browse near.ai" fell off the half
//   sheet here and not on iOS.

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import kotlin.math.sqrt
import kotlinx.coroutines.Job

/**
 * The bottom safe-area a sheet's scrolling content must end above. The sheet consumes the navigation-bar inset (its own
 * frame is placed over the home-indicator area, as UIKit's is), so a page's `navigationBarsPadding()` reads zero inside
 * it — and every sheet page's last lines ended under the gesture bar and the 44dp corner, unreachable by scrolling
 * (the trust sheet, both rungs, Pixel 10 Pro 2026-09-24). iOS's scroll views add the safe area below their content.
 * Zero outside a sheet, where the page's own inset padding already applies.
 */
val LocalSheetBottomInset = compositionLocalOf { 0.dp }

/** The end of a sheet page's scrolling content: the sheet's safe area plus [extra] (the document's own bottom padding). */
@Composable
fun SheetScrollEnd(extra: Dp = 0.dp) {
    Spacer(Modifier.height(LocalSheetBottomInset.current + extra))
}

object SheetDetents {
    const val MEDIUM = 0.475f
    const val LARGE = 0.083f
    /** WhereSheetView's first run: `.fraction(0.76)` of the large detent. */
    const val FIRST_RUN = 0.327f
}

/**
 * A sheet with explicit detents. [tops] are the resting positions offered (fractions of the screen height, smaller
 * is taller); the sheet opens at the first one. Drag, scrim tap and a downward fling past the lowest detent call
 * [onDismiss]; the caller drops [visible] and the sheet animates out. Inner scrollables expand the sheet before
 * they scroll, and collapse it when they are scrolled back to their top, as UIKit's do.
 */
@Composable
fun IosSheet(
    visible: Boolean, tops: List<Float>, onDismiss: () -> Unit, grabber: Boolean, scrimAlpha: Float = 0.45f,
    containerColor: Color,
    /**
     * How far the sheet has risen from the medium detent toward the large one, 0..1, written as it moves. UIKit
     * scales the presenting view down and pushes it under the sheet's top edge at the large detent (the chat's bar
     * is not visible above a large sheet on the tag); the presenter reads this to do the same.
     */
    lift: MutableFloatState? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenPx = with(density) { maxHeight.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        val anchors = tops.sorted().map { it * screenPx }          // tallest first
        val hiddenPx = screenPx
        val scope = rememberCoroutineScope()
        var shown by remember { mutableStateOf(false) }
        val settleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SHEET_STIFFNESS)
        // THE FINGER MOVES THE SHEET SYNCHRONOUSLY; only a settle animates. Each drag delta used to launch its own
        // `snapTo` coroutine: a delta landed a frame late about every tenth frame (a stall, then a double step) and the
        // first ones stepped backwards. And the settle started from rest: onPreFling settled with the fling's velocity,
        // then onPostFling settled AGAIN with zero and cancelled it — a flick reached y 297, snapped back 163 px and crept
        // up from a standstill (Pixel 10 Pro at 60 fps, 2026-10-06). UIKit carries the release velocity into the spring.
        val motion = remember { SheetMotion(hiddenPx) }

        fun nearest(y: Float, velocity: Float): Float {
            // a decisive fling goes one detent in its direction; otherwise the closest resting position, hidden included
            val rest = anchors + hiddenPx
            val current = rest.minByOrNull { abs(it - y) } ?: hiddenPx
            if (abs(velocity) > 1600f) {
                val idx = rest.indexOf(current)
                return if (velocity > 0) rest.getOrElse(idx + 1) { hiddenPx } else rest.getOrElse(idx - 1) { rest.first() }
            }
            return current
        }
        /** One spring to [target], from where the sheet is and how fast it is moving (px/s, down positive). */
        fun animateTo(target: Float, velocity: Float = 0f, then: () -> Unit = {}) {
            motion.job?.cancel()
            // A critically damped spring overshoots its target when it starts faster than ω·distance (ω = √stiffness), and
            // the clamp then stopped it dead at the detent. Capped there, the release speed carries into a deceleration
            // that lands on the detent (measured against the iOS sheet: a flick at rest in ~250 ms against 291, a 0.7 s
            // drag in 861 against 868 — close enough; the stiffness is Compose's, not tuned to UIKit's).
            val toward = (target - motion.y) * velocity > 0
            val cap = sqrt(SHEET_STIFFNESS) * abs(target - motion.y)
            val v0 = if (toward) velocity.coerceIn(-cap, cap) else velocity
            motion.job = scope.launch {
                animate(motion.y, target, v0, settleSpec) { v, _ -> motion.y = v.coerceIn(anchors.first(), hiddenPx) }
                then()
            }
        }
        fun settle(velocity: Float) {
            val target = nearest(motion.y, velocity)
            animateTo(target, velocity) { if (target >= hiddenPx) { shown = false; onDismiss() } }
        }
        fun dragBy(delta: Float) { motion.job?.cancel(); motion.y = (motion.y + delta).coerceIn(anchors.first(), hiddenPx) }

        LaunchedEffect(visible) {
            // a user drag is the only thing that cancels these now (a field focusing itself — bringIntoView — is not user
            // input and never reaches the sheet), so the open always ends at its detent rather than parked half-way
            if (visible) { shown = true; animateTo(anchors.last()) }   // in from the bottom to the first (lowest) detent
            else if (shown) animateTo(hiddenPx) { shown = false }
        }
        if (lift != null) {
            val largePx = SheetDetents.LARGE * screenPx; val mediumPx = SheetDetents.MEDIUM * screenPx
            LaunchedEffect(Unit) { snapshotFlow { motion.y }.collect { y -> lift.floatValue = ((mediumPx - y) / (mediumPx - largePx)).coerceIn(0f, 1f) } }
        }
        if (!shown && !visible) return@BoxWithConstraints

        val progress = ((hiddenPx - motion.y) / (hiddenPx - anchors.last())).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha * progress))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { animateTo(hiddenPx) { shown = false; onDismiss() } }
            .semantics { contentDescription = A11yLabels.DISMISS_SHEET })

        val drag = rememberDraggableState { delta -> dragBy(delta) }
        val visibleNow by rememberUpdatedState(visible)   // the connection is remembered once; read the live flag
        val nested = remember {
            object : NestedScrollConnection {
                /** The sheet took this gesture's scroll, so its fling is the sheet's too — settled once, in onPreFling. */
                var sheetTookScroll = false
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // only the user's finger moves the sheet: a bringIntoView (a field taking focus) or a programmatic scroll is
                    // not a drag, and a sheet on its way out ignores everything
                    if (source != NestedScrollSource.UserInput || !visibleNow) return Offset.Zero
                    // scrolling up while the sheet can still rise: the sheet takes it
                    if (available.y < 0 && motion.y > anchors.first()) {
                        val take = maxOf(available.y, anchors.first() - motion.y)
                        dragBy(take); sheetTookScroll = true; return Offset(0f, take)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || !visibleNow) return Offset.Zero
                    // scrolling down with the child at its top: the sheet comes down
                    if (available.y > 0) { dragBy(available.y); sheetTookScroll = true; return Offset(0f, available.y) }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!visibleNow) return Velocity.Zero
                    val resting = anchors.any { abs(it - motion.y) < 1f }
                    val took = sheetTookScroll; sheetTookScroll = false
                    // the sheet is between detents: the whole fling is its settle, and the list does not fling under it
                    if (took || !resting) { settle(available.y); return available }
                    return Velocity.Zero
                }
                // a fling the list ran (the sheet resting at a detent the whole gesture) is not the sheet's to settle; only
                // what the list left over at its top edge — pulled down past it — moves the sheet
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (!visibleNow || available.y <= 0f || motion.job?.isActive == true) return Velocity.Zero
                    settle(available.y); return available
                }
            }
        }
        val topPx = motion.y
        // the bottom edge sits 8pt above the screen's bottom, over the home-indicator area (measured: 0.99 of the screen on the
        // tag; subtracting the gesture-nav inset here left every Android sheet ~23pt short)
        val heightDp = with(density) { (screenPx - topPx - margin).coerceAtLeast(0f).toDp() }
        Box(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .padding(horizontal = 8.dp)
                .height(heightDp)
                .clip(RoundedCornerShape(44.dp))
                .background(containerColor)
                .draggable(drag, Orientation.Vertical, enabled = visible, onDragStopped = { v -> settle(v) })
                .nestedScroll(nested)
                .consumeWindowInsets(WindowInsets.statusBars).consumeWindowInsets(WindowInsets.navigationBars),
        ) {
            Box(Modifier.fillMaxSize()) {
                // read RAW here, before the consumption below reaches the content: that is the height a page must end above
                val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
                CompositionLocalProvider(LocalSheetBottomInset provides navBottom) {
                    // ONE inset, grabber or not: on iOS 26 a sheet's bar title sits ~27pt under its top edge either way. 18/6
                    // put the chats title 2dp low and every settings page's 10dp high (parity capture, 2026-10-05).
                    Box(Modifier.fillMaxSize().padding(top = 16.dp)) { content() }
                }
                if (grabber) Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).height(5.dp).fillMaxWidth(0.1f).clip(RoundedCornerShape(3.dp)).background(TeemoonColors.tertiaryLabel.copy(alpha = 0.5f)))
            }
        }
    }
}

/** The sheet's settle spring: Compose's own medium-low stiffness, critically damped (no bounce). */
private const val SHEET_STIFFNESS = Spring.StiffnessMediumLow

/** The sheet's position (px from the top of the screen) and the settle in flight, if any. */
private class SheetMotion(start: Float) {
    var y by mutableFloatStateOf(start)
    var job: Job? = null
}

