// ports: teemoon/Views/Settings/NearImportView.swift @ cdea210c — REBUILD in Compose, and AHEAD OF iOS (below)
package ai.teemoon.app

// The import page, Safari style: the maintainer approved pm's mapping of iOS Safari's import onto ours (pm canvas
// L4jWEVeC8Mte2ipnkSsHKB, 2026-11-01). AHEAD OF iOS — built from the canvas in the 1.0.6 look before iOS lands its
// NearImportView; reconcile with that commit. One page, one shape for every state: the hero tiles, a big title, the
// state's own middle, and one full-width action pinned at the bottom. The words are ImportCopy's v2 placeholders.

import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The page's buttons for a state: the bar's (leading) and the one pinned at the bottom. Finished has one prominent
 * "done" at the bottom and nothing in the bar (the maintainer 2026-11-07, "match safari"): "show chats" and the bar's
 * "done" are gone, and "done" closes the flow back to its opener (ChatImportRunner.done).
 */
/**
 * Where the page's one action stands, as iOS's matches Safari (teemoon-ios a919ff53, S26): its bottom 34 above the
 * screen's edge, 30 in from each side, iOS's 54 pill (the maintainer: "pill is fine"), in the same place on every step;
 * the importing progress takes the same slot. D7's tiles are outlined 1.5 in the accent, inside their edge (iOS's width).
 */
internal object ImportLayout {
    val SIDE = 30.dp
    val BUTTON_HEIGHT = 54.dp
    val TILE_OUTLINE = 1.5.dp
    private val FROM_EDGE = 34.dp
    /** iOS's 34 is its home-indicator inset; a gesture bar is shorter, and over a taller three-button bar the button stands on it. */
    fun bottomGap(navigationBar: Dp): Dp = maxOf(FROM_EDGE, navigationBar)
}

internal object ImportActions {
    enum class Bar { CANCEL, NONE }
    enum class Bottom { IMPORT, IMPORT_GREYED, DONE, PROGRESS }

    private fun nothingToImport(state: ChatImportUi) =
        state is ChatImportUi.Confirm && (ImportCopy.nothingNew(state.preview) || ImportCopy.empty(state.preview))

    /** "cancel" while choosing or confirming; nothing while importing (back still leaves; the import runs on), after it, or with nothing to import. */
    fun bar(state: ChatImportUi): Bar =
        if (state is ChatImportUi.Importing || state is ChatImportUi.Finished || nothingToImport(state)) Bar.NONE else Bar.CANCEL

    fun bottom(state: ChatImportUi): Bottom = when {
        state is ChatImportUi.Importing -> Bottom.PROGRESS
        state is ChatImportUi.Finished || nothingToImport(state) -> Bottom.DONE
        state is ChatImportUi.Confirm && state.preview.chatsImported > 0 -> Bottom.IMPORT
        else -> Bottom.IMPORT_GREYED
    }
}

/**
 * The six states: start (choose a file, "import" greyed); the file chosen, with what it holds ("import" live), or
 * nothing new in it ("import" greyed); importing, counted in chats; done, with one "done". Not an export: the
 * unchanged alert over the start. An export opened from Files or shared lands on the file card.
 */
@Composable
fun NearImportScreen(
    state: ChatImportUi, onChooseFile: () -> Unit, onConfirm: () -> Unit, onDone: () -> Unit,
    onDismissFailure: () -> Unit, onBack: () -> Unit,
) = Column(Modifier.fillMaxSize().background(TeemoonColors.background).statusBarsPadding()) {
    val nothingNew = state is ChatImportUi.Confirm && ImportCopy.nothingNew(state.preview)
    val empty = state is ChatImportUi.Confirm && ImportCopy.empty(state.preview)
    // the bar (ImportActions.bar). Its 44dp holds with no button, so the hero doesn't jump between steps (iOS keeps its
    // bar visible, 478b3bc3).
    NavBar("", leading = {
        if (ImportActions.bar(state) == ImportActions.Bar.CANCEL) PillButton(ImportCopy.CANCEL, onBack, Modifier.testTag("import.cancel"))
    })
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        HeroTiles(done = state is ChatImportUi.Finished, Modifier.padding(top = 24.dp))
        val title = when { state is ChatImportUi.Importing -> ImportCopy.IMPORTING_TITLE; state is ChatImportUi.Finished -> ImportCopy.TITLE
            nothingNew -> ImportCopy.NOTHING_NEW_TITLE; empty -> ImportCopy.EMPTY_TITLE; else -> ImportCopy.PAGE_TITLE }
        Text(title, style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, color = TeemoonColors.label, modifier = Modifier.padding(top = 28.dp))
        val body = when { state is ChatImportUi.Importing -> ImportCopy.IMPORTING_BODY; state is ChatImportUi.Finished -> ImportCopy.doneBody(state.summary)
            nothingNew -> ImportCopy.NOTHING_NEW_BODY; empty -> ImportCopy.EMPTY_BODY; else -> ImportCopy.BODY }
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = TeemoonColors.label, modifier = Modifier.padding(top = 12.dp))
        when (state) {
            ChatImportUi.Idle, is ChatImportUi.Failed -> {
                Small(ImportCopy.EXPORT_PATH, Modifier.padding(top = 10.dp))
                Box(Modifier.padding(top = 32.dp).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(TeemoonColors.secondaryBackground)
                    .clickable(onClick = onChooseFile).padding(horizontal = 16.dp, vertical = 12.dp).testTag("import.chooseFile"), contentAlignment = Alignment.Center) {
                    Text(ImportCopy.CHOOSE_FILE, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.tint, textAlign = TextAlign.Center)
                }
            }
            is ChatImportUi.Checking -> { FileCard(state.file, line = null, note = null, checking = true); ChooseAnother(onChooseFile) }
            is ChatImportUi.Confirm -> { FileCard(state.file, ImportCopy.cardLine(state.preview), ImportCopy.cardNote(state.preview)); ChooseAnother(onChooseFile) }
            is ChatImportUi.Importing -> Unit
            is ChatImportUi.Finished -> ImportCopy.doneNotes(state.summary).forEach { Small(it, Modifier.padding(top = 10.dp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
    // the bottom: one action, pinned, with room above it: at the largest text on a phone the file card scrolled flush
    // against it, "choose another file…" touching "import" (the emulator run, 2026-11-06)
    // (the navigation bar is in ImportLayout.bottomGap, so the page itself takes no bottom inset)
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxWidth().padding(start = ImportLayout.SIDE, end = ImportLayout.SIDE, top = 16.dp, bottom = ImportLayout.bottomGap(navigationBar)).height(ImportLayout.BUTTON_HEIGHT),
        contentAlignment = Alignment.Center) {
        when (ImportActions.bottom(state)) {
            ImportActions.Bottom.PROGRESS -> if (state is ChatImportUi.Importing) Column(Modifier.fillMaxWidth().testTag("import.progress"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { ImportCopy.fraction(state.read, state.total) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = TeemoonColors.tint, trackColor = TeemoonColors.tertiaryBackground, gapSize = 0.dp, drawStopIndicator = {})   // a plain bar, as iOS's ProgressView
                Text(ImportCopy.progress(state.read, state.total), style = MaterialTheme.typography.titleSmall.copy(fontWeight = null), color = TeemoonColors.secondaryLabel)
            }
            // after the import, nothing new, or an empty file: one "done" — the flow closes back to what opened it
            ImportActions.Bottom.DONE -> PrimaryPill(ImportCopy.DONE, onDone, Modifier.testTag("import.done"), height = ImportLayout.BUTTON_HEIGHT)
            ImportActions.Bottom.IMPORT -> PrimaryPill(ImportCopy.IMPORT, onConfirm, Modifier.testTag("import.confirm"), height = ImportLayout.BUTTON_HEIGHT)
            ImportActions.Bottom.IMPORT_GREYED -> Box(Modifier.fillMaxWidth().height(ImportLayout.BUTTON_HEIGHT).clip(CircleShape).background(TeemoonColors.tertiaryBackground).testTag("import.confirmDisabled"), contentAlignment = Alignment.Center) {
                Text(ImportCopy.IMPORT, style = MaterialTheme.typography.titleMedium, color = TeemoonColors.secondaryLabel)
            }
        }
    }
    if (state is ChatImportUi.Failed) IosAlert(ImportCopy.failureTitle(state.reason), ImportCopy.failureMessage(state.reason).orEmpty(),
        listOf(ImportCopy.ACKNOWLEDGE to onDismissFailure), onDismiss = onDismissFailure)
}

/**
 * The offer to delete the export and its failure, over whatever the page closed to (the chats settings, or the chat
 * a shared file arrived over), never over "import finished" (v9, D4). The red delete names the file and comes first, then
 * "not now"; dismissing the alert is "not now" (D5).
 */
@Composable
fun ExportDeleteAlert(exportDelete: ExportDelete?, onDelete: () -> Unit, onNotNow: () -> Unit, onDismissFailure: () -> Unit) {
    when (exportDelete) {
        is ExportDelete.Offer -> {
            // a long name wraps after a hyphen, in the button and the body, never splitting off ".json" (pm 2026-11-01): Android's line breaker
            // doesn't break at a hyphen, so a name wider than the button broke by character; a zero-width space after
            // each hyphen is the break it takes instead
            val delete = ImportCopy.deleteButton(exportDelete.name).replace("-", "-\u200B")
            IosAlert(ImportCopy.DELETE_TITLE, ImportCopy.deleteMessage(exportDelete.name?.replace("-", "-\u200B")),
                listOf(delete to onDelete, ImportCopy.NOT_NOW to onNotNow), onDismiss = onNotNow, destructive = setOf(delete))
        }
        ExportDelete.Failed -> IosAlert(ImportCopy.DELETE_FAILED_TITLE, ImportCopy.DELETE_FAILED,
            listOf(ImportCopy.ACKNOWLEDGE to onDismissFailure), onDismiss = onDismissFailure)
        ExportDelete.Deleting, null -> Unit
    }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = null), textAlign = TextAlign.Center, color = TeemoonColors.secondaryLabel, modifier = modifier)

@Composable
private fun ChooseAnother(onClick: () -> Unit) =
    Text(ImportCopy.CHOOSE_ANOTHER, style = MaterialTheme.typography.titleSmall.copy(fontWeight = null), color = TeemoonColors.tint,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp).testTag("import.chooseAnother"))

/** The chosen file: its glyph, name, what it holds (a spinner while the dry run reads it), date · size, and the small line. */
@Composable
private fun FileCard(file: ImportFile, line: String?, note: String?, checking: Boolean = false) {
    Column(Modifier.padding(top = 28.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TeemoonColors.secondaryBackground)
        .padding(horizontal = 16.dp, vertical = 14.dp).testTag("import.fileCard"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(TeemoonColors.tertiaryBackground), contentAlignment = Alignment.Center) { DocGlyph(TeemoonColors.label, 22.dp) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                file.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = TeemoonColors.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                if (checking) SpokeSpinner(TeemoonColors.secondaryLabel, Modifier.padding(vertical = 2.dp).size(16.dp).testTag("import.checking"))
                else if (line != null) Text(line, style = MaterialTheme.typography.titleSmall.copy(fontWeight = null), color = TeemoonColors.label)
                ImportCopy.fileMeta(file)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel) }
            }
        }
        if (note != null) {
            HorizontalDivider(color = TeemoonColors.separator, thickness = 0.5.dp)
            Text(note, style = MaterialTheme.typography.titleSmall.copy(fontWeight = null, fontSize = MaterialTheme.typography.bodySmall.fontSize * 14 / 13), color = TeemoonColors.secondaryLabel)
        }
    }
}

/**
 * Safari's cluster of app tiles, ours: lock, image, the chat bubble (large, in front), moon, download. On the start
 * only do the small tiles carry glyphs; after it they go plain, and done fills the bubble's tile with a check.
 */
@Composable
private fun HeroTiles(done: Boolean, modifier: Modifier = Modifier) = Box(modifier.size(250.dp, 120.dp)) {
    val accent = TeemoonColors.tint
    @Composable fun tile(x: Dp, y: Dp, side: Dp, corner: Dp, fill: Color = TeemoonColors.secondaryBackground, glyph: (@Composable () -> Unit)? = null) =
        Box(Modifier.offset(x, y).size(side).clip(RoundedCornerShape(corner)).background(fill)
            .border(ImportLayout.TILE_OUTLINE, accent, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) { glyph?.invoke() }
    // a glyph on every tile, in every state, outlined in the accent (v9, D7): lock, image, moon, file; the chat in front
    tile(0.dp, 52.dp, 44.dp, 12.dp) { LockGlyph(open = false, tint = accent, sizeDp = 22.dp) }
    tile(52.dp, 8.dp, 54.dp, 14.dp) { PhotoGlyph(accent, 26.dp) }
    tile(178.dp, 4.dp, 50.dp, 14.dp) { CrescentGlyph(accent, 24.dp) }
    tile(190.dp, 66.dp, 44.dp, 12.dp) { DocGlyph(accent, 22.dp) }
    if (done) tile(96.dp, 40.dp, 72.dp, 18.dp, fill = accent) { Icon(Icons.Filled.Check, null, tint = TeemoonColors.background, modifier = Modifier.size(40.dp)) }
    else tile(96.dp, 40.dp, 72.dp, 18.dp) { BubbleGlyph(accent, 36.dp) }
}

/**
 * iOS's small `ProgressView()`: eight spokes, the lit one stepping round. Material's indeterminate arc starts as a sliver,
 * which a still frame (a golden, a screenshot) shows as a lone "," under the name (pm 2026-11-02); every frame of this
 * one is the whole wheel.
 */
@Composable
internal fun SpokeSpinner(tint: Color, modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 8f, Motion.spin(), label = "spoke")
    val step = turn.toInt()
    Canvas(modifier) {
        val c = center; val r = size.minDimension / 2; val w = size.minDimension * 0.11f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 - 90.0); val cos = kotlin.math.cos(a).toFloat(); val sin = kotlin.math.sin(a).toFloat()
            val alpha = 1f - ((step - i + 8) % 8) / 8f * 0.85f
            drawLine(tint.copy(alpha = tint.alpha * alpha), Offset(c.x + cos * r * 0.45f, c.y + sin * r * 0.45f), Offset(c.x + cos * (r - w / 2), c.y + sin * (r - w / 2)), w, StrokeCap.Round)
        }
    }
}

/** SF `doc`: a page with its corner folded. */
@Composable
private fun DocGlyph(tint: Color, sizeDp: Dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.8.dp.toPx()
    val page = Path().apply { moveTo(w * 0.58f, h * 0.12f); lineTo(w * 0.29f, h * 0.12f); quadraticTo(w * 0.2f, h * 0.12f, w * 0.2f, h * 0.21f)
        lineTo(w * 0.2f, h * 0.79f); quadraticTo(w * 0.2f, h * 0.88f, w * 0.29f, h * 0.88f); lineTo(w * 0.71f, h * 0.88f); quadraticTo(w * 0.8f, h * 0.88f, w * 0.8f, h * 0.79f)
        lineTo(w * 0.8f, h * 0.33f); close() }
    drawPath(page, tint, style = Stroke(s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(Path().apply { moveTo(w * 0.58f, h * 0.12f); lineTo(w * 0.58f, h * 0.33f); lineTo(w * 0.8f, h * 0.33f) }, tint, style = Stroke(s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** SF `photo`: a frame, a sun and a hill. */
@Composable
internal fun PhotoGlyph(tint: Color, sizeDp: Dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.8.dp.toPx()
    drawRoundRect(tint, Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.6f), CornerRadius(w * 0.08f), style = Stroke(s))
    drawCircle(tint, w * 0.07f, Offset(w * 0.36f, h * 0.4f), style = Stroke(s))
    drawPath(Path().apply { moveTo(w * 0.88f, h * 0.64f); lineTo(w * 0.66f, h * 0.44f); lineTo(w * 0.32f, h * 0.8f) }, tint, style = Stroke(s, cap = StrokeCap.Round))
}

/** SF `eye` (Material "visibility", outlined): an almond and its iris. The model browser's "reads images" marker. */
@Composable
internal fun EyeGlyph(tint: Color, sizeDp: Dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.6.dp.toPx()
    drawPath(Path().apply {
        moveTo(w * 0.06f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.02f, w * 0.94f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.98f, w * 0.06f, h * 0.5f)
        close()
    }, tint, style = Stroke(s, join = StrokeJoin.Round))
    drawCircle(tint, w * 0.15f, Offset(w * 0.5f, h * 0.5f), style = Stroke(s))
}

/** SF `moon`: a crescent. */
@Composable
private fun CrescentGlyph(tint: Color, sizeDp: Dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = 1.8.dp.toPx()
    drawPath(Path().apply {
        moveTo(w * 0.62f, h * 0.12f)
        cubicTo(w * 0.2f, h * 0.14f, w * 0.06f, h * 0.62f, w * 0.34f, h * 0.84f)
        cubicTo(w * 0.56f, h * 1.0f, w * 0.86f, h * 0.88f, w * 0.88f, h * 0.6f)
        cubicTo(w * 0.62f, h * 0.74f, w * 0.38f, h * 0.44f, w * 0.62f, h * 0.12f); close()
    }, tint, style = Stroke(s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
