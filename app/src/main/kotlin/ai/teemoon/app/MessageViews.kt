// ports: teemoon/Views/Chat/MessageView.swift @ 2fd9f7e — the assistant row (the thinking disclosure, the answer, the
//        sources rule, copy), the user bubble's geometry and retry, TimeInterval.formatted, DisclosureState
package ai.teemoon.app

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.PaddingValues
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.ThinkingContentParser
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

/** TimeInterval.formatted: "4s", "1m", "1m 5s". */
fun formattedDuration(seconds: Double): String {
    val total = seconds.toInt(); val minutes = total / 60; val secs = total % 60
    return if (minutes > 0) (if (secs > 0) "${minutes}m ${secs}s" else "${minutes}m") else "${secs}s"
}

/**
 * Whether the activity chip stands in for the reply: streaming, nothing of the answer shown yet, and no thinking block
 * — which already says what is happening, so the reveal's hold after `</think>` does not put "thinking... 4s" under
 * "thought for 3s" (ReplyPlaceholderTest).
 */
object ReplyPlaceholder {
    fun shown(streaming: Boolean, hasPlaceholder: Boolean, thinking: String?, shownAnswer: String?) =
        streaming && hasPlaceholder && thinking == null && shownAnswer.isNullOrEmpty()
    /** The answer's body, except while it is held after a thinking block: empty streaming markdown draws "…". */
    fun bodyShown(streaming: Boolean, thinking: String?, shownAnswer: String) = !(streaming && thinking != null && shownAnswer.isEmpty())
}

/**
 * The thinking disclosure's label, on ONE clock: while thinking, the activity chip's own words for the turn clock
 * ([activityLabel]: "thinking..." under a second, then "thinking... 4s"); once the answer begins, the thinking time the
 * view model stamped at `</think>` (ThinkingTimeTest).
 */
fun thinkingHeader(isThinking: Boolean, elapsedMs: Long?, thoughtMs: Long?): String =
    if (isThinking) activityLabel(ActivityState.Thinking, elapsedMs?.let { it / 1000.0 })
    else "thought for ${formattedDuration((thoughtMs ?: 0) / 1000.0)}"

/**
 * Disclosure state that survives leaving the viewport. A lazy list destroys a row's state when it scrolls out of
 * view: without this, expanding a reasoning block, scrolling down and back would find it collapsed again. Held
 * outside the row, keyed on message id, so the disclosure is a property of the MESSAGE rather than of the row that
 * happens to be showing it. Grows only by explicit taps, so it is bounded by what a person can do with a thumb.
 */
object DisclosureState {
    private val expanded = HashSet<String>()
    fun isExpanded(id: String) = id in expanded
    fun setExpanded(id: String, value: Boolean) { if (value) expanded.add(id) else expanded.remove(id) }
}

/**
 * MessageView, assistant role: the thinking disclosure (chevron + italic "thinking... 4s" / "thought for 4s", the
 * reasoning behind a 2pt rail once opened — open by default only for the turn in flight, remembered per message), the
 * answer as markdown, and the sources stack ONLY when there was no thinking. Selection is Android's long press (the
 * iOS "Copy" context menu, docs/ANDROID_NATIVE.md).
 */
@Composable
fun AssistantMessageView(
    messageId: String, content: String, generatingTimeMs: Long?, isLLMRunning: Boolean, streaming: Boolean, sources: List<GroundingSource>,
    /** The row's activity chip while the reply has no text yet (StreamingMessageView). */
    placeholder: (@Composable () -> Unit)? = null,
    /** The trailing chip, drawn under text already on screen while [showTrailing] (TrailingActivityChip.visible). */
    trailing: (@Composable () -> Unit)? = null,
    showTrailing: Boolean = false,
    /** The long-press menu's "copy" (iOS MessageView's context menu); null: no menu (a streaming reply). */
    onCopy: ((String) -> Unit)? = null,
    /** "think harder", only on the newest finished reply (ConversationView.thinkHarderAction); null elsewhere. */
    onThinkHarder: (() -> Unit)? = null,
    /**
     * Whether the provider can be asked for more (ReasoningControl, iOS 7cd8c10), asked when the menu OPENS: what it
     * depends on — the server kind a probe records, the saved /models row — is not Compose state, so a value read at
     * composition went stale (the AVD gate, 2026-10-22: an Ollama box learned on the Where sheet offered nothing until
     * a relaunch).
     */
    thinkHarderOffered: () -> Boolean = { true },
    /** While the reply thinks: the turn clock the activity chip counts on (ms since the turn began), so the two agree. */
    thinkingElapsedMs: Long? = null,
    /** StreamReveal on a streaming answer; off only where time does not run (a snapshot of text already on screen). */
    reveal: Boolean = true,
    /** The turn runs on the phone (ChatUiState.turnOnDevice): its first paragraph is not held (StreamReveal.Origin). */
    onDevice: Boolean = false,
) {
    // ONCE PER MESSAGE: the parse is remembered by id and content, never redone per appearance.
    val parse = remember(messageId, content) { ThinkingContentParser.parse(content) }
    val thinking = parse.thinking
    val answer = parse.answer ?: if (thinking == null) content else null
    val isThinking = thinking != null && !content.contains("</think>")
    // "WHOLE IF QUICK" (StreamReveal; the maintainer 2026-10-25): while streaming, the answer is shown as the reveal says — a
    // quick paragraph whole, with a fade; a slow one smoothly — not as its arrival bursts. Display only: arrivals still
    // drive the stall chip and everything else. Thinking is shown as it arrives. A finished reply shows everything.
    val pacer = remember(messageId) { StreamRevealFeed() }
    var revealed by remember(messageId) { mutableStateOf(StreamReveal.Shown(0)) }
    val revealing = reveal && streaming && answer != null
    if (revealing) {
        // Frames only while something is still to show or fading: caught up, the loop ends, and the next arrival
        // restarts it. A loop for the whole stream did work every frame between chunks, and kept Compose from ever
        // going idle — the test rule's idle waits then stalled (GroundedTurnTest on the Pixel, 2026-10-26).
        LaunchedEffect(messageId, answer) {
            pacer.arrive(answer, if (onDevice) StreamReveal.Origin.ON_DEVICE else StreamReveal.Origin.CLOUD, SystemClock.uptimeMillis())
            // and through a hold, a partial word's wait or a fade it sleeps until the next change (StreamReveal.waitMs)
            while (true) {
                withFrameMillis { revealed = pacer.frame(SystemClock.uptimeMillis()) }
                if (revealed.length >= answer.length && revealed.fadeFrom == null) break
                pacer.waitMs(SystemClock.uptimeMillis()).let { if (it > 0) delay(it) }
            }
        }
    }
    val shownAnswer = if (revealing) answer.take(revealed.length) else answer
    val fade = revealed.fadeFrom?.let { from -> revealed.fadeStartedAtMs?.let { RevealFade((shownAnswer.orEmpty().trimEnd().length - from).coerceAtLeast(0), it) } }
    var collapsed by remember(messageId) { mutableStateOf(!(DisclosureState.isExpanded(messageId) || isLLMRunning)) }
    // iOS's persisted MessageView APPEARS after the hand-off, collapsed unless the reader opened it; this composable
    // lives across the hand-off, so the open-while-thinking state has to fold when the turn ends (2026-09-09).
    LaunchedEffect(isLLMRunning) { if (!isLLMRunning && !DisclosureState.isExpanded(messageId)) collapsed = true }
    fun toggle() { collapsed = !collapsed; DisclosureState.setExpanded(messageId, !collapsed) }

    // THE LONG PRESS IS A MENU, as on iOS (the maintainer, 2026-10-19, for Think Harder): "think harder" on the newest finished
    // reply and "copy". A DOUBLE TAP selects the word under the finger, with Android's handles and copy toolbar (iOS
    // 8c19327, build 57: 56's "select text" item is gone on both). Until 1.0.6 a long press WAS the selection
    // (docs/ANDROID_NATIVE.md). A gesture, not clickable(): clickable merges the reply into one semantics node; the
    // actions ride as custom accessibility actions instead.
    var menu by remember(messageId) { mutableStateOf(false) }
    var offered by remember(messageId) { mutableStateOf(false) }
    val menuOn = onCopy != null && !streaming
    val gestures = if (!menuOn) Modifier else Modifier
        .pointerInput(messageId, onThinkHarder != null) { detectTapGestures(onLongPress = { offered = onThinkHarder != null && thinkHarderOffered(); menu = true }) }
        .semantics {
            customActions = listOfNotNull(
                onThinkHarder?.takeIf { thinkHarderOffered() }?.let { CustomAccessibilityAction(ReplyMenu.THINK_HARDER) { if (thinkHarderOffered()) { it(); true } else false } },
                CustomAccessibilityAction(ReplyMenu.COPY) { onCopy(answer ?: content); true },
            )
        }
    Box {
    Column(Modifier.fillMaxWidth().then(gestures), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (thinking != null) Column(Modifier.clickable(onClick = ::toggle), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chevron(down = !collapsed, TeemoonColors.secondaryLabel)
                Text(thinkingHeader(isThinking, thinkingElapsedMs, generatingTimeMs), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = TeemoonColors.secondaryLabel)
            }
            if (!collapsed && thinking.isNotBlank()) Row(Modifier.padding(start = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().padding(vertical = 1.dp).clip(CircleShape).background(TeemoonColors.fill))
                CompositionLocalProvider(LocalContentColor provides TeemoonColors.secondaryLabel) { MarkdownBody(thinking, streaming = streaming) }
            }
        }
        // Every reply with sources shows its chip, a thinking block or not (pm sources-ui, the maintainer 2026-10-16; iOS 2fd9f7e):
        // the chip is that answer's receipt. Gating it on `thinking == null` hid the stored sources of a model that writes
        // <think> into its answer. Never while streaming, as before.
        val hasSources = sources.isNotEmpty()
        val placeholderShown = ReplyPlaceholder.shown(streaming, placeholder != null, thinking, shownAnswer)
        if (shownAnswer != null) {
            if (placeholderShown) placeholder?.invoke()
            // iOS: `.padding(.bottom, hasSources ? 12 : 0)` under a Text whose box ends at its last line. Compose's text box
            // carries the line-height slack (bodyLarge 24/16: 4dp under the descender) and the renderer its block
            // padding, so the same 12 measured 39dp to the chip against iOS's 28 (Pixel, 2026-09-17): 2 here lands at 28.
            else if (ReplyPlaceholder.bodyShown(streaming, thinking, shownAnswer)) ReplySelection(longPressSelects = !menuOn) { Box(Modifier.padding(bottom = if (hasSources) 2.dp else 0.dp)) { MarkdownBody(shownAnswer, streaming = streaming, fade = fade) } }
        } else if (placeholderShown) placeholder?.invoke()
        if (streaming && showTrailing && trailing != null && !placeholderShown) Box(Modifier.padding(top = 8.dp)) { trailing() }
        // the sources chip arrives as the reply finishes: it fades and grows in, not a cut (motion-pass item 8)
        AppearFade(sources.takeIf { hasSources && !streaming }, "chat.sourcesFade", scaleFrom = 0.92f) { SourcesChip(it) }
    }
    if (menuOn) ReplyContextMenu(expanded = menu, onDismiss = { menu = false }, onThinkHarder = onThinkHarder?.takeIf { offered },
        onCopy = { onCopy(answer ?: content) })
    }
}

/**
 * The answer's text selection (iOS `.textSelection(.enabled)` with the reply's long press waiting on the menu's,
 * 8c19327). SelectionContainer selects a word on a double tap and on a long press; under the menu ([longPressSelects]
 * false) its long press is moved out of reach by giving the container a long-press timeout no finger holds, so the
 * reply's own detector, outside it at the platform timeout, is the only long press. The double tap, the handles and
 * the toolbar are untouched, and links still open on a tap. The thinking block stays outside: not selectable.
 */
@Composable
private fun ReplySelection(longPressSelects: Boolean, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val config = remember(base, longPressSelects) {
        if (longPressSelects) base else object : ViewConfiguration by base { override val longPressTimeoutMillis = NO_LONG_PRESS_MS }
    }
    CompositionLocalProvider(LocalViewConfiguration provides config) { SelectionContainer { content() } }
}

/** An hour: past any press, short of withTimeout's overflow. */
private const val NO_LONG_PRESS_MS = 3_600_000L

/** SF chevron.right / chevron.down, 12pt medium. */
@Composable
private fun Chevron(down: Boolean, color: Color) = Canvas(Modifier.size(12.dp)) {
    val w = size.width; val h = size.height; val s = 1.8.dp.toPx(); val cap = StrokeCap.Round
    if (down) { drawLine(color, Offset(w * 0.2f, h * 0.38f), Offset(w * 0.5f, h * 0.68f), s, cap); drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.8f, h * 0.38f), s, cap) }
    else { drawLine(color, Offset(w * 0.38f, h * 0.2f), Offset(w * 0.68f, h * 0.5f), s, cap); drawLine(color, Offset(w * 0.68f, h * 0.5f), Offset(w * 0.38f, h * 0.8f), s, cap) }
}

/**
 * MessageView, user role: the bubble at 24pt radius on the secondary background, 16 × 12 inside, 48pt of leading air;
 * the retry — a 16pt ring at the bottom-trailing corner, 8pt below, holding an 8pt arrow.clockwise — when the turn
 * failed; "copy" on a long press (the iOS context menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onCopy: ((String) -> Unit)? = null,
               /** IMAGES.md §5: thumbnails above the text, up to 4 in a 2×2 grid and "+N" on the 4th; a tap opens the viewer. */
               images: List<ChatImage> = emptyList(), onOpenImage: (Int) -> Unit = {}, imageActions: ImageActions = ImageActions()) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(start = 48.dp, bottom = if (onRetry != null) 8.dp else 0.dp), horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) ImageThumbRow(images, onOpenImage, Modifier.padding(bottom = if (text.isNotEmpty()) 6.dp else 0.dp), imageActions)
        if (text.isNotEmpty()) Box {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(TeemoonColors.secondaryBackground)
                    .combinedClickable(onClick = {}, onLongClick = { if (onCopy != null) menu = true }).padding(horizontal = 16.dp, vertical = 12.dp))
            CopyContextMenu(expanded = menu, onDismiss = { menu = false }, onCopy = { onCopy?.invoke(text) })
            AppearFade(onRetry, "chat.retryFade", Modifier.align(Alignment.BottomEnd)) {
                Box(Modifier.offset(x = (-10).dp, y = 8.dp).size(16.dp).clip(CircleShape).background(TeemoonColors.secondaryBackground)
                    .border(1.dp, TeemoonColors.secondaryLabel.copy(alpha = 0.4f), CircleShape).clickable(onClick = it).semantics { contentDescription = "retry" }.testTag("chat.retry"), contentAlignment = Alignment.Center) {
                    RefreshGlyph(TeemoonColors.secondaryLabel, 8.dp)
                }
            }
        }
    }
}

/**
 * iOS MessageImagesRow and ImageViewer (MessageImages.swift @ 85451a58; the grid MessageImageGrid.swift @ 760ba58f),
 * sized by IMAGES.md §4d (the 1.1.0 image flow, the maintainer 2026-11-14 for both apps): at most [SHOWN] tiles, [PER_ROW] to a
 * row; the last carries "+(count − 3)" when there are more and opens at its own index; each is "image n of count",
 * that one "images 4 to count of count". The viewer pages through every image of the message with page dots when
 * there is more than one — no "n / N" text.
 */
internal object ImageGrid {
    const val SHOWN = 4
    const val PER_ROW = 2
    data class Tile(val index: Int, val overlay: String?, val label: String)
    /** Past [SHOWN] the last tile stands for itself and the rest: "+N" counts it too, and so does its label. */
    fun tiles(count: Int): List<Tile> = (0 until minOf(count, SHOWN)).map { n ->
        if (n == SHOWN - 1 && count > SHOWN) Tile(n, "+${count - n}", "images ${n + 1} to $count of $count")
        else Tile(n, null, "image ${n + 1} of $count")
    }
    fun rows(count: Int): List<List<Tile>> = tiles(count).chunked(PER_ROW)
    /** Width to height in dp: one image 240 × 180, two as 136 squares, three or more as 100 squares. */
    fun tileSize(count: Int): Pair<Int, Int> = when {
        count <= 1 -> 240 to 180
        count == 2 -> 136 to 136
        else -> 100 to 100
    }
    fun viewerShowsDots(count: Int): Boolean = count > 1
    @Suppress("UNUSED_PARAMETER") fun viewerCounterText(page: Int, count: Int): String? = null
}

/**
 * A message's images as tiles (IMAGES.md §5, sized by §4d): up to four, two to a row and against the trailing edge as
 * the bubble is, 4dp apart with 16dp corners, the fourth showing "+N" over it when there are more. The thumbnails are JPEGs our own isolated decoder wrote — never
 * bytes a user or an export supplied.
 */
@Composable
fun ImageThumbRow(images: List<ChatImage>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier, actions: ImageActions = ImageActions()) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val (w, h) = ImageGrid.tileSize(images.size)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) { ImageGrid.rows(images.size).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { tile ->
            val img = images[tile.index]
            val bmp = remember(img.id, img.thumbnail.size) { android.graphics.BitmapFactory.decodeByteArray(img.thumbnail, 0, img.thumbnail.size)?.asImageBitmap() }
            // a tap opens the viewer; a long press opens the image's menu on the photo, with combinedClickable's long-press
            // haptic when the setting allows it, and never the viewer too (iOS S42; the maintainer 2026-11-12: a Material menu on every Android)
            val longPress: (() -> Unit)? = if (actions.any) ({ menuFor = tile.index }) else null
            Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(16.dp)).background(TeemoonColors.secondaryBackground)
                .combinedClickable(onClick = { onOpen(tile.index) }, onLongClick = longPress, hapticFeedbackEnabled = actions.haptics)
                .semantics { contentDescription = tile.label }.testTag("chat.image"), contentAlignment = Alignment.Center) {
                ImageContextMenu(expanded = menuFor == tile.index, onDismiss = { menuFor = null }, image = img, actions = actions)
                if (bmp != null) androidx.compose.foundation.Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // iOS's +N: black at 0.45 under white .headline (semibold 17)
                tile.overlay?.let { plus -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Text(plus, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 17.sp), color = Color.White)
                } }
            }
        }
    } } }
}

/**
 * The iOS context menu's one item, `Label("Copy", systemImage: "square.on.square")`: the title at the leading edge and
 * the glyph at the TRAILING edge, on a 250pt rounded menu. Material's bare DropdownMenuItem drew "copy" alone at the
 * left of its 112dp minimum, which read as a label with a wide empty right margin (user, 2026-10-02).
 */
@Composable
fun CopyContextMenu(expanded: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(14.dp), containerColor = TeemoonColors.secondaryBackground,
        modifier = Modifier.width(250.dp).testTag("chat.copyMenu")) {
        DropdownMenuItem(text = { Text("copy", style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) },
            trailingIcon = { CopyGlyph(TeemoonColors.label) }, onClick = { onDismiss(); onCopy() },
            contentPadding = PaddingValues(horizontal = 16.dp))
    }
}

/** What an image's menu offers; a null action is left out (iOS S42: copy, save, share). */
data class ImageActions(val copy: ((ChatImage) -> Unit)? = null, val save: ((ChatImage) -> Unit)? = null, val share: ((ChatImage) -> Unit)? = null,
                        /** The long press's haptic, behind the chats setting as every other (ThreadsScreen). */
                        val haptics: Boolean = true) {
    val any: Boolean get() = copy != null || save != null || share != null
}

/**
 * A chat image's long-press menu rows, in iOS's order (S42): copy, save, share, each with its Material icon at the
 * leading edge (the maintainer 2026-11-12, "Material menu"). A null action is left out.
 */
object ImageMenu {
    data class Row(val title: String, val icon: String)
    const val ICON_COPY = "content_copy"
    const val ICON_SAVE = "download"
    const val ICON_SHARE = "share"
    fun rows(actions: ImageActions): List<Row> = listOfNotNull(
        Row(ReplyMenu.COPY, ICON_COPY).takeIf { actions.copy != null },
        Row(ai.teemoon.app.imaging.ImageCopy.SAVE, ICON_SAVE).takeIf { actions.save != null },
        Row(ai.teemoon.app.imaging.ImageCopy.SHARE, ICON_SHARE).takeIf { actions.share != null })
}

/**
 * [ImageMenu] drawn as Material 3's own menu (the maintainer 2026-11-12: "Material menu"; the platform way, listed in
 * ANDROID_NATIVE.md): the theme's surface container, 48dp rows, the icon leading. Copy puts the stored JPEG/PNG on the
 * clipboard as it is, save adds it to the gallery, share opens the plain system sheet.
 */
@Composable
fun ImageContextMenu(expanded: Boolean, onDismiss: () -> Unit, image: ChatImage, actions: ImageActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("chat.imageMenu")) {
        ImageMenuItems(image, actions, onDismiss)
    }
}

/** The menu's rows, apart from its popup so the golden can draw them (Paparazzi does not render popups). */
@Composable
fun ImageMenuItems(image: ChatImage, actions: ImageActions, onDismiss: () -> Unit) {
    for (row in ImageMenu.rows(actions)) {
        val act = when (row.icon) { ImageMenu.ICON_COPY -> actions.copy; ImageMenu.ICON_SAVE -> actions.save; else -> actions.share } ?: continue
        DropdownMenuItem(text = { Text(row.title) }, onClick = { onDismiss(); act(image) },
            leadingIcon = { ImageMenuIcon(row.icon) }, modifier = Modifier.testTag("chat.imageMenu.${row.title}"))
    }
}

@Composable
private fun ImageMenuIcon(icon: String) = when (icon) {
    ImageMenu.ICON_SHARE -> androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Share, contentDescription = null)
    else -> androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(
        if (icon == ImageMenu.ICON_COPY) R.drawable.ic_content_copy else R.drawable.ic_download), contentDescription = null)
}

object ReplyMenu {
    const val THINK_HARDER = "think harder"
    const val COPY = "copy"
}

/**
 * A reply's long-press menu (iOS MessageView's context menu): `Label("Think Harder", systemImage: "brain")` above
 * `Label("Copy", systemImage: "square.on.square")`; a double tap selects (iOS 8c19327 removed "select text" on both).
 * Lowercase, as every menu here. Same shape as [CopyContextMenu].
 */
@Composable
fun ReplyContextMenu(expanded: Boolean, onDismiss: () -> Unit, onThinkHarder: (() -> Unit)?, onCopy: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(14.dp), containerColor = TeemoonColors.secondaryBackground,
        modifier = Modifier.width(250.dp).testTag("chat.replyMenu")) {
        onThinkHarder?.let { act ->
            DropdownMenuItem(text = { Text(ReplyMenu.THINK_HARDER, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) },
                trailingIcon = { BrainGlyph(TeemoonColors.label) }, onClick = { onDismiss(); act() },
                contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.testTag("chat.thinkHarder"))
        }
        DropdownMenuItem(text = { Text(ReplyMenu.COPY, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) },
            trailingIcon = { CopyGlyph(TeemoonColors.label) }, onClick = { onDismiss(); onCopy() },
            contentPadding = PaddingValues(horizontal = 16.dp))
    }
}

/** The where chip's menu: "model info" (info.circle) and "copy model id" (square.on.square), title leading, glyph trailing. */
@Composable
fun ChipMenu(expanded: Boolean, onDismiss: () -> Unit, onModelInfo: () -> Unit, onCopyModelID: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(14.dp), containerColor = TeemoonColors.secondaryBackground,
        modifier = Modifier.width(250.dp).testTag("chat.whereChipMenu")) {
        DropdownMenuItem(text = { Text("model info", style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) },
            trailingIcon = { FilledCircleGlyph("i", TeemoonColors.label, size = 18.dp) }, onClick = { onDismiss(); onModelInfo() },
            contentPadding = PaddingValues(horizontal = 16.dp))
        DropdownMenuItem(text = { Text(A11yLabels.COPY_MODEL_ID, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) },
            trailingIcon = { CopyGlyph(TeemoonColors.label) }, onClick = { onDismiss(); onCopyModelID() },
            contentPadding = PaddingValues(horizontal = 16.dp))
    }
}

/** A reply's marker (reply-markers): the approved line in the caption style, and "retry" when the reply didn't finish. */
/**
 * A reply and, under it, its sent-before-confirmed mark and how its turn ended (reply-markers), in one column. The
 * transcript draws this inside AnimatedContent, whose content is a Box: as siblings there the marker sat on the
 * reply's first line (the maintainer's Pixel, 2026-11-07: "you stopped this reply." over a story's title).
 */
@Composable
internal fun ReplyWithMarkers(reply: @Composable () -> Unit, sentMark: Pair<String, Boolean>?, marker: String?, onRetry: (() -> Unit)?) = Column {
    reply()
    sentMark?.let { (id, failed) -> ReplyMarkerLine(id, failed = failed) }
    marker?.let { id -> ReplyMarkerLine(id, onRetry = onRetry) }
}

@Composable
internal fun ReplyMarkerLine(markerId: String, onRetry: (() -> Unit)? = null, failed: Boolean = false) {
    val text = ReplyMarkerCopy.text(markerId) ?: return
    Row(Modifier.padding(top = 4.dp).testTag("chat.replyMarker"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (failed) TeemoonColors.red else TeemoonColors.secondaryLabel, modifier = Modifier.weight(1f, fill = false))
        onRetry?.let { Text("retry", style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = TeemoonColors.tint,
            modifier = Modifier.clickable(onClick = it).semantics { contentDescription = "retry" }.testTag("chat.markerRetry")) }
    }
}
