// Renders a TrustSnapshot: the hero verdict, then the everyday chain and
// pillars or the expert evidence — what you still trust, and the self-verify
// script. (The documented verification run itself is VerificationRun.kt's
// data; this screen reads the snapshot built from it, never the run.) It composes no trust language of its own (CONTRIBUTING.md:
// trust copy is data). Ports the shape of TrustLadderView / VerificationRunView.
package ai.teemoon.app

import ai.teemoon.confidential.EncryptedModelCTA
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import ai.teemoon.providers.WhereLocality
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp

/** near.ai's TEE green (`teeVerified`), amber for advisories, red for breaks; the rest defers to the theme. */
@Composable
fun TrustTone.color(): Color = when (this) {
    TrustTone.OK -> TeemoonColors.verified          // TEE-verified: the adaptive green, never the tint
    TrustTone.ADVISORY -> TeemoonColors.orange
    TrustTone.FAILED -> TeemoonColors.red
    TrustTone.VERIFYING, TrustTone.NEUTRAL -> TeemoonColors.secondaryLabel
}

enum class TrustRung(val label: String) { EVERYDAY("everyday"), EXPERT("expert"); val showsValues get() = this == EXPERT; val showsLinks get() = this == EXPERT }

@Composable
fun TrustScreen(
    snapshot: TrustSnapshot,
    onBack: () -> Unit,
    onReverify: () -> Unit,
    /** Copies the self-verify script; null hides the button. */
    onCopyScript: ((String) -> Unit)? = null,
    /**
     * The way forward from a chat that cannot be attested (TrustLadderView+None's last button).
     *
     * Resolved by the caller, because it depends on whether a USABLE near.ai key exists on this phone and only the
     * app state knows. `:confidential`'s EncryptedModelCTA has carried both states and the rule that a keyless setup
     * must not open the picker since 2026-09-12 — and nothing rendered it until now, so `offersEncryptedModels` was
     * computed on every not-attestable screen and read by no one. Exactly the 2026-09-20 defect shape: the policy
     * was right and was never reached.
     */
    encryptedModelCTA: EncryptedModelCTA? = null,
    onEncryptedModelCTA: ((EncryptedModelCTA) -> Unit)? = null,
    initialRung: TrustRung = TrustRung.EVERYDAY,
) {
    // TrustLadderView.body (81107be, unchanged on main): hero, the rung picker, the chain, then known code / hardware /
    // transport / disclosed limits / residual trust / re-verify — the sections read the rung themselves. Nothing else:
    // the everyday rung is the chain and "what you still trust", and the sheet opens no second sheet.
    var rung by rememberSaveable { mutableStateOf(initialRung) }
    // the sheet's grouped ground (elevated in dark), not the window's black — black on black erased the sheet's edges
    Column(Modifier.fillMaxSize().background(TeemoonColors.groupedBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        NavBar("who can read this?", leading = { CircleButton(Icons.Filled.Close, "close", onBack, tint = TeemoonColors.tint) })
        // the bar is pinned; the ladder scrolls under it (a sheet's toolbar on iOS)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {   // the hero sits 30pt under the bar on the tag
        val none = snapshot.none
        if (none != null) {
            NoneView(none, rung, if (none.offersEncryptedModels) encryptedModelCTA else null, onEncryptedModelCTA)
        } else {
            Hero(snapshot, onReverify)
            RungPicker(rung) { rung = it }
            // chainSection: the headline, then the rail
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("how your words reach ${snapshot.modelName} and come back — with no one else able to read them", style = MaterialTheme.typography.titleSmall)
                val nodes = if (rung == TrustRung.EVERYDAY) TrustChain.everyday(snapshot.verdict.everydayClaims) else snapshot.expertNodes
                ChainRail(nodes, rung, onJump = { rung = TrustRung.EXPERT })
            }
            val x = snapshot.expert
            if (rung == TrustRung.EXPERT) {
                // known code: the enclave groups — every image the quote measures, traced to public source
                Section("known code", x.knownCodeIntro) {
                    x.enclaveGroups.forEach { EnclaveGroupView(it, rung, onCopy = { v -> onCopyScript?.invoke(v) }) }
                    x.knownCodeEmpty?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel) }
                    x.auditStamp?.let { Text(it.text, style = MaterialTheme.typography.labelSmall, color = if (it.stale) TrustTone.ADVISORY.color() else TeemoonColors.secondaryLabel) }
                }
                if (x.hardware.isNotEmpty()) EvidenceCard("genuine hardware", x.hardwareSubtitle) { x.hardware.forEach { EvidenceRowView(it) } }
                x.transport?.let { EvidenceCard("sealed transport", ExpertSections.TRANSPORT_SUBTITLE) { EvidenceRowView(it) } }
                EvidenceCard("disclosed limits", ExpertSections.LIMITS_SUBTITLE) {
                    x.limits.forEach { (title, detail) -> LimitRow(title, detail) }
                }
                EvidenceCard("what you still trust", "verification shrinks what you trust — it can't take it to zero. this is the part no proof removes.") {
                    x.residualExpert.forEach { (icon, text) -> TrustRow(icon, text) }
                }
                ReverifySection(snapshot, onCopyScript)
            } else {
                // No pillar cards here. iOS drew the three pillars (8df024e) and replaced them with this chain in the
                // trust-ladder redesign (6e7112e, 2026-07-18) — before the port began; the Android screen was built
                // over AttestationSummary.pillars, which iOS keeps only as data, and rendered them under the chain for
                // three weeks (found 2026-09-24 in the first side-by-side capture).
                EvidenceCard("what you still trust", "the one part no proof can remove.") {
                    TrustRow("shield.lefthalf.filled", x.residualEveryday)
                }
            }
        }
        }
        // TrustLadderView: `.padding(20)` on the whole document, and the scroll view's safe area below it
        SheetScrollEnd(extra = 12.dp)
            }
    }
}

/** RungPicker: "show me the proof as…" over the everyday / expert segments. */
@Composable
private fun RungPicker(rung: TrustRung, onPick: (TrustRung) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("show me the proof as…", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel)
        SegmentedPicker(TrustRung.entries.map { it.label }, TrustRung.entries.indexOf(rung), { onPick(TrustRung.entries[it]) })
    }
}

/** TrustLadderView+Hero.hero: the 40pt glyph in the hero colour, the title, the subtitle, the stamp line with re-verify. */
@Composable
private fun Hero(s: TrustSnapshot, onReverify: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HeroGlyph(s.summary.headerIcon, s.heroTone.color())
        Text(s.verdict.heroTitle, style = MaterialTheme.typography.headlineLarge)   // TrustLadderView+Hero: .title.weight(.bold), 28pt
        Text(s.verdict.heroSubtitle, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            if (s.metaLine.isNotEmpty()) Text(s.metaLine, style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel)
            Row(Modifier.clickable(onClick = onReverify).semantics(mergeDescendants = true) { contentDescription = A11yLabels.RE_RUN_VERIFICATION },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Refresh, null, tint = TeemoonColors.tint, modifier = Modifier.size(14.dp))
                Text("re-verify", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.tint)
            }
        }
    }
}

/**
 * The hero glyph, from the summary's SF symbol name (AttestationSummary.headerIcon): a shield for the sealed
 * states, a laptop with a lock for a home server, a phone for on-device — drawn, 40pt, in the hero colour.
 */
@Composable
fun HeroGlyph(symbol: String, color: Color, modifier: Modifier = Modifier) {
    val mark = when {
        "xmark" in symbol -> "×"; "exclamationmark" in symbol -> "!"; "questionmark" in symbol -> "?"; "checkmark" in symbol -> "✓"; "lock" in symbol -> "🔒"; else -> ""
    }
    // SF's laptopcomputer at 40pt is a wide glyph (~56pt across on the tag's capture); the others are square
    val wide = "laptop" in symbol || "desktopcomputer" in symbol
    Box(modifier.size(if (wide) 60.dp else 48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (wide) 60.dp else 44.dp)) {
            val w = size.width; val h = size.height
            if ("shield" in symbol) {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.04f); lineTo(w * 0.92f, h * 0.2f)
                    cubicTo(w * 0.92f, h * 0.62f, w * 0.72f, h * 0.86f, w * 0.5f, h * 0.98f)
                    cubicTo(w * 0.28f, h * 0.86f, w * 0.08f, h * 0.62f, w * 0.08f, h * 0.2f); close()
                }
                drawPath(path, color)
            } else if ("laptop" in symbol || "desktopcomputer" in symbol) {
                drawRoundRect(color, topLeft = Offset(w * 0.12f, h * 0.18f), size = Size(w * 0.76f, h * 0.5f), cornerRadius = CornerRadius(w * 0.06f), style = Stroke(w * 0.07f))
                drawRect(color, topLeft = Offset(w * 0.02f, h * 0.72f), size = Size(w * 0.96f, h * 0.07f))
            } else if ("iphone" in symbol || "phone" in symbol) {
                drawRoundRect(color, topLeft = Offset(w * 0.28f, h * 0.04f), size = Size(w * 0.44f, h * 0.92f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(w * 0.07f))
            } else {
                drawCircle(color, radius = w * 0.46f, style = Stroke(w * 0.07f))
            }
        }
        if (mark.isNotEmpty()) Text(mark, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = if ("shield" in symbol) TeemoonColors.groupedBackground else color, modifier = Modifier.padding(top = if ("laptop" in symbol) 0.dp else 2.dp))
    }
}

/** SF `lock.open` at 40pt — the body with the shackle swung open to one side. */
@Composable
private fun OpenLockGlyph(color: Color) {
    Canvas(Modifier.size(44.dp)) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.46f), size = Size(w * 0.58f, h * 0.44f), cornerRadius = CornerRadius(w * 0.1f))
        drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.5f, h * 0.08f), size = Size(w * 0.42f, h * 0.44f), style = stroke)
        drawLine(color, Offset(w * 0.92f, h * 0.3f), Offset(w * 0.92f, h * 0.46f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    }
}

/** ChainRailView: each node on a spine — the status circle, then a 2pt segment to the next node (dashed red after a break). */
@Composable
private fun ChainRail(nodes: List<ChainNode>, rung: TrustRung, onJump: (ChainNode) -> Unit) {
    val uri = LocalUriHandler.current
    Column {
        nodes.forEachIndexed { index, node ->
            val last = index == nodes.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(26.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusIcon(node)
                    if (!last) {
                        val segment = when {
                            node.dimmed -> TeemoonColors.separator
                            node.status == ChainNode.Status.OK || node.status == ChainNode.Status.ORIGIN || node.status == ChainNode.Status.INFO -> TeemoonColors.verified.copy(alpha = 0.55f)
                            node.status == ChainNode.Status.ALERT -> TeemoonColors.orange.copy(alpha = 0.55f)
                            node.status == ChainNode.Status.FAIL -> TeemoonColors.red
                            else -> TeemoonColors.separator
                        }
                        Canvas(Modifier.width(2.dp).weight(1f)) {
                            if (node.status == ChainNode.Status.FAIL) drawLine(segment, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                            else drawRect(segment)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = if (last) 2.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val title = if (rung == TrustRung.EVERYDAY) node.everydayTitle ?: node.title else node.title
                    val detail = if (rung == TrustRung.EVERYDAY) node.everydayDetail ?: node.detail else node.detail
                    val titleColor = when { node.dimmed -> TeemoonColors.secondaryLabel; node.status == ChainNode.Status.FAIL -> TeemoonColors.red; node.status == ChainNode.Status.ALERT -> TeemoonColors.orange; else -> TeemoonColors.label }
                    Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = titleColor)
                    Text(proofText(detail, tokenColor = TeemoonColors.label, proseColor = TeemoonColors.secondaryLabel), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel.copy(alpha = if (node.dimmed) 0.55f else 1f))
                    if (rung.showsValues) node.value?.let { ValuePill(it, node.copyValue) }
                    val showJump = node.jumpTitle != null && (rung.showsLinks || node.jumpAtEveryday)
                    if (showJump || (rung.showsLinks && node.links.isNotEmpty())) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 2.dp)) {
                        if (showJump) Text("${node.jumpTitle} ↓", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.tint, modifier = Modifier.clickable { onJump(node) })
                        if (rung.showsLinks) node.links.forEach { l -> Text("${l.title} ↗", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.tint, modifier = Modifier.clickable { runCatching { uri.openUri(l.url) } }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(node: ChainNode) {
    val size = 26.dp
    when (node.status) {
        ChainNode.Status.ORIGIN -> Box(Modifier.size(size).clip(CircleShape).background(TeemoonColors.fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = TeemoonColors.secondaryLabel, modifier = Modifier.size(12.dp))
        }
        ChainNode.Status.OK -> FilledCircleGlyph("✓", if (node.dimmed) TeemoonColors.secondaryLabel else TeemoonColors.verified)
        ChainNode.Status.ALERT -> Text("⚠", color = TeemoonColors.orange, style = MaterialTheme.typography.titleMedium)
        ChainNode.Status.INFO -> FilledCircleGlyph("i", TeemoonColors.secondaryLabel)
        ChainNode.Status.FAIL -> FilledCircleGlyph("×", TeemoonColors.red)
        ChainNode.Status.PENDING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TeemoonColors.secondaryLabel)
    }
}

/** checkmark.circle.fill and friends: a filled disc with the mark in the ground colour. */
@Composable
fun FilledCircleGlyph(mark: String, color: Color, size: Dp = 22.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(mark, color = TeemoonColors.groupedBackground, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/** limitRow: a minus in a circle, the title, the detail. */
@Composable
private fun LimitRow(title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).border(1.5.dp, TeemoonColors.secondaryLabel, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(width = 8.dp, height = 1.5.dp).background(TeemoonColors.secondaryLabel)) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun TrustRow(icon: String, text: String) {
    // trustRow: the symbol in tertiary at a fixed 18pt column, the line in secondary, `**bold**` honoured as in Text(.init()).
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        val tint = TeemoonColors.tertiaryLabel
        Box(Modifier.width(18.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            when (icon) {
                "eye" -> EyeGlyph(tint = tint, sizeDp = 16.dp)
                "chevron.left.forwardslash.chevron.right" -> CodeGlyph(tint, 16.dp)
                "cpu" -> CpuGlyph(tint, 16.dp)
                else -> HalfShieldGlyph(tint, 16.dp)
            }
        }
        val parts = text.split("**")
        Text(buildAnnotatedString {
            parts.forEachIndexed { i, part -> if (i % 2 == 1) with(this) { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(part); pop() } else append(part) }
        }, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
    }
}

@Composable
internal fun ReverifySection(s: TrustSnapshot, onCopyScript: ((String) -> Unit)?) {
    // TrustLadderView+Sections.reverifySection: re-run the published checks yourself, with the generated script inline.
    // Expert-only — everyday stays calm reassurance, and the "show me the proof as… expert" control is the path here,
    // so verifiability is offered, not hidden. (The report export and the cloud-verifier link live in
    // VerificationRunView, which iOS's ladder does not present.)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("re-check the live service yourself", style = MaterialTheme.typography.titleSmall)
            Text("copy this into your terminal to re-verify on your own machine, with your own fresh nonces.", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel)
        }
        s.selfVerifyScript?.let { script -> SelfVerifyScriptCard(script, onCopy = { onCopyScript?.invoke(it) }) }
    }
}

@Composable
private fun NoneView(
    n: NoneScreen,
    rung: TrustRung = TrustRung.EVERYDAY,
    cta: EncryptedModelCTA? = null,
    onCTA: ((EncryptedModelCTA) -> Unit)? = null,
) {
    // TrustLadderView+None: lock.iphone / lock.laptopcomputer at 40pt in teeVerified (lock.open in secondary for an
    // unsealed cloud model), the title at title size, the lead, then the rows with checkmark.circle.fill / minus.circle.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (n.heroSymbol) {
            "lock.iphone" -> LocalityGlyph(WhereLocality.PHONE, TeemoonColors.verified, sizeDp = 44.dp)
            // `lock.laptopcomputer` at 40pt semibold draws ~74pt wide
            "lock.laptopcomputer" -> LaptopLockGlyph(TeemoonColors.verified, sizeDp = 76.dp)
            else -> OpenLockGlyph(TeemoonColors.secondaryLabel)
        }
        Text(n.heroTitle, style = MaterialTheme.typography.headlineLarge)   // TrustLadderView+None: .title.weight(.bold), 28pt
        Text(n.heroLead, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel)
    }
    Section("what this means", "the whole picture — nothing hidden.") {
        n.rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.positive) FilledCircleGlyph("✓", TeemoonColors.verified)
                else Box(Modifier.size(22.dp).border(1.5.dp, TeemoonColors.secondaryLabel, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(width = 9.dp, height = 1.5.dp).background(TeemoonColors.secondaryLabel)) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    Text(r.detail, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                }
            }
        }
    }
    // Expert: the technical reason there is nothing to attest. Everyday readers are told what it MEANS above;
    // this is the same fact in the vocabulary someone would use to check it.
    if (rung == TrustRung.EXPERT) {
        EvidenceCard("why there's nothing to attest", "the technical reason, for the record.") {
            NoneReasons.ROWS.forEach { (title, detail) -> LimitRow(title, detail) }
        }
    }
    n.footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel) }
    // The constructive way out. Low-key and last, after the explanation — it is an offer, not a warning.
    if (cta != null && onCTA != null) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TeemoonColors.verified.copy(alpha = 0.14f))
                .clickable { onCTA(cta) }.padding(14.dp).testTag("trust.encryptedModelCTA"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldCheckGlyph(TeemoonColors.verified)
                Text(cta.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TeemoonColors.verified, modifier = Modifier.weight(1f))
                Text("→", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.verified)
            }
            Text(cta.detail, style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel)
        }
    }
}

/** `checkmark.shield.fill`: a filled shield with a check cut out of it. */
@Composable
private fun ShieldCheckGlyph(color: Color, sizeDp: Dp = 18.dp) {
    val ground = TeemoonColors.groupedBackground   // read in composition: a DrawScope is not @Composable
    Canvas(Modifier.size(sizeDp)) {
    val w = size.width; val h = size.height
    val shield = Path().apply {
        moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.90f, h * 0.22f)
        lineTo(w * 0.90f, h * 0.52f); cubicTo(w * 0.90f, h * 0.78f, w * 0.72f, h * 0.90f, w * 0.5f, h * 0.96f)
        cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.78f, w * 0.10f, h * 0.52f)
        lineTo(w * 0.10f, h * 0.22f); close()
    }
    drawPath(shield, color)
    // the check, drawn in the ground colour so the shield reads as filled rather than outlined
    val tick = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
    drawLine(ground, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.44f, h * 0.64f), strokeWidth = tick.width, cap = tick.cap)
    drawLine(ground, Offset(w * 0.44f, h * 0.64f), Offset(w * 0.71f, h * 0.36f), strokeWidth = tick.width, cap = tick.cap)
    }
}
