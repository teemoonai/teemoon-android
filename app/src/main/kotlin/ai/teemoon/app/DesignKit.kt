@file:OptIn(ExperimentalFoundationApi::class)

// design: teemoon-ios docs/DESIGN.md @ v1.0.1 — REBUILD in Compose. The pieces every screen is built from:
//         the nav bar with its circular icon buttons, inset-grouped lists, capsule chips, the segmented picker,
//         the search pill and the composer pill. Copy is lowercase; the grid is 8pt; tint is the user's.
package ai.teemoon.app

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup

// MARK: chrome

/**
 * Whether the screen sits on the grouped ground. Chrome that is raised over the system background (the chat's
 * circle buttons) uses the secondary background; over the grouped ground that colour IS the ground in the light,
 * so the same chrome uses the card colour there (white / #1C1C1E), as iOS does with its bar pills on a sheet.
 */
val LocalOnGroupedGround = compositionLocalOf { false }


/** `.lineLimit(1).minimumScaleFactor(0.8)` for a row's caption: one line, shrinking up to 20% before it would wrap. */
val LocalSubtitleShrinksToFit = staticCompositionLocalOf { false }

/**
 * Whether [MoonGlyph] draws. Snapshots of the empty chat turn it off: it draws today's phase, and a golden image of today's
 * moon matches only on the days that share its phase (the share-in snapshots went red on 2026-11-19). The app always draws it.
 */
val LocalShowsMoon = staticCompositionLocalOf { true }

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
    // The title's side inset is the WIDER control's width plus a gap (at most 80pt), the same on both sides so the title
    // stays centred — not a fixed 80pt, which cut "import from near.ai" to "import from near…" beside a lone back circle (pm
    // 2026-11-01) where UIKit's bar, which also lays the title out between the measured items, fits it.
    Layout(modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp), content = {
        Box { leading() }
        Box { trailing() }
        // E2EETitleBlock: the title line AND the caption are ONE control (44pt minimum, the whole rectangle) with one
        // action. Two stacked targets a few points apart let the finger pick which sheet opened (2026-09-09).
        Column(Modifier.heightIn(min = 44.dp)
            .then(if (onTitle != null) Modifier.clickable(onClick = onTitle).testTag("chat.titleBlock") else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                titleLeading?.invoke()
                titleGlyph?.let { Icon(it, null, tint = TeemoonColors.label, modifier = Modifier.size(16.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                if (titleChevron) Icon(Icons.Filled.KeyboardArrowDown, null, tint = TeemoonColors.secondaryLabel, modifier = Modifier.size(18.dp))
            }
            // a caption that changes ("checking …" → "end-to-end encrypted") fades through, the old one out first, rather
            // than cut (motion-pass item 4)
            subtitle?.let { caption ->
                androidx.compose.animation.AnimatedContent(caption, label = "caption", transitionSpec = {
                    androidx.compose.animation.fadeIn(Motion.axisFadeIn()) togetherWith androidx.compose.animation.fadeOut(Motion.axisFadeOut())
                }) { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chat.trustChip"))
                }
            }
        }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables[0].measure(loose)
        val trail = measurables[1].measure(loose)
        // never MORE than the old 80pt: a text pill ("cancel", "save") reaches under it, and the titles beside pills fit there.
        // Except at the accessibility sizes, where the pill outgrows it and the title ran into it: "cancelbrowse models"
        // at 200% (pm 2026-11-04). There the title takes the space BETWEEN the controls, as UIKit's bar lays it out, and
        // sits as near the centre as that allows.
        val gap = NAV_TITLE_GAP.roundToPx()
        val large = fontScale >= 1.5f
        val side = minOf(maxOf(lead.width, trail.width) + gap, NAV_TITLE_INSET.roundToPx())
        val titleWidth = (if (large) constraints.maxWidth - lead.width - trail.width - 2 * gap else constraints.maxWidth - 2 * side).coerceAtLeast(0)
        val title = measurables[2].measure(loose.copy(maxWidth = titleWidth))
        val height = maxOf(lead.height, trail.height, title.height, constraints.minHeight)
        // rounded as Alignment.Center rounds, so a title that fitted before sits on the same pixel
        fun mid(space: Int, size: Int) = ((space - size) / 2f).roundToInt()
        layout(constraints.maxWidth, height) {
            lead.place(0, mid(height, lead.height))
            trail.place(constraints.maxWidth - trail.width, mid(height, trail.height))
            val centred = mid(constraints.maxWidth, title.width)
            val x = if (large) centred.coerceIn(lead.width + gap, maxOf(lead.width + gap, constraints.maxWidth - trail.width - gap - title.width)) else centred
            title.place(x, mid(height, title.height))
        }
    }
}

/**
 * iOS's `dynamicTypeSize.isAccessibilitySize`: Android 14's nonlinear font sizes above the old 1.3 maximum (1.5, 1.8,
 * 2.0) are the counterpart of iOS's AX1–AX5, where a layout that holds at the standard sizes changes shape.
 */
@Composable
fun isAccessibilitySize(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f

/** The gap between a navigation bar's control and its title, and the most the title is inset from the bar's padding. */
private val NAV_TITLE_GAP = 8.dp
private val NAV_TITLE_INSET = 80.dp

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
    /**
     * Pinned under the scrolling content, with a hairline above it, at every sheet height: the sheet's frame ends at the
     * visible edge, so this stays on screen at the half detent too (the where sheet's reasoning bar, the maintainer 2026-11-13).
     * Header pages only. It takes the sheet's bottom safe area, which the scrolling content then doesn't add.
     */
    footer: (@Composable () -> Unit)? = null,
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
            Column((if (scroll) Modifier.weight(1f).fillMaxWidth().then(fade).verticalScroll(state) else Modifier.weight(1f).fillMaxWidth()).padding(top = topInset, bottom = 24.dp)) { content(); if (scroll && footer == null) SheetScrollEnd() }
            if (footer != null) {
                val separator = TeemoonColors.separator
                // a band of the sheet's frame (TeemoonColors.sheetBand), 8 above and below its row, the sheet's safe area under it
                Box(Modifier.fillMaxWidth().background(TeemoonColors.sheetBand).drawWithContent { drawContent(); drawLine(separator, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
                    .padding(top = 8.dp, bottom = 8.dp + LocalSheetBottomInset.current)) { footer() }
            }
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

/** SF `square.and.arrow.down`: an open tray with an arrow into it — save in an image's menu. */
@Composable
fun SaveGlyph(tint: Color = TeemoonColors.label, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        val tray = Path().apply { moveTo(w * 0.34f, h * 0.42f); lineTo(w * 0.16f, h * 0.42f); lineTo(w * 0.16f, h * 0.9f); lineTo(w * 0.84f, h * 0.9f); lineTo(w * 0.84f, h * 0.42f); lineTo(w * 0.66f, h * 0.42f) }
        drawPath(tray, tint, style = Stroke(s, cap = cap, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.64f), s, cap)
        drawLine(tint, Offset(w * 0.36f, h * 0.5f), Offset(w * 0.5f, h * 0.64f), s, cap); drawLine(tint, Offset(w * 0.64f, h * 0.5f), Offset(w * 0.5f, h * 0.64f), s, cap)
    }
}

/** SF `square.and.arrow.up`: an open tray with an arrow out of it — share in an image's menu. */
@Composable
fun ShareGlyph(tint: Color = TeemoonColors.label, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        val tray = Path().apply { moveTo(w * 0.34f, h * 0.42f); lineTo(w * 0.16f, h * 0.42f); lineTo(w * 0.16f, h * 0.9f); lineTo(w * 0.84f, h * 0.9f); lineTo(w * 0.84f, h * 0.42f); lineTo(w * 0.66f, h * 0.42f) }
        drawPath(tray, tint, style = Stroke(s, cap = cap, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.06f), Offset(w * 0.5f, h * 0.62f), s, cap)
        drawLine(tint, Offset(w * 0.36f, h * 0.2f), Offset(w * 0.5f, h * 0.06f), s, cap); drawLine(tint, Offset(w * 0.64f, h * 0.2f), Offset(w * 0.5f, h * 0.06f), s, cap)
    }
}

/**
 * The composer's remove control on an attached image (iOS S42, the maintainer 2026-11-11: "clearly visible"): a 24dp
 * black circle at 85%, a 1.5dp white ring and a bold white x, its top-right corner 8dp past the thumbnail's; the
 * target is 48dp (Android's minimum; iOS 44pt) around it, reaching into the thumbnail.
 */
@Composable
fun ImageRemoveButton(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clickable(onClick = onRemove).semantics { contentDescription = "remove image"; role = Role.Button }
        .testTag("chat.image.remove"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.85f)).border(1.5.dp, Color.White, CircleShape))
        Canvas(Modifier.size(9.dp)) {
            val s = 2.dp.toPx()
            drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), s, StrokeCap.Round)
            drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
        }
    }
}

/** SF `brain`: two lobes and the fissure between them — Think Harder in a reply's menu. */
@Composable
fun BrainGlyph(tint: Color = TeemoonColors.label, sizeDp: Dp = 18.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx()
        val outline = Path().apply {
            moveTo(w * 0.5f, h * 0.16f)
            cubicTo(w * 0.36f, h * 0.06f, w * 0.14f, h * 0.14f, w * 0.16f, h * 0.36f)
            cubicTo(w * 0.04f, h * 0.44f, w * 0.06f, h * 0.68f, w * 0.2f, h * 0.74f)
            cubicTo(w * 0.24f, h * 0.92f, w * 0.44f, h * 0.94f, w * 0.5f, h * 0.84f)
            cubicTo(w * 0.56f, h * 0.94f, w * 0.76f, h * 0.92f, w * 0.8f, h * 0.74f)
            cubicTo(w * 0.94f, h * 0.68f, w * 0.96f, h * 0.44f, w * 0.84f, h * 0.36f)
            cubicTo(w * 0.86f, h * 0.14f, w * 0.64f, h * 0.06f, w * 0.5f, h * 0.16f)
        }
        drawPath(outline, tint, style = Stroke(s, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), s, StrokeCap.Round)
        val fold = Path().apply { moveTo(w * 0.26f, h * 0.46f); quadraticTo(w * 0.36f, h * 0.4f, w * 0.38f, h * 0.54f); moveTo(w * 0.74f, h * 0.46f); quadraticTo(w * 0.64f, h * 0.4f, w * 0.62f, h * 0.54f) }
        drawPath(fold, tint, style = Stroke(s, cap = StrokeCap.Round))
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
 * wraps three actions onto two lines. A choice of three (the E2EE confirm, the mobile-data download) is the same card
 * with three pills. 1.0.6's look (the maintainer 2026-10-31, "Revert all to 1.0.6": the 10-25 Material dialog and bottom sheet
 * are gone, and with them the red "delete…" they brought); only an action the call site names keeps iOS's red.
 */
@Composable
fun IosAlert(
    title: String, message: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit,
    /** Labels drawn red: iOS's role .destructive, e.g. "send unencrypted" (E2EESendChoice, iOS f600e4e). */
    destructive: Set<String> = emptySet(),
) {
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
            // an empty title or message takes no line (the image-refusal note is a message alone)
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEBEBF5).copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 6.dp))
            actions.forEach { (label, act) ->
                // at least 48dp, and taller when a label wraps (the export's delete names the file, v9) rather than clipped
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF3A3A3C)).clickable(onClick = act)
                    .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = if (label in destructive) TeemoonColors.red else TeemoonColors.tint)
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
 * `.swipeActions(edge: .trailing)` for one destructive action, as iOS draws it: a part swipe OPENS the row on a red
 * button that stays and acts on a tap; a full swipe acts at once where iOS allows it ([allowsFullSwipe], its default),
 * else it only opens. Either way the row closes again rather than dismissing — what happens next is the action's own
 * affair (an alert, or the row leaving the list because the store no longer has it). A tap on an open row only closes
 * it. No leading swipe. The content carries the card's ground so the red is REVEALED beside the row.
 *
 * It was a SwipeToDismissBox whose red label was only a background painted while the row moved (the maintainer's Pixel,
 * 2026-11-08: "sometimes it just shows unequip but doesnt select it"): a swipe short of the threshold showed the word,
 * sprang back and did nothing — there was no button — and its confirmValueChange fired the action on every frame past
 * the threshold (eleven times for one full swipe, TrailingSwipeTest). The chats list, places & keys, a setup's models
 * and the where sheet all go through here.
 */
@Composable
fun TrailingSwipe(label: String, onAction: () -> Unit, modifier: Modifier = Modifier, allowsFullSwipe: Boolean = true, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val act by rememberUpdatedState(onAction)
    val state = remember { AnchoredDraggableState(SwipeSlot.CLOSED) }
    var width by remember { mutableIntStateOf(0) }
    val buttonWidth = 96.dp
    LaunchedEffect(width, allowsFullSwipe) {
        if (width == 0) return@LaunchedEffect
        val open = with(density) { buttonWidth.toPx() }
        state.updateAnchors(DraggableAnchors {
            SwipeSlot.CLOSED at 0f
            SwipeSlot.OPEN at -open
            if (allowsFullSwipe) SwipeSlot.FULL at -width.toFloat()
        })
    }
    // a full swipe acts ONCE, when it settles there, then the row closes
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { if (it == SwipeSlot.FULL) { act(); state.animateTo(SwipeSlot.CLOSED) } }
    }
    // Where the row lands is decided by how far it was pulled, never how fast (iOS): the default fling carried a moderate
    // part swipe on past the button to the full anchor, and the action ran on a swipe meant to open.
    val fling = remember(state) { NearestAnchorFling(state) }
    val open = state.currentValue != SwipeSlot.CLOSED || (state.offset.takeIf { !it.isNaN() } ?: 0f) < 0f
    Box(modifier.onSizeChanged { width = it.width }.clipToBounds()) {
        // the revealed button: under the row, reachable only once the row has moved off it
        Box(Modifier.matchParentSize().background(TeemoonColors.red), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxHeight().widthIn(min = buttonWidth)
                .clickable(enabled = open) { act(); scope.launch { state.animateTo(SwipeSlot.CLOSED) } }
                .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1)
            }
        }
        Box(Modifier.offset { IntOffset((state.offset.takeIf { !it.isNaN() } ?: 0f).roundToInt(), 0) }
            .anchoredDraggable(state, Orientation.Horizontal, flingBehavior = fling)
            .background(TeemoonColors.groupedCard)
            // iOS exposes swipe actions to VoiceOver as actions; TalkBack gets the same one
            .semantics { customActions = listOf(CustomAccessibilityAction(label) { act(); true }) }) {
            content()
            // an open row's tap only closes it (iOS), never reaches the row's own action
            if (open) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { scope.launch { state.animateTo(SwipeSlot.CLOSED) } } })
        }
    }
}

private enum class SwipeSlot { CLOSED, OPEN, FULL }

/** Settles a released row on the anchor nearest its offset, whatever the release velocity. */
private class NearestAnchorFling(private val state: AnchoredDraggableState<SwipeSlot>) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
        val from = state.offset.takeIf { !it.isNaN() } ?: return initialVelocity
        val target = state.anchors.closestAnchor(from) ?: return initialVelocity
        val to = state.anchors.positionOf(target)
        var last = from
        animate(from, to, animationSpec = Motion.settle()) { value, _ -> scrollBy(value - last); last = value }
        return 0f
    }
}

/** A row whose trailing control is a switch. */
@Composable
fun ToggleInsetRow(title: String, on: Boolean, onSet: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true, divider: Boolean = false, leading: (@Composable () -> Unit)? = null,
    /** The on-track: the accent (ContentView's `.tint`), or a row's own (developer mode's green). */
    checkedTrack: Color = TeemoonColors.tint) {
    LocalRenderRecorder.current?.let { rec ->
        rec.row(kind = "toggleRowTitle", text = title)
        subtitle?.let { rec.row(kind = "toggleRowSubtitle", text = it) }
    }
    // The whole row is the toggle: its touch target is the full-width row, at least 48dp tall. The switch is drawn inside
    // the title's line (it overflows into the row's padding), so a toggle row is as tall as its sibling rows, as iOS's
    // (the maintainer's Pixel, 2026-11-07: settings › chats' haptics row stood taller — the switch's own 48dp touch box
    // plus the row's padding made it 72dp).
    InsetRow(title, subtitle, divider = divider, leading = leading,
        modifier = Modifier.toggleable(value = on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onSet),
        trailing = {
            Box(Modifier.height(24.dp).wrapContentHeight(unbounded = true), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    // ContentView / SettingsView apply `.tint(appTintColor)`: every toggle's on-track is the accent, not UIKit's green
                    Switch(checked = on, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = checkedTrack, checkedThumbColor = Color.White, uncheckedTrackColor = TeemoonColors.fill, uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White))
                }
            }
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

/** SF `hourglass` at caption2: a cap and a base, and two bulbs meeting at the waist. */
@Composable
fun HourglassGlyph(color: Color, size: Dp = 12.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width; val h = this.size.height; val st = 1.2.dp.toPx()
    val l = w * 0.24f; val r = w * 0.76f; val top = h * 0.1f; val bot = h * 0.9f
    drawLine(color, Offset(w * 0.16f, top), Offset(w * 0.84f, top), st, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, bot), Offset(w * 0.84f, bot), st, cap = StrokeCap.Round)
    val p = Path()
    p.moveTo(l, top); p.lineTo(r, top); p.quadraticTo(r, h * 0.38f, w / 2, h / 2); p.quadraticTo(l, h * 0.38f, l, top)
    p.moveTo(l, bot); p.lineTo(r, bot); p.quadraticTo(r, h * 0.62f, w / 2, h / 2); p.quadraticTo(l, h * 0.62f, l, bot)
    drawPath(p, color, style = Stroke(st, join = StrokeJoin.Round))
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
    /** false: the glyph alone (the composer's chips at accessibility sizes); the label is still the content description. */
    labelShown: Boolean = true,
    /** The composer's row takes 34 tall and 14 at the sides (COMPOSER_PANEL.md @ 06c7e759); elsewhere 32 and 12. */
    height: androidx.compose.ui.unit.Dp = 32.dp,
    sidePadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    // Raised: the tertiary background in the dark (the lightest rung of that ladder); in the light that rung is white,
    // the same as the chat's ground, so the system fill stands in.
    val bg = background ?: when { filled -> tint; raised -> if (TeemoonColors.palette.dark) TeemoonColors.tertiaryBackground else TeemoonColors.fill; else -> TeemoonColors.secondaryBackground }
    // Disabled: the label and glyph in TERTIARY, then the whole chip at 40% (WebSearchChip's `.disabled`) — the text
    // ends near 12% white, not 40% white. Material's 38% content alpha alone read four times brighter than iOS.
    val fg = if (filled) Color.White else if (enabled) tint else TeemoonColors.tertiaryLabel
    val alpha = if (enabled) 1f else 0.4f
    // the glyph alone is a circle the row's height, as "+" (COMPOSER_PANEL.md @ 06c7e759: "web goes icon-only (34)")
    val circle = !labelShown && leading == null && glyphText == null
    var m = (if (circle) modifier.size(height) else modifier.height(height)).clip(CircleShape).background(bg.copy(alpha = bg.alpha * alpha))
    if (outlined) m = m.border(1.dp, TeemoonColors.separator, CircleShape)
    if (onClick != null && enabled) m = m.clickable(onClick = onClick)
    if (!labelShown) m = m.semantics { contentDescription = text }
    Row(if (circle) m else m.padding(horizontal = sidePadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (circle) Arrangement.Center else Arrangement.spacedBy(6.dp)) {
        leading?.invoke()
        glyph?.let { Icon(it, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(15.dp)) }
        glyphText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = alpha)) }
        if (labelShown) Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The full-width filled action ("download · 2.4 gb", "add", "send anyway"). */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = TeemoonColors.tint, glyph: (@Composable () -> Unit)? = null,
    /** Null: the background colour, inverting against the user's tint (iOS's Label). Set for a FIXED fill. */
    labelColor: Color? = null, height: Dp = 48.dp) {
    val pillStyle = MaterialTheme.typography.titleMedium
    LocalRenderRecorder.current?.row(
        kind = "primaryPill", text = text,
        fontSize = pillStyle.fontSize, fontWeight = pillStyle.fontWeight,
        color = if (tint == TeemoonColors.tint) "tint" else "custom")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(tint.copy(alpha = if (enabled) 1f else 0.4f)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
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
    /**
     * IMAGES.md §4d: the "+" (null: none) — the attach sheet on a model that reads images, the filtered where sheet on
     * one that can't, tint on every model (IMAGES.md §4d) — and the picked images, each removable.
     */
    onAttach: (() -> Unit)? = null,
    images: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(),
    onRemoveImage: (Int) -> Unit = {},
    /** Photos on their way into the strip (ChatViewModel.draftPending): a placeholder tile each, with a small spinner. */
    pendingImages: Int = 0,
    /**
     * A paste that attaches images (ComposerPaste.images): the clip's type list, its items in order, and the data: URL its
     * text-only items make, which attaches after them (ComposerPaste.dataUrlBeside).
     */
    onPasteImages: ((mimeTypes: List<String>, items: List<ai.teemoon.app.imaging.ComposerPaste.Item>, dataUrl: String?) -> Unit)? = null,
    /** A paste whose text is one `data:` URL (IMAGES.md §4c): it is an image, never text. */
    onPasteDataUrl: ((String) -> Unit)? = null,
    /** The image / over-limit note: a caption inside the panel, under the strip, above the text (COMPOSER_PANEL.md). */
    note: String? = null,
    /** The bottom row's where chip and web chip, drawn icon-only at accessibility sizes (null: not shown). */
    whereChip: (@Composable (iconOnly: Boolean) -> Unit)? = null,
    webChip: (@Composable (iconOnly: Boolean) -> Unit)? = null,
    /** The where chip carries a status (downloading, waiting, not downloaded): the web chip goes icon-only to give it room. */
    whereStatus: Boolean = false,
    /** The text's scroll (the 17s scroll with it); a snapshot sets a resting offset to show the top edge. */
    textScroll: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
) {
    // One panel (teemoon-ios docs/COMPOSER_PANEL.md @ 85c544c6; the maintainer 2026-11-12, "One panel, in 1.1.0"): the
    // photos strip, the note, the text, then one bottom row — "+" · where chip · web chip … send/stop. Nothing floats
    // beside or above it (it replaces dbf25b36's "+" outside and the chip row above).
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val iconOnly = ComposerPanel.iconOnlyChips(fontScale)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(ComposerSpacing.PANEL_RADIUS)).background(TeemoonColors.secondaryBackground)
        .border(ComposerSpacing.PANEL_EDGE, TeemoonColors.separator, RoundedCornerShape(ComposerSpacing.PANEL_RADIUS))
        .semantics { isTraversalGroup = true }.testTag("chat.composerPanel")) {
    // IMAGES.md §4d: 64dp thumbnails with 14dp corners. A new one scales from 0.6 to 1 while it fades in; the x takes one
    // away by the reverse (to 0.6, fading) while the ones after it slide over — the width shrinking with it — all on one
    // spring (teemoon-ios d14c703a: duration 0.35 s, bounce 0.3 → damping 0.7, stiffness (2π/0.35)²). The last one leaving
    // folds the strip in the same motion, the composer's height with it. A send takes the photos on its frame and only the height closes, with the keyboard. Thumbnails here
    // when the composer first draws (a restored draft, a snapshot) don't grow. Each is known by its bitmap, never its
    // position (ComposerThumbs): an x mid-animation acts on its own image at once. Remove animations: all instant.
    // ComposerSpacing's air above and 24dp after, so the remove circle riding past a corner isn't clipped.
    val keys = images.map(System::identityHashCode)
    val firstThumbs = remember { keys.toSet() }
    // the bitmaps by key, kept while one is leaving: it has gone from [images] by then
    val bitmaps = remember { HashMap<Int, androidx.compose.ui.graphics.ImageBitmap>() }
    images.forEach { bitmaps[System.identityHashCode(it)] = it }
    var xed by remember { mutableStateOf(emptySet<Int>()) }
    val shownBefore = remember { arrayOf(emptyList<ai.teemoon.app.imaging.ComposerThumbs.Shown>()) }
    val shown = ai.teemoon.app.imaging.ComposerThumbs.next(shownBefore[0], keys, xed).also { shownBefore[0] = it }
    // A photo that lands where its placeholder tile was takes the tile's place by fading in over it (the maintainer 2026-11-14,
    // "so fade"): no grow, no scale from 0.6 — the tile already held the space. Counted as the tiles that went in the
    // same composition the new keys came.
    val tileTrack = remember { arrayOf(keys.toSet(), pendingImages) }
    val fromTile = remember { mutableSetOf<Int>() }
    var replacedNow = 0
    // the placeholder tiles, each [id, leaving]: plain state read once per composition; a tile's fade-out ending bumps tileTick
    val tiles = remember { mutableListOf<IntArray>() }
    val tileSeq = remember { intArrayOf(0) }
    // tiles there when the composer first draws (a share's chat opening with its photos still being read) are simply
    // there, as first thumbnails don't grow; only later ones fade in
    val firstTiles = remember { pendingImages }
    var tileTick by remember { mutableStateOf(0) }
    run {
        val replaced = ((tileTrack[1] as Int) - pendingImages).coerceAtLeast(0)
        @Suppress("UNCHECKED_CAST") val before = tileTrack[0] as Set<Int>
        val landed = keys.filter { it !in before }.take(replaced)
        fromTile += landed; replacedNow = landed.size
        fromTile.retainAll(keys.toSet())
        tileTrack[0] = keys.toSet(); tileTrack[1] = pendingImages
    }
    // a send takes every image at once (the maintainer 2026-11-13, "Ios way"): from that frame the strip draws nothing, as the text
    // goes; the row it had keeps its place so the panel's height closes on the keyboard's frames
    val lastRow = remember { arrayOf(emptyList<ai.teemoon.app.imaging.ComposerThumbs.Shown>()) }
    val sent = ai.teemoon.app.imaging.ComposerThumbs.sent(shown, lastRow[0])
    val drawn = ai.teemoon.app.imaging.ComposerThumbs.drawn(shown, lastRow[0]).also { if (shown.isNotEmpty()) lastRow[0] = shown }
    bitmaps.keys.retainAll(drawn.map { it.key }.toSet())
    val latestKeys by androidx.compose.runtime.rememberUpdatedState(keys)
    val motion = Motion.playful<Float>()
    // sizes never bounce (motion-pass item 3: the panel overshot its rest by 3.7 dp and crept back); only a
    // thumbnail's scale keeps the playful spring
    val sizeMotion = Motion.spatial<androidx.compose.ui.unit.IntSize>()
    // the space closes on the thumbnails' spring, which ends with the keyboard (SendMotionTest: both settle ~345 ms after
    // the tap); the last x-ed one also fades as it folds, a send's photos are gone already
    androidx.compose.animation.AnimatedVisibility(visible = keys.isNotEmpty() || pendingImages > 0,
        enter = androidx.compose.animation.expandVertically(sizeMotion) + androidx.compose.animation.fadeIn(Motion.fadeIn()),
        exit = androidx.compose.animation.shrinkVertically(sizeMotion) + (if (sent) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut(Motion.fadeOut()))) {
        androidx.compose.runtime.SideEffect { ComposerTrace.stripDrawn = if (sent) 0 else drawn.size }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ComposerTrace.stripDrawn = 0 } }
        Row(Modifier.graphicsLayer { alpha = if (sent) 0f else 1f }.testTag("chat.strip").semantics { traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.STRIP); motionAlpha = if (sent) 0f else 1f }.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(start = ComposerSpacing.THUMBS_START, end = 24.dp - ComposerSpacing.THUMB_GAP, top = ComposerSpacing.THUMBS_TOP)) {
            for (s in drawn) androidx.compose.runtime.key(s.key) {
                val img = bitmaps[s.key]
                val state = remember { androidx.compose.animation.core.MutableTransitionState(s.key in firstThumbs) }
                state.targetState = !s.leaving
                if (s.leaving && state.isIdle && !state.currentState) androidx.compose.runtime.LaunchedEffect(s.key) { xed = xed - s.key }
                val tile = s.key in fromTile
                if (img != null) androidx.compose.animation.AnimatedVisibility(state,
                    enter = if (tile) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.scaleIn(motion, initialScale = 0.6f) + androidx.compose.animation.fadeIn(Motion.fadeIn()) +
                        androidx.compose.animation.expandHorizontally(sizeMotion, clip = false),
                    exit = androidx.compose.animation.scaleOut(motion, targetScale = 0.6f) + androidx.compose.animation.fadeOut(Motion.fadeOut()) +
                        androidx.compose.animation.shrinkHorizontally(sizeMotion, clip = false)) {
                    // the gap after each thumbnail travels with it, so it closes with the slide, not after
                    // from a tile: the photo fades in over the tile's fill, Motion's short fade; otherwise the scale-in above
                    val fade by transition.animateFloat({ Motion.fadeIn() }, label = "tile-fade") { if (!tile || it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
                    Box(Modifier.padding(end = ComposerSpacing.THUMB_GAP)) {
                        // the whole cell fades, its x with it (iOS fades the cell; the design review of d64e430f found the x
                        // drawn at full strength over a bare tile); the tile's fill shows under it while it does
                        Box(Modifier.size(64.dp).then(if (tile && fade < 1f) Modifier.clip(RoundedCornerShape(14.dp)).background(TeemoonColors.fill) else Modifier)) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.testTag("chat.image").semantics { motionAlpha = fade }) {
                            androidx.compose.foundation.Image(img, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
                            // the 48dp target's centre sits on the circle's: 20dp right of and 20dp above the TopEnd-aligned box's
                            ImageRemoveButton({ ai.teemoon.app.imaging.ComposerThumbs.indexOf(latestKeys, s.key)?.let { i -> xed = xed + s.key; onRemoveImage(i) } },
                                Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-20).dp).semantics { motionAlpha = fade })
                        }
                        }
                    }
                }
            }
            // a photo on its way: its placeholder, where the photo will land (the maintainer 2026-11-14, the approved prototype: 64 dp,
            // the thumbnail's 14 dp corners, a small grey spinner). It fades in, and a failed read fades it out (iOS; the
            // design review of d64e430f); one its photo lands on goes at once, the photo fading in over its fill.
            run {
                val live = tiles.filter { it[1] == 0 }
                if (pendingImages > live.size) repeat(pendingImages - live.size) { tiles += intArrayOf(tileSeq[0]++, 0) }
                else if (pendingImages < live.size) {
                    val gone = live.size - pendingImages
                    val swapped = minOf(replacedNow, gone)
                    live.take(swapped).forEach { tiles.remove(it) }
                    live.drop(swapped).take(gone - swapped).forEach { it[1] = 1 }
                }
            }
            @Suppress("UNUSED_EXPRESSION") tileTick
            for (t in tiles.toList()) androidx.compose.runtime.key("tile" + t[0]) {
                val st = remember { androidx.compose.animation.core.MutableTransitionState(t[0] < firstTiles) }
                st.targetState = t[1] == 0
                if (t[1] == 1 && st.isIdle && !st.currentState) androidx.compose.runtime.LaunchedEffect(Unit) { tiles.remove(t); tileTick++ }
                androidx.compose.animation.AnimatedVisibility(st, enter = androidx.compose.animation.fadeIn(Motion.fadeIn()), exit = androidx.compose.animation.fadeOut(Motion.fadeOut())) {
                    // what the fade draws, for the motion check: the same transition and specs as the fade above
                    val a by transition.animateFloat({ if (targetState == androidx.compose.animation.EnterExitState.Visible) Motion.fadeIn() else Motion.fadeOut() }, label = "tile") {
                        if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
                    }
                    Box(Modifier.padding(end = ComposerSpacing.THUMB_GAP)) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(TeemoonColors.fill).testTag("chat.image.pending")
                            .semantics { contentDescription = "loading image"; motionAlpha = a }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TeemoonColors.secondaryLabel, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
    // the image / over-limit note: under the strip, above the text, never truncated
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(start = ComposerSpacing.FIELD_START, end = ComposerSpacing.FIELD_START, top = if (images.isEmpty() && pendingImages == 0) ComposerSpacing.THUMBS_TOP else ComposerSpacing.NOTE_UNDER_STRIP).testTag("chat.imageNote")) }
    val style = MaterialTheme.typography.bodyLarge.copy(color = TeemoonColors.label)
    // The top 17 scrolls WITH the text (UITextView's textContainerInset): a field that scrolled within its own bounds
    // stopped its text at a hard line 17 inside the panel and cut a glyph through there (pm, iOS's check-7 notes; iOS
    // had the same bug). Scrolled, the text now runs on under the panel's edge. The 17 to the row stays outside the
    // scroll, so the last line never sits on the chips, and the cap (the lines plus the top 17) shows whole lines.
    val capLines = ComposerPanel.maxLines(fontScale)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val capHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        remember(style, capLines, fontScale) { measurer.measure("\n".repeat(capLines - 1), style).size.height }.toDp()
    } + ComposerSpacing.TEXT_TOP
    Column(Modifier.fillMaxWidth().padding(start = ComposerSpacing.FIELD_START, end = ComposerSpacing.FIELD_START, bottom = ComposerSpacing.TEXT_TO_ROW)
        .heightIn(max = capHeight).verticalScroll(textScroll).padding(top = ComposerSpacing.TEXT_TOP)) {
        // The field holds a TextFieldState so a paste can bring images (contentReceiver works only there); the prompt
        // stays the caller's String. An outside change (a returned question, a send clearing it) keeps the caret where it
        // was, held inside the new text — what the String field did (ComposerPinTest), not a whole-text replace's end.
        val field = androidx.compose.foundation.text.input.rememberTextFieldState(value)
        val latestValue by androidx.compose.runtime.rememberUpdatedState(value)
        val latestChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
        androidx.compose.runtime.LaunchedEffect(value) {
            if (field.text.toString() != value) field.edit {
                val was = selection
                replace(0, length, value)
                selection = androidx.compose.ui.text.TextRange(was.start.coerceAtMost(value.length), was.end.coerceAtMost(value.length))
            }
        }
        androidx.compose.runtime.LaunchedEffect(field) {
            androidx.compose.runtime.snapshotFlow { field.text.toString() }.collect { if (it != latestValue) latestChange(it) }
        }
        val paste = if (onPasteImages == null) Modifier else Modifier.contentReceiver { content ->
            val clip = content.clipEntry.clipData
            val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
            val pasteItems = items.map { ai.teemoon.app.imaging.ComposerPaste.Item(it.uri?.toString(), it.text?.toString()) }
            val mimeTypes = clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }
            fun isImage(item: android.content.ClipData.Item) = ai.teemoon.app.imaging.ComposerPaste.Item(item.uri?.toString(), null).isImage
            // an image item's URL is not text (IMAGES.md §4c, Messages-like): the image items go to the composer and the
            // paste puts in no text — a URL item beside them neither (the maintainer 2026-11-09)
            if (ai.teemoon.app.imaging.ComposerPaste.images(mimeTypes, pasteItems).isNotEmpty()) {
                val dataUrl = ai.teemoon.app.imaging.ComposerPaste.dataUrlBeside(pasteItems)
                onPasteImages(mimeTypes, pasteItems, dataUrl)
                return@contentReceiver null
            }
            // a clip whose text is one data: URL never reaches the field (IMAGES.md §4c): the text, caret and selection
            // stay as they were, and the URL goes to the image intake
            if (onPasteDataUrl != null) ai.teemoon.app.imaging.ComposerPaste.dataUrl(items.map { it.text?.toString() }, items.map { it.uri?.toString() })
                ?.let { url -> onPasteDataUrl(url); return@contentReceiver null }
            if (!content.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) return@contentReceiver content
            // text beside an image is text: an image item with no text never reaches the field as a URI, and nothing attaches
            content.consume { isImage(it) && it.text == null }
        }
        // The keyboard's action key is "send" (Android chat convention); a hardware return still breaks the line.
        BasicTextField(field, modifier = Modifier.fillMaxWidth().semantics { traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.TEXT) }.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).then(paste).testTag("chat.composer"),
            textStyle = style, lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(), cursorBrush = SolidColor(TeemoonColors.tint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
            onKeyboardAction = { if (canSend && !streaming) onSend() },
            decorator = { inner -> Box { if (field.text.isEmpty()) Text(placeholder, style = style, color = TeemoonColors.tertiaryLabel); inner() } })
    }
    // the bottom row, pinned as the text grows: "+" · where chip · web chip … send / stop
    Row(Modifier.fillMaxWidth().padding(start = ComposerSpacing.ROW_EDGE, end = ComposerSpacing.ROW_EDGE, bottom = ComposerSpacing.ROW_EDGE),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ComposerSpacing.ROW_GAP)) {
        // "+" · where · web on the left, as wide as they need; only the where chip gives way (a spacer weighted beside it
        // took half the row and squeezed the model's name to "…")
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ComposerSpacing.ROW_GAP)) {
        // a 32dp glyph in a 48dp target (iOS 32 pt in 44 pt; Android's minimum target is 48dp), tint on every model (IMAGES.md §4d)
        // every row item 34 tall (COMPOSER_PANEL.md @ 06c7e759); Compose widens a clickable under 48dp to the minimum touch
        // target on its own, so the hit area stays 48dp without spreading the row
        if (onAttach != null) Box(Modifier.size(ComposerSpacing.ROW_ITEM).clip(CircleShape).background(TeemoonColors.fill).clickable(onClick = onAttach)
            .semantics { contentDescription = ai.teemoon.app.imaging.ImageCopy.ATTACH; role = androidx.compose.ui.semantics.Role.Button; traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.ATTACH) }
            .testTag("chat.attach"), contentAlignment = Alignment.Center) {
            PlusGlyph(TeemoonColors.tint)
        }
        // the where chip takes what is left after the others, which never shrink (the spec's row budget)
        whereChip?.let { chip -> Box(Modifier.weight(1f, fill = false).semantics { traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.WHERE) }) { chip(iconOnly) } }
        webChip?.let { chip -> Box(Modifier.semantics { traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.WEB) }) { chip(iconOnly || whereStatus) } }
        }
        if (streaming) {
            // stop: a 34 circle filled secondary, the square in the label colour
            Box(Modifier.size(ComposerSpacing.ROW_ITEM).clip(CircleShape).background(TeemoonColors.fill).clickable(onClick = onStop).semantics { contentDescription = "stop"; traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.SEND) }.testTag("chat.stop"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(TeemoonColors.label))
            }
        } else {
            // send: a 34 circle filled with the accent, a white arrow; disabled, the accent fill at 30%
            val fill = if (canSend) TeemoonColors.tint else TeemoonColors.tint.copy(alpha = 0.3f)
            Box(Modifier.size(ComposerSpacing.ROW_ITEM).clip(CircleShape).background(fill).clickable(enabled = canSend, onClick = onSend).semantics { traversalIndex = ComposerPanel.traversal(ComposerPanel.Item.SEND) }.testTag("chat.send"), contentAlignment = Alignment.Center) {
                // arrow.up, not a chevron: ArrowForward turned a quarter turn (the core icon set has no ArrowUpward).
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "send", tint = Color.White, modifier = Modifier.size(18.dp).rotate(-90f))
            }
        }
    }
    }
}

/**
 * The composer's spacing around the attached images and the "+" (the mock's V18 stripStyle; the maintainer 2026-11-12,
 * "uneven" on the S44 builds, then "outside"): 16 from the card's top edge to the thumbnails', 8 from their bottom edge
 * to the field row; the "+" a circle beside the card (IMAGES.md §4d @ 8919f6d7). The Pixel measured 18, 8 and 9.5 before (ComposerSpacingTest).
 */
object ComposerSpacing {
    /** The panel: radius 30 and a 1dp hairline edge (COMPOSER_PANEL.md @ 06c7e759). */
    val PANEL_RADIUS = 30.dp
    /** A hairline is one device pixel, as iOS's; 1dp would draw three on the Pixel. */
    val PANEL_EDGE = Dp.Hairline
    /** The panel's top edge to the thumbnails', 17 as to the text. The remove circle rides 8dp above a thumbnail's corner, inside it. */
    val THUMBS_TOP = 17.dp
    /** Under the thumbnails, above the note. */
    val ROW_TOP = 8.dp
    /** The image note under the strip (iOS: 6 pt); alone, it sits where the strip would, [THUMBS_TOP] from the panel's top. */
    val NOTE_UNDER_STRIP = 6.dp
    /** The text: 17 from the top (or the strip / note above it) and 17 down to the row. */
    val TEXT_TOP = 17.dp
    val TEXT_TO_ROW = 17.dp
    /** Every row item ("+", the chips, send, stop) is 34 tall; the chips have 14 at their sides. */
    val ROW_ITEM = 34.dp
    val CHIP_SIDE = 14.dp
    /** 8 between the row's items, and 8 from it to the panel's left, right and bottom edges. */
    val ROW_GAP = 8.dp
    val ROW_EDGE = 8.dp
    /** The panel's leading edge to the text and the note, and to the first thumbnail (IMAGES.md §4d @ 8919f6d7). */
    val FIELD_START = 16.dp
    val THUMBS_START = FIELD_START
    /** Between thumbnails, iOS's 8. */
    val THUMB_GAP = 8.dp
}
/**
 * The one-panel composer's rules (teemoon-ios docs/COMPOSER_PANEL.md @ 85c544c6; the maintainer 2026-11-12): what the
 * bottom row carries, how tall the text grows, when the chips go icon-only, and the order TalkBack reads it in.
 */
object ComposerPanel {
    enum class Item { STRIP, TEXT, ATTACH, WHERE, WEB, SEND }

    /** Android's accessibility sizes start here (its 1.5× font step; iOS's AX sizes): chips go icon-only, the text 4 lines. */
    const val ACCESSIBILITY_SCALE = 1.5f

    /** "+" · where · web … send; the web chip hides when the selected model can't take tools (new on both apps). */
    fun bottomRow(modelTakesTools: Boolean): List<Item> = listOfNotNull(Item.ATTACH, Item.WHERE, Item.WEB.takeIf { modelTakesTools }, Item.SEND)

    /** The text grows to this many lines, then scrolls inside with the caret kept visible. */
    fun maxLines(fontScale: Float): Int = if (fontScale >= ACCESSIBILITY_SCALE) 4 else 6

    /** At accessibility sizes the bottom row stays one row: the chips keep their glyphs and drop their words. */
    fun iconOnlyChips(fontScale: Float): Boolean = fontScale >= ACCESSIBILITY_SCALE

    /** TalkBack: the strip's thumbnails (each with its x), then the text, "+", where, web, send (or stop). */
    val readingOrder = listOf(Item.STRIP, Item.TEXT, Item.ATTACH, Item.WHERE, Item.WEB, Item.SEND)
    fun traversal(item: Item): Float = readingOrder.indexOf(item).toFloat()
}


/** SF Symbols' plus, drawn: two round-capped 2dp strokes, 14dp across. */
@Composable
fun PlusGlyph(color: Color) = androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
    val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
    drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, h), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 2), androidx.compose.ui.geometry.Offset(w, h / 2), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
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
 *
 * Snapshots hide it ([LocalShowsMoon]).
 */
@Composable
fun MoonGlyph(phase: Int = MoonPhase.index(), modifier: Modifier = Modifier) {
    if (!LocalShowsMoon.current) return
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

/** What the composer asks the root to present as the attach sheet (IMAGES.md §4d). */
data class AttachSheetRequest(
    /** Left to right (ImageCopy.attachSheet). */
    val buttons: List<String>,
    /** "up to {n} images per message" when the composer is full: shown here, and the buttons don't act. */
    val fullNote: String?,
    /** A button's action, run once the sheet is dismissed — never a presentation over the sheet. */
    val act: (String) -> Unit,
    /** On a model that can't see images: "<model> can't see images." at the top (IMAGES.md §4d, 5722f76d). */
    val cantSee: String? = null,
    /** "choose an image-capable model", run once the sheet is dismissed: the where sheet, filtered. */
    val chooseModel: () -> Unit = {},
)

/** The root's presenter for the attach sheet, as for the sources sheet; null in a preview or a test with no root. */
val LocalAttachPresenter = androidx.compose.runtime.staticCompositionLocalOf<((AttachSheetRequest) -> Unit)?> { null }

/** The attach sheet's resting top: its content's natural height, about half a phone screen. */
fun attachSheetTops(screenHeightDp: Float): List<Float> = listOf((1f - 300f / screenHeightDp).coerceIn(SheetDetents.MEDIUM, 0.8f))

/**
 * The attach sheet (IMAGES.md §4d, the image-flow mock Version 18): two big buttons side by side, 112dp tall (taller
 * when the largest text wraps a label, never cut) with 22dp corners, the glyph top-left and the semibold label bottom-left — take photo, choose photos — and the footnote under
 * them. [onDismiss] closes the sheet before the button's action runs, so cancel in the camera or picker returns to the
 * composer, not to the sheet.
 */
@Composable
fun AttachSheet(request: AttachSheetRequest, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // a model that can't see images: say so, and offer one that can — take photo and choose photos still attach
        request.cantSee?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, modifier = Modifier.testTag("chat.attachSheet.cantSee"))
            Text(ai.teemoon.app.imaging.CantSeeRoute.CHOOSE, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.tint,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onDismiss(); request.chooseModel() }
                    .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).semantics { role = androidx.compose.ui.semantics.Role.Button }
                    .testTag("chat.attachSheet.chooseModel"))
        }
        request.fullNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.testTag("chat.attachSheet.note")) }
        // both buttons as tall as the taller one when a label wraps
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            request.buttons.forEach { label ->
                Column(Modifier.weight(1f).heightIn(min = 112.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(TeemoonColors.groupedCard)
                    .clickable(enabled = request.fullNote == null) { onDismiss(); request.act(label) }
                    .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button }
                    .padding(16.dp).testTag("chat.attachSheet.$label"), verticalArrangement = Arrangement.SpaceBetween) {
                    if (label == ai.teemoon.app.imaging.ImageCopy.TAKE_PHOTO) CameraGlyph(TeemoonColors.tint, 28.dp) else PhotoStackGlyph(TeemoonColors.tint, 28.dp)
                    Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label)
                }
            }
        }
        Text(ai.teemoon.app.imaging.ImageCopy.ATTACH_FOOTNOTE, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
        SheetScrollEnd(16.dp)
    }
}

/** SF `photo.on.rectangle`: a frame behind a photo (its frame, sun and hill). */
@Composable
private fun PhotoStackGlyph(tint: Color, sizeDp: Dp = 20.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
    drawPath(Path().apply { moveTo(w * 0.28f, h * 0.22f); lineTo(w * 0.28f, h * 0.14f); lineTo(w * 0.94f, h * 0.14f); lineTo(w * 0.94f, h * 0.66f); lineTo(w * 0.86f, h * 0.66f) },
        tint, style = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawRoundRect(tint, Offset(w * 0.06f, h * 0.3f), Size(w * 0.72f, h * 0.56f), CornerRadius(w * 0.06f), style = Stroke(s))
    drawCircle(tint, w * 0.06f, Offset(w * 0.28f, h * 0.48f), style = Stroke(s))
    drawPath(Path().apply { moveTo(w * 0.78f, h * 0.76f); lineTo(w * 0.58f, h * 0.58f); lineTo(w * 0.26f, h * 0.86f) }, tint, style = Stroke(s, cap = StrokeCap.Round))
}

/** SF `camera`: the body with its viewfinder hump, and the lens. */
@Composable
private fun CameraGlyph(tint: Color, sizeDp: Dp = 20.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
    drawPath(Path().apply {
        moveTo(w * 0.1f, h * 0.32f); lineTo(w * 0.32f, h * 0.32f); lineTo(w * 0.4f, h * 0.2f); lineTo(w * 0.6f, h * 0.2f); lineTo(w * 0.68f, h * 0.32f)
        lineTo(w * 0.9f, h * 0.32f); lineTo(w * 0.9f, h * 0.82f); lineTo(w * 0.1f, h * 0.82f); close()
    }, tint, style = Stroke(s, join = StrokeJoin.Round))
    drawCircle(tint, w * 0.15f, Offset(w * 0.5f, h * 0.56f), style = Stroke(s))
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
    /** At accessibility sizes (COMPOSER_PANEL.md): the glyph and chevrons alone; the full label is the content description. */
    iconOnly: Boolean = false,
    /**
     * Developer mode's reasoning level, after the name in secondary text ("glm-5p3 low"; the maintainer 2026-11-13, ReasoningUi). Never
     * cut: the name gives way first. A status (download) replaces it, and at accessibility sizes it is spoken, not drawn.
     */
    level: String? = null,
) {
    // WhereChip.swift: with nothing equipped the chip fills with `ML.accent` (the fixed orange, so the label is white
    // on it) under `arrow.triangle.branch` — the one moment the chip leaves the chrome and BECOMES the accent, because
    // it is the only thing on the screen that can move the user forward. Equipped, it sits on the resting plate.
    val unconfigured = model == null
    // equipped: a plain capsule on the tertiary fill, so it reads inside the composer's panel (COMPOSER_PANEL.md)
    var m = modifier.height(ComposerSpacing.ROW_ITEM).clip(CircleShape).background(if (unconfigured) TeemoonColors.tint else TeemoonColors.panelChip)
    // Tap still opens Where — switching models is what the chip is for and stays one tap; a long press gets the menu.
    if (onClick != null) m = if (onLongClick != null) m.combinedClickable(onClick = onClick, onLongClick = onLongClick) else m.clickable(onClick = onClick)
    m = m.semantics { contentDescription = listOfNotNull(model ?: "choose where", place?.lowercase(), level?.let { "${ai.teemoon.inference.ReasoningLevel.TITLE} $it" },
        progress?.let { progressLabel ?: "downloading ${(it * 100).toInt()}%" } ?: "not downloaded".takeIf { needsDownload }).joinToString(" · ") }
    // 14dp sides (COMPOSER_PANEL.md @ 06c7e759's table), 4dp between glyph, words and chevrons
    Row(m.padding(horizontal = ComposerSpacing.CHIP_SIDE), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // a status (downloading, waiting, not downloaded) drops the glyph at every width: only phone models download, so the
        // phone glyph says nothing the status doesn't, and the name needs the room (pm/ios 2026-11-12, option a); the
        // content description keeps the place
        val status = !unconfigured && (progress != null || needsDownload)
        // a level drops it too (developer mode, the maintainer 2026-11-13): at 360dp the name lost 22dp to it and cut to "glm-5.3-…";
        // the place is in the content description and the where sheet
        if (unconfigured) BranchGlyph(Color.White)
        else if (!status && (level == null || iconOnly)) locality?.let { LocalityGlyph(it, tint = TeemoonColors.secondaryLabel) }

        // accessibility sizes: the glyph alone, or with a status the status alone — no name (COMPOSER_PANEL.md @ cbacf674)
        if (iconOnly && status) {
            if (progress != null) Text(progressLabel ?: "downloading ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, softWrap = false)
            else Text("not downloaded", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.red, maxLines = 1, softWrap = false)
        }
        if (!iconOnly) { if (model == null) Text("choose where", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        else {
            // while a download reports, the name keeps its width and the progress gives way (the design review of 7e43b5b0:
            // "gemma 4 e4b" truncated to "gemm…" at 360dp); otherwise the name is what gives way, as before
            // the name gives way before any status, down to a floor of 4 characters (pm, COMPOSER_PANEL.md's "glyph plus 4")
            val nameStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val floor = with(androidx.compose.ui.platform.LocalDensity.current) { measurer.measure(model.take(4) + "…", nameStyle).size.width.toDp() }
            // a switch fades the name through, the old one out first, inside the same width rule (motion-pass item 8)
            androidx.compose.animation.AnimatedContent(model, modifier = Modifier.weight(1f, fill = false).widthIn(min = floor), label = "chip-model",
                transitionSpec = { (androidx.compose.animation.fadeIn(Motion.axisFadeIn()) togetherWith androidx.compose.animation.fadeOut(Motion.axisFadeOut()))
                    .using(androidx.compose.animation.SizeTransform(clip = false)) }) { name ->
                Text(name, style = nameStyle, color = TeemoonColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("chip.model"))
            }
            // Tabular figures for the moving number only (trailingMonospaced: arriving && waiting == nil); on a word they
            // clipped the "fi" of "wi-fi".
            // a status is never cut (pm): the name gives way first
            if (level != null && progress == null && !needsDownload) Text(level, style = MaterialTheme.typography.labelMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, softWrap = false)
            if (progress != null) Text("· " + (progressLabel ?: "downloading ${(progress * 100).toInt()}%"), style = if (progressLabel == null) MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum") else MaterialTheme.typography.labelMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, softWrap = false)
            // "not downloaded" is never cut: it is what blocks the send (iOS WhereChip, red); the name gives way before it
            // (pm on the card: "not d…" was cut mid-word at 360dp)
            else if (needsDownload) Text("· not downloaded", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.red, maxLines = 1, softWrap = false)
            // No place text in the composer's bottom row (COMPOSER_PANEL.md @ 85c544c6: "[▣ gemma 4 e2b ⌃]"; its row budget
            // keeps the model's name whole at 360dp): the locality glyph says where, and the full label is the content
            // description. With the place beside it the name truncated to "gemma …" at 412dp.
        }
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
