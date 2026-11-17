// Predictive back, wired (card motion-pass item 1; pm/features/motion-audit.md: "the code reads the back-swipe progress
// and then never uses it, so on Android 14+ nothing follows the finger"). While the gesture is in flight the screen it
// leaves follows it: a pushed page slides toward its edge with its parent coming in (the same transition as the pop,
// seeked), a sheet sinks and narrows (Material 3's bottom-sheet preview), the chat recedes. Release completes the move
// from where the finger left it; a cancel springs back.
package ai.teemoon.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Which surface a back gesture previews. */
internal enum class BackPreview { NONE, CHAT, SHEET, PAGE, CHILD }

/**
 * What the back gesture on [route] leaves: a page pushed inside its sheet (it pops to a page of the same stack), the
 * child sheet over a sheet (the add form over where), a sheet (back to the chat), or the chat itself (it opens the list).
 */
internal fun backPreview(route: Route?, sheetA: Route?, sheetB: Route?, parentOf: (Route) -> Route?): BackPreview = when {
    route == null -> BackPreview.NONE
    route is Route.Chat -> BackPreview.CHAT
    sheetB != null -> BackPreview.CHILD
    sheetA == null -> BackPreview.NONE
    parentOf(route)?.let { routeLevel(it) >= 1 && pageSwap(route, it, parentOf) == PageSwap.POP } == true -> BackPreview.PAGE
    else -> BackPreview.SHEET
}

/** Material 3's bottom-sheet back preview: at full progress 48 dp narrower and 24 dp shorter, from its bottom edge. */
internal val SHEET_BACK_SHRINK_X = 48.dp
internal val SHEET_BACK_SHRINK_Y = 24.dp
/** Material's shared axis: how far a page moves along it. */
internal val SHARED_AXIS_SHIFT = 30.dp
/** The chat at full progress, receding toward the list it opens. */
internal const val CHAT_BACK_SCALE = 0.95f

/**
 * A sheet's pages: a push slides in from the right, a pop from the left (a third of the way, under the page leaving),
 * another stack just appears. While [seeking] (a back gesture on a pushed page) the pop is seeked to [progress] toward
 * the page's parent; when the route then moves to the parent the pop runs on from there, and when the gesture is
 * cancelled it springs back.
 */
@Composable
internal fun SheetPages(page: Route, parentOf: (Route) -> Route?, seeking: Boolean, progress: Float, fromRightEdge: Boolean = false, content: @Composable (Route) -> Unit) {
    val seek = remember { SeekableTransitionState(page) }
    LaunchedEffect(page) { seek.animateTo(page) }
    val parent = parentOf(page)
    LaunchedEffect(seeking, progress, parent) {
        if (seeking && parent != null) seek.seekTo(progress.coerceIn(0f, 1f), parent)
        else if (!seeking && seek.targetState != page) {
            // cancelled: back along the same path to 0, then the page again. animateTo(page) first ran on toward the
            // parent and came back, a 27 dp kick outward (design review of f57ea4f5)
            val to = seek.targetState
            androidx.compose.animation.core.animate(seek.fraction, 0f, animationSpec = Motion.spatial()) { v, _ -> launch { seek.seekTo(v, to) } }
            seek.snapTo(page)
        }
    }
    // a gesture's pop stays linear until it settles, released or cancelled: switching to the spring at release kicked
    // the page 27 dp further out before it came back (design review of f57ea4f5)
    var interactive by remember { mutableStateOf(false) }
    if (seeking) interactive = true
    val pages = rememberTransition(seek, label = "sheetPage")
    LaunchedEffect(interactive, pages.isRunning, seeking) { if (interactive && !seeking && !pages.isRunning && pages.currentState == pages.targetState) interactive = false }
    TraceWhile("page:" + NavTrace.name(pages.targetState), pages.isRunning)
    val axis = with(LocalDensity.current) { SHARED_AXIS_SHIFT.roundToPx() }
    val rightEdge by rememberUpdatedState(fromRightEdge)
    pages.AnimatedContent(transitionSpec = {
        // Material's shared axis X (motion-pass item 10): the pages move 30 dp along the axis and fade through, the old
        // one out first; a push goes forward, a pop back. Seeked by a finger it moves with the finger at constant speed
        // (a spring or an eased curve seeked to 10% of its time is far more than 10% of the way), and a swipe from the
        // right edge, the finger moving left, takes the page left with it.
        val slide = if (interactive) Motion.backSeek<IntOffset>() else Motion.axisSlide<IntOffset>()
        val dir = when (pageSwap(initialState, targetState, parentOf)) {
            PageSwap.PUSH -> 1
            PageSwap.POP -> if (interactive && rightEdge) 1 else -1
            PageSwap.SWAP -> 0
        }
        if (dir == 0) EnterTransition.None togetherWith ExitTransition.None
        else (slideInHorizontally(slide) { dir * axis } + fadeIn(Motion.axisFadeIn())) togetherWith
            (slideOutHorizontally(slide) { -dir * axis } + fadeOut(Motion.axisFadeOut()))
    }) { page -> content(page) }
}
