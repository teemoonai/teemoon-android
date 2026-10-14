// ports: teemoon/Views/Settings/OllamaModelDownloadView.swift @ v1.0.1 — the sections in its order, its copy, and the
//        start / completion contract; the pull itself is OllamaDownloadCenter (providers)
package ai.teemoon.app

import androidx.compose.ui.platform.testTag
import ai.teemoon.providers.EndpointModelCatalog
import ai.teemoon.providers.OllamaAdapter
import ai.teemoon.providers.OllamaDownloadCenter
import ai.teemoon.providers.OllamaModelShortlist
import ai.teemoon.providers.Provider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.ImeAction

/**
 * Server-side model pull for an Ollama endpoint. RECOMMENDATIONS FIRST, then the field, then browse: the shortlist asks
 * the one question a user can answer about their own machine — how much GPU memory it has — and answers it with a name;
 * paste keeps its place directly under it. The download runs on the SERVER, owned by the app-level center, so this
 * sheet can close (or teemoon can background) and it keeps going, surfacing as a "downloading" row wherever models are
 * listed. And it LEAVES on start: the row this pull now belongs to is on the screen underneath, rendering the same bar
 * with the same cancel — staying here showed the identical download twice (user report: "this screen showing a progress bar
 * seems redundant").
 */
@Composable
fun OllamaDownloadScreen(
    provider: Provider, center: OllamaDownloadCenter, onBack: () -> Unit,
    /** Called with the normalised ref the moment a pull STARTS, so the parent shows it as arriving (equipped, selected). */
    onStarted: (String) -> Unit = {},
    /** Called with the ref when the pull finishes WHILE THIS SHEET IS OPEN — the parent re-fetches and selects. */
    onCompleted: (String) -> Unit = {},
) {
    val baseUrl = provider.openAIBaseUrl ?: provider.endpoint
    var ref by remember { mutableStateOf("") }
    /** The normalised ref this sheet started, so its progress can be tracked. */
    var startedRef by remember { mutableStateOf<String?>(null) }
    /** Set when the pasted text is knowably not a model — see startPull. */
    var refProblem by remember { mutableStateOf<String?>(null) }
    // What the server already has, as /api/tags spells it: the shortlist must not recommend a model the machine has had
    // for weeks, and a re-pull must not look like a first one.
    var installed by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(baseUrl) { installed = (OllamaAdapter.listModels(baseUrl) as? EndpointModelCatalog.ProbeResult.Connected)?.models?.map { it.id }.orEmpty() }
    val downloads by center.downloads.collectAsState()
    val trimmed = ref.trim()
    // The pull this sheet is showing: the one it started, or the one the center already knows for whatever is in the
    // field — closing the sheet mid-pull and reopening it must show the download that is still running.
    val current: OllamaDownloadCenter.Download? = startedRef?.let { s -> downloads.firstOrNull { it.id == s } }
        ?: OllamaAdapter.normalizePullRef(trimmed).takeIf { it.isNotEmpty() }?.let { n -> downloads.firstOrNull { it.id == n } }
    val isPulling = current?.phase == OllamaDownloadCenter.Download.Phase.Active || current?.phase == OllamaDownloadCenter.Download.Phase.Reconnecting
    fun installedID(r: String): String? = if (installed.isEmpty()) null else OllamaAdapter.installedMatch(r, installed)
    val uri = LocalUriHandler.current

    fun startPull() {
        // The mistake this screen invites: its own browse links open ollama.com's SEARCH page and hugging face's model
        // LISTING, so the url in the clipboard is often a search, not a model. Caught here, before anything starts.
        OllamaAdapter.refProblem(ref)?.let { refProblem = it; return }
        refProblem = null
        val normalized = OllamaAdapter.normalizePullRef(ref)
        if (normalized.isEmpty()) return
        center.start(normalized, baseUrl)
        startedRef = normalized
        onStarted(normalized)   // announced at START, not just completion — a dismissed sheet must leave a row behind
        onBack()                // AND LEAVE
    }
    // Finished while the sheet is open → select it and close.
    LaunchedEffect(current?.phase) { if (current?.phase == OllamaDownloadCenter.Download.Phase.Done) startedRef?.let { onCompleted(it); onBack() } }

    GroupedPage(header = { NavBar("download a model", leading = { PillButton(if (isPulling) "close" else "cancel", onBack) },
        trailing = { PillButton("download", onClick = ::startPull, tint = if (trimmed.isEmpty() || isPulling) TeemoonColors.tertiaryLabel else TeemoonColors.tint, enabled = trimmed.isNotEmpty() && !isPulling) }) }, topInset = 6.dp) {

        // shortlistSection — "pick by the memory you can spare"
        if (!isPulling) {
            SectionLabel("pick by the memory you can spare", style = MaterialTheme.typography.titleMedium)
            InsetGroup {
                OllamaModelShortlist.recommended.forEach { m ->
                    val have = installedID(m.tag)
                    // The right edge belongs to the CHECKMARK, and to nothing else: both numbers are metadata about the model, so both sit on its caption line.
                    Row(Modifier.fillMaxWidth().clickable { ref = m.tag }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.tag.lowercase(), style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.label)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (m.isMixtureOfExperts) Text("MoE", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 10.sp), color = TeemoonColors.secondaryLabel,
                                    modifier = Modifier.border(1.dp, TeemoonColors.secondaryLabel.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                                // One metadata run, both figures labelled: two bare numbers in the same unit, one a range, is what made the row ambiguous.
                                // "installed" REPLACES the download size — for a model already on the machine the size describes nothing the user is about to do.
                                Text(if (have == null) "${m.vramLabel} · ${m.downloadLabel} download" else "${m.vramLabel} · installed", style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                            }
                        }
                        if (trimmed == m.tag) Icon(Icons.Filled.Check, "selected", tint = TeemoonColors.tint, modifier = Modifier.width(18.dp).height(18.dp))
                    }
                }
            }
            GroupFooter("vram is what the model wants at q4 — the low end is tight, the high end comfortable. the other figure is the download.")
        }

        // bringYourOwnSection — the two halves of one action, in one box: links first, field last, a footer naming the step between
        SectionLabel("or bring any other model", style = MaterialTheme.typography.titleMedium)
        InsetGroup {
            if (!isPulling) {
                BrowseLink("ollama.com", "curated · tools and vision badges", "https://ollama.com/search") { uri.openUri(it) }
                BrowseLink("hugging face — gguf", "any community quant", "https://huggingface.co/models?library=gguf&sort=trending") { uri.openUri(it) }
            }
            InsetField(ref, { ref = it; refProblem = null }, "paste the link here", enabled = !isPulling,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { startPull() }))
            val problem = refProblem
            if (problem != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                CircledMark("!", TeemoonColors.red, sizeDp = 14.dp); Text(problem, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.red)   // red: the reference is wrong and the pull cannot proceed
            } else installedID(trimmed)?.let { have ->
                // NOT an error, and not blocked: re-pulling is how a moving tag like :latest gets updated
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    CircledMark("✓", TeemoonColors.secondaryLabel, sizeDp = 14.dp); Text(MarkdownInline.annotate("`$have` is already on this machine — downloading again just checks for a newer build."), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                }
            }
        }
        GroupFooter("open one of those, find a model, and copy the link from its page.")

        // progressSection — DIRECTLY under the field: progress belongs next to the action that started it, and a FAILURE has to
        current?.takeIf { it.phase != OllamaDownloadCenter.Download.Phase.Done }?.let { d ->
            val phase = d.phase
            if (phase is OllamaDownloadCenter.Download.Phase.Failed) {
                val failure = OllamaDownloadCenter.classifyPullError(phase.message)
                InsetGroup(Modifier.padding(top = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("failed", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.red); Spacer(Modifier.weight(1f))
                        d.bytes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel) }
                    }
                    Text(failure.message, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))   // the CLASSIFIED message, not Ollama's raw one
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clickable { center.start(d.id, baseUrl) }.testTag("ollamaPull.retry"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { RefreshGlyph(TeemoonColors.tint, 16.dp); Text("retry", style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.tint) }
                        Text("dismiss", style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.red, modifier = Modifier.clickable { center.dismiss(d.id); startedRef = null })
                    }
                }
                failure.hint?.let { GroupFooter(it) }   // quant advice is advice only when the model exists
            } else {
                InsetGroup(Modifier.padding(top = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val f = d.fraction
                        if (f != null && phase == OllamaDownloadCenter.Download.Phase.Active) LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = TeemoonColors.tint, trackColor = TeemoonColors.fill)
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = if (phase == OllamaDownloadCenter.Download.Phase.Reconnecting) TeemoonColors.orange else TeemoonColors.tint, trackColor = TeemoonColors.fill)
                        Row(Modifier.fillMaxWidth()) {
                            Text(d.status.lowercase(), style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel); Spacer(Modifier.weight(1f))
                            d.bytes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel) }
                        }
                    }
                    InsetRow("stop", onClick = { center.cancel(d.id); startedRef = null }, titleColor = TeemoonColors.red)
                }
                GroupFooter("the pull runs on the server — close this sheet and keep using teemoon; it keeps downloading.")
            }
        }
    }
}

/** browseLink: the title over its subtitle, arrow.up.forward.app in the tint at the trailing edge. */
@Composable
private fun BrowseLink(title: String, subtitle: String, url: String, onOpen: (String) -> Unit) =
    InsetRow(title, subtitle = subtitle, onClick = { onOpen(url) }, trailing = { OpenLinkGlyph(sizeDp = 16.dp) })
