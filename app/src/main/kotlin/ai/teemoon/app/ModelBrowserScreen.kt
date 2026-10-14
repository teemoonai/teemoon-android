// ports: teemoon/Views/Settings/ModelBrowserView.swift @ v1.0.4 (the browser; the equipped marking d7a30c9 added and
//        bf7a86e removed is not here), teemoon/Views/Settings/ModelDetailView.swift @ v1.0.4 (+ the providers section @ 835d7c8),
//        teemoon/Presentation/ModelBrowserQuery.swift @ v1.0.4 — REBUILD in Compose (one screen: the search field
//        is always there; a large catalogue simply starts empty until the live list lands)
//
// ONE BROWSER, and this port chose that first. iOS had two — a search-only one for OpenRouter whose rows arrived
// with no price, and a search-less one everywhere else where sorting by price reordered rows INSIDE each vendor
// section, so the cheapest model in the catalogue was never the first row. Two screens for one job had no idiom
// here, so this was one screen from 20b108a (2026-09-03). iOS consolidated on 2026-09-15 in df74f65 and deleted
// SearchFirstModelBrowser.swift, which this header used to name.
package ai.teemoon.app

import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.ModelBrowserDoor
import ai.teemoon.providers.ModelBrowserListing
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.ModelCatalog
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.metaLabel
import ai.teemoon.providers.priceLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import ai.teemoon.providers.ModelOffer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign

/**
 * The model browser, as ModelBrowserView renders it since df74f65: ONE value in
 * ([ModelBrowserDoor]), and the view draws what [ModelBrowserListing] answers —
 * it does not compute sections, sorts or filters itself. The listing was ported
 * on 2026-09-15 and never called; until 2026-09-22 this screen ran its own
 * grouping and a price sort that reordered rows INSIDE vendor sections, so the
 * cheapest model in the catalogue was never the first row.
 *
 * Tap a row to pick it; a long press opens "model info" (one tap target per row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowserScreen(
    door: ModelBrowserDoor,
    onSelect: (KnownModel) -> Unit,
    onCancel: () -> Unit,
    /** The setup's current model — the row that wears the check. Empty for a picker that opens with no pick. */
    selectedModel: String = "",
    onCopyID: (String) -> Unit = {},
    /** Preview/test seam: the sort and filters the screen opens with. */
    initialQuery: ModelBrowserQuery = ModelBrowserQuery(),
) {
    var live by remember { mutableStateOf<List<KnownModel>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(initialQuery) }
    var detail by remember { mutableStateOf<KnownModel?>(null) }
    // A model's card is a push inside the browser: system back returns to the list, not out of the browser
    BackHandler(enabled = detail != null) { detail = null }
    var reloads by remember { mutableStateOf(0) }
    // One fetch per appearance; a reload (pull-down, or the failed state's button) is the retry, and clears the
    // failure so the empty states re-evaluate.
    LaunchedEffect(reloads) {
        val loader = door.liveLoader ?: return@LaunchedEffect
        if (reloads == 0 && live != null) return@LaunchedEffect
        loadFailed = false
        loading = true
        val list = loader()
        if (!list.isNullOrEmpty()) live = list else if (live == null) loadFailed = true
        loading = false
    }
    detail?.let { m ->
        // An alias has no providers of its own; ask about the model it redirects to.
        ModelDetailScreen(m, confidentiality = if (door.showsConfidentialityTags) NearAIModelCatalog.confidentiality(m.id).label else null, onCopyID = onCopyID, onBack = { detail = null },
            offersLoader = door.offersLoader?.let { load -> { load(m.aliasOf ?: m.id) } })
        return
    }
    val effective = live ?: door.models
    val listing = ModelBrowserListing.of(effective, query, tiered = door.showsConfidentialityTags,
        selectedID = selectedModel, allowsCustomID = door.allowsCustomID)
    // `.searchable`: the field is above the list's top and appears on a pull, not as a permanent row — the list starts
    // scrolled past it here, so the first frame matches iOS and pulling down reveals it.
    val pillPx = with(LocalDensity.current) { 52.dp.roundToPx() }
    GroupedPage(scroll = false) {
        NavBar("browse models", subtitle = if (loading && effective.isNotEmpty()) "loading…" else null, leading = { PillButton("cancel", onCancel) })
        PullToRefreshBox(isRefreshing = loading && effective.isNotEmpty(), onRefresh = { reloads++ }, modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(initial = pillPx))) {
        SearchPill(query.text, { query = query.copy(text = it) }, placeholder = "search models", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Spacer(Modifier.height(28.dp))
        if (effective.isNotEmpty()) Controls(listing, query, onQuery = { query = it })
        listing.customID?.let { typed ->
            InsetGroup(Modifier.padding(top = 12.dp)) {
                InsetRow("use “$typed” as model id", onClick = {
                    onSelect(KnownModel(id = typed, displayName = typed.substringAfterLast('/'), vendor = "", price = ""))
                }, titleColor = TeemoonColors.tint, titleMaxLines = 2)
            }
            GroupFooter("no match in the list — sends the typed id as-is.")
        }
        EmptyState(listing, effective, loading, loadFailed, onRetry = { reloads++ })
        listing.sections.forEach { section ->
            section.header?.let { SectionLabel(it, style = MaterialTheme.typography.titleMedium, topPadding = 28.dp) } ?: Spacer(Modifier.height(20.dp))
            InsetGroup { section.rows.forEach { row -> ModelRow(row, onPick = onSelect, onInfo = { detail = it }) } }
        }
        Spacer(Modifier.height(24.dp))
        SheetScrollEnd()
        }
        }
    }
}

/** The count on the left, one menu on the right holding sort AND the show-only filters, and a caption naming what is hiding rows. */
@Composable
private fun Controls(listing: ModelBrowserListing, query: ModelBrowserQuery, onQuery: (ModelBrowserQuery) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(listing.countLabel, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, modifier = Modifier.weight(1f))
        Box {
            Text("≡ ${listing.effectiveSort.summary} ⌄", style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel,
                modifier = Modifier.clickable { menu = true }.padding(vertical = 10.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listing.sorts.forEach { sort ->
                    DropdownMenuItem(text = { Text((if (listing.effectiveSort == sort) "✓ " else "   ") + sort.label) },
                        onClick = { onQuery(query.copy(sort = sort)); menu = false })
                }
                if (listing.filters.isNotEmpty()) {
                    HorizontalDivider()
                    Text("show only", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    listing.filters.forEach { filter ->
                        val on = filter in query.filters
                        DropdownMenuItem(text = { Text((if (on) "✓ " else "   ") + filter.label) },
                            onClick = { onQuery(query.copy(filters = if (on) query.filters - filter else query.filters + filter)) })
                    }
                }
            }
        }
    }
    listing.activeSummary?.let { active ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(active, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.weight(1f))
            TextButton(onClick = { onQuery(query.copy(filters = emptySet())) }) { Text("clear", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Three empty states, because they need three different answers: still loading, the fetch failed with nothing
 * saved, or the search and filters hide everything.
 */
@Composable
private fun EmptyState(listing: ModelBrowserListing, effective: List<KnownModel>, loading: Boolean, loadFailed: Boolean, onRetry: () -> Unit) {
    @Composable fun block(title: String, body: String, action: (@Composable () -> Unit)? = null) =
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TeemoonColors.label)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel)
            action?.invoke()
        }
    when {
        effective.isEmpty() && loading -> block("loading models…", "")
        effective.isEmpty() && loadFailed -> block("couldn't load models", "check the key and network.") {
            TextButton(onClick = onRetry, modifier = Modifier.semantics { testTag = "modelBrowser.retry" }) { Text("try again") }
        }
        listing.isEmpty && listing.customID == null && effective.isNotEmpty() ->
            block("no models match", if (listing.activeSummary == null) "try a different search." else "try a different search, or clear the filters.")
    }
}

@Composable
private fun ModelRow(row: ModelBrowserListing.Row, onPick: (KnownModel) -> Unit, onInfo: (KnownModel) -> Unit) {
    val m = row.model
    // LONG PRESS for "model info", not a second tap target in the row: this sheet exists to switch models quickly,
    // and two tap targets in one row make which one fired ambiguous.
    var rowMenu by remember(m.id) { mutableStateOf(false) }
    // Only when the list is flat: grouped, the header above already says the vendor or the tier, and repeating it
    // spends the width the name needs.
    val second = listOfNotNull(
        m.vendor.lowercase().takeIf { row.showsVendor && it.isNotEmpty() },
        row.tier?.label?.takeIf { row.showsTier },
    ).joinToString(" · ").ifEmpty { null }
    Box {
        InsetRow(m.displayName.lowercase(), subtitle = second, modifier = Modifier.heightIn(min = 50.dp), onClick = { onPick(m) }, onLongClick = { rowMenu = true },
            leading = if (row.isSelected) ({ Text("✓", color = TeemoonColors.tint, style = MaterialTheme.typography.bodyMedium) }) else null,
            trailing = {
                // RETIRING in place of `new` when both would show: a model on its way out is the more
                // decision-relevant fact, and two badges is where the name starts truncating.
                if (m.isRetiring) Tag("retiring", warn = true) else if (m.isNew) Tag("new")
                Text(m.metaLabel, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, maxLines = 1)   // reference data — quiet gray; the name yields first
            })
        DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
            DropdownMenuItem(text = { Text("model info") }, onClick = { rowMenu = false; onInfo(m) })
        }
    }
}

@Composable
private fun Tag(text: String, warn: Boolean = false) = Text(text, style = MaterialTheme.typography.labelSmall, color = if (warn) TeemoonColors.orange else TeemoonColors.tint, maxLines = 1,
    modifier = Modifier.background((if (warn) TeemoonColors.orange else TeemoonColors.tint).copy(alpha = 0.14f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))

/** ModelDetailView: the id (tap to copy), the retirement notice, what it costs, the weights, what it can do, about, request parameters, elsewhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelDetailScreen(model: KnownModel, confidentiality: String?, onCopyID: (String) -> Unit, onBack: () -> Unit, liveLoader: (suspend () -> KnownModel?)? = null,
    /** Who can serve this model (OpenRouter's routing table); null for a catalogue that publishes none. */
    offersLoader: (suspend () -> List<ModelOffer>)? = null) {
    val uri = LocalUriHandler.current
    var live by remember { mutableStateOf<KnownModel?>(null) }
    var offers by remember { mutableStateOf<List<ModelOffer>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }
    // Both at once: the card is worth less with either half missing, and the providers table is a second request.
    LaunchedEffect(model.id) { liveLoader?.let { l -> l()?.let { live = it } } }
    LaunchedEffect(model.id) { offersLoader?.let { l -> l().takeIf { it.isNotEmpty() }?.let { offers = it } } }
    val shown = live ?: model
    // LabeledContent: the label keeps its width and the VALUE wraps — a long reasoning-effort list split "reasoning" mid-word.
    @Composable fun row(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, maxLines = 1, softWrap = false)
        Text(value.lowercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    @Composable fun section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    @Composable fun chips(items: List<String>) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { Capsule(it, tint = TeemoonColors.secondaryLabel) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        NavBar("model", leading = { PillButton("back", onBack, tint = TeemoonColors.label) })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(shown.displayName.lowercase(), style = MaterialTheme.typography.titleMedium)
        Text(shown.vendor.lowercase(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().clickable { onCopyID(shown.id); copied = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(shown.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f))
            Text(if (copied) "✓" else "copy", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.tint)
        }
        shown.deprecationLabel?.let { Text("⚠ $it", color = TeemoonColors.orange); Text("the provider has announced a retirement date for this model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        confidentiality?.let { section("confidentiality"); Text(it.lowercase()) }
        section("what it costs")
        // A free tier has no per-token price, and saying so is not "no published price" (iOS 96d801e: NVIDIA's catalogue).
        if (shown.price.isNotEmpty()) row("price", shown.priceLabel) else if (shown.isFree) row("price", "free") else Text("no published price", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shown.contextWindow.isNotEmpty()) row("context", shown.contextWindow)
        shown.maxOutputTokens?.let { row("max output", ModelCatalog.contextLabel(it)) }
        // What else it costs (iOS 835d7c8): parsed for every OpenRouter row and, until now, drawn nowhere.
        shown.extraCosts.forEach { row(it.label, it.amount) }
        if (shown.parameterSize != null || shown.quantization != null) {
            section("weights"); shown.parameterSize?.let { row("parameters", it) }; shown.quantization?.let { row("quantization", it) }
        }
        if (shown.features.isNotEmpty() || shown.inputModalities.isNotEmpty() || shown.reasoningEfforts.isNotEmpty() || shown.knowledgeCutoff != null) {
            section("what it can do"); if (shown.features.isNotEmpty()) chips(shown.features); if (shown.inputModalities.isNotEmpty()) row("accepts", shown.inputModalities.joinToString(", "))
            if (shown.reasoningEfforts.isNotEmpty()) row("reasoning", shown.reasoningEfforts.joinToString(", "))
            shown.knowledgeCutoff?.let { row("knows up to", it) }
        }
        shown.summary?.let { section("about"); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (shown.samplingParameters.isNotEmpty()) { section("request parameters"); chips(shown.samplingParameters); Text("what this model accepts. published by the provider, not guessed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (offers.isNotEmpty()) {
            section("providers")
            offers.forEach { offer -> ProviderOffer(offer, ::row) }
            Text("who openrouter can route this model to. speed is measured over the last half hour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val links = listOf("model page" to shown.modelPageURL, "hugging face" to shown.huggingFaceURL, "source" to shown.githubURL).filter { it.second != null }
        if (links.isNotEmpty()) { section("elsewhere"); links.forEach { (label, url) -> Text("↗ $label", color = TeemoonColors.tint, modifier = Modifier.padding(vertical = 8.dp).clickable { uri.openUri(url!!) }) } }
        }
    }
}

/** One routing target. Only what the catalogue actually reported: latency and throughput are null without a key, and a row of blanks says nothing. */
@Composable
private fun ProviderOffer(offer: ModelOffer, row: @Composable (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(offer.title.lowercase(), style = MaterialTheme.typography.bodyLarge)
            if (offer.isZeroDataRetention) Text("zero data retention", style = MaterialTheme.typography.labelSmall, color = TeemoonColors.verified, maxLines = 1,
                modifier = Modifier.background(TeemoonColors.verified.copy(alpha = 0.13f), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        if (offer.isFree) row("price", "free") else if (offer.price.isNotEmpty()) row("price", offer.price + " / 1M tokens")
        offer.contextTokens?.let { row("context", ModelCatalog.contextLabel(it)) }
        offer.maxOutputTokens?.let { row("max output", ModelCatalog.contextLabel(it)) }
        offer.quantization?.let { row("quantization", it) }
        offer.uptimeLabel?.let { row("uptime · 1d", it) }
        offer.latencyLabel?.let { row("first token", it) }
        offer.throughputLabel?.let { row("speed", it) }
        if (offer.supportsImplicitCaching) row("caching", "automatic")
    }
}

