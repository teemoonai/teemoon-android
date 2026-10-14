// ports: teemoon/Views/Settings/AddEditProviderView.swift @ v1.0.4 (identity / preset / connection / model / edit
//        actions, section for section; the sheets, confirmations and the scheme chip — the actions themselves live
//        in AddEditProviderModel), teemoon/Views/Settings/ProviderModelSection.swift @ v1.0.1,
//        teemoon/Views/Settings/ProviderPresetSection.swift @ v1.0.4 (rows, not tiles: fcf4d93 + cf251b5),
//        teemoon/Views/Settings/ProviderIdentityTile.swift @ v1.0.1, teemoon/Views/Settings/DownloadingModelRow.swift @ v1.0.1
//
// This used to cite AddEditProviderView+Sections.swift and +Actions.swift, which iOS deleted in 9a87ab7 on
// 2026-08-30 — two days BEFORE the 2026-09-01 baseline this header pins to, so they never existed at the sha it
// claims. The successors above are where that content went, and all are mapped. Corrected 2026-09-22.
// Layout over AddEditProviderModel. Verify-first: enter an endpoint, one "fetch models" probe, pick a model, save. No
// branching lives here that the model does not already decide.
package ai.teemoon.app

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.EndpointScheme
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.OllamaDownloadCenter
import ai.teemoon.providers.Provider
import ai.teemoon.providers.metaLabel
import ai.teemoon.providers.presetDescription
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.teemoon.providers.ProviderCapability
import ai.teemoon.providers.WhereLocality
import ai.teemoon.providers.WhereProviderPresentation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import java.net.URI
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditProviderScreen(
    model: AddEditProviderModel, onDone: () -> Unit,
    /** snapshots pass false: Paparazzi cannot host a focus request */
    focusOnOpen: Boolean = true,
    /** Ollama's server-side pull (ProviderSheets.showDownloadSheet). Null when the setup has no id yet to route by. */
    onDownload: (() -> Unit)? = null,
    /** In-flight pulls, every endpoint; the screen keeps the ones for THIS host (`downloadingHere`). */
    downloads: List<OllamaDownloadCenter.Download> = emptyList(),
    onCancelDownload: (String) -> Unit = {},
) {
    // The model is plain Kotlin; a version counter re-reads it after every mutation. The counter only invalidates THIS
    // scope — the sections below take the model by reference and are marked non-skippable, or a fetch result would
    // never reach the screen (strong skipping compares the unchanged instance and skips them).
    var version by remember { mutableIntStateOf(0) }
    // AddEditProviderView.task: "only focus the url in custom mode" — a new custom place raises the keyboard on the host field after 0.4s
    val hostFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusOnOpen && !model.isEditing && model.selectedPresetName.isEmpty()) { delay(400); runCatching { hostFocus.requestFocus() } } }
    fun mutate(block: () -> Unit) { block(); version++ }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    remember { model.loadInitialValues(); model.onAnimatedChange = { it(); version++ }; model }
    @Suppress("UNUSED_VARIABLE") val tick = version
    var endpointFocused by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var keyCopied by remember { mutableStateOf(false) }
    val preset = model.initialPreset

    // navTitle
    val title = when {
        model.isEditing && model.scope == AddEditProviderModel.Scope.SERVER_AND_KEY -> if (model.isSelfHosted) "computer" else "cloud key"
        model.isEditing -> "provider"
        preset != null -> "add ${preset.name.lowercase()} key"
        model.startsCustom -> model.customStart.title
        else -> "add provider"
    }
    GroupedPage(header = { NavBar(title, leading = { PillButton("cancel", onDone) },
            trailing = { PillButton("save", onClick = { mutate { if (model.save()) onDone() } }, tint = if (model.isValid) TeemoonColors.tint else TeemoonColors.tertiaryLabel, enabled = model.isValid) }) },
        topInset = if (!model.isEditing && preset == null) 8.dp else 35.dp) {   // a new custom place starts with a label that fills Form's inset; a keyed preset or an edit starts with a card

        // formCore, in order: identity (edit) · preset grid (add, nothing named yet) · connection · model · edit actions
        if (model.isEditing) IdentitySection(model, onConsole = { uri.openUri(it) })
        if (!model.isEditing && preset == null && !model.startsCustom) PresetSection(model) { mutate { model.applyPresetChange(model.selectedPresetName, it) }; endpointFocused = it.isEmpty() }

        // connectionSection: label / scheme + host / key. The footer says where the key goes.
        InsetGroup(Modifier.padding(top = 12.dp)) {
            InsetField(model.name, { mutate { model.name = it } }, "label")
            var schemeMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // SchemeChipView: lock / open lock, the scheme, up-down chevrons; a menu switches it
                Box {
                    Capsule(model.scheme.prefix, onClick = { schemeMenu = true }, leading = { LockGlyph(open = model.scheme != EndpointScheme.HTTPS, tint = if (model.scheme == EndpointScheme.HTTPS) TeemoonColors.verified else TeemoonColors.secondaryLabel) }, tint = TeemoonColors.label, background = TeemoonColors.fill)
                    DropdownMenu(expanded = schemeMenu, onDismissRequest = { schemeMenu = false }) {
                        val other = if (model.scheme == EndpointScheme.HTTPS) EndpointScheme.HTTP else EndpointScheme.HTTPS
                        DropdownMenuItem(
                            text = { Text(if (other == EndpointScheme.HTTP) "use http (insecure)" else "switch back to https") },
                            leadingIcon = { LockGlyph(open = other == EndpointScheme.HTTP, tint = TeemoonColors.label) },
                            onClick = { schemeMenu = false; mutate { model.scheme = other; model.conn = AddEditProviderModel.ConnState.Idle } })
                    }
                }
                val style = MaterialTheme.typography.bodyLarge.copy(color = TeemoonColors.label)   // the host is a plain 17pt field; only the scheme chip is monospaced
                BasicTextField(model.endpointHost, { mutate { model.endpointEdited(it) } },
                    modifier = Modifier.weight(1f).focusRequester(hostFocus).onFocusChanged { endpointFocused = it.isFocused },
                    textStyle = style, singleLine = true, cursorBrush = SolidColor(TeemoonColors.tint),
                    // a URL, not a credential — no password autofill; no autocorrect
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    decorationBox = { inner -> Box { if (model.endpointHost.isEmpty()) Text("api.example.com/v1", style = style, color = TeemoonColors.tertiaryLabel); inner() } })
            }
            if (model.endpointHost.isNotBlank() && model.fullEndpointURL == null)
                Text("that doesn't look like a valid url", style = MaterialTheme.typography.bodySmall, color = TeemoonColors.red, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            // Autocomplete from previously-saved endpoints — while the field is being edited.
            if (endpointFocused) model.endpointSuggestions.forEach { sg ->
                InsetRow(sg.hostPath.lowercase(), onClick = { mutate { model.applySuggestion(sg) } }, leading = { RefreshGlyph(TeemoonColors.secondaryLabel, sizeDp = 14.dp) })
            }
            if (model.showKeyField) {
                // The refusal lands ON this row (ProviderConnectionSection, iOS 148a131): red prompt, mark, one shake, focus.
                val keyFocus = remember { FocusRequester() }
                val shake = remember { Animatable(0f) }
                LaunchedEffect(model.keyFocusRequest) {
                    if (model.keyFocusRequest == 0) return@LaunchedEffect
                    runCatching { keyFocus.requestFocus() }
                    shake.snapTo(0f); shake.animateTo(1f, tween(400, easing = LinearEasing))
                }
                InsetField(model.apiKey, { mutate { model.apiKey = it } }, model.apiKeyPlaceholder, secret = !model.showAPIKey, sensitive = true, singleLine = !model.showAPIKey,
                    // ShakeEffect: 8pt · sin(t · 3π) across the 0.4 s — one horizontal "no"
                    modifier = Modifier.graphicsLayer { translationX = 8.dp.toPx() * sin(shake.value * Math.PI.toFloat() * 3f) },
                    placeholderColor = if (model.keyFieldError != null) TeemoonColors.red else TeemoonColors.tertiaryLabel, focusRequester = keyFocus, trailing = {
                    if (model.keyFieldError != null) Box(Modifier.testTag("provider.keyFieldErrorMark").semantics { contentDescription = "key required" }) { FilledCircleGlyph("!", TeemoonColors.red, size = 18.dp) }
                    // the key row ends in square.on.square (a checkmark for 1.5 s once copied) and the eye
                    if (model.apiKey.isNotEmpty()) Box(Modifier.clickable { Clipboard.copySensitive(context, model.apiKey); keyCopied = true; scope.launch { delay(1_500); keyCopied = false } }.semantics { contentDescription = A11yLabels.COPY_KEY }) {
                        if (keyCopied) SelectedCheck() else CopyGlyph()
                    }
                    Box(Modifier.clickable { mutate { model.showAPIKey = !model.showAPIKey } }.semantics { contentDescription = if (model.showAPIKey) A11yLabels.HIDE_KEY else A11yLabels.SHOW_KEY }) { EyeGlyph(open = !model.showAPIKey) }
                })
                // WHERE TO GET THE VALUE, next to the field asking for it — gated on the FIELD BEING EMPTY, not on adding:
                // with a key present the identity tile's quieter "{vendor} console" takes over.
                if (model.apiKey.isBlank()) model.providerConsoleURL?.let { url ->
                    InsetRow("get ${model.consoleDisplayName.lowercase()} api key", onClick = { uri.openUri(url) }, titleColor = TeemoonColors.tint, leading = { OpenLinkGlyph() })
                }
            }
        }
        // The refusal's sentence takes the keystore sentence's place in the footer, in red.
        model.keyFieldError?.let { GroupFooter(it.lowercase(), color = TeemoonColors.red, modifier = Modifier.testTag("provider.keyFieldError")) }
        GroupFooter(buildString {
            // Arriving with the preset chosen hides the preset section and, with it, the description that section's
            // footer shows — including brave's line that it needs a different key than the grounding api. Said here
            // instead, beside the field it is about (iOS cf251b5).
            model.initialPreset?.presetDescription?.let { append(it).append("\n") }
            if (model.scheme == EndpointScheme.HTTP) append("http is unencrypted — use only on a trusted network. ")
            // Every clause checked against Keystore.kt, as iOS checks its own against Keychain.swift: the master key is in the
            // Android Keystore, the wrapped secret in the app's private files; nothing writes it to the config.
            if (model.keyFieldError == null && model.showKeyField) append("the key is stored in the android keystore, not in teemoon's own files${model.keyDestinationClause}.")
            if (model.awaitingFirstModel) append(" waiting for the endpoint to answer — teemoon picks a model from what it serves, and saves once it has one.")
        }.trim())

        // modelSection: only once there is an endpoint to ask; a fixed-answer endpoint shows a connection check instead
        if (model.scope == AddEditProviderModel.Scope.FULL && model.fullEndpointURL != null) {
            if (model.fixedModelID != null) ConnectionCheckSection(model) { scope.launch { model.probe(userInitiated = true); version++ } }
            else ModelListSection(model, downloads, onCancelDownload, onDownload,
                onPick = { m -> mutate { model.model = m.id; model.autofillPlaceLabel() } },
                onProbe = { scope.launch { model.probe(userInitiated = true); version++ } },
                onManualModel = { mutate { model.model = it } },
                onAskDelete = { mutate { model.pendingDelete = it } })
        }

        // editActionsSection
        if (model.isEditing) {
            val pid = model.editingProviderID
            val store = model.store
            if (model.scope == AddEditProviderModel.Scope.FULL && pid != null && store != null) {
                // "use this provider" SELECTS — the one button here that changes what the next question goes to
                val active = store.currentProviderID == pid
                InsetGroup(Modifier.padding(top = 16.dp)) { InsetRow(if (active) "active provider" else "use this provider", onClick = { mutate { store.currentProviderID = pid; model.onPlayHaptic?.invoke() } }, trailing = { if (active) SelectedCheck() }) }
            }
            // TWO destructive actions, because they are different sizes: revoking a key at the vendor means clearing it here
            if (model.apiKey.isNotBlank()) {
                InsetGroup(Modifier.padding(top = 16.dp)) { InsetRow("remove key", onClick = { mutate { model.apiKey = "" } }, titleColor = TeemoonColors.red) }
                GroupFooter("clears the key and keeps the setup — save to apply.")
            }
            // Names what LEAVES: "delete provider" is a data-model word for a row the user thinks of as a key or a computer.
            InsetGroup(Modifier.padding(top = 16.dp)) { InsetRow(if (model.isSelfHosted) "forget this computer" else "delete this setup", onClick = { showDeleteConfirm = true }, titleColor = TeemoonColors.red) }
            GroupFooter(if (model.isSelfHosted) "removes it from teemoon. the machine and its models are untouched." else "removes the endpoint, its key, and the models equipped on it.")
        }
    }

    // ProviderConfirmations
    if (showDeleteConfirm) IosAlert("delete this provider?", "", listOf("delete provider" to { showDeleteConfirm = false; if (model.deleteProvider()) onDone() }, "cancel" to { showDeleteConfirm = false }), onDismiss = { showDeleteConfirm = false })
    model.pendingDelete?.let { m ->
        IosAlert("delete model?", "delete “${m.displayName}” from the server? this frees disk and can't be undone.",
            listOf("delete" to { scope.launch { model.performDelete(m); version++ } }, "cancel" to { mutate { model.pendingDelete = null } }), onDismiss = { mutate { model.pendingDelete = null } })
    }
    // Surfaced rather than swallowed: the provider is saved but has no credential, and silence there only reappears later
    // as an unexplained unauthorized request.
    model.keySaveError?.let { reason -> IosAlert("couldn't save the api key", "the provider was saved, but the key could not be written to the keystore — $reason", listOf("ok" to { mutate { model.keySaveError = null } }), onDismiss = { mutate { model.keySaveError = null } }) }
    model.modelDeleteError?.let { reason -> IosAlert("couldn't delete the model", reason, listOf("ok" to { mutate { model.modelDeleteError = null } }), onDismiss = { mutate { model.modelDeleteError = null } }) }
}

/** identitySection — edit mode: the identity tile, the name and host, a quiet "{vendor} console" for cloud presets. */
@Composable
@NonSkippableComposable   // reads the plain model by reference: strong skipping would compare the same instance and skip the fetch result
private fun IdentitySection(model: AddEditProviderModel, onConsole: (String) -> Unit) {
    InsetGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderIdentityTile(name = model.name.ifEmpty { "?" }, isSelfHosted = model.isSelfHosted)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name.lowercase(), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)   // .title2.weight(.semibold)
                Text(model.fullEndpointURL?.host ?: model.endpointHost, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        model.providerConsoleURL?.let { url -> InsetRow("${model.consoleDisplayName.lowercase()} console", onClick = { onConsole(url) }, titleColor = TeemoonColors.secondaryLabel, leading = { OpenLinkGlyph(TeemoonColors.secondaryLabel) }) }
    }
}

/** ProviderIdentityTile: a 58pt rounded tile — a logo when there is one, server.rack for a machine, else the monogram. No logo assets here yet. */
@Composable
fun ProviderIdentityTile(name: String, isSelfHosted: Boolean, size: Dp = 58.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(TeemoonColors.fill), contentAlignment = Alignment.Center) {
        if (isSelfHosted) LocalityGlyph(WhereLocality.HOME, tint = TeemoonColors.secondaryLabel, sizeDp = size * 0.4f)
        else Text(name.trim().take(1).lowercase(), style = MaterialTheme.typography.headlineLarge, color = TeemoonColors.secondaryLabel)
    }
}

/**
 * presetSection: provider marks — one labelled line above the fields it fills. The filled tile IS the selection, and
 * editing the endpoint moves it to custom; a wrapping grid so every mark, "custom" included, is always visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@NonSkippableComposable   // reads the plain model by reference: strong skipping would compare the same instance and skip the fetch result
private fun PresetSection(model: AddEditProviderModel, onSelect: (String) -> Unit) {
    // Rows, like every other list of things to pick in the app — the Where sheet's `get` section in particular, which
    // offers the same presets (iOS fcf4d93 + cf251b5). The tile grid this replaces was the one surface that looked like
    // a different app. One glyph for every hosted provider, brave included: the row is a place you send a key to, and
    // the caption says what comes back. Tapping a row fills the fields below; editing the endpoint moves to custom.
    InsetGroup(Modifier.padding(top = 12.dp), dividerInset = 52.dp) {
        Provider.presets.forEach { p ->
            val selected = model.selectedPresetName == p.name
            InsetRow(p.name.lowercase(), subtitle = WhereProviderPresentation.presetCaption(p)?.lowercase(),
                onClick = { if (!selected) onSelect(p.name) },
                leading = { LocalityGlyph(WhereLocality.CLOUD, tint = TeemoonColors.tint) },
                titleTags = if (ProviderCapability.END_TO_END_ENCRYPTION in p.capabilities) ({ E2EETag() }) else null,
                trailing = { if (selected) SelectedCheck() })
        }
        InsetRow("custom", subtitle = "any endpoint serving /v1/chat/completions", onClick = { if (model.selectedPresetName.isNotEmpty()) onSelect("") },
            leading = { LocalityGlyph(WhereLocality.HOME, tint = TeemoonColors.tint) },   // server.rack
            trailing = { if (model.selectedPresetName.isEmpty()) SelectedCheck() })
    }
    val desc = Provider.presets.firstOrNull { it.name == model.selectedPresetName }?.presetDescription
    GroupFooter(desc ?: "tap a provider to fill the fields below, or enter any endpoint serving /v1/chat/completions")
}

/** connectionCheckSection — fixed-model providers: no picker, no fetch; just whether the key works. */
@Composable
@NonSkippableComposable   // reads the plain model by reference: strong skipping would compare the same instance and skip the fetch result
private fun ConnectionCheckSection(model: AddEditProviderModel, onProbe: () -> Unit) {
    SectionLabel("connection", style = MaterialTheme.typography.titleMedium)   // this Form's headers are prominent, as the web search page's
    InsetGroup {
        when (val c = model.conn) {
            AddEditProviderModel.ConnState.Testing -> SpinnerRow("checking…")
            // No retry on success: "check again" next to a green tick makes the tick look provisional.
            AddEditProviderModel.ConnState.Connected -> InsetRow("key accepted", titleColor = TeemoonColors.secondaryLabel, leading = { Box(Modifier.size(18.dp).clip(CircleShape).background(TeemoonColors.verified), contentAlignment = Alignment.Center) { Text("✓", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TeemoonColors.background) } })
            else -> {
                val blocked = model.probeBlockedOnMissingKey
                InsetRow("test connection", onClick = if (blocked) null else onProbe, titleColor = TeemoonColors.tint, leading = { RefreshGlyph(TeemoonColors.tint) }, modifier = Modifier.alpha(if (blocked) 0.4f else 1f))
                if (c is AddEditProviderModel.ConnState.Failed) InlineError(model.failureMessage(c.kind))
            }
        }
    }
    GroupFooter("this endpoint answers directly — there is no model to choose")
}

/** modelListSection — the verify-first heart: the fetch button that doubles as the connection test, the picker, one inline line on failure. */
@Composable
@NonSkippableComposable   // reads the plain model by reference: strong skipping would compare the same instance and skip the fetch result
private fun ModelListSection(
    model: AddEditProviderModel, downloads: List<OllamaDownloadCenter.Download>, onCancelDownload: (String) -> Unit, onDownload: (() -> Unit)?,
    onPick: (KnownModel) -> Unit, onProbe: () -> Unit, onManualModel: (String) -> Unit, onAskDelete: (KnownModel) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val host = model.fullEndpointURL?.host
    val downloadingHere = downloads.filter { d -> host != null && runCatching { URI(d.baseUrl).host }.getOrNull() == host }
    val blocked = model.probeBlockedOnMissingKey
    SectionLabel("model", style = MaterialTheme.typography.titleMedium)
    InsetGroup {
        downloadingHere.forEach { d -> DownloadingModelRow(d) { onCancelDownload(d.id) } }   // in-flight pulls sit at the TOP — the most active item
        val fetchRow: @Composable () -> Unit = {
            InsetRow(if (model.fetchedModels.isEmpty()) "fetch models" else "refresh models", onClick = if (blocked) null else onProbe, titleColor = TeemoonColors.tint,
                leading = { RefreshGlyph(TeemoonColors.tint) }, modifier = Modifier.alpha(if (blocked) 0.4f else 1f))   // EXPLICIT DIM: 40%, one dim across the app meaning "you cannot tap this"
        }
        val picker: @Composable () -> Unit = {
            val shown = if (showAll) model.fetchedModels else model.recommendedModels
            shown.forEach { m -> ModelRow(m, selected = m.id == model.model, warm = m.id in model.loadedModelIDs, showTier = model.showsInlineTiers, onPick = { onPick(m) }, onDelete = if (model.isOllama) ({ onAskDelete(m) }) else null) }
            // "all N models" opens the catalogue browser on iOS; here the group expands in place (docs/ANDROID_NATIVE.md)
            if (model.fetchedModels.size > 3) InsetRow(if (showAll) "fewer" else "all ${model.fetchedModels.size} models", onClick = { showAll = !showAll }, chevron = !showAll)
        }
        when (val c = model.conn) {
            AddEditProviderModel.ConnState.Testing -> SpinnerRow("connecting…")
            AddEditProviderModel.ConnState.Connected -> {
                // Connected with nothing installed — a fresh ollama. Say so rather than showing an empty picker.
                if (model.fetchedModels.isEmpty()) Text(if (model.isOllama) "no models installed on this server yet" else "this server returned no models", style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                else picker()
                fetchRow()   // acts as "refresh models"
                if (model.isOllama && onDownload != null) InsetRow("download a model", onClick = onDownload, titleColor = TeemoonColors.tint, leading = { DownloadGlyph(TeemoonColors.tint) })
            }
            else -> {
                if (model.fetchedModels.isEmpty()) {
                    // NOTHING YET until the user has asked; a browsable preset's model comes from the catalogue, never a raw field;
                    // otherwise the manual fallback (soft-gate): type a model even without a fetch
                    if (model.hasProbed && !model.supportsModelBrowsing) InsetField(model.model, onManualModel, "model", monospace = true)
                } else picker()
                fetchRow()
                if (c is AddEditProviderModel.ConnState.Failed) InlineError(model.failureMessage(c.kind))
            }
        }
    }
    // The row above is the SELECTION, not a fixed recommendation — so the note is conditional on the two coinciding.
    model.recommendedModelID?.takeIf { it == model.model }?.let { GroupFooter("recommended for this provider.") }
}

@Composable
private fun SpinnerRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = TeemoonColors.secondaryLabel, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel)
    }
}

/** The failure, stated. NO retry button — the refresh button is already there, and two controls a line apart read as two remedies. */
@Composable
private fun InlineError(message: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(TeemoonColors.red), contentAlignment = Alignment.Center) { Text("!", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TeemoonColors.background) }
        Text(message, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
    }
}

/**
 * A fetched model, the browser's one-line layout: name → tier badge → warm → price·context on the right → the check on
 * the chosen one. Ollama rows offer "delete" on a trailing swipe (iOS: `.swipeActions`, Ollama only) and on a long press,
 * confirmed before anything happens.
 */
@Composable
private fun ModelRow(m: KnownModel, selected: Boolean, warm: Boolean, showTier: Boolean, onPick: () -> Unit, onDelete: (() -> Unit)?) {
    val row: @Composable () -> Unit = { InsetRow(m.displayName.lowercase(), onClick = onPick, onLongClick = onDelete, trailing = {
        if (showTier) ConfidentialityTag(NearAIModelCatalog.confidentiality(m.id))
        if (warm) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { WarmDot(); Text("warm", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel) }
        Spacer(Modifier.width(6.dp))
        val sub = m.metaLabel
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, maxLines = 1)
        if (selected) SelectedCheck()
    }) }
    if (onDelete != null) TrailingSwipe("delete", onDelete, content = row) else row()
}

/** The "ink ladder": e2ee is filled + shield (proven), attested-third-party a hairline outline, proxied bare text. */
@Composable
fun ConfidentialityTag(tier: NearAIModelCatalog.Confidentiality) {
    val small = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    when (tier) {
        NearAIModelCatalog.Confidentiality.TEE_OWN -> Row(Modifier.clip(CircleShape).background(TeemoonColors.verified.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("✓", style = small, color = TeemoonColors.verified); Text("e2ee", style = small, color = TeemoonColors.verified)
        }
        // NOT "unverified": it IS attested, just on hardware that isn't near.ai's own. Name the fact, not a verdict.
        NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY -> Text("tee · third-party", style = small, color = TeemoonColors.secondaryLabel, modifier = Modifier.border(1.dp, TeemoonColors.secondaryLabel.copy(alpha = 0.35f), CircleShape).padding(horizontal = 6.dp, vertical = 3.dp))
        NearAIModelCatalog.Confidentiality.PROXIED -> Text("proxied", style = small, color = TeemoonColors.secondaryLabel)
    }
}

/** WarmDot: ambient "loaded in the server's memory" — a green dot breathing between full and 45%, not a badge. */
@Composable
fun WarmDot() {
    val a by rememberInfiniteTransition(label = "warm").animateFloat(1f, 0.45f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "warmAlpha")
    Box(Modifier.size(6.dp).clip(CircleShape).background(TeemoonColors.verified.copy(alpha = a)))
}

/** DownloadingModelRow: an in-flight pull as a disabled progress row in the model list; orange = reconnecting (degraded). */
@Composable
fun DownloadingModelRow(d: OllamaDownloadCenter.Download, onCancel: (() -> Unit)?) {
    val name = d.id.substringAfterLast('/')
    val reconnecting = d.phase == OllamaDownloadCenter.Download.Phase.Reconnecting
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name.lowercase(), style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val f = d.fraction
            if (f != null && !reconnecting) LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = TeemoonColors.tint, trackColor = TeemoonColors.fill)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = if (reconnecting) TeemoonColors.orange else TeemoonColors.tint, trackColor = TeemoonColors.fill)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (reconnecting) "reconnecting…" else "downloading", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.tertiaryLabel)
            d.bytes?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TeemoonColors.tertiaryLabel) }
        }
        onCancel?.let { Box(Modifier.size(22.dp).clip(CircleShape).background(TeemoonColors.secondaryLabel).clickable(onClick = it), contentAlignment = Alignment.Center) { Text("×", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = TeemoonColors.background) } }
    }
}

/** SF `arrow.down.circle`: a circle with a down arrow. */
@Composable
fun DownloadGlyph(tint: Color = TeemoonColors.tint, sizeDp: Dp = 20.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val w = size.width; val h = size.height; val s = 1.5.dp.toPx(); val cap = StrokeCap.Round
        drawCircle(tint, radius = w * 0.44f, style = Stroke(s))
        drawLine(tint, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.7f), s, cap)
        drawLine(tint, Offset(w * 0.34f, h * 0.55f), Offset(w * 0.5f, h * 0.7f), s, cap)
        drawLine(tint, Offset(w * 0.66f, h * 0.55f), Offset(w * 0.5f, h * 0.7f), s, cap)
    }
}
