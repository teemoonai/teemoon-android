// ports: teemoon/Views/Chat/MessageView.swift @ 8c19327 — the assistant row (the thinking disclosure, the answer, the
//        sources rule, copy), the user bubble's geometry and retry, TimeInterval.formatted, DisclosureState
package ai.teemoon.app

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
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

/** TimeInterval.formatted: "4s", "1m", "1m 5s". */
fun formattedDuration(seconds: Double): String {
    val total = seconds.toInt(); val minutes = total / 60; val secs = total % 60
    return if (minutes > 0) (if (secs > 0) "${minutes}m ${secs}s" else "${minutes}m") else "${secs}s"
}

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
) {
    // ONCE PER MESSAGE: the parse is remembered by id and content, never redone per appearance.
    val parse = remember(messageId, content) { ThinkingContentParser.parse(content) }
    val thinking = parse.thinking
    val answer = parse.answer ?: if (thinking == null) content else null
    val isThinking = thinking != null && !content.contains("</think>")
    var collapsed by remember(messageId) { mutableStateOf(!(DisclosureState.isExpanded(messageId) || isLLMRunning)) }
    // iOS's persisted MessageView APPEARS after the hand-off, collapsed unless the reader opened it; this composable
    // lives across the hand-off, so the open-while-thinking state has to fold when the turn ends (2026-09-09).
    LaunchedEffect(isLLMRunning) { if (!isLLMRunning && !DisclosureState.isExpanded(messageId)) collapsed = true }
    fun toggle() { collapsed = !collapsed; DisclosureState.setExpanded(messageId, !collapsed) }
    val time = generatingTimeMs?.let { formattedDuration(it / 1000.0) } ?: "0s"

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
                Text("${if (isThinking) "thinking..." else "thought for"} $time", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = TeemoonColors.secondaryLabel)
            }
            if (!collapsed && thinking.isNotBlank()) Row(Modifier.padding(start = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().padding(vertical = 1.dp).clip(CircleShape).background(TeemoonColors.fill))
                CompositionLocalProvider(LocalContentColor provides TeemoonColors.secondaryLabel) { MarkdownBody(thinking, streaming = streaming) }
            }
        }
        val hasSources = thinking == null && sources.isNotEmpty()
        if (answer != null) {
            if (streaming && answer.isEmpty() && placeholder != null) placeholder()
            // iOS: `.padding(.bottom, hasSources ? 12 : 0)` under a Text whose box ends at its last line. Compose's text box
            // carries the line-height slack (bodyLarge 24/16: 4dp under the descender) and the renderer its block
            // padding, so the same 12 measured 39dp to the chip against iOS's 28 (Pixel, 2026-09-17): 2 here lands at 28.
            else ReplySelection(longPressSelects = !menuOn) { Box(Modifier.padding(bottom = if (hasSources) 2.dp else 0.dp)) { MarkdownBody(answer, streaming = streaming) } }
        } else if (streaming && placeholder != null) placeholder()
        if (streaming && showTrailing && trailing != null) Box(Modifier.padding(top = 8.dp)) { trailing() }
        if (hasSources && !streaming) SourcesChip(sources)
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
fun UserBubble(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onCopy: ((String) -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(start = 48.dp, bottom = if (onRetry != null) 8.dp else 0.dp), horizontalArrangement = Arrangement.End) {
        Box {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label,
                modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(TeemoonColors.secondaryBackground)
                    .combinedClickable(onClick = {}, onLongClick = { if (onCopy != null) menu = true }).padding(horizontal = 16.dp, vertical = 12.dp))
            CopyContextMenu(expanded = menu, onDismiss = { menu = false }, onCopy = { onCopy?.invoke(text) })
            onRetry?.let {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = (-10).dp, y = 8.dp).size(16.dp).clip(CircleShape).background(TeemoonColors.secondaryBackground)
                    .border(1.dp, TeemoonColors.secondaryLabel.copy(alpha = 0.4f), CircleShape).clickable(onClick = it).semantics { contentDescription = "retry" }.testTag("chat.retry"), contentAlignment = Alignment.Center) {
                    RefreshGlyph(TeemoonColors.secondaryLabel, 8.dp)
                }
            }
        }
    }
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
