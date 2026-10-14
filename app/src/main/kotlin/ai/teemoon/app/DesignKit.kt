@file:OptIn(ExperimentalFoundationApi::class)

// design: teemoon-ios docs/DESIGN.md @ v1.0.1 — REBUILD in Compose. The pieces every screen is built from:
//         the nav bar with its circular icon buttons, inset-grouped lists, capsule chips, the segmented picker,
//         the search pill and the composer pill. Copy is lowercase; the grid is 8pt; tint is the user's.
package ai.teemoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import kotlin.math.abs
import kotlin.math.cos

// MARK: chrome

/**
 * Whether the screen sits on the grouped ground. Chrome that is raised over the system background (the chat's
 * circle buttons) uses the secondary background; over the grouped ground that colour IS the ground in the light,
 * so the same chrome uses the card colour there (white / #1C1C1E), as iOS does with its bar pills on a sheet.
 */
val LocalOnGroupedGround = compositionLocalOf { false }


/** `.lineLimit(1).minimumScaleFactor(0.8)` for a row's caption: one line, shrinking up to 20% before it would wrap. */
val LocalSubtitleShrinksToFit = staticCompositionLocalOf { false }

@Composable
fun chromeBackground(): Color = if (LocalOnGroupedGround.current) TeemoonColors.groupedCard else TeemoonColors.secondaryBackground

/** The 44pt circular icon button of the chat toolbar (list.bullet / gear / plus / xmark) on the secondary background. */
@Composable
fun CircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = TeemoonColors.tint) {
    Box(modifier.size(44.dp).clip(CircleShape).background(chromeBackground()).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A text pill of the same weight as [CircleButton] — "close", "done", "back". */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    // a plain toolbar button takes the accent on iOS ("cancel", "save"); only the system back chevron is the label colour
    tint: Color = TeemoonColors.tint, enabled: Boolean = true) {
    LocalRenderRecorder.current?.row(
        kind = "pillButton", text = text,
        color = if (tint == TeemoonColors.label) "label" else "custom")
    Box(modifier.height(44.dp).clip(CircleShape).background(chromeBackground()).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

/**
 * The title block: headline title, an optional footnote under it (the trust caption, tappable),
 * a leading and a trailing control. 56pt tall; the title yields to the controls.
 */
@Composable
fun NavBar(
    title: String, subtitle: String? = null, subtitleColor: Color = TeemoonColors.secondaryLabel,
    leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}, modifier: Modifier = Modifier, titleGlyph: ImageVector? = null,
    /** A glyph drawn before the title (the chat's locality glyph) and a chevron after it — the affordance for [onTitle], the block's ONE action. */
    titleLeading: (@Composable () -> Unit)? = null, titleChevron: Boolean = false, onTitle: (() -> Unit)? = null,
) {
    // The title is centred on the screen, not between the controls; the controls size to their content (a text pill is wider than a circle).
    // 44pt, the UIKit navigation bar (measured on the tag: the title sits 9pt under a sheet's top edge, not 36)
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        // E2EETitleBlock: the title line AND the caption are ONE control (44pt minimum, the whole rectangle) with one
        // action. Two stacked targets a few points apart let the finger pick which sheet opened (2026-09-09).
        Column(Modifier.align(Alignment.Center).padding(horizontal = 80.dp).heightIn(min = 44.dp)
            .then(if (onTitle != null) Modifier.clickable(onClick = onTitle).testTag("chat.titleBlock") else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                titleLeading?.invoke()
                titleGlyph?.let { Icon(it, null, tint = TeemoonColors.label, modifier = Modifier.size(16.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                if (titleChevron) Icon(Icons.Filled.KeyboardArrowDown, null, tint = TeemoonColors.secondaryLabel, modifier = Modifier.size(18.dp))
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chat.trustChip"))
            }
        }
    }
}

// MARK: grouped lists

/** The top inset for a grouped page whose content begins with a SectionLabel (the label's own 20pt sits inside Form's inset). */
val LABEL_FIRST_INSET = 0.dp

/** A grouped screen: the grouped ground, safe areas, a scrolling column. */
@Composable
fun GroupedPage(modifier: Modifier = Modifier, scroll: Boolean = true, scrollState: ScrollState? = null,
    /** The navigation bar, PINNED above the scrolling content as a sheet's toolbar is on iOS — it never scrolls away. */
    header: (@Composable () -> Unit)? = null,
    /**
     * Form's top inset: measured on the tag, the first card sits ~68pt under the title when the section has no
     * header, ~72pt when it has one — UIKit's ~35pt list inset with the header living inside it. Pages whose content
     * starts with a SectionLabel (20pt of its own top padding) pass [LABEL_FIRST_INSET] instead.
     */
    topInset: Dp = 35.dp,
    content: @Composable ColumnScope.() -> Unit) {
    val focus = LocalFocusManager.current
    val base = modifier.fillMaxSize().background(TeemoonColors.groupedBackground).statusBarsPadding().navigationBarsPadding().imePadding()
        .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) }   // a tap outside a field dismisses the keyboard
    CompositionLocalProvider(LocalOnGroupedGround provides true) {
        if (header != null) Column(base) {
            val state = scrollState ?: rememberScrollState()
            // derived, so a scroll recomposes this only when it crosses the top, not on every frame
            val scrolled by remember(state, scroll) { derivedStateOf { scroll && state.value > 0 } }
            header()
            // iOS 26's scroll edge effect: content scrolled under the bar dissolves into it. Without it the rows were cut
            // flat at the bar's edge and read as touching the title (the where sheet, Pixel, 2026-10-13). Material 3's
            // tinted bar was compared and not taken: it is the cards' grey, and it swallowed the close circle. DRAWN over
            // the viewport, not a Box around it: the Box version changed the layout, and the trust ladder's expert rung
            // stopped scrolling its last line clear of the bottom edge (UserJourneysTest E5, 2026-10-13).
            val ground = TeemoonColors.groupedBackground
            val fade = Modifier.drawWithContent {
                drawContent()
                if (scrolled) drawRect(Brush.verticalGradient(listOf(ground, ground.copy(alpha = 0f)), endY = 24.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width, 24.dp.toPx()))
            }
            Column((if (scroll) Modifier.weight(1f).fillMaxWidth().then(fade).verticalScroll(state) else Modifier.weight(1f).fillMaxWidth()).padding(top = topInset, bottom = 24.dp)) { content(); if (scroll) SheetScrollEnd() }
        }
        else Column((if (scroll) base.verticalScroll(scrollState ?: rememberScrollState()) else base).padding(bottom = 24.dp)) { content(); if (scroll) SheetScrollEnd() }
    }
}

/** The lowercase footnote above a group ("ready now", "get"); uppercase for the date buckets of the chats list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, uppercase: Boolean = false,
    // iOS 26's grouped-list section header is 17pt semibold in the secondary colour (it was a 13pt footnote before 26);
    // the date buckets of the chats list keep the small uppercase label.
    style: TextStyle = if (uppercase) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
    // iOS 26 also spaces its sections wider: ~42pt from a group's bottom to the next header's text, ~23pt from that text
    // to the group under it (parity capture, 2026-10-05). The 20/6 here were the iOS 18 footnote's.
    topPadding: Dp = if (uppercase) 20.dp else 26.dp) {
    val shown = if (uppercase) text.uppercase() else text
    val bottom = if (uppercase) 6.dp else 10.dp
    // RESOLVED, not declared: `style` arrives already merged, so the size and
    // weight recorded here are what this call site actually draws — including a
    // caller's override and the type ramp's font scale. That is the whole reason
    // the record exists; a source-reading tool sees the default and nothing else.
    LocalRenderRecorder.current?.row(
        kind = "sectionLabel", text = shown,
        fontSize = style.fontSize, fontWeight = style.fontWeight, color = "secondaryLabel",
        padding = RenderRecorder.Padding(top = topPadding, leading = 32.dp, bottom = bottom, trailing = 32.dp))
    Text(shown, style = style, color = TeemoonColors.secondaryLabel, modifier = modifier.padding(start = 32.dp, end = 32.dp, top = topPadding, bottom = bottom))
}

/** A footnote under a group. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier, color: Color = TeemoonColors.secondaryLabel) {
    val style = MaterialTheme.typography.bodySmall
    LocalRenderRecorder.current?.row(
        kind = "groupFooter", text = text,
        fontSize = style.fontSize, fontWeight = style.fontWeight,
        color = if (color == TeemoonColors.secondaryLabel) "secondaryLabel" else "custom",
        padding = RenderRecorder.Padding(top = 6.dp, leading = 32.dp, bottom = 6.dp, trailing = 32.dp))
    Text(text, style = style, color = color, modifier = modifier.padding(horizontal = 32.dp, vertical = 6.dp))
}

/**
 * An inset-grouped card: raised over the grouped ground, 20pt radius. The group draws the separators itself,
 * between consecutive children and never under the last, so rows need not know their neighbours.
 */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, dividerInset: Dp = 16.dp, content: @Composable () -> Unit) {
    val separator = TeemoonColors.separator
    val density = LocalDensity.current
    val inset = with(density) { dividerInset.toPx() }
    val stroke = with(density) { 0.5.dp.toPx() }.coerceAtLeast(1f)
    var boundaries by remember { mutableStateOf<List<Float>>(emptyList()) }
    Layout(
        content = content,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(TeemoonColors.groupedCard)
            .drawWithContent { drawContent(); boundaries.forEach { y -> drawLine(separator, Offset(inset, y), Offset(size.width, y), stroke) } },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = placeables.sumOf { it.height }
        val ys = ArrayList<Float>(placeables.size)
        var y = 0
        placeables.forEachIndexed { i, p -> y += p.height; if (i < placeables.lastIndex) ys += y.toFloat() }
        if (ys != boundaries) boundaries = ys   // read in the draw phase only; a measure-time write is the supported way to feed it
        layout(constraints.maxWidth, height) { var top = 0; placeables.forEach { it.placeRelative(0, top); top += it.height } }
    }
}

/** The glyph of a locality: phone / a computer / a cloud, drawn in the label color at 20pt. */
@Composable
fun LocalityGlyph(locality: WhereLocality, tint: Color = TeemoonColors.secondaryLabel, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
        when (locality) {
            WhereLocality.PHONE -> {
                drawRoundRect(tint, topLeft = Offset(w * 0.28f, h * 0.06f), size = Size(w * 0.44f, h * 0.88f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(s))
                drawLine(tint, Offset(w * 0.42f, h * 0.8f), Offset(w * 0.58f, h * 0.8f), s)
            }
            WhereLocality.HOME -> {
                // WhereLocality.home.systemImage is `desktopcomputer` — the Where rows, the chip and Places use the
                // monitor. The laptop-with-lock is the TITLE block's and the trust hero's glyph (LaptopLockGlyph).
                drawRoundRect(tint, topLeft = Offset(w * 0.06f, h * 0.14f), size = Size(w * 0.88f, h * 0.58f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s))
                drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.9f), s)
                drawLine(tint, Offset(w * 0.3f, h * 0.9f), Offset(w * 0.7f, h * 0.9f), s)
            }
            WhereLocality.CLOUD -> {
                val path = Path().apply {
                    moveTo(w * 0.28f, h * 0.78f)
                    cubicTo(w * 0.02f, h * 0.78f, w * 0.02f, h * 0.42f, w * 0.3f, h * 0.44f)
                    cubicTo(w * 0.36f, h * 0.14f, w * 0.78f, h * 0.14f, w * 0.78f, h * 0.42f)
                    cubicTo(w * 1.02f, h * 0.42f, w * 1.02f, h * 0.78f, w * 0.74f, h * 0.78f)
                    close()
                }
                drawPath(path, tint, style = Stroke(s))
            }
        }
    }
}

/** SF `lock.laptopcomputer`: the lid with a small lock on it, the base line under it — E2EETitleBlock's self-hosted glyph and the trust hero's. */
@Composable
fun LaptopLockGlyph(tint: Color = TeemoonColors.label, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = maxOf(1.6.dp.toPx(), w * 0.05f)   // a semibold symbol's stroke grows with it
        drawRoundRect(tint, topLeft = Offset(w * 0.14f, h * 0.18f), size = Size(w * 0.72f, h * 0.5f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.02f, h * 0.78f), Offset(w * 0.98f, h * 0.78f), s)
        drawRoundRect(color = tint, topLeft = Offset(w * 0.4f, h * 0.38f), size = Size(w * 0.2f, h * 0.16f), cornerRadius = CornerRadius(w * 0.03f))
        drawArc(color = tint, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.43f, h * 0.26f), size = Size(w * 0.14f, h * 0.2f), style = Stroke(s * 0.8f))
    }
}

/** SF `key`: the bow, the shaft, two teeth. */
@Composable
fun KeyGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
        drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.32f, h * 0.32f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.44f, h * 0.44f), Offset(w * 0.86f, h * 0.86f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.7f, h * 0.7f), Offset(w * 0.58f, h * 0.82f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.82f), Offset(w * 0.7f, h * 0.94f), s, StrokeCap.Round)
    }
}

/** SF `paintpalette`: the disc with a thumb notch and three paint wells. */
@Composable
fun PaletteGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
        drawArc(tint, startAngle = 20f, sweepAngle = 320f, useCenter = false, topLeft = Offset(w * 0.08f, h * 0.08f), size = Size(w * 0.84f, h * 0.84f), style = Stroke(s))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.34f, h * 0.36f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.54f, h * 0.28f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.3f, h * 0.6f))
    }
}

/** SF `message`: the speech bubble with its tail at the lower left. */
@Composable
fun BubbleGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.9f); lineTo(w * 0.26f, h * 0.72f)
            cubicTo(w * 0.1f, h * 0.64f, w * 0.06f, h * 0.44f, w * 0.14f, h * 0.3f)
            cubicTo(w * 0.3f, h * 0.06f, w * 0.7f, h * 0.06f, w * 0.86f, h * 0.3f)
            cubicTo(w * 0.98f, h * 0.5f, w * 0.86f, h * 0.74f, w * 0.6f, h * 0.78f)
            cubicTo(w * 0.5f, h * 0.8f, w * 0.4f, h * 0.78f, w * 0.36f, h * 0.76f); close()
        }
        drawPath(path, tint, style = Stroke(s))
    }
}

/** SF `paintbrush.pointed`: the handle from the lower left into the ferrule, a pointed tip at the upper right. */
@Composable
fun PaintbrushGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
        drawLine(tint, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.5f, h * 0.5f), s * 1.6f, StrokeCap.Round)
        val tip = Path().apply { moveTo(w * 0.46f, h * 0.36f); lineTo(w * 0.9f, h * 0.1f); lineTo(w * 0.64f, h * 0.54f); close() }
        drawPath(tip, tint, style = Stroke(s))
        drawLine(tint, Offset(w * 0.4f, h * 0.6f), Offset(w * 0.56f, h * 0.44f), s)
    }
}

/** SF `arrow.left.and.line.vertical.and.arrow.right`: arrows out from a centre bar. */
@Composable
fun WidthGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx(); val cap = StrokeCap.Round; val y = h * 0.5f
        drawLine(tint, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), s, cap)
        drawLine(tint, Offset(w * 0.06f, y), Offset(w * 0.38f, y), s, cap); drawLine(tint, Offset(w * 0.62f, y), Offset(w * 0.94f, y), s, cap)
        drawLine(tint, Offset(w * 0.06f, y), Offset(w * 0.2f, h * 0.36f), s, cap); drawLine(tint, Offset(w * 0.06f, y), Offset(w * 0.2f, h * 0.64f), s, cap)
        drawLine(tint, Offset(w * 0.94f, y), Offset(w * 0.8f, h * 0.36f), s, cap); drawLine(tint, Offset(w * 0.94f, y), Offset(w * 0.8f, h * 0.64f), s, cap)
    }
}

/** SF `arrow.up.left.and.arrow.down.right`: two arrows along the diagonal. */
@Composable
fun ResizeGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx(); val cap = StrokeCap.Round
        drawLine(tint, Offset(w * 0.12f, h * 0.12f), Offset(w * 0.42f, h * 0.42f), s, cap); drawLine(tint, Offset(w * 0.58f, h * 0.58f), Offset(w * 0.88f, h * 0.88f), s, cap)
        drawLine(tint, Offset(w * 0.12f, h * 0.12f), Offset(w * 0.36f, h * 0.12f), s, cap); drawLine(tint, Offset(w * 0.12f, h * 0.12f), Offset(w * 0.12f, h * 0.36f), s, cap)
        drawLine(tint, Offset(w * 0.88f, h * 0.88f), Offset(w * 0.64f, h * 0.88f), s, cap); drawLine(tint, Offset(w * 0.88f, h * 0.88f), Offset(w * 0.88f, h * 0.64f), s, cap)
    }
}

/** SF `square.on.square`: two overlapping squares — the copy affordance beside a key. */
@Composable
fun CopyGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx()
        drawRoundRect(tint, topLeft = Offset(w * 0.3f, h * 0.3f), size = Size(w * 0.62f, h * 0.62f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(s))
        val p = Path().apply { moveTo(w * 0.7f, h * 0.2f); lineTo(w * 0.2f, h * 0.2f); lineTo(w * 0.2f, h * 0.7f) }
        val q = Path().apply { moveTo(w * 0.22f, h * 0.08f); lineTo(w * 0.7f, h * 0.08f) }
        drawPath(p, tint, style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** SF `eye` / `eye.slash`: the outline with its pupil, struck through when the secret is shown. */
@Composable
fun EyeGlyph(open: Boolean = true, tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx()
        val lid = Path().apply { moveTo(w * 0.06f, h * 0.5f); quadraticTo(w * 0.5f, h * 0.06f, w * 0.94f, h * 0.5f); quadraticTo(w * 0.5f, h * 0.94f, w * 0.06f, h * 0.5f); close() }
        drawPath(lid, tint, style = Stroke(s))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(s))
        if (!open) drawLine(tint, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.85f, h * 0.15f), s, StrokeCap.Round)
    }
}

/** SF `arrow.up.right.square`: a square with the arrow leaving through its top-right corner. */
@Composable
fun OpenLinkGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        drawRoundRect(tint, topLeft = Offset(w * 0.12f, h * 0.12f), size = Size(w * 0.76f, h * 0.76f), cornerRadius = CornerRadius(w * 0.12f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.38f, h * 0.62f), Offset(w * 0.66f, h * 0.34f), s, cap)
        drawLine(tint, Offset(w * 0.46f, h * 0.34f), Offset(w * 0.66f, h * 0.34f), s, cap); drawLine(tint, Offset(w * 0.66f, h * 0.34f), Offset(w * 0.66f, h * 0.54f), s, cap)
    }
}

/** SF `arrow.up.right`, bare: the diagonal shaft with its head — the link row's trailing glyph. */
@Composable
fun ArrowUpRightGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 14.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 2.dp.toPx(); val cap = StrokeCap.Round
        drawLine(tint, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), s, cap)
        drawLine(tint, Offset(w * 0.34f, h * 0.2f), Offset(w * 0.8f, h * 0.2f), s, cap); drawLine(tint, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.8f, h * 0.66f), s, cap)
    }
}

/** SF `arrow.triangle.2.circlepath`: two arcs chasing each other, an arrowhead on each. */
@Composable
fun RefreshGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        val box = Size(w * 0.72f, h * 0.72f); val tl = Offset(w * 0.14f, h * 0.14f)
        drawArc(tint, startAngle = 200f, sweepAngle = 140f, useCenter = false, topLeft = tl, size = box, style = Stroke(s, cap = cap))
        drawArc(tint, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = tl, size = box, style = Stroke(s, cap = cap))
        drawLine(tint, Offset(w * 0.78f, h * 0.2f), Offset(w * 0.82f, h * 0.42f), s, cap); drawLine(tint, Offset(w * 0.82f, h * 0.42f), Offset(w * 0.6f, h * 0.4f), s, cap)
        drawLine(tint, Offset(w * 0.22f, h * 0.8f), Offset(w * 0.18f, h * 0.58f), s, cap); drawLine(tint, Offset(w * 0.18f, h * 0.58f), Offset(w * 0.4f, h * 0.6f), s, cap)
    }
}

/** SF `lock.fill` / `lock.open`: the body filled, the shackle closed over it or swung open to the right. */
@Composable
fun LockGlyph(open: Boolean, tint: Color = TeemoonColors.label, sizeDp: Dp = 14.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = w * 0.12f
        drawRoundRect(tint, topLeft = Offset(w * 0.16f, h * 0.46f), size = Size(w * 0.68f, h * 0.46f), cornerRadius = CornerRadius(w * 0.12f))
        if (open) {
            drawArc(tint, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.46f, h * 0.06f), size = Size(w * 0.46f, h * 0.44f), style = Stroke(s, cap = StrokeCap.Round))
            drawLine(tint, Offset(w * 0.92f, h * 0.28f), Offset(w * 0.92f, h * 0.46f), s, StrokeCap.Round)
        } else {
            drawArc(tint, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.27f, h * 0.08f), size = Size(w * 0.46f, h * 0.44f), style = Stroke(s, cap = StrokeCap.Round))
            drawLine(tint, Offset(w * 0.27f, h * 0.3f), Offset(w * 0.27f, h * 0.46f), s); drawLine(tint, Offset(w * 0.73f, h * 0.3f), Offset(w * 0.73f, h * 0.46f), s)
        }
    }
}

/** SF `chevron.up.chevron.down`: two small chevrons stacked, one glyph — not the two text characters "⌃⌄". */
@Composable
fun UpDownChevrons(tint: Color = TeemoonColors.secondaryLabel, sizeDp: Dp = 12.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.6.dp.toPx(); val cap = StrokeCap.Round
        drawLine(tint, Offset(w * 0.2f, h * 0.38f), Offset(w * 0.5f, h * 0.08f), s, cap); drawLine(tint, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.8f, h * 0.38f), s, cap)
        drawLine(tint, Offset(w * 0.2f, h * 0.62f), Offset(w * 0.5f, h * 0.92f), s, cap); drawLine(tint, Offset(w * 0.5f, h * 0.92f), Offset(w * 0.8f, h * 0.62f), s, cap)
    }
}

/**
 * The app's alert (ProductAlert on iOS): a card on the elevated tertiary ground, a semibold title, a secondary
 * message, then every action as a full-width pill stacked in order — never Material's row of text buttons, which
 * wraps three actions onto two lines.
 */
@Composable
fun IosAlert(title: String, message: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    // An alert is three kinds of copy and each is a separate row: a title that
    // matches while its body does not is exactly the ChatView finding the copy
    // audit turned up, and one merged row would have hidden it.
    LocalRenderRecorder.current?.let { rec ->
        rec.row(kind = "alertTitle", text = title)
        rec.row(kind = "alertMessage", text = message)
        for ((label, _) in actions) rec.row(kind = "alertAction", text = label)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF2C2C2E)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEBEBF5).copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 6.dp))
            actions.forEach { (label, act) ->
                Box(Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Color(0xFF3A3A3C)).clickable(onClick = act), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.tint)
                }
            }
        }
    }
}

/** The divider between two rows of an [InsetGroup]. */
@Composable
fun InsetDivider(startInset: Dp = 16.dp) = HorizontalDivider(Modifier.padding(start = startInset), color = TeemoonColors.separator, thickness = 0.5.dp)

/**
 * One row: an optional leading glyph, a title, an optional subtitle, a trailing slot. Rows are at least 44pt;
 * the divider goes under every row but the last (the caller says which).
 */
@Composable
fun InsetRow(
    title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    titleColor: Color = TeemoonColors.label, subtitleColor: Color = TeemoonColors.secondaryLabel, titleWeight: FontWeight = FontWeight.Normal,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null, chevron: Boolean = false, divider: Boolean = false, titleMaxLines: Int = 1,
    /** WhereRow.trailingGlyph is drawn in the accent; a settings row's chevron stays tertiary. */
    chevronTint: Color = TeemoonColors.tertiaryLabel,
    /** iOS row actions live in a context menu on a long press; the row itself stays clean. */
    onLongClick: (() -> Unit)? = null,
    /** Tags drawn after the title on its own line (the green e2ee tag). */
    titleTags: (@Composable RowScope.() -> Unit)? = null,
    /**
     * LabeledContent: the title takes its own width and the TRAILING value takes the rest, ending at the chevron. The
     * trailing content must then carry `weight(1f)` itself. Off, the title column takes the slack, as before.
     */
    valueFills: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row((if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier).fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.let { Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { it() } }
            Column(if (valueFills) Modifier else Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = titleWeight), color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis, modifier = if (valueFills) Modifier else Modifier.weight(1f, fill = false))
                    titleTags?.invoke(this)
                }
                subtitle?.let {
                    if (LocalSubtitleShrinksToFit.current) BasicText(it, style = MaterialTheme.typography.bodySmall.copy(color = subtitleColor), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = MaterialTheme.typography.bodySmall.fontSize * 0.8f, maxFontSize = MaterialTheme.typography.bodySmall.fontSize))
                    else Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.let { it() }
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = chevronTint, modifier = Modifier.size(20.dp))
        }
        if (divider) InsetDivider(if (leading != null) 52.dp else 16.dp)
    }
}

/**
 * `.swipeActions(edge: .trailing)` for one destructive action: a swipe from the trailing edge reveals [label] on red, the
 * action fires when the swipe settles past the threshold, and the row springs back rather than dismissing (`false`) —
 * what happens next is the action's own affair (an alert, or the row leaving the list because the store no longer has
 * it). No leading swipe. The content carries the card's ground so the red is REVEALED beside the row, not seen through
 * it. The same box the chats list uses; every inset row that iOS lets you swipe goes through here.
 *
 * `confirmValueChange` is deprecated in favour of dynamic anchor sets. Vetoing the dismiss is the whole point here
 * (the row never leaves; the action decides), and the journeys' swipe goldens were recorded against this state, so the
 * migration is a device-verified change — kept in this one place.
 */
@Composable
fun TrailingSwipe(label: String, onAction: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    @Suppress("DEPRECATION")
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) onAction(); false })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, modifier = modifier, backgroundContent = {
        Box(Modifier.fillMaxSize().background(TeemoonColors.red), contentAlignment = Alignment.CenterEnd) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.padding(end = 24.dp))
        }
    }) { Box(Modifier.background(TeemoonColors.groupedCard)) { content() } }
}

/** A row whose trailing control is a switch. */
@Composable
fun ToggleInsetRow(title: String, on: Boolean, onSet: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true, divider: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    LocalRenderRecorder.current?.let { rec ->
        rec.row(kind = "toggleRowTitle", text = title)
        subtitle?.let { rec.row(kind = "toggleRowSubtitle", text = it) }
    }
    InsetRow(title, subtitle, divider = divider, leading = leading, trailing = {
        // ContentView / SettingsView apply `.tint(appTintColor)`: every toggle's on-track is the accent, not UIKit's green
        Switch(checked = on, onCheckedChange = onSet, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = TeemoonColors.tint, checkedThumbColor = Color.White, uncheckedTrackColor = TeemoonColors.fill, uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White))
    })
}

/** A text field that lives inside an [InsetGroup]: no outline, no floating label — the placeholder is the label. */
@Composable
fun InsetField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, secret: Boolean = false, singleLine: Boolean = true,
    minLines: Int = 1, leading: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null, divider: Boolean = false, monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** SwiftUI's `onSubmit` / focus loss: the field commits when the user leaves it (the Brave key saves this way). */
    onFocusLost: (() -> Unit)? = null,
    /** The keyboard's action key (`.submitLabel(.go)` + `onSubmit`). */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    /**
     * The field holds a credential, SHOWN OR NOT. Masking ([secret]) is only what the screen draws; this is what the
     * KEYBOARD is told. Without it an API key typed here reached Gboard as ordinary text — eligible for its learned
     * words and suggestion strip — while iOS's SecureField tells the keyboard to keep nothing. Independent of
     * [secret] because the eye toggle un-masks the key, and revealing it must not make the keyboard start learning.
     */
    sensitive: Boolean = false,
    /** The prompt's colour: red for a field a save was refused on (ProviderConnectionSection's keyFieldError). */
    placeholderColor: Color = TeemoonColors.tertiaryLabel,
    focusRequester: FocusRequester? = null,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val options = if (sensitive) keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboardOptions
    val style = (if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge).copy(color = TeemoonColors.label)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label) }
            BasicTextField(value, onValueChange, modifier = Modifier.weight(1f).let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }.onFocusChanged { st -> if (st.isFocused) hadFocus = true else if (hadFocus) { hadFocus = false; onFocusLost?.invoke() } }, textStyle = style, singleLine = singleLine, minLines = minLines,
                cursorBrush = SolidColor(TeemoonColors.tint), keyboardOptions = options, keyboardActions = keyboardActions, enabled = enabled,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = placeholderColor); inner() } })
            trailing?.let { it() }
        }
        if (divider) InsetDivider()
    }
}

// MARK: chips and controls

/**
 * A capsule chip. Filled chips carry the tint (the "on" state); resting chips sit on the secondary background;
 * the raised state is the tertiary background (Appearance.swift's fill-ladder note). Only [enabled]=false dims.
 */
/**
 * The chips that live IN the transcript — GenerationActivityChipView and SourceStackChipView: caption text at medium
 * weight, a caption2-sized glyph, 10 by 5 padding, a capsule fill — about 24pt tall. Not `Capsule`, which is the
 * composer's 32dp control (WebSearchChip's 14pt semibold) and read as "taller than iOS" under a reply (2026-09-17).
 */
@Composable
fun TranscriptChip(text: String, foreground: Color, background: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, glyph: @Composable () -> Unit) {
    // THE component this whole layer exists for: it drew a 32dp composer Capsule
    // against iOS's 24pt chip, and the 32 was a Compose default nobody typed, so
    // no source-reading tool could see it. Here the resolved padding and type go
    // into the record whether or not anyone wrote them down.
    val chipStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    LocalRenderRecorder.current?.row(
        kind = "transcriptChip", text = text,
        fontSize = chipStyle.fontSize, fontWeight = chipStyle.fontWeight,
        padding = RenderRecorder.Padding(top = 5.dp, leading = 10.dp, bottom = 5.dp, trailing = 10.dp))
    var m = modifier.clip(CircleShape).background(background)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        glyph()
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = foreground)
    }
}

/** SF `globe` at caption2: a circle, its equator, and one meridian. An emoji globe was unreadable on the blue fill. */
@Composable
fun GlobeGlyph(color: Color, size: Dp = 12.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height; val st = 1.2.dp.toPx(); val stroke = Stroke(st)
    val r = w / 2 - st / 2
    drawCircle(color, r, Offset(w / 2, h / 2), style = stroke)
    drawLine(color, Offset(w / 2 - r, h / 2), Offset(w / 2 + r, h / 2), st)
    drawOval(color, Offset(w / 2 - r * 0.45f, h / 2 - r), Size(r * 0.9f, r * 2), style = stroke)
}

/** SF `sparkles` at caption2: one four-point star, a smaller one at its shoulder. */
@Composable
fun SparklesGlyph(color: Color, size: Dp = 12.dp) = Canvas(Modifier.size(size)) {
    fun star(cx: Float, cy: Float, r: Float) {
        val p = Path()
        p.moveTo(cx, cy - r); p.quadraticTo(cx, cy, cx + r, cy); p.quadraticTo(cx, cy, cx, cy + r); p.quadraticTo(cx, cy, cx - r, cy); p.quadraticTo(cx, cy, cx, cy - r); p.close()
        drawPath(p, color)
    }
    val w = this.size.width; val h = this.size.height
    star(w * 0.42f, h * 0.58f, w * 0.42f); star(w * 0.78f, h * 0.24f, w * 0.2f)
}

@Composable
fun Capsule(
    text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tint: Color = TeemoonColors.label, filled: Boolean = false, raised: Boolean = false,
    enabled: Boolean = true, glyph: ImageVector? = null, glyphText: String? = null, background: Color? = null, outlined: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    // Raised: the tertiary background in the dark (the lightest rung of that ladder); in the light that rung is white,
    // the same as the chat's ground, so the system fill stands in.
    val bg = background ?: when { filled -> tint; raised -> if (TeemoonColors.palette.dark) TeemoonColors.tertiaryBackground else TeemoonColors.fill; else -> TeemoonColors.secondaryBackground }
    // Disabled: the label and glyph in TERTIARY, then the whole chip at 40% (WebSearchChip's `.disabled`) — the text
    // ends near 12% white, not 40% white. Material's 38% content alpha alone read four times brighter than iOS.
    val fg = if (filled) Color.White else if (enabled) tint else TeemoonColors.tertiaryLabel
    val alpha = if (enabled) 1f else 0.4f
    var m = modifier.height(32.dp).clip(CircleShape).background(bg.copy(alpha = bg.alpha * alpha))
    if (outlined) m = m.border(1.dp, TeemoonColors.separator, CircleShape)
    if (onClick != null && enabled) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        leading?.invoke()
        glyph?.let { Icon(it, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(15.dp)) }
        glyphText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = alpha)) }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The full-width filled action ("download · 2.4 gb", "add", "send anyway"). */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = TeemoonColors.tint, glyph: (@Composable () -> Unit)? = null,
    /** Null: the background colour, inverting against the user's tint (iOS's Label). Set for a FIXED fill. */
    labelColor: Color? = null) {
    val pillStyle = MaterialTheme.typography.titleMedium
    LocalRenderRecorder.current?.row(
        kind = "primaryPill", text = text,
        fontSize = pillStyle.fontSize, fontWeight = pillStyle.fontWeight,
        color = if (tint == TeemoonColors.tint) "tint" else "custom")
    Box(modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(tint.copy(alpha = if (enabled) 1f else 0.4f)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            glyph?.invoke()
            // the label is PlatformColors.background on the accent (black in the dark, white in the light), as iOS's Label is
            Text(text, style = MaterialTheme.typography.titleMedium, color = labelColor ?: TeemoonColors.background)
        }
    }
}

/** SF `arrow.down.circle` / `checkmark.circle` at label size: a stroked ring around the mark. */
@Composable
fun CircledMark(mark: String, color: Color, sizeDp: Dp = 20.dp) {
    Box(Modifier.size(sizeDp).border(1.5.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(mark, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

/** The all / phone / home / cloud picker. */
@Composable
fun SegmentedPicker(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(36.dp).clip(CircleShape).background(TeemoonColors.fill).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            // the selected segment is UISegmentedControl's raised platter — #69696F over the #313136 track in dark, measured on
            // the tag's trust capture; the grouped card colour vanished against the elevated sheet's track
            val platter = if (TeemoonColors.palette.dark) Color(0xFF69696F) else Color.White
            Box(Modifier.weight(1f).fillMaxSize().clip(CircleShape).background(if (on) platter else Color.Transparent).clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = TeemoonColors.label)
            }
        }
    }
}

/** The search pill at the foot of the chats list. */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "search") {
    Row(modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(TeemoonColors.groupedCard).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Search, null, tint = TeemoonColors.secondaryLabel, modifier = Modifier.size(20.dp))
        val style = MaterialTheme.typography.bodyLarge.copy(color = TeemoonColors.label)
        BasicTextField(value, onValueChange, modifier = Modifier.weight(1f).testTag("chats.search"), textStyle = style, singleLine = true, cursorBrush = SolidColor(TeemoonColors.tint),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = TeemoonColors.tertiaryLabel); inner() } })
    }
}

/**
 * The composer: a 24pt-radius pill on the secondary background, at least 48pt tall, the field growing to a few lines,
 * and the filled arrow.up.circle send button — a stop square while a reply streams. Send is dim until there is text.
 */
@Composable
fun Composer(
    value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "message", canSend: Boolean = value.isNotBlank(),
    /** ContentView: the prompt takes focus when the current thread is empty (the keyboard rises with a new chat). */
    focusRequester: FocusRequester? = null,
) {
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(24.dp)).background(TeemoonColors.secondaryBackground).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.bodyLarge.copy(color = TeemoonColors.label)
        // The keyboard's action key is "send" (Android chat convention); a hardware return still breaks the line.
        BasicTextField(value, onValueChange, modifier = Modifier.weight(1f).padding(vertical = 6.dp).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).testTag("chat.composer"), textStyle = style, maxLines = 6, cursorBrush = SolidColor(TeemoonColors.tint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onSend = { if (canSend && !streaming) onSend() }),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = TeemoonColors.tertiaryLabel); inner() } })
        if (streaming) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(TeemoonColors.tint).clickable(onClick = onStop).semantics { contentDescription = "stop" }.testTag("chat.stop"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))

            }
        } else {
            val tint = if (canSend) TeemoonColors.tint else TeemoonColors.gray.copy(alpha = 0.5f)
            Box(Modifier.size(32.dp).clip(CircleShape).background(tint).clickable(enabled = canSend, onClick = onSend).testTag("chat.send"), contentAlignment = Alignment.Center) {
                // arrow.up, not a chevron: ArrowForward turned a quarter turn (the core icon set has no ArrowUpward).
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "send", tint = Color.White, modifier = Modifier.size(18.dp).rotate(-90f))
            }
        }
    }
}

/** The check that marks the equipped row. */
@Composable
fun SelectedCheck() = Icon(Icons.Filled.Check, "selected", tint = TeemoonColors.label, modifier = Modifier.size(18.dp))

/** A 12pt panel: debug cards, tool output, errors. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TeemoonColors.secondaryBackground), content = content)
}

/** WhereRow's e2ee tag: a small green capsule with a shield. Semantic green — the same one the trust chip uses. */
@Composable
fun E2EETag() {
    Row(Modifier.clip(CircleShape).background(TeemoonColors.verified.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Filled.Lock, null, tint = TeemoonColors.verified, modifier = Modifier.size(10.dp))
        Text("e2ee", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.verified)
    }
}

/**
 * The empty transcript's moon (ConversationView / MoonPhase): today's phase, drawn in the tertiary label
 * colour at 56pt — a quiet mark, not an illustration. Phase 0 is new, 4 is full; the lit limb is a half disc
 * plus an ellipse whose width follows the phase.
 */
@Composable
fun MoonGlyph(phase: Int = MoonPhase.index(), modifier: Modifier = Modifier) {
    // ChatView: `moonphase.*` at 32×32 in `.quaternary` (quaternaryLabel ≈ 18% of the label colour) — a mark, not a
    // picture. It was 56dp in tertiaryLabel here, three times the ink of the iOS one, until the captures were measured.
    val lit = TeemoonColors.label.copy(alpha = 0.18f); val dim = TeemoonColors.label.copy(alpha = 0.06f)
    Canvas(modifier.size(32.dp)) {
        val r = size.minDimension / 2; val c = Offset(size.width / 2, size.height / 2)
        drawCircle(dim, r, c)
        if (phase == 0) return@Canvas
        val waxing = phase in 1..4
        val f = cos(Math.PI * phase / 4.0).toFloat()   // +1 new … -1 full, the terminator's ellipse half-width as a fraction of r
        val litRect = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
        // the outer half on the lit side
        drawArc(lit, if (waxing) -90f else 90f, 180f, true, topLeft = litRect.topLeft, size = litRect.size)
        // the terminator: an ellipse of half-width |f|·r, lit when the phase is past the quarter, dark before it
        val w = abs(f) * r
        val ell = Rect(c.x - w, c.y - r, c.x + w, c.y + r)
        drawOval(if (f < 0) lit else dim, topLeft = ell.topLeft, size = ell.size)
    }
}

/** ChatComposer's where chip: the locality glyph, the model in the label weight, the place in secondary, the up/down chevrons. */
@Composable
fun WhereChip(model: String?, place: String?, locality: WhereLocality?, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    /** WhereChip.swift `progress`: the equipped model's download, as "downloading 61%" — a number, not a bar, because the number is what says whether to wait. */
    progress: Double? = null,
    /** WhereChip.swift `progressLabel`: what to say instead of the percentage — "waiting for wi-fi" for a parked job (iOS 3b1c494). */
    progressLabel: String? = null,
    /** ChatView's chip Menu: a long press offers "model info" and "copy model id" (iOS 835d7c8). */
    onLongClick: (() -> Unit)? = null,
    /** WhereChip.swift `needsDownload`: equipped, named, and unable to answer — "not downloaded", in red, because this state BLOCKS. */
    needsDownload: Boolean = false,
) {
    // WhereChip.swift: with nothing equipped the chip fills with `ML.accent` (the fixed orange, so the label is white
    // on it) under `arrow.triangle.branch` — the one moment the chip leaves the chrome and BECOMES the accent, because
    // it is the only thing on the screen that can move the user forward. Equipped, it sits on the resting plate.
    val unconfigured = model == null
    var m = modifier.height(36.dp).clip(CircleShape).background(if (unconfigured) TeemoonColors.tint else TeemoonColors.secondaryBackground)
    // Tap still opens Where — switching models is what the chip is for and stays one tap; a long press gets the menu.
    if (onClick != null) m = if (onLongClick != null) m.combinedClickable(onClick = onClick, onLongClick = onLongClick) else m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (unconfigured) BranchGlyph(Color.White)
        else locality?.let { LocalityGlyph(it, tint = TeemoonColors.secondaryLabel) }
        if (model == null) Text("choose where", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        else {
            Text(model, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // Tabular figures for the moving number only (trailingMonospaced: arriving && waiting == nil); on a word they
            // clipped the "fi" of "wi-fi".
            if (progress != null) Text("· " + (progressLabel ?: "downloading ${(progress * 100).toInt()}%"), style = if (progressLabel == null) MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum") else MaterialTheme.typography.labelMedium, color = TeemoonColors.secondaryLabel, maxLines = 1)
            else if (needsDownload) Text("· not downloaded", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.red, maxLines = 1)
            // The place only when there is no progress to report: model · place · downloading 5% is three facts in a capsule with room for two.
            else place?.let { Text("· ${it.lowercase()}", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        }
        UpDownChevrons(if (unconfigured) Color.White.copy(alpha = 0.75f) else TeemoonColors.secondaryLabel)
    }
}

/** SF `arrow.triangle.branch` at chip size: a trunk that forks into two arrowed branches. */
@Composable
private fun BranchGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height; val sw = w * 0.13f
        val cap = StrokeCap.Round
        drawLine(color, Offset(w * 0.5f, h * 0.95f), Offset(w * 0.5f, h * 0.5f), sw, cap)
        drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.18f, h * 0.15f), sw, cap)
        drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.82f, h * 0.15f), sw, cap)
        drawLine(color, Offset(w * 0.18f, h * 0.15f), Offset(w * 0.18f, h * 0.4f), sw, cap)
        drawLine(color, Offset(w * 0.18f, h * 0.15f), Offset(w * 0.4f, h * 0.15f), sw, cap)
        drawLine(color, Offset(w * 0.82f, h * 0.15f), Offset(w * 0.82f, h * 0.4f), sw, cap)
        drawLine(color, Offset(w * 0.82f, h * 0.15f), Offset(w * 0.6f, h * 0.15f), sw, cap)
    }
}

/** The back control of a pushed page inside a sheet: chevron.left in the 44pt circle (iOS), not a text pill. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) = CircleButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "back", onClick, modifier, tint = TeemoonColors.label)

/** ChatsListView's empty state: the bubble glyph and "no chats yet", centred. */
@Composable
fun EmptyChatsState(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(56.dp)) {
            val w = size.width; val h = size.height; val s = 2.2.dp.toPx()
            val p = Path().apply {
                addRoundRect(RoundRect(w * 0.06f, h * 0.1f, w * 0.94f, h * 0.74f, CornerRadius(w * 0.3f)))
            }
            drawPath(p, Color.Gray, style = Stroke(s))
            drawLine(Color.Gray, Offset(w * 0.22f, h * 0.7f), Offset(w * 0.14f, h * 0.94f), s)
            drawLine(Color.Gray, Offset(w * 0.14f, h * 0.94f), Offset(w * 0.4f, h * 0.74f), s)
        }
        Text("no chats yet", style = MaterialTheme.typography.titleLarge)
    }
}

/** SF `chevron.left.forwardslash.chevron.right`: `</>`. */
@Composable
fun CodeGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        drawPath(Path().apply { moveTo(w * 0.30f, h * 0.25f); lineTo(w * 0.06f, h * 0.5f); lineTo(w * 0.30f, h * 0.75f) }, tint, style = Stroke(s, cap = cap))
        drawPath(Path().apply { moveTo(w * 0.70f, h * 0.25f); lineTo(w * 0.94f, h * 0.5f); lineTo(w * 0.70f, h * 0.75f) }, tint, style = Stroke(s, cap = cap))
        drawLine(tint, Offset(w * 0.58f, h * 0.16f), Offset(w * 0.42f, h * 0.84f), s, cap)
    }
}

/** SF `cpu`: a square die with pins on every side. */
@Composable
fun CpuGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.4.dp.toPx(); val cap = StrokeCap.Round
        drawRoundRect(tint, topLeft = Offset(w * 0.22f, h * 0.22f), size = Size(w * 0.56f, h * 0.56f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s))
        drawRect(tint, topLeft = Offset(w * 0.38f, h * 0.38f), size = Size(w * 0.24f, h * 0.24f), style = Stroke(s))
        for (f in listOf(0.36f, 0.5f, 0.64f)) {
            drawLine(tint, Offset(w * f, h * 0.06f), Offset(w * f, h * 0.22f), s, cap); drawLine(tint, Offset(w * f, h * 0.78f), Offset(w * f, h * 0.94f), s, cap)
            drawLine(tint, Offset(w * 0.06f, h * f), Offset(w * 0.22f, h * f), s, cap); drawLine(tint, Offset(w * 0.78f, h * f), Offset(w * 0.94f, h * f), s, cap)
        }
    }
}

/** SF `shield.lefthalf.filled`: a shield outline with its left half filled. */
@Composable
fun HalfShieldGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.4.dp.toPx()
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.90f, h * 0.22f); lineTo(w * 0.90f, h * 0.52f)
            cubicTo(w * 0.90f, h * 0.78f, w * 0.72f, h * 0.90f, w * 0.5f, h * 0.96f)
            cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.78f, w * 0.10f, h * 0.52f); lineTo(w * 0.10f, h * 0.22f); close()
        }
        val left = Path().apply {
            moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.5f, h * 0.96f)
            cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.78f, w * 0.10f, h * 0.52f); lineTo(w * 0.10f, h * 0.22f); close()
        }
        drawPath(left, tint); drawPath(shield, tint, style = Stroke(s))
    }
}

/** SF `apple.terminal`: a window with a prompt chevron and a cursor line. */
@Composable
fun TerminalGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 14.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.3.dp.toPx(); val cap = StrokeCap.Round
        drawRoundRect(tint, topLeft = Offset(w * 0.06f, h * 0.16f), size = Size(w * 0.88f, h * 0.68f), cornerRadius = CornerRadius(w * 0.12f), style = Stroke(s))
        drawPath(Path().apply { moveTo(w * 0.24f, h * 0.38f); lineTo(w * 0.38f, h * 0.5f); lineTo(w * 0.24f, h * 0.62f) }, tint, style = Stroke(s, cap = cap))
        drawLine(tint, Offset(w * 0.46f, h * 0.64f), Offset(w * 0.66f, h * 0.64f), s, cap)
    }
}
