// spec: IMAGES.md draft 2 §5 (teemoon-ios fa3c46c): full screen, pinch to zoom, swipe between the message's images, share
// (the user's own choice to send one somewhere), done. No save-to-photos permission.
package ai.teemoon.app

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import ai.teemoon.app.imaging.ImageCopy
import ai.teemoon.app.imaging.ViewerZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The viewer's top bar, left to right — iOS's toolbar (MessageImages.swift ImageViewer): done, then one share icon. Copy
 * and save are in the long press and the share sheet, not the bar (the maintainer 2026-11-12: "it's already in the long
 * press"); it listed share, copy and save as words.
 */
object ViewerBar {
    fun items(canShare: Boolean, canCopy: Boolean, canSave: Boolean): List<String> =
        listOfNotNull(ImageCopy.DONE, ImageCopy.SHARE.takeIf { canShare })
}

/** [ViewerBar] drawn: "done" at the leading edge, the share glyph at the trailing edge, white over the black viewer. */
@Composable
fun ViewerTopBar(onDone: () -> Unit, onShare: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (item in ViewerBar.items(canShare = onShare != null, canCopy = false, canSave = false)) when (item) {
            ImageCopy.DONE -> { TextButton(onClick = onDone) { Text(ImageCopy.DONE, color = Color.White) }; Box(Modifier.weight(1f)) }
            ImageCopy.SHARE -> androidx.compose.material3.IconButton(onClick = { onShare?.invoke() },
                modifier = Modifier.semantics { contentDescription = ImageCopy.SHARE }.testTag("image.viewer.share")) { ShareGlyph(Color.White, 22.dp) }
        }
    }
}

/**
 * The image viewer. [bytes] reads a stored image — a JPEG or PNG our isolated decoder wrote, so the platform decoder
 * only ever sees our own bytes here. A missing file shows its thumbnail.
 */
@Composable
fun ImageViewer(images: List<ChatImage>, start: Int, bytes: (ChatImage) -> ByteArray?, onShare: ((ChatImage) -> Unit)?, onDone: () -> Unit,
                onCopy: ((ChatImage) -> Unit)? = null, onSave: ((ChatImage) -> Unit)? = null, hapticsEnabled: Boolean = true) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("image.viewer")) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val img = images[page]
                val bmp = remember(img.id) {
                    val b = bytes(img) ?: img.thumbnail
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                }
                var scale by remember { mutableFloatStateOf(1f) }
                // the long-press menu (iOS S42): copy, save, share, as the thumbnail's, opened on the photo where the finger
                // pressed — a menu anchored to the full-screen page opened at its top-left (design review of 3a9678bf)
                var menu by remember { mutableStateOf(false) }
                var pressedAt by remember { mutableStateOf(Offset.Zero) }
                val actions = ImageActions(onCopy, onSave, onShare)
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                var offset by remember { mutableStateOf(Offset.Zero) }
                val zoomScope = rememberCoroutineScope()
                var zoomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                // One box per page holds the photo and the menu's anchor: HorizontalPager centres a page's content
                // vertically, so a bare 1dp anchor sat at mid-height and the press offset pushed the menu to the screen's
                // foot; a second full-size sibling beside the photo left the page black (the Pixel, 2026-11-12).
                Box(Modifier.fillMaxSize()) {
                // Pinch zooms, and a zoomed image pans; one finger at 1× is left to the pager, so a swipe pages
                // (detectTransformGestures took every pan, and the viewer was stuck on the image it opened at: the
                // maintainer's Pixel, 2026-11-07)
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(img.id) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1 || scale > 1f) {
                                        // a pinch or a pan takes over from a double tap's zoom; the tap's own up does not
                                        if (event.changes.size > 1 || event.changes.any { it.positionChanged() }) zoomJob?.cancel()
                                        scale = ViewerZoom.clamp(scale * event.calculateZoom())
                                        offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        // iOS: a double tap toggles 1× and 2.5×; back at 1× the image is centred again. It zooms on the
                        // spatial spring, where it cut from one size to the other (motion-pass item 7); a pinch stays under
                        // the fingers
                        .pointerInput(img.id) { detectTapGestures(onDoubleTap = {
                                val from = scale; val to = ViewerZoom.doubleTap(scale); val fromOffset = offset
                                zoomJob?.cancel()
                                zoomJob = zoomScope.launch {
                                    androidx.compose.animation.core.animate(0f, 1f, animationSpec = Motion.spatial()) { t, _ ->
                                        scale = from + (to - from) * t
                                        offset = if (to == 1f) fromOffset * (1f - t) else offset
                                    }
                                }
                            },
                            onLongPress = { at -> if (actions.any) {
                                if (hapticsEnabled) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                pressedAt = at; menu = true } }) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }.testTag("image.photo"))
                // the anchor, measured from the page's top-left, where the finger pressed
                Box(Modifier.align(Alignment.TopStart).offset { androidx.compose.ui.unit.IntOffset(pressedAt.x.toInt(), pressedAt.y.toInt()) }.size(1.dp)) {
                    ImageContextMenu(expanded = menu, onDismiss = { menu = false }, image = img, actions = actions)
                }
                }
            }
            ViewerTopBar(onDone, onShare?.let { share -> { share(images[pager.currentPage]) } }, Modifier.statusBarsPadding())
            // page dots, as iOS's TabView .page indicator: shown for more than one image, the current one bright
            if (ImageGrid.viewerShowsDots(images.size)) Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp).testTag("image.pageDots"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(images.size) { n ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (n == pager.currentPage) 1f else 0.35f)))
                }
            }
        }
    }
}
