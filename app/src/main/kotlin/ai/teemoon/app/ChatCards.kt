// ports: teemoon/Views/Chat/ErrorMessageView.swift @ v1.0.4, teemoon/Views/Chat/RequestDebugView.swift @ v1.0.1 (the card),
//        teemoon/Views/Chat/DebugComponents.swift @ v1.0.1, teemoon/Views/Chat/GroundingSourcesView.swift @ v1.0.1,
//        teemoon/Views/Chat/GenerationActivityChipView.swift @ 4510788 (+ the provider wait of 9e090f8, the load line's loadingModel of afc4327), teemoon/Views/Chat/WebSearchChip.swift @ v1.0.1,
//        teemoon/Views/Chat/ChatView.swift @ v1.0.4 (webSearchState), teemoon/Views/Chat/StreamingMessageView.swift @ v1.0.1 (chipState)
//
// The cards and chips that sit in the transcript. Every string they show is
// either a value's own copy (LlmError.userMessage, the console recovery) or a
// pure function here with a test; the composables only lay it out.
package ai.teemoon.app

import androidx.compose.animation.togetherWith
import ai.teemoon.inference.ProviderWait
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.ToolCallDebugFormat
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import ai.teemoon.providers.ModelCatalog
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

// MARK: - Pure

/** ErrorMessageView's derived text. */
object ErrorCardText {
    fun sourceLabel(error: LlmError): String = when (val s = error.source) {
        is LlmError.Source.Provider -> s.name
        LlmError.Source.BraveGrounding -> "brave search"
    }

    /** The one action a status earns: a key problem goes to the key page, an empty balance to billing. Everything else has no button. */
    fun consoleAction(error: LlmError, recovery: Pair<String, String>?): Pair<String, String>? {
        val (url, name) = recovery ?: return null
        return when (error.httpStatus) {
            401 -> "manage api key at $name" to url
            402 -> "add credits at $name" to url
            else -> null
        }
    }

    fun messageCount(error: LlmError): Int =
        error.messageHistory?.let { runCatching { (Json.parseToJsonElement(it) as? JsonArray)?.size }.getOrNull() } ?: 0

    /** The copied form: redacted URL and headers, everything else verbatim except a stored key wherever it appears. */
    fun debugText(error: LlmError): String {
        val parts = ArrayList<String>()
        parts += "=== ${sourceLabel(error)} Error ==="
        error.httpStatus?.let { parts += "HTTP $it" }
        parts += error.userMessage
        error.url?.let { parts += "\n--- URL ---\n${DebugHeaderRedaction.copyURLString(it)}" }
        error.requestHeaders?.takeIf { it.isNotEmpty() }?.let { parts += "\n--- Headers ---\n${DebugHeaderRedaction.copyHeaderBlock(it)}" }
        error.requestBodyJson?.let { parts += "\n--- Request Body ---\n${ai.teemoon.app.imaging.ImageBodyText.summarize(it)}" }
        error.messageHistory?.let { parts += "\n--- Message History ---\n$it" }
        error.responseBody?.takeIf { it.isNotEmpty() }?.let { parts += "\n--- Response ---\n$it" }
        error.underlyingError?.let { parts += "\n--- Underlying Error ---\n$it" }
        // a provider can echo the key in its body or message: any stored key left anywhere is masked here
        return DebugHeaderRedaction.scrubStoredSecrets(parts.joinToString("\n"))
    }
}

/**
 * NOTHING EQUIPPED means grounding cannot run whatever the setting says, and
 * this is the only state that dims. It is what frees the dim to mean "you
 * cannot tap this" — while anything else is using it, the signal is spent.
 */
enum class WebSearchChipState { UNCONFIGURED, ON, OFF, DISABLED;
    /** A Brave key exists (on or off): the transcript's offer cards go (TranscriptItemsPolicy, iOS 008cc19). */
    val searchConfigured: Boolean get() = this == ON || this == OFF
}

/**
 * IMAGES.md §4b: the paperclip offers the second download (instead of saying "not yet") when the phone's model is an NPU
 * bundle alone, and not while that download is already running — the chip shows its progress then.
 */
fun imageDownloadOffered(needsSecondDownload: Boolean, downloading: Boolean): Boolean = needsSecondDownload && !downloading

fun webSearchChipState(hasProvider: Boolean, hasKey: Boolean, groundingEnabled: Boolean): WebSearchChipState {
    if (!hasProvider) return WebSearchChipState.DISABLED
    if (!hasKey) return WebSearchChipState.UNCONFIGURED
    return if (groundingEnabled) WebSearchChipState.ON else WebSearchChipState.OFF
}

/**
 * StreamingMessageView's TRAILING chip: once a reply has text on screen, the leading chip is gone, and nothing else says
 * teemoon is still working while the model does something the screen is not showing. The rule is pure so it is testable:
 * text on screen, plus some form of unshown work. These are the COMPLETE inputs — scroll state is not one of them:
 * hiding a progress indicator from the one reader who scrolled away to wait is backwards (iOS removed it in 047e4fc,
 * re-added it by accident in 47ef09b, and the code and its comment disagreed for four days).
 *
 * `awaitingModel` is the load-bearing input. A model that writes a preamble BEFORE calling a tool leaves text on
 * screen and then a follow-up round trip plus prefill with nothing moving — measured on iOS: DeepSeek V4 Flash, a
 * 3.0 s blind window per tool round; a second web-search round on a real generation sat still for 13.7 s.
 */
object TrailingActivityChip {
    fun visible(hasText: Boolean, stalled: Boolean, executingTools: Boolean, awaitingModel: Boolean): Boolean =
        hasText && (stalled || executingTools || awaitingModel)

}

/**
 * The stall half of [TrailingActivityChip.visible]: when a reply that has text PAUSES, and for how long the chip stays.
 *
 * WAS a 1 s timer re-armed by every text change (iOS's stallDelay; before that 3 s). A model that pauses mid-turn after
 * text has arrived is covered by nothing else — a 93 s DeepSeek generation went static at 72.36 s and the chip returned
 * at 75.43 s; sampled 507 times, normal static periods were median 0.18 s, p99 0.20 s, max 0.37 s. That held for
 * providers that stream tokens. It did not for one that streams CHUNKS slowly: near.ai at ~10 tok/s sends 24-character
 * chunks 0.5–1.3 s apart, and every gap over 1 s put a breathing "thinking… Ns" row under the reply and took it away
 * again — every few seconds, for the whole reply (the maintainer, 2026-10-25). A false positive is not benign when it repeats.
 *
 * So a pause earns nothing until [SHOW_AFTER_MS] of silence — past any chunk cadence, well inside the pauses it exists
 * for (tool rounds show the chip at once anyway, through executingTools / awaitingModel). Once shown it stays at least
 * [MIN_SHOWN_MS], and goes [FLOWING_MS] after text resumes, so it never blinks. Pure, so it is tested against arrival
 * times (StallChipTest); the view feeds it text changes and a clock.
 */
data class StallChip(val shownAtMs: Long? = null, val resumedAtMs: Long? = null) {
    val visible: Boolean get() = shownAtMs != null

    /** Text changed at [now]. */
    fun onText(now: Long): StallChip = if (shownAtMs != null && resumedAtMs == null) copy(resumedAtMs = now) else this

    /** The clock reads [now]; the text last changed at [lastTextAt]. */
    fun tick(now: Long, lastTextAt: Long): StallChip = when {
        shownAtMs == null -> if (now - lastTextAt >= SHOW_AFTER_MS) StallChip(shownAtMs = now) else this
        // stalled again after a chunk or two: stay up rather than blink off and back on
        resumedAtMs != null && now - lastTextAt >= SHOW_AFTER_MS -> copy(resumedAtMs = null)
        // hidden at max(shownAt + 1 s, resumedAt + 0.5 s) — after one chunk too, as iOS's StallIndicator does
        resumedAtMs != null && now - shownAtMs >= MIN_SHOWN_MS && now - resumedAtMs >= FLOWING_MS -> StallChip()
        else -> this
    }

    companion object {
        const val SHOW_AFTER_MS = 3_000L
        const val MIN_SHOWN_MS = 1_000L
        const val FLOWING_MS = 500L
        /** How often the view reads the clock while a reply streams; state is written only when it changes. */
        const val TICK_MS = 100L
    }
}

sealed interface ActivityState {
    data object Thinking : ActivityState
    /** Think Harder, while the old reply waits dimmed above. */
    data object ThinkingHarder : ActivityState
    data object Searching : ActivityState
    data class SourcesFound(val count: Int) : ActivityState
    /** Shown once one request has had no response headers for [ProviderWait.THRESHOLD_MS]. */
    data class WaitingForProvider(val providerName: String) : ActivityState
    /** An on-device engine loading for this turn: the load line's own words (EngineLoadLine), in place of thinking. */
    data class LoadingModel(val line: String) : ActivityState
    /** The attestation gate holds the send: nothing has left yet (S32; iOS GenerationActivityChipView @ 59b9280e). */
    data object WaitingForVerification : ActivityState

    companion object {
        /** StreamingMessageView.chipState: a running tool wins, then a request past the wait threshold, then thinking. */
        fun of(isExecutingTools: Boolean, sourceCount: Int, wait: ProviderWait? = null, nowMs: Long = 0, thinkingHarder: Boolean = false, loadLine: String? = null,
               gateWaiting: Boolean = false): ActivityState = when {
            isExecutingTools && sourceCount > 0 -> SourcesFound(sourceCount)
            isExecutingTools -> Searching
            // ConversationView.pendingChip: the engine's load line stands in for thinking (and thinking harder) while it shows
            loadLine != null -> LoadingModel(loadLine)
            // S32: while prepareTurn holds the send nothing has left, so it never reads thinking (formal/ ThinkingOnlyAfterSend)
            gateWaiting -> WaitingForVerification
            wait?.shownSeconds(nowMs) != null -> WaitingForProvider(wait.providerName)
            thinkingHarder -> ThinkingHarder
            else -> Thinking
        }
    }
}

/**
 * What the reply placeholder says before any text arrives (iOS PendingReplyPhase @ 59b9280e). Nothing has been sent while
 * the gate waits, so it never reads thinking then (formal/ ThinkingOnlyAfterSend). [ActivityState.of] draws it.
 */
enum class PendingReplyPhase {
    WAITING_FOR_VERIFICATION, THINKING, THINKING_HARDER, LOADING_MODEL;

    companion object {
        /** [gateWaiting]: prepareTurn is holding this turn's send. [loadLine]: an on-device engine load (EngineLoadLine). */
        fun of(gateWaiting: Boolean, loadLine: String?, thinkingHarder: Boolean): PendingReplyPhase = when {
            loadLine != null -> LOADING_MODEL
            gateWaiting -> WAITING_FOR_VERIFICATION
            thinkingHarder -> THINKING_HARDER
            else -> THINKING
        }
    }
}

/** The approved words (the maintainer 2026-11-07T22:37:08Z; the ellipsis character 22:46Z, as every chip label). */
const val WAITING_FOR_VERIFICATION_LABEL = "waiting for verification\u2026"

fun activityLabel(state: ActivityState, elapsedSeconds: Double?): String = when (state) {
    // the ellipsis character (the maintainer 2026-11-07T22:46Z, iOS S32): every chip label, the thinking header and the provider wait
    ActivityState.Thinking -> if (elapsedSeconds != null && elapsedSeconds >= 1) "thinking\u2026 ${elapsedSeconds.toInt()}s" else "thinking\u2026"
    ActivityState.ThinkingHarder -> if (elapsedSeconds != null && elapsedSeconds >= 1) "thinking harder\u2026 ${elapsedSeconds.toInt()}s" else "thinking harder\u2026"
    ActivityState.Searching -> "searching\u2026"
    is ActivityState.SourcesFound -> "${state.count} source${if (state.count == 1) "" else "s"}"
    // The counter is the whole turn's (the maintainer), in every state.
    is ActivityState.WaitingForProvider -> ProviderWait.label(state.providerName, (elapsedSeconds ?: 0.0).toInt())
    is ActivityState.LoadingModel -> state.line
    // no clock: the wait is for the checks, not the model
    ActivityState.WaitingForVerification -> WAITING_FOR_VERIFICATION_LABEL
}

fun sourcesChipLabel(count: Int) = "$count source${if (count == 1) "" else "s"}"

/** SourceRowView.pathCrumb: the path, 40 characters at most. */
fun sourcePathCrumb(url: String): String? {
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    if (path.length <= 1) return null
    val trimmed = path.take(40)
    return if (trimmed.length < path.length) "$trimmed…" else trimmed
}

// MARK: - Composables

private val mono = FontFamily.Monospace

@Composable
private fun DebugRowLabel(text: String) = Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * The debug rows' expand control. iOS (`CollapsibleDebugRow`) draws SF Symbols `chevron.right` collapsed /
 * `chevron.down` expanded. Android used a text triangle prefix, which reads as a bullet, not a control. This is the
 * same chevron the thinking disclosure draws (`MessageViews.Chevron`). iOS tints it `.quaternary`, a notch fainter
 * than the label; kept at the label's `onSurfaceVariant` here because the reported problem was that the control was
 * hard to see and not obviously tappable (2026-09-17). A one-notch tint deviation from iOS, flagged for both platforms.
 */
@Composable
private fun DisclosureChevron(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(10.dp)) {
        val w = size.width; val h = size.height; val st = 1.4.dp.toPx(); val cap = StrokeCap.Round
        if (expanded) {
            drawLine(color, Offset(w * 0.2f, h * 0.4f), Offset(w * 0.5f, h * 0.66f), st, cap)
            drawLine(color, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.8f, h * 0.4f), st, cap)
        } else {
            drawLine(color, Offset(w * 0.4f, h * 0.2f), Offset(w * 0.66f, h * 0.5f), st, cap)
            drawLine(color, Offset(w * 0.66f, h * 0.5f), Offset(w * 0.4f, h * 0.8f), st, cap)
        }
    }
}

@Composable
private fun CollapsibleRow(label: String, content: String, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { DisclosureChevron(expanded); DebugRowLabel(label) }
    if (expanded) Text(content, style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
}

@Composable
private fun InlineRow(label: String, value: String) {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        DebugRowLabel(label)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), maxLines = 1, modifier = Modifier.horizontalScroll(rememberScrollState()))
    }
}

/** The request body under E2EE: what went over the wire, and what this device held and never sent in that form. */
@Composable
private fun EncryptedBodyRow(content: String, sealed: String?) {
    var expanded by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DisclosureChevron(expanded); DebugRowLabel("request body"); Text("encrypted", style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.primary)
    }
    if (expanded) Column(Modifier.padding(horizontal = 12.dp)) {
        Row { TextButton(onClick = { full = false }) { Text(if (full) "compact" else "[compact]") }; TextButton(onClick = { full = true }) { Text(if (full) "[full]" else "full") } }
        if (sealed != null) {
            Text("on the wire", style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.primary)
            Text(if (full) sealed else ToolCallDebugFormat.compactRequestBody(sealed), style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), modifier = Modifier.padding(bottom = 8.dp))
            Text("on this device — never sent in this form", style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.tertiary)
            Text(content, style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), modifier = Modifier.padding(bottom = 8.dp))
        } else Text(if (full) content else ToolCallDebugFormat.compactRequestBody(content), style = MaterialTheme.typography.bodySmall.copy(fontFamily = mono), modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun ToolCallRow(label: String, subtitle: String?, rawJson: String?, processedForLLM: String) {
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { DisclosureChevron(expanded); DebugRowLabel(label) }
    subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, end = 12.dp, bottom = 4.dp)) }
    if (expanded) Column(Modifier.padding(start = 12.dp)) {
        rawJson?.let { CollapsibleRow("raw json", it) }
        CollapsibleRow("processed for llm", processedForLLM)
    }
}


/**
 * The developer card header's layouts, the first that fits wins (iOS near-import-request-header, ios 2026-11-13): one line
 * with the id middle-cut between the provider and the status; two lines, the provider and the status over the id; three
 * lines, provider / status / id. The id counts as [ID_MIN] wide for the test, so at default sizes it is one line and the
 * id simply truncates.
 */
enum class RequestHeaderLayout {
    ONE_LINE, TWO_LINES, THREE_LINES;
    companion object {
        val ID_MIN = 60.dp
        fun choose(available: Int, provider: Int, status: Int, gap: Int, idMin: Int): RequestHeaderLayout = when {
            provider + gap + idMin + gap + status <= available -> ONE_LINE
            provider + gap + status <= available -> TWO_LINES
            else -> THREE_LINES
        }
    }
}

/** RequestDebugView: the developer card under a reply. Long-press copies the redacted form. */
@Composable
fun RequestDebugCard(info: LastRequestDebugInfo, onCopy: (String) -> Unit, modifier: Modifier = Modifier,
    /** Test seam: each header token's line count and whether it was cut ("provider", "model", "status", "e2ee", "copy"). */
    onHeaderToken: ((token: String, lines: Int, ellipsized: Boolean) -> Unit)? = null,
    /** Test seam: which of the header's layouts this width took. */
    onHeaderLayout: ((RequestHeaderLayout) -> Unit)? = null) {
    Panel(modifier.testTag("chat.debugCard")) {
        Column {
            // The header both apps agreed (pm 2026-11-13, after the maintainer's Pixel: a long near.ai id broke "HTTP 200" into
            // "HTT / P / 200" and stood "E2EE" one letter a line), in iOS's three layouts (RequestHeaderLayout): the
            // provider and the status tokens keep their own width and one line; the model id is cut in the middle. The full
            // id is in the body and the copied text.
            val tokenStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val providerStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            val copyStyle = MaterialTheme.typography.labelSmall.copy(color = TeemoonColors.tint)
            val model = if (info.modelID.isNotEmpty() && info.url != null) ModelCatalog.displayName(info.modelID) else null
            val status = if (info.url == null) "on-device" else "HTTP 200"
            @Composable fun token(name: String, text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) =
                Text(text, style = style, maxLines = 1, softWrap = false, modifier = modifier,
                    onTextLayout = { r -> onHeaderToken?.invoke(name, r.lineCount, r.hasVisualOverflow) })
            @Composable fun statusGroup() = Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                token("status", status, tokenStyle)
                if (info.isE2EEActive) token("e2ee", "E2EE", tokenStyle.copy(color = MaterialTheme.colorScheme.primary), Modifier.testTag("chat.debugCard.e2ee"))
                token("copy", "  copy", copyStyle, Modifier.clickable { onCopy(RequestDebugText.debugText(info)) })
            }
            @Composable fun modelId(modifier: Modifier) = model?.let { m -> Text(m, style = tokenStyle, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis, modifier = modifier,
                onTextLayout = { r -> onHeaderToken?.invoke("model", r.lineCount, false) }) }
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val measurer = rememberTextMeasurer()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val gap = with(density) { 6.dp.roundToPx() }
                fun w(text: String, style: androidx.compose.ui.text.TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
                val statusWidth = w(status, tokenStyle) + (if (info.isE2EEActive) w("E2EE", tokenStyle) + gap else 0) + gap + w("  copy", copyStyle)
                val layout = RequestHeaderLayout.choose(available = constraints.maxWidth, provider = w("🔧 ${info.providerName}", providerStyle),
                    status = statusWidth, gap = gap, idMin = if (model == null) -gap else with(density) { RequestHeaderLayout.ID_MIN.roundToPx() })
                onHeaderLayout?.invoke(layout)
                when (layout) {
                    RequestHeaderLayout.ONE_LINE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        token("provider", "🔧 ${info.providerName}", providerStyle); modelId(Modifier.weight(1f)); statusGroup()
                    }
                    RequestHeaderLayout.TWO_LINES -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { token("provider", "🔧 ${info.providerName}", providerStyle); Spacer(Modifier.weight(1f).widthIn(min = 6.dp)); statusGroup() }
                        modelId(Modifier)
                    }
                    // the provider alone may be too wide here: it is cut at its end, the status never
                    RequestHeaderLayout.THREE_LINES -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🔧 ${info.providerName}", style = providerStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            onTextLayout = { r -> onHeaderToken?.invoke("provider", r.lineCount, false) })
                        statusGroup(); modelId(Modifier)
                    }
                }
            }
            info.url?.let { InlineRow("url", it) }
            info.cardTimingLine?.let { timing ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                // Timing chain, then effort and cache (the maintainer, card-one-line; iOS 6fdcb43). It WRAPS to a second line
                // rather than truncating; only when two lines are not enough does it scale down, floored at 0.75 of its
                // size, as iOS's `.lineLimit(2)` with `.minimumScaleFactor(0.75)`. (Before: one line that scaled to fit —
                // a long chain was once clipped, near.ai's "… · 11282tok · " losing its tok/s, user-reported 2026-09-23.)
                val statsStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // On-device turns lead with the speed line (decode · prefill · backend), the timings under it — no divider
                // between: one measurement in two rows, at ONE size, so the rows never render at two sizes.
                val lines = listOfNotNull(info.localStatsLine?.let { it to 1 }, timing to 2)
                BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val measurer = rememberTextMeasurer()
                    val maxPx = constraints.maxWidth
                    val size = remember(lines, maxPx) {
                        var sp = statsStyle.fontSize.value
                        val floor = sp * 0.75f
                        while (sp > floor && lines.any { (line, max) -> measurer.measure(line, statsStyle.copy(fontSize = sp.sp), maxLines = max, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxPx)).hasVisualOverflow }) sp -= 0.25f
                        sp.coerceAtLeast(floor).sp
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.forEachIndexed { n, (line, max) ->
                            BasicText(line, style = statsStyle.copy(fontSize = size), maxLines = max, softWrap = max > 1,
                                modifier = if (n == 0 && info.localStatsLine != null) Modifier.testTag("chat.debug.localStats") else Modifier.testTag("chat.debugCard.timing"))
                        }
                    }
                }
            }
            info.promptBudget?.takeIf { it.looksTruncated }?.let { budget ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("⚠ context truncated", style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.tertiary)
                    Text("server evaluated ${budget.evaluated} of ~${budget.sentEstimate} input tokens — it dropped the front of the prompt (system prompt and question first). raise its context window; ollama: OLLAMA_CONTEXT_LENGTH",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.tertiary)
                }
            }
            info.requestHeaders?.takeIf { it.isNotEmpty() }?.let { headers ->
                CollapsibleRow("headers (${headers.size})", headers.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${DebugHeaderRedaction.value(it.value, it.key)}" })
            }
            info.requestBodyJson?.let(ai.teemoon.app.imaging.ImageBodyText::summarize)?.let { if (info.isE2EEActive) EncryptedBodyRow(it, info.sealedBodyJson) else CollapsibleRow("request body", it) }
            info.toolCalls.forEach { tc ->
                ToolCallRow("tool: ${ToolCallDebugFormat.toolCallSignature(tc.name, tc.arguments, tc.result)}", ToolCallDebugFormat.toolCallSubtitle(tc.name, tc.arguments), ToolCallDebugFormat.sourcesJSONString(tc.result), tc.displayResult)
            }
            info.responseBody?.takeIf { it.isNotEmpty() }?.let { CollapsibleRow("response", it) }
        }
    }
}

/** ErrorMessageView: what went wrong, the one console action the status earns, and the request as the developer wants to read it. */
@Composable
fun ErrorCard(error: LlmError, consoleRecovery: Pair<String, String>?, onDisableWebSearch: (() -> Unit)?, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    // ErrorMessageView: the debug surface stroked in orange at 35%, the triangle in orange — not the grey separator
    Panel(modifier.border(0.5.dp, TeemoonColors.orange.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).testTag("chat.error")) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", style = MaterialTheme.typography.labelMedium, color = TeemoonColors.orange)
                Text(ErrorCardText.sourceLabel(error), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                error.httpStatus?.let { Text("http $it", style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCopy(ErrorCardText.debugText(error)) }.semantics { contentDescription = A11yLabels.COPY_DEBUG_INFO })
            }
            HorizontalDivider()
            Text(error.userMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            ErrorCardText.consoleAction(error, consoleRecovery)?.let { (title, url) ->
                TextButton(onClick = { uri.openUri(url) }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("↗ $title") }
                Text(url, style = MaterialTheme.typography.labelSmall.copy(fontFamily = mono), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                HorizontalDivider()
            }
            onDisableWebSearch?.let {
                TextButton(onClick = it, modifier = Modifier.padding(horizontal = 4.dp)) { Text("🔍 this model can't search the web — turn off & retry", color = MaterialTheme.colorScheme.tertiary) }
                HorizontalDivider()
            }
            error.url?.let { InlineRow("url", it) }
            error.requestHeaders?.takeIf { it.isNotEmpty() }?.let { headers -> CollapsibleRow("headers (${headers.size})", headers.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${DebugHeaderRedaction.value(it.value, it.key)}" }) }
            error.requestBodyJson?.let(ai.teemoon.app.imaging.ImageBodyText::summarize)?.let { CollapsibleRow("request body", it) }
            error.messageHistory?.let { val n = ErrorCardText.messageCount(error); CollapsibleRow("message history" + (if (n > 0) " ($n)" else ""), it) }
            error.responseBody?.takeIf { it.isNotEmpty() }?.let { CollapsibleRow("response", it) }
        }
    }
}

/**
 * GenerationActivityChipView: "thinking… 4s" / "searching…" / "3 sources" while the reply has no text yet. It BREATHES —
 * opacity 1.0 ↔ 0.4, easeInOut 0.85 s, repeating — so a long reasoning phase reads as alive rather than stuck (iOS
 * `startBreathing`); the seconds tick once a second from the caller's clock (iOS `TimelineView(.periodic(by: 1))`).
 */
@Composable
fun ActivityChip(state: ActivityState, elapsedSeconds: Double?, modifier: Modifier = Modifier) {
    // rememberInfiniteTransition, the idiomatic form (the warm dot uses it too). Under the Compose test rule an
    // infinite animation runs only while the test drives the clock by hand from BEFORE it starts (the rule's
    // InfiniteAnimationPolicy cancels it under autoAdvance) — theActivityChipTicksAndBreathes does exactly that.
    val breath = rememberInfiniteTransition(label = "chip-breath")
    val alpha by breath.animateFloat(initialValue = 1f, targetValue = 0.4f, label = "chip-alpha",
        animationSpec = Motion.breath())
    // GenerationActivityChipView: `sparkles` while thinking, `brain` while thinking harder, `hourglass` while waiting, `globe`
    // for the search, on the `.fill` capsule.
    val fg = TeemoonColors.secondaryLabel
    TranscriptChip(activityLabel(state, elapsedSeconds), foreground = fg, background = TeemoonColors.fill, modifier = modifier.graphicsLayer { this.alpha = alpha }.testTag("chat.activityChip")) {
        // a new phase's glyph replaces the old one by fading through, not a cut (motion-pass item 6); the label beside
        // it ticks as it always has
        androidx.compose.animation.AnimatedContent(state, contentKey = { it::class }, label = "chip-glyph", transitionSpec = {
            androidx.compose.animation.fadeIn(Motion.axisFadeIn()) togetherWith androidx.compose.animation.fadeOut(Motion.axisFadeOut())
        }) { shown -> when (shown) {
            ActivityState.Thinking -> SparklesGlyph(fg)
            ActivityState.ThinkingHarder -> BrainGlyph(fg, 12.dp)
            is ActivityState.WaitingForProvider -> HourglassGlyph(fg)
            // GenerationActivityChipView: `cpu` while a model loads
            is ActivityState.LoadingModel -> CpuGlyph(fg, 12.dp)
            // iOS `lock.shield`: the checks, not the model
            ActivityState.WaitingForVerification -> LockGlyph(open = false, tint = fg, sizeDp = 12.dp)
            else -> GlobeGlyph(fg)
        } }
    }
}

/**
 * SourceStackChipView + GroundingSourcesView: the "n sources" chip and the sheet it opens — a navigation bar with the
 * inline title "sources" and an xmark close at the leading edge, an inset-grouped list of rows (favicon tile, domain ›
 * path, title, two lines of snippet), each opening its page.
 *
 * The sheet is an [IosSheet] presented from the root through [LocalSourcesPresenter], like every other sheet here.
 * Until 2026-10-02 it was a Material ModalBottomSheet drawn from inside the chip, the one sheet in the app that was:
 * Material chrome, half/full detents instead of iOS's three, no favicon tiles, and the type a size or two large
 * (17/16 against iOS's fixed 15.5/13.5). Nothing snapshotted the sheet, only the chip, so no round caught it.
 */
@Composable
fun SourcesChip(sources: List<GroundingSource>, modifier: Modifier = Modifier) {
    val present = LocalSourcesPresenter.current
    // SourceStackChipView: a white `globe` and caption text on blue at 75%, and the STACK — for two sources a second
    // capsule peeks 6pt out to the right at 45%, for three or more a third at 25% another 6pt out.
    val peek = 6.dp; val n = sources.size
    val blue = TeemoonColors.blue
    Box(modifier.padding(end = if (n >= 3) peek * 2 else if (n >= 2) peek else 0.dp)) {
        if (n >= 3) Box(Modifier.matchParentSize().offset(x = peek * 2).clip(CircleShape).background(blue.copy(alpha = 0.25f)))
        if (n >= 2) Box(Modifier.matchParentSize().offset(x = peek).clip(CircleShape).background(blue.copy(alpha = 0.45f)))
        TranscriptChip(sourcesChipLabel(n), foreground = Color.White, background = blue.copy(alpha = 0.75f), onClick = { present(sources) }, modifier = Modifier.testTag("chat.sourcesChip")) { GlobeGlyph(Color.White) }
    }
}

/** Root's handle for presenting the sources sheet. Outside a root that provides it, a tap is a bug, and says so. */
val LocalSourcesPresenter = staticCompositionLocalOf<(List<GroundingSource>) -> Unit> {
    { error("SourcesChip outside a root that presents the sources sheet") }
}

/**
 * SourceStackChipView.detents(for:): a couple of sources sit at content height (73pt of header, 100pt a row) with
 * medium and large above it, three to six open at medium, seven or more open large. As [IosSheet] tops: fractions of
 * the screen from its top, the opening one first.
 */
fun sourcesSheetTops(count: Int, screenHeightDp: Float): List<Float> = when {
    count <= 2 -> listOf((1f - (73f + 100f * count) / screenHeightDp).coerceAtLeast(SheetDetents.MEDIUM), SheetDetents.MEDIUM, SheetDetents.LARGE)
    count >= 7 -> listOf(SheetDetents.LARGE)
    else -> listOf(SheetDetents.MEDIUM, SheetDetents.LARGE)
}

/** FaviconView's letter: the domain without `www.`/`en.`/`m.` (every occurrence, as `replacingOccurrences`), first character. */
fun faviconGlyph(domain: String): String = domain.replace("www.", "").replace("en.", "").replace("m.", "").take(1).uppercase()

/** FaviconView's tile hue: djb2 over the UTF-8 bytes in a wrapping 64-bit UInt (iOS's `UInt`), mod 360. */
fun faviconHueDegrees(domain: String): Int {
    var hash: ULong = 5381u
    for (b in domain.toByteArray(Charsets.UTF_8)) hash = (hash shl 5) + hash + b.toUByte().toULong()
    return (hash % 360u).toInt()
}

@Composable
private fun FaviconTile(domain: String) {
    val size = 16.dp
    Box(Modifier.size(size).clip(RoundedCornerShape(4.dp))
        .background(Color.hsv(faviconHueDegrees(domain).toFloat(), 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
        Text(faviconGlyph(domain), color = Color.White, style = TextStyle(fontSize = (16 * 0.62).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (16 * 0.62).sp))
    }
}

/** GroundingSourcesView: the sheet's content. Fixed iOS sizes (`.system(size:)`): 12 domain and path, 15.5 title, 13.5 snippet. */
@Composable
fun GroundingSourcesView(sources: List<GroundingSource>, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    val base = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("chat.sourcesSheet")) {
        NavBar(title = "sources", leading = { CircleButton(Icons.Filled.Close, "close", onClose, tint = TeemoonColors.label) })
        InsetGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), dividerInset = 18.dp) {
            sources.forEach { s ->
                Column(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s.url) } }.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FaviconTile(s.domain)
                        Text(s.domain, style = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold), color = TeemoonColors.label.copy(alpha = 0.9f))
                        sourcePathCrumb(s.url)?.let {
                            Text("›", style = base.copy(fontSize = 12.sp, lineHeight = 15.sp), color = TeemoonColors.secondaryLabel.copy(alpha = 0.6f))
                            Text(it, style = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium), color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(s.title.ifEmpty { s.domain }, style = base.copy(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = TeemoonColors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (s.displaySnippet.isNotEmpty()) Text(s.displaySnippet, style = base.copy(fontSize = 13.5.sp, lineHeight = 18.sp), color = TeemoonColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SheetScrollEnd()
    }
}

/** WebSearchChip: the "web" pill beside the composer. Only DISABLED dims. */
@Composable
fun WebSearchChip(state: WebSearchChipState, onClick: () -> Unit, modifier: Modifier = Modifier, iconOnly: Boolean = false) {
    val on = state == WebSearchChipState.ON
    // unconfigured reads secondary in the composer's row and opens setup (COMPOSER_PANEL.md); only DISABLED dims
    Capsule("web", onClick = onClick, enabled = state != WebSearchChipState.DISABLED, glyph = Icons.Filled.Search,
        tint = when (state) { WebSearchChipState.ON -> TeemoonColors.tint; WebSearchChipState.UNCONFIGURED -> TeemoonColors.secondaryLabel; else -> TeemoonColors.label },
        raised = on, background = TeemoonColors.panelChip.takeIf { !on }, labelShown = !iconOnly, height = ComposerSpacing.ROW_ITEM, sidePadding = ComposerSpacing.CHIP_SIDE, modifier = modifier.testTag("chat.webSearchChip"))
}
