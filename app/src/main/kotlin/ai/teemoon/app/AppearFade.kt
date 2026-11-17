// What comes and goes in the chat fades (card motion-pass item 6; motion-audit.md G5: "tail card (error/debug), offer
// card, retry button … just pop in" on Android). M3's fade: in over 200 ms decelerating, out over 150 accelerating.
package ai.teemoon.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics

/**
 * Shows [content] for [value], fading in when it arrives and out (on the last value) when it goes. A value present on
 * the first composition shows at once — a card scrolled back into view does not fade again — unless [appearOnFirst]
 * (a list item that exists only because the thing just arrived, the error card). [scaleFrom] < 1 grows it in with the fade.
 */
@Composable
internal fun <T : Any> AppearFade(value: T?, tag: String, modifier: Modifier = Modifier, appearOnFirst: Boolean = false, scaleFrom: Float = 1f, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    val alpha = remember { Animatable(if (value != null && !appearOnFirst) 1f else 0f) }
    LaunchedEffect(value != null) { alpha.animateTo(if (value != null) 1f else 0f, if (value != null) Motion.fadeIn() else Motion.fadeOut()) }
    val shown = last
    if (shown != null && (value != null || alpha.value > 0f))
        Box(modifier.graphicsLayer { this.alpha = alpha.value; val s = scaleFrom + (1f - scaleFrom) * alpha.value; scaleX = s; scaleY = s }
            .testTag(tag).semantics { motionAlpha = alpha.value }) { content(shown) }
}
