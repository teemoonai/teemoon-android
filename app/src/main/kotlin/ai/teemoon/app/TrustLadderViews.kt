// ports: teemoon/Views/Chat/EnclaveGroupView.swift, TrustSeamDivider.swift, NoActionLogNotice.swift, RecipeCardView.swift,
//        ImageRowView.swift, NodeEgressSection.swift, FableAuditBlock.swift @ v1.0.1 — every branch, in the same
//        registers. (These were one 892-line TrustLadderKnownCode.swift until iOS split them into "honest filenames"
//        in 4f367df on 2026-08-30 — two days BEFORE the 2026-09-01 baseline, so the old name never existed at the sha
//        this header pins to. Corrected 2026-09-22.) teemoon/Views/Chat/TrustLadder.swift @ v1.0.1
//        (ValuePill), teemoon/Views/Chat/VerificationRunView.swift @ v1.0.4 (proofText), teemoon/Views/Chat/TrustLadderView+Sections.swift @ v1.0.1
//        (EvidenceCard / EvidenceRowView) — the section views, rebuilt in Compose. Registers, as the Swift keeps them: teeVerified for
//        facts that are the design working, red only for tamper evidence, orange for what cannot be checked, secondary for plain facts.
package ai.teemoon.app

import ai.teemoon.confidential.AuditIndex
import ai.teemoon.confidential.AuditVerdictClass
import ai.teemoon.confidential.EnclaveGroup
import ai.teemoon.confidential.GuestOSProvenance
import ai.teemoon.confidential.ImageEntry
import ai.teemoon.confidential.MeasuredConfig
import ai.teemoon.confidential.ModelLayerVerification
import ai.teemoon.confidential.NodeFinding
import ai.teemoon.confidential.PlaintextExposure
import ai.teemoon.confidential.StepState
import ai.teemoon.confidential.partitionRecipeRows
import ai.teemoon.confidential.splitNodeFindings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

// MARK: - Pure

/** ImageRowView's tiering: who sees plaintext outright, who could reach it, who sees metrics, who forwards logs. */
object ImageRowPresentation {
    fun tier(image: ImageEntry): Int? {
        if (image.kind == ImageEntry.Kind.GUEST_OS || image.plaintextRole != null) return 1
        return when (image.capability) {
            PlaintextExposure.Capability.PROCESS_ACCESS -> 2
            PlaintextExposure.Capability.DEVICE_PRIVILEGE -> 3
            PlaintextExposure.Capability.LOG_ACCESS -> 4
            null -> null
        }
    }
    fun capsuleLabel(image: ImageEntry): String? {
        if (image.kind == ImageEntry.Kind.GUEST_OS || image.plaintextRole != null) return "sees your message"
        return image.capability?.capsule
    }
    fun capNote(image: ImageEntry): String? {
        if (image.kind == ImageEntry.Kind.GUEST_OS) return "as the kernel it manages every container’s memory, so your plaintext is unavoidably visible to it — intrinsic to any OS, which is why its image is measured into the quote."
        return when (image.plaintextRole) {
            PlaintextExposure.Role.E2EE_TERMINATOR -> "decrypts your request sealed to the model key."
            PlaintextExposure.Role.MODEL_SERVER -> "runs the model over your plaintext tokens."
            null -> null
        }
    }
    fun egress(tier: Int?): String = when (tier) { 1 -> "plaintext egress"; 2 -> "process-access egress"; 3 -> "metrics egress"; 4 -> "log-forwarding egress"; else -> "" }

    /** RecipeCardView's verdict line and scope caption. */
    fun recipeVerdict(verification: ModelLayerVerification?, tagPinnedCount: Int): String? = when (verification) {
        ModelLayerVerification.VERIFIED -> if (tagPinnedCount == 0) "verified on this device ✓" else "recipe file verified on this device ✓"
        ModelLayerVerification.HASH_MISMATCH -> "✗ does not match the action log — recipe unverified, do not trust"
        ModelLayerVerification.FETCH_FAILED -> "source unreachable — could not verify (transient)"
        null -> null
    }
    fun recipeScopeCaption(verification: ModelLayerVerification?, images: List<ImageEntry>): String? {
        val tagged = images.filter { it.kind == ImageEntry.Kind.TAG_PINNED }
        if (verification != ModelLayerVerification.VERIFIED || tagged.isEmpty() || images.isEmpty()) return null
        val names = tagged.joinToString(", ") { it.name.substringAfterLast('/') }
        val pinned = images.size - tagged.size
        val pronoun = if (tagged.size == 1) "its" else "their"; val rows = if (tagged.size == 1) "its row" else "their rows"
        return "pins $pinned of ${images.size} images by content digest — ${tagged.size} ($names) by tag only: $pronoun running bytes can change without changing the hash verified above. see $rows."
    }
    fun handlesMessage(image: ImageEntry) = image.plaintextRole != null || image.kind == ImageEntry.Kind.LOCAL
    /** A tag-pinned sidecar with no capability and no plaintext role is named, not individually shown. */
    fun collapsible(image: ImageEntry) = image.kind == ImageEntry.Kind.TAG_PINNED && image.capability == null && image.plaintextRole == null
}

/** proofText: backtick tokens render monospace and primary, the prose secondary. */
fun proofText(detail: String, tokenColor: Color, proseColor: Color): AnnotatedString = buildAnnotatedString {
    detail.split("`").forEachIndexed { i, seg ->
        if (i % 2 == 0) withStyle(SpanStyle(color = proseColor)) { append(seg) }
        else withStyle(SpanStyle(color = tokenColor, fontFamily = FontFamily.Monospace)) { append(seg) }
    }
}

// MARK: - Views

// iOS text styles at their point sizes: caption2 11, caption 12, footnote 13 — the Theme's label/body scale carries them
private val caption2 @Composable get() = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
private val captionStyle @Composable get() = MaterialTheme.typography.labelSmall
private val footnote @Composable get() = MaterialTheme.typography.bodySmall
@Composable private fun caption(text: String, color: Color = TeemoonColors.secondaryLabel) = Text(text, style = caption2, color = color)
@Composable private fun ProofText(detail: String, style: TextStyle = caption2) = Text(proofText(detail, TeemoonColors.label, TeemoonColors.secondaryLabel), style = style)
/** A Link: the title and arrow.up.right, caption2 medium, in the tint unless a register says otherwise. */
@Composable private fun LinkText(title: String, url: String, color: Color = TeemoonColors.tint, leading: (@Composable () -> Unit)? = null) {
    val uri = LocalUriHandler.current
    Row(Modifier.clickable { uri.openUri(url) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        leading?.invoke(); Text(title, style = caption2.copy(fontWeight = FontWeight.Medium), color = color); ArrowUpRightGlyph(color, 10.dp)
    }
}
/** A capsule tag: caption2, 6×2 padding, a soft fill of its colour. */
@Composable private fun CapsuleTag(text: String, color: Color, fillAlpha: Float = 0.15f, weight: FontWeight = FontWeight.Medium, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = fillAlpha)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        leading?.invoke(); Text(text, style = caption2.copy(fontWeight = weight), color = color)
    }
}
@Composable private fun SubHeader(text: String, color: Color = TeemoonColors.secondaryLabel) = Text(text.uppercase(), style = caption2.copy(fontWeight = FontWeight.SemiBold), color = color, modifier = Modifier.padding(top = 2.dp))

// The SF glyphs this screen speaks in, drawn (no symbol font here): the seal, the octagon, the triangle, the magnifier over a
// page, the shipping box, the recipe list, and the tier glyphs (eye / cpu / memorychip / doc.text).
@Composable private fun SealGlyph(color: Color, ok: Boolean, sizeDp: Dp = 12.dp) = Canvas(Modifier.size(sizeDp)) {
    val r = size.minDimension / 2; val c = center
    val path = Path()
    for (i in 0 until 16) { val a = i * (Math.PI / 8); val rr = if (i % 2 == 0) r else r * 0.82f; val x = c.x + (rr * Math.cos(a)).toFloat(); val y = c.y + (rr * Math.sin(a)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    path.close(); drawPath(path, color)
    val s = size.minDimension * 0.11f; val cap = StrokeCap.Round; val ink = Color.Black.copy(alpha = 0.85f)
    if (ok) { drawLine(ink, Offset(c.x - r * 0.35f, c.y), Offset(c.x - r * 0.08f, c.y + r * 0.3f), s, cap); drawLine(ink, Offset(c.x - r * 0.08f, c.y + r * 0.3f), Offset(c.x + r * 0.4f, c.y - r * 0.3f), s, cap) }
    else { drawLine(ink, Offset(c.x - r * 0.3f, c.y - r * 0.3f), Offset(c.x + r * 0.3f, c.y + r * 0.3f), s, cap); drawLine(ink, Offset(c.x + r * 0.3f, c.y - r * 0.3f), Offset(c.x - r * 0.3f, c.y + r * 0.3f), s, cap) }
}
@Composable private fun TriangleGlyph(color: Color, sizeDp: Dp = 12.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height
    val path = Path().apply { moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.96f, h * 0.9f); lineTo(w * 0.04f, h * 0.9f); close() }
    drawPath(path, color)
    val ink = Color.Black.copy(alpha = 0.85f); val s = w * 0.1f; val cap = StrokeCap.Round
    drawLine(ink, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.64f), s, cap); drawCircle(ink, w * 0.06f, Offset(w * 0.5f, h * 0.78f))
}
@Composable private fun OctagonGlyph(color: Color, sizeDp: Dp = 12.dp) = Canvas(Modifier.size(sizeDp)) {
    val r = size.minDimension / 2; val c = center; val path = Path()
    for (i in 0 until 8) { val a = Math.PI / 8 + i * Math.PI / 4; val x = c.x + (r * Math.cos(a)).toFloat(); val y = c.y + (r * Math.sin(a)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    path.close(); drawPath(path, color)
    val ink = Color.Black.copy(alpha = 0.85f); val s = r * 0.22f; val cap = StrokeCap.Round
    drawLine(ink, Offset(c.x - r * 0.35f, c.y - r * 0.35f), Offset(c.x + r * 0.35f, c.y + r * 0.35f), s, cap); drawLine(ink, Offset(c.x + r * 0.35f, c.y - r * 0.35f), Offset(c.x - r * 0.35f, c.y + r * 0.35f), s, cap)
}
@Composable private fun MagnifierDocGlyph(color: Color, sizeDp: Dp = 16.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = w * 0.09f; val stroke = Stroke(s); val cap = StrokeCap.Round
    drawRoundRect(color, topLeft = Offset(w * 0.12f, h * 0.06f), size = Size(w * 0.56f, h * 0.76f), cornerRadius = CornerRadius(w * 0.08f), style = stroke)
    drawLine(color, Offset(w * 0.26f, h * 0.3f), Offset(w * 0.54f, h * 0.3f), s, cap); drawLine(color, Offset(w * 0.26f, h * 0.45f), Offset(w * 0.5f, h * 0.45f), s, cap)
    drawCircle(color, w * 0.17f, Offset(w * 0.66f, h * 0.66f), style = stroke); drawLine(color, Offset(w * 0.78f, h * 0.78f), Offset(w * 0.94f, h * 0.94f), s * 1.3f, cap)
}
@Composable private fun BoxGlyph(color: Color, sizeDp: Dp = 11.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = w * 0.1f; val cap = StrokeCap.Round
    val p = Path().apply { moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.94f, h * 0.28f); lineTo(w * 0.94f, h * 0.72f); lineTo(w * 0.5f, h * 0.94f); lineTo(w * 0.06f, h * 0.72f); lineTo(w * 0.06f, h * 0.28f); close() }
    drawPath(p, color, style = Stroke(s)); drawLine(color, Offset(w * 0.06f, h * 0.28f), Offset(w * 0.5f, h * 0.5f), s, cap); drawLine(color, Offset(w * 0.94f, h * 0.28f), Offset(w * 0.5f, h * 0.5f), s, cap); drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.94f), s, cap)
}
@Composable private fun ListDocGlyph(color: Color, sizeDp: Dp = 12.dp) = Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height; val s = w * 0.09f; val cap = StrokeCap.Round
    drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.06f), size = Size(w * 0.72f, h * 0.88f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(s))
    for (i in 0..2) { val y = h * (0.3f + i * 0.2f); drawCircle(color, w * 0.05f, Offset(w * 0.3f, y)); drawLine(color, Offset(w * 0.42f, y), Offset(w * 0.7f, y), s, cap) }
}
/** The tier glyph on the plaintext capsule: eye (sees), cpu (process access), memorychip (device privilege), doc.text (logs). */
@Composable private fun TierGlyph(tier: Int?, color: Color, sizeDp: Dp = 11.dp) {
    when (tier) {
        2 -> Canvas(Modifier.size(sizeDp)) { val w = size.width; val h = size.height; val s = w * 0.1f; drawRoundRect(color, topLeft = Offset(w * 0.22f, h * 0.22f), size = Size(w * 0.56f, h * 0.56f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s)); for (f in listOf(0.35f, 0.5f, 0.65f)) { drawLine(color, Offset(w * f, h * 0.06f), Offset(w * f, h * 0.22f), s); drawLine(color, Offset(w * f, h * 0.78f), Offset(w * f, h * 0.94f), s); drawLine(color, Offset(w * 0.06f, h * f), Offset(w * 0.22f, h * f), s); drawLine(color, Offset(w * 0.78f, h * f), Offset(w * 0.94f, h * f), s) } }
        3 -> Canvas(Modifier.size(sizeDp)) { val w = size.width; val h = size.height; val s = w * 0.1f; drawRoundRect(color, topLeft = Offset(w * 0.1f, h * 0.28f), size = Size(w * 0.8f, h * 0.44f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s)); for (f in listOf(0.28f, 0.44f, 0.6f, 0.76f)) drawLine(color, Offset(w * f, h * 0.72f), Offset(w * f, h * 0.9f), s) }
        4 -> Canvas(Modifier.size(sizeDp)) { val w = size.width; val h = size.height; val s = w * 0.1f; val cap = StrokeCap.Round; drawRoundRect(color, topLeft = Offset(w * 0.16f, h * 0.06f), size = Size(w * 0.68f, h * 0.88f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(s)); for (i in 0..2) { val y = h * (0.32f + i * 0.18f); drawLine(color, Offset(w * 0.32f, y), Offset(w * 0.68f, y), s, cap) } }
        else -> EyeGlyph(open = true, tint = color, sizeDp = sizeDp)
    }
}
@Composable private fun CircleMark(ok: Boolean, color: Color, sizeDp: Dp = 13.dp) = Canvas(Modifier.size(sizeDp)) {
    val r = size.minDimension / 2; val c = center; val s = r * 0.18f; val cap = StrokeCap.Round
    drawCircle(color, r - s / 2, c, style = Stroke(s))
    if (ok) { drawLine(color, Offset(c.x - r * 0.4f, c.y), Offset(c.x - r * 0.1f, c.y + r * 0.32f), s, cap); drawLine(color, Offset(c.x - r * 0.1f, c.y + r * 0.32f), Offset(c.x + r * 0.42f, c.y - r * 0.32f), s, cap) }
    else { drawLine(color, Offset(c.x, c.y - r * 0.45f), Offset(c.x, c.y + r * 0.1f), s, cap); drawCircle(color, s * 0.7f, Offset(c.x, c.y + r * 0.42f)) }
}

/** ValuePill: a truncated monospaced value on the tertiary fill, its full form one tap away (doc.on.doc → a green check for 1.4 s). */
@Composable
fun ValuePill(value: String, fullValue: String? = null, onCopy: (String) -> Unit = {}) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(TeemoonColors.fill).clickable { onCopy(fullValue ?: value); copied = true; scope.launch { delay(1_400); copied = false } }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = captionStyle.copy(fontFamily = FontFamily.Monospace), color = TeemoonColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (copied) Text("✓", style = caption2, color = TeemoonColors.verified) else CopyGlyph(TeemoonColors.secondaryLabel, 12.dp)
    }
}

@Composable
fun EvidenceCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TeemoonColors.groupedCard).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall); caption(subtitle); content()
    }
}

@Composable
fun EvidenceRowView(item: EvidenceItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        val (glyph, tone) = when (item.state) { StepState.DONE -> "✓" to TrustTone.OK; StepState.LIVE -> "●" to TrustTone.OK; StepState.PENDING -> "…" to TrustTone.VERIFYING; StepState.STUCK -> "✕" to TrustTone.FAILED }
        Text(glyph, color = tone.color(), modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(item.label, style = MaterialTheme.typography.bodyMedium); ProofText(item.detail, captionStyle) }
    }
}

/**
 * FableAuditBlock: a point-in-time source review of this exact build, in a distinct ORANGE register (a human review,
 * not a green attestation check). The reviewer's conclusion replaces the fixed subtitle — boilerplate must never
 * outrank the verdict — clipped to three lines; the class badge survives any truncation. nil ⇒ neutral copy, never reassurance.
 */
@Composable
fun FableAuditBlock(url: String, title: String, subtitle: String, verdict: String?, verdictClass: AuditVerdictClass?) {
    val uri = LocalUriHandler.current
    val badge = when (verdictClass) { AuditVerdictClass.PRIVATE_REVIEW -> TeemoonColors.verified; AuditVerdictClass.LEAKS -> TeemoonColors.red; AuditVerdictClass.COMPROMISABLE -> TeemoonColors.orange; AuditVerdictClass.QUALIFIED_PASS -> Color(0xFF30B0C7); else -> TeemoonColors.gray }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(10.dp)).background(TeemoonColors.orange.copy(alpha = 0.12f)).border(0.5.dp, TeemoonColors.orange.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
        .clickable { uri.openUri(url) }.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MagnifierDocGlyph(TeemoonColors.orange)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = captionStyle.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label)
                verdictClass?.let { Text(it.label.uppercase(), style = caption2.copy(fontWeight = FontWeight.Bold), color = badge, modifier = Modifier.clip(CircleShape).background(badge.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.5.dp)) }
            }
            if (verdict != null) Text(MarkdownInline.annotate(verdict), style = caption2, color = TeemoonColors.label, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            else { caption(subtitle); Text("verdict not recorded — open the review", style = caption2, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(top = 2.dp)) }
        }
        ArrowUpRightGlyph(TeemoonColors.orange, 10.dp)
    }
}

/** NodeEgressSection: the machine-read findings unioned and severity-sorted — live first, latent second, informational behind a disclosure. */
@Composable
fun NodeEgressSection(findings: List<NodeFinding>) {
    val split = splitNodeFindings(findings)
    val sources = findings.map { it.source }.toSet().size
    var showInfo by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    @Composable fun severityColor(severity: String): Color = when (severity) { "critical" -> TeemoonColors.red; "high" -> TeemoonColors.orange; "medium" -> if (TeemoonColors.palette.dark) Color(0xFFFFD60A) else Color(0xFFB08700); "low" -> TeemoonColors.blue; else -> TeemoonColors.gray }
    @Composable fun row(f: NodeFinding) = Row(Modifier.fillMaxWidth().clickable { uri.openUri(f.url) }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        val color = severityColor(f.severity)
        // Outlined, not filled: the filled register belongs to the verdict badge — one card never shouts twice.
        Box(Modifier.widthIn(min = 40.dp).border(0.8.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.5.dp), contentAlignment = Alignment.Center) {
            Text((if (f.severity == "medium") "med" else f.severity).uppercase(), style = caption2.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp), color = color)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(f.title, style = captionStyle, color = TeemoonColors.label)
            Text(f.qualifier?.let { "${f.source} · $it" } ?: f.source, style = caption2.copy(fontFamily = FontFamily.Monospace), color = TeemoonColors.tertiaryLabel)
        }
        ArrowUpRightGlyph(TeemoonColors.tertiaryLabel, 10.dp)
    }
    Column(Modifier.fillMaxWidth().padding(top = 3.dp).border(0.5.dp, TeemoonColors.separator, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("plaintext egress · this node", style = captionStyle.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label)
            caption("what ${if (sources == 1) "the published review" else "the $sources published reviews"} found at this attestation's exact identities — live at deployed flags first.")
        }
        if (split.live.isNotEmpty()) { SubHeader("live at deployed flags", TeemoonColors.red); split.live.forEach { row(it) } }
        if (split.latent.isNotEmpty()) { SubHeader("latent — off, armed, or opt-in"); split.latent.forEach { row(it) } }
        if (split.info.isNotEmpty()) {
            Row(Modifier.clickable { showInfo = !showInfo }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${split.info.size} informational finding${if (split.info.size == 1) "" else "s"}", style = caption2, color = TeemoonColors.secondaryLabel)
                Text(if (showInfo) "▾" else "▸", style = caption2, color = TeemoonColors.secondaryLabel)
            }
            if (showInfo) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { split.info.forEach { row(it) } }
        }
    }
}

@Composable
fun ImageRowView(image: ImageEntry, rung: TrustRung, audit: AuditIndex = AuditIndex.shared, onCopy: (String) -> Unit = {}) {
    val expert = rung == TrustRung.EXPERT
    val tier = ImageRowPresentation.tier(image)
    val unverified = image.kind == ImageEntry.Kind.UNVERIFIED; val tagPinned = image.kind == ImageEntry.Kind.TAG_PINNED
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(image.name, style = footnote.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium), color = TeemoonColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // The provenance-tier chip beside the name: a red `unverified` always shows; a tag-pinned row's chip is ORANGE
            // (cannot-check — a tag pins no bytes), never red (red = positive evidence of tamper).
            if (image.kind != ImageEntry.Kind.LOCAL) {
                val color = if (unverified) TeemoonColors.red else if (tagPinned) TeemoonColors.orange else TeemoonColors.secondaryLabel
                if (unverified || tagPinned) CapsuleTag(image.kind.label, color, weight = FontWeight.Normal)
                else Text(image.kind.label, style = caption2, color = color, modifier = Modifier.clip(CircleShape).background(TeemoonColors.fill).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        // Green plaintext-tier capsule — one soft teeVerified register at every tier: these facts are the design working, not warnings.
        ImageRowPresentation.capsuleLabel(image)?.let { CapsuleTag(it, TeemoonColors.verified, leading = { TierGlyph(tier, TeemoonColors.verified) }) }
        ImageRowPresentation.capNote(image)?.let { caption(it) }
        if (expert && tier != null) image.auditURL?.let { url ->
            if (image.kind == ImageEntry.Kind.GUEST_OS) FableAuditBlock(url, "fable miniaudit · guest OS", "image + disk-encryption posture reviewed · this measurement", audit.verdict(url), audit.verdictClass(url))
            else FableAuditBlock(url, "fable miniaudit", "source reviewed for ${ImageRowPresentation.egress(tier)} · this exact build", audit.verdict(url), audit.verdictClass(url))
        }
        // The tag of a tag-pinned image — PLAIN secondary text, never a ValuePill: a tag is a name, not a content hash.
        if (tagPinned) image.tag?.let { caption("tag $it — a name, not a content hash") }
        // Register matches the claim: red = tamper evidence, ORANGE = structurally uncheckable, secondary = plain fact.
        image.note?.let { Text(it, style = captionStyle, color = if (unverified) TeemoonColors.red else if (tagPinned) TeemoonColors.orange else TeemoonColors.secondaryLabel) }
        if (expert) image.digest?.let { ValuePill(it, image.digestFull, onCopy) }
        if (image.kind == ImageEntry.Kind.GUEST_OS) {
            // A recorded, out-of-band digest match — a muted source link, never a peer of the orange provenance links; NOT
            // recorded → an amber caution that states what is true and does not claim "doesn't match latest".
            val src = GuestOSProvenance.source(image.digestFull ?: "")
            if (src != null) Column(Modifier.padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (expert) LinkText("${src.tag} · ${src.commit.take(7)}", src.releaseURL, color = TeemoonColors.secondaryLabel, leading = { BoxGlyph(TeemoonColors.secondaryLabel) })
                caption("recorded match, not a live check: this measured hash equals the image digest that release publishes — reproducible by anyone (download it, compare digest.txt), unlike the per-image provenance links which the app re-derives each session.", TeemoonColors.tertiaryLabel)
            } else Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                TriangleGlyph(TeemoonColors.orange, 11.dp)
                Text("teemoon hasn’t matched this guest-OS build to a published near.ai release — it may be newer than this app’s records, or a build outside the public releases. it can’t confirm this is a known-good image.", style = caption2, color = TeemoonColors.orange)
            }
        }
        // What the attested launch config forbids, live-parsed from the hash-verified inner compose — the soft verified register.
        if (expert && image.configLines.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(TeemoonColors.verified.copy(alpha = 0.06f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SubHeader("in the attested compose")
            image.configLines.forEach { line ->
                val holds = line.state == MeasuredConfig.Line.State.HOLDS
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { CircleMark(ok = holds, color = if (holds) TeemoonColors.verified else TeemoonColors.orange) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(line.title, style = captionStyle.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.label); ProofText(line.detail)
                        if (line.links.isNotEmpty()) Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { line.links.forEach { LinkText(it.title, it.url) } }
                    }
                }
            }
        }
        // Caveated upstream-project pointer: a MUTED affordance in its own register — never one of the provenance links.
        if (expert) image.projectPointer?.let { pp -> Column(Modifier.padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { LinkText("upstream · ${pp.name}", pp.url, color = TeemoonColors.secondaryLabel, leading = { BoxGlyph(TeemoonColors.secondaryLabel) }); caption(pp.status) } }
        if (expert && image.links.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { image.links.forEach { LinkText(it.title, it.url) } }
    }
}

/** An ABSENCE, surfaced (orange) rather than collapsing to a benign flat list: the seam's load-bearing check has nothing to check. */
@Composable
fun NoActionLogNotice() = Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TeemoonColors.orange.copy(alpha = 0.08f)).border(1.dp, TeemoonColors.orange.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(11.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    TriangleGlyph(TeemoonColors.orange)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("recipe not verifiable", style = footnote.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label)
        caption("this enclave exposed no signed action log, so the running engine + proxy recipe can’t be hash-checked on this device — it isn’t pinned by anything you can verify here.")
    }
}

/** The trust seam: a dashed rule with a centred caption — everything below it is pinned by the signed action log. */
@Composable
fun TrustSeamDivider() = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    val tertiary = TeemoonColors.tertiaryLabel
    val rule: @Composable RowScope.() -> Unit = { Canvas(Modifier.weight(1f).height(1.dp)) { drawLine(tertiary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))) } }
    rule(); Text("pinned by the signed action log", style = caption2, color = TeemoonColors.tertiaryLabel); rule()
}

/**
 * The recipe card: the action-log-pinned engine + proxy in a bordered box whose header carries the pinned file_sha256,
 * the yaml source link and the on-device verdict — green verified, the whole card RED on a hash mismatch (never a quiet
 * dropped row), orange when the source was unreachable, a spinner while pending.
 */
@Composable
fun RecipeCardView(recipe: EnclaveGroup.RecipeCard, images: List<ImageEntry>, rung: TrustRung, audit: AuditIndex = AuditIndex.shared, onCopy: (String) -> Unit = {}) {
    val expert = rung == TrustRung.EXPERT
    val isFailure = recipe.verification == ModelLayerVerification.HASH_MISMATCH
    val tint = when (recipe.verification) { ModelLayerVerification.VERIFIED -> TeemoonColors.verified; ModelLayerVerification.HASH_MISMATCH -> TeemoonColors.red; ModelLayerVerification.FETCH_FAILED -> TeemoonColors.orange; null -> TeemoonColors.secondaryLabel }
    val tagPinned = images.count { it.kind == ImageEntry.Kind.TAG_PINNED }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = if (isFailure) 0.08f else 0.05f)).border(if (isFailure) 1.5.dp else 1.dp, tint.copy(alpha = if (isFailure) 0.9f else 0.35f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // header
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { ListDocGlyph(TeemoonColors.secondaryLabel); Text("the running recipe", style = footnote.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label) }
            // "the exact images … pinned" overclaims the moment a tag-pinned member exists (a tag names, it doesn't pin)
            Text(MarkdownInline.annotate(if (tagPinned == 0) "the exact images the signed action log pinned — the engine + proxy that handle your message, plus their telemetry sidecars — checked on this device against its `file_sha256`."
                                          else "the images the signed action log’s recipe declares — checked on this device against its `file_sha256`."), style = caption2, color = TeemoonColors.secondaryLabel)
            val verdict = ImageRowPresentation.recipeVerdict(recipe.verification, tagPinned)
            if (verdict != null) Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                when (recipe.verification) { ModelLayerVerification.VERIFIED -> SealGlyph(tint, ok = true); ModelLayerVerification.HASH_MISMATCH -> OctagonGlyph(tint); else -> TriangleGlyph(tint) }
                Text(verdict, style = caption2.copy(fontWeight = FontWeight.SemiBold), color = tint)
            } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(10.dp), color = TeemoonColors.secondaryLabel, strokeWidth = 1.5.dp); Text("verifying recipe…", style = caption2.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.secondaryLabel)
            }
            ImageRowPresentation.recipeScopeCaption(recipe.verification, images)?.let { caption(it, TeemoonColors.orange) }
            if (expert) recipe.fileSHA?.takeIf { it.isNotEmpty() }?.let { ValuePill("file_sha256 ${it.take(12)}…", it, onCopy) }
            if (expert) recipe.yamlLink?.let { LinkText(it.title, it.url) }
            if (expert) recipe.auditURL?.let { FableAuditBlock(it, "fable miniaudit · recipe", "launch flags, logging & egress reviewed · this exact file", audit.verdict(it), audit.verdictClass(it)) }
        }
        // Two labelled sub-sections INSIDE the one card, partitioned by DATA PATH — never by name. FAIL-SOFT: no handler identified ⇒ the flat list.
        val split = partitionRecipeRows(images, ImageRowPresentation::handlesMessage)
        if (split != null) {
            val (handlers, telemetry) = split
            SubHeader("handles your message")
            handlers.forEach { ImageRowView(it, rung, audit, onCopy) }
            if (telemetry.isNotEmpty()) {
                SubHeader("telemetry sidecars")
                caption(if (telemetry.any { it.kind == ImageEntry.Kind.TAG_PINNED }) "pinned by the same action log and verified the same way — except rows marked ‘pinned by tag’, which the action log names but cannot pin by content — not on your message’s path, but each runs with real privileges inside the enclave: see each row’s tag."
                        else "pinned by the same action log and verified the same way — not on your message’s path, but each runs with real privileges inside the enclave: see each row’s tag.", TeemoonColors.tertiaryLabel)
                telemetry.filterNot(ImageRowPresentation::collapsible).forEach { ImageRowView(it, rung, audit, onCopy) }
                val collapsed = telemetry.filter(ImageRowPresentation::collapsible)
                if (collapsed.isNotEmpty()) caption("${collapsed.size} more pinned by tag only — not individually verifiable here: ${collapsed.joinToString(", ") { it.name }}", TeemoonColors.orange)
            }
        } else images.forEach { ImageRowView(it, rung, audit, onCopy) }
        // A mismatch drops the inner compose, so the card can be image-less. Say so — an empty red card must not read as "nothing to see."
        if (images.isEmpty() && isFailure) caption("recipe contents unavailable — the fetched file didn’t match the pinned hash, so its images aren’t shown.")
    }
}

@Composable
fun EnclaveGroupView(group: EnclaveGroup, rung: TrustRung, audit: AuditIndex = AuditIndex.shared, onCopy: (String) -> Unit = {}) {
    val expert = rung == TrustRung.EXPERT
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TeemoonColors.groupedCard).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.role, style = footnote.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.label)
            if (group.primary) CapsuleTag("primary · own quote", TeemoonColors.verified)
            Spacer(Modifier.weight(1f))
            // Tri-state: a FAILED quote must show a red counter-signal, not silently drop the seal.
            when (group.quoteVerified) {
                true -> SealGlyph(TeemoonColors.verified, ok = true)
                false -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) { SealGlyph(TeemoonColors.red, ok = false); Text("quote failed", style = caption2.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.red) }
                null -> Unit
            }
        }
        Text(group.binding, style = captionStyle, color = TeemoonColors.secondaryLabel)
        group.deployment?.let { caption(it, TeemoonColors.tertiaryLabel) }
        if (expert) group.mrConfig?.let { ValuePill("mr_config ${it.take(12)}…", it, onCopy) }
        // Recipe IA (model enclave only): the images split at a dashed trust seam. The seam + recipe are load-bearing — NEVER
        // silently dropped; only a genuine ABSENCE of any action-log pin gets the explicit "not verifiable" notice.
        val recipe = group.recipe
        if (recipe != null) {
            val measured = group.images.filter { !it.isRecipe }; val recipeImages = group.images.filter { it.isRecipe }
            measured.forEach { ImageRowView(it, rung, audit, onCopy) }
            if (measured.isNotEmpty()) TrustSeamDivider()
            if (recipeImages.isNotEmpty() || recipe.fileSHA != null || recipe.verification != null) RecipeCardView(recipe, recipeImages, rung, audit, onCopy) else NoActionLogNotice()
        } else group.images.forEach { ImageRowView(it, rung, audit, onCopy) }
        // The one caption stating the counterpart of the role tags — rendered even (especially) when no image is tagged.
        group.plaintextCaption?.let { Text(it, style = captionStyle, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(top = 2.dp)) }
        if (group.images.any { it.configLines.isNotEmpty() }) caption(ExpertSections.BINDING_NOTE, TeemoonColors.tertiaryLabel)
        group.measurementCaption?.let { Text(proofText(it, TeemoonColors.label, TeemoonColors.tertiaryLabel), style = caption2) }
        if (expert && group.egressFindings.isNotEmpty()) NodeEgressSection(group.egressFindings)
        if (expert) group.configAuditURL?.let { FableAuditBlock(it, "fable miniaudit · deployment config", "allowed_envs, egress & telemetry reviewed · this measurement", audit.verdict(it), audit.verdictClass(it)) }
    }
}
