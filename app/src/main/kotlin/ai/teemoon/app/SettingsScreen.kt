// ports: teemoon/Views/Where/WhereSheetView.swift @ v1.0.4, WhereSheetView+FirstRun.swift, +Ready.swift, +Recents.swift,
//        +Status.swift, +Actions.swift, WhereGetSection.swift, WhereRow.swift @ v1.0.1 — REBUILD in Compose.
//        Not carried: the model-detail sheet. Keys, web search and developer mode live where iOS keeps them — the edit
//        screen and settings — not on this sheet. A ready-now row's destructive action is a trailing swipe AND a long-press
//        menu item, the same action from both, as `.swipeActions` + `.contextMenu` both call rowDestructiveActions.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageModelChoice
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.Equipped
import ai.teemoon.providers.LocalModel
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.OllamaDownloadCenter
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import ai.teemoon.providers.TrustLabel
import ai.teemoon.providers.WhereGetPolicy
import ai.teemoon.providers.WhereLocality
import ai.teemoon.providers.WhereProviderPresentation
import ai.teemoon.providers.WhereReadyRows
import ai.teemoon.providers.WhereRecentEntry
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.WhereFilter
import ai.teemoon.providers.WhereFilterSheet
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import ai.teemoon.providers.CellularDownloadGate
import ai.teemoon.providers.CellularDownloadRequest
import ai.teemoon.providers.DownloadNetwork
import ai.teemoon.providers.LocalMemory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp

/** What the trust label says on a settings card — the value's own copy, never composed here (§9.5). */
fun TrustLabel.copyLine(): String = when (this) {
    TrustLabel.LOCAL -> "stays on your network"
    TrustLabel.TLS_ONLY -> "encrypted in transit only"
    TrustLabel.ATTESTED -> "attested"
}

/** WhereSheetView+FirstRun.downloadEstimate: minutes at 100 Mbps, never "0 minutes". */
fun downloadEstimate(sizeMB: Int): String {
    val seconds = sizeMB * 8.0 / 100.0
    val minutes = maxOf(1, Math.round(seconds / 60).toInt())
    return if (minutes == 1) "about a minute on wi-fi" else "about $minutes minutes on wi-fi"
}
fun downloadSizeLabel(sizeMB: Int) = "${sizeMB / 1000}.${(sizeMB % 1000) / 100} gb"


/** WhereSheetView.FirstRunPush: where first run's two rows go. */
enum class FirstRunPush { COMPUTER, CLOUD_KEY }

/** ML.accent: the fixed product orange the where chip and first run's pill share, whatever the user's tint. */
val ONBOARDING_ORANGE = Color(0xFFFF7A1A)

@Composable
fun WhereSheet(
    /** Whether a phone row's engine is loaded right now (AppState.isPhoneWarm); null = no label. */
    phoneWarm: (Provider) -> Boolean? = { null },
    /** "· images" ends the caption of a model that reads images (iOS b8457654, ImageModelChoice): asked of the row's model as current. */
    readsImages: (Provider) -> Boolean = { false },
    /** IMAGES.md §4b: a phone model this phone can never fit for images (AppState.imagesRefused), and whether a download would fit. */
    imagesRefused: (Provider) -> Boolean = { false },
    phoneFits: (LocalModel) -> Boolean = { true },
    /** IMAGES.md §4e: whether this phone's model reads images now (LocalModelCatalog.phoneReadsImages). */
    imagesOnDevice: Boolean = ai.teemoon.providers.LocalModelCatalog.imagesOnDevice,
    providers: List<Provider>,
    selectedId: String?,
    problem: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    hasKey: (Provider) -> Boolean = { false },
    onDownload: (String) -> Unit = {},
    /** What has answered lately, newest first; labels come from the live provider. */
    recents: List<WhereRecentEntry> = emptyList(),
    onActivateRecent: (WhereRecentEntry) -> Unit = {},
    /** The guided add screen: a preset name, or a custom start. */
    onAddPlace: (presetName: String?, custom: AddEditProviderModel.CustomStart?) -> Unit = { _, _ -> },
    /**
     * First run's two rows PUSH settings' own screens (WhereSheetView firstRunPush, iOS cf251b5): the computer form, or
     * the cloud provider PICKER — `get`'s generic key row opens the custom-endpoint form, which is right there and
     * wrong here (first run lists no presets, so "near.ai" in the subtitle led to typing its endpoint by hand).
     */
    onFirstRunPush: (FirstRunPush) -> Unit = { p -> onAddPlace(null, if (p == FirstRunPush.COMPUTER) AddEditProviderModel.CustomStart.COMPUTER else AddEditProviderModel.CustomStart.CLOUD_KEY) },
    onEdit: (String) -> Unit = {},
    /** The model browser for a configured place (or a preset's id when the place is keyed but not yet saved). */
    onBrowse: (String) -> Unit = onEdit,
    /**
     * The "show only" row (IMAGES.md §4e): how many of a setup's saved models pass these filters, by the browser's own
     * predicate; null when it has no saved list. With no filters, the saved list's length.
     */
    listCount: (Provider, Set<ModelBrowserQuery.Filter>) -> Int? = { _, _ -> null },
    /** The filters the sheet opens with: none from the chip; "reads images" from the can't-see routes. */
    initialShowOnly: Set<ModelBrowserQuery.Filter> = emptySet(),
    /** A preset or custom row tapped with a filter on: its browser opens with the same filters. */
    onBrowseShowOnly: (String, Set<ModelBrowserQuery.Filter>) -> Unit = { id, _ -> onBrowse(id) },
    /** The filters as the person changes them, so a browser opened from here and closed comes back to the same ones. */
    onShowOnlyChange: (Set<ModelBrowserQuery.Filter>) -> Unit = {},
    /** Spoken as the sheet appears (IMAGES.md §4d: "this model can't see images." from the attach control or a paste). */
    announce: String? = null,
    onAnnounced: () -> Unit = {},
    /** Developer mode's reasoning bar for the selected model (ReasoningUi.bar), pinned to the sheet's bottom; null: none. */
    reasoningBar: ReasoningUi.Bar? = null,
    onOpenReasoning: () -> Unit = {},
    // The Where sheet proper
    policyFor: (WhereLocality?) -> WhereGetPolicy = { f -> WhereGetPolicy(f, providers, true, emptyMap(), { "" }, { "" }) },
    networkSatisfied: Boolean = true,
    mergeNotice: String? = null, onDismissMergeNotice: () -> Unit = {},
    pulls: List<OllamaDownloadCenter.Download> = emptyList(),
    onCancelPull: (String) -> Unit = {},
    onActivate: (Equipped) -> Unit = { onSelect(it.provider.id) },
    onUnequip: (Equipped) -> Unit = {},
    onDeleteFromServer: (Equipped) -> Unit = {},
    onRefreshHome: () -> Unit = {},
    /** Changes on every presentation of the sheet; the machines are re-read each time (WhereSheetView's .task). */
    refreshKey: Int = 0,
    /** Re-issues a failed Ollama pull of (ref, base url) — OllamaDownloadCenter.start. */
    onRetryPull: (String, String) -> Unit = { _, _ -> },
    deleteFailure: String? = null, onDismissDeleteFailure: () -> Unit = {},
    // On-device models (LocalModelDownloader / LocalModelStorage)
    downloads: Map<String, Double> = emptyMap(),
    /** The name of the file each download is fetching, by model id — the row says "gemma 4 e2b · tensor" while it lands. */
    downloadNames: Map<String, String> = emptyMap(),
    downloadFailures: Map<String, String> = emptyMap(),
    installed: (LocalModel) -> Boolean = { false },
    /**
     * The catalog entry this DEVICE runs for a model id — `LocalModelStorage.catalogEntry`, which resolves through
     * `LocalModelCatalog.forDevice`.
     *
     * Not `LocalModelCatalog.model`, which is the static table. On a Tensor phone the E2B entry resolves to the
     * Tensor bundle, a different file name; asking the static table returns the GENERAL entry, `installed` then
     * looks for a file that was never downloaded, and a ready row for a working model reads "not downloaded — tap
     * to resume" over a 2.9 GB bundle sitting on disk. Seen on the Pixel 10 Pro, 2026-09-21.
     */
    localModelFor: (String) -> LocalModel? = LocalModelCatalog::model,
    /**
     * What deleting a model's weights would actually free — `LocalModelStorage.installedBytes`, measured.
     *
     * Not the catalog's `sizeMB`: deleting removes the model's DIRECTORY, so a Tensor phone holding both bundles
     * frees both, and the dialog promised 2.4 GB while freeing more than twice that. Defaults to the catalog
     * figure for callers with no storage to ask — a preview or a snapshot test, where nothing is on disk anyway.
     */
    installedBytesFor: (LocalModel) -> Long = { it.sizeMB.toLong() * 1024 * 1024 },
    availableMB: Int = 0,
    /** MemoryInfo.totalMem: the "may not fit" floor and the start-here hero read it (LocalMemory.belowFloor). 0: silent. */
    totalMemBytes: Long = 0L,
    onStartDownload: (LocalModel) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onDeleteWeights: (LocalModel) -> Unit = {},
    /** CellularDownloadPrompt: a phone download tapped on mobile data, waiting on the user's answer. */
    cellularPrompt: CellularDownloadRequest? = null,
    /** The answer: ANY ("download now"), WIFI_ONLY ("wait for wi-fi"), or null ("not now" — starts nothing). */
    onAnswerCellular: (DownloadNetwork?) -> Unit = {},
    /** Model ids whose wi-fi-only download is parked on mobile data — the row says "waiting for wi-fi". */
    parkedDownloads: Set<String> = emptySet(),
) {
    // The question iOS draws with `.cellularDownloadPrompt` — same title, message and three answers. It was
    // resolved in AppState and never drawn, so a download tapped on mobile data silently never started (2026-09-24).
    cellularPrompt?.let { pending ->
        IosAlert(CellularDownloadGate.title(pending.reason), CellularDownloadGate.message(pending.reason, pending.model),
            CellularDownloadChoice.entries.map { choice -> choice.label to {
                onAnswerCellular(when (choice) { CellularDownloadChoice.DOWNLOAD_NOW -> DownloadNetwork.ANY; CellularDownloadChoice.WAIT_FOR_WIFI -> DownloadNetwork.WIFI_ONLY; CellularDownloadChoice.NOT_NOW -> null })
            } },
            onDismiss = { onAnswerCellular(null) })
    }
    var filter by remember { mutableStateOf<WhereLocality?>(null) }
    // held across tab changes and the half / full drag; every open starts from initialShowOnly (§4e "Opening")
    var showOnly by remember { mutableStateOf(initialShowOnly) }
    var pendingServerDelete by remember { mutableStateOf<Equipped?>(null) }
    var pendingWeightsDelete by remember { mutableStateOf<LocalModel?>(null) }
    LaunchedEffect(refreshKey) { onRefreshHome() }

    val policy = policyFor(filter)
    // WhereSheetView.isWarm: home from the server's loaded models, the PHONE from whether its engine is resident
    // (iOS 390d8fa — Android had only the home half, so a loaded gemma never read "warm"), cloud never.
    val isWarm: (Equipped) -> Boolean? = { row ->
        when (WhereLocality.of(row.provider)) {
            WhereLocality.HOME -> policy.isWarm(row.provider, row.modelID)
            WhereLocality.PHONE -> phoneWarm(row.provider)
            WhereLocality.CLOUD -> null
        }
    }
    val rows = WhereReadyRows.ordered(WhereReadyRows.rows(policy.filteredProviders), isWarm)
    // The filter's narrowing of all three sections; recently used is the all tab's, online only, as before. The all
    // tab's download steps aside while its file lands over an existing place (the same download, shown once).
    val sheet = WhereFilterSheet.of(policy, rows, if (networkSatisfied && filter == null) recents else emptyList(), showOnly, readsImages, hasKey, listCount,
        imagesOnDevice = imagesOnDevice, recommendedStepsAside = { m -> downloads[m.id] != null && providers.any { it.localModelID == m.id } },
        imagesRefused = imagesRefused, phoneFits = phoneFits)
    // a refused row's tap never switches and never downloads: it says why (IMAGES.md §4b)
    var refusedTapped by remember { mutableStateOf<Equipped?>(null) }
    val browse: (String) -> Unit = { id -> if (showOnly.isEmpty()) onBrowse(id) else onBrowseShowOnly(id, showOnly) }
    val recommended = WhereGetPolicy.recommendedPhoneModel(catalog = policy.catalog, totalBytes = totalMemBytes)
    val showsFirstRun = policy.showsFirstRun(loadFailed = problem != null, recommended = recommended)
    val localities = listOf(null) + WhereLocality.entries

    // WhereSheetView: close / "where"; the locality picker; ready now / recently used / get as inset groups.
    // the picker leads, not a card: measured 11pt short of the tag with no inset
    GroupedPage(header = { NavBar("where", leading = { CircleButton(Icons.Filled.Close, "close", onBack, tint = TeemoonColors.tint) }) }, topInset = 11.dp,
        footer = reasoningBar?.let { bar -> { ReasoningBar(bar, onOpenReasoning) } }) {
        
        if (!networkSatisfied) Banner("you're offline", "cloud and home need a network. models on this phone still answer.")
        mergeNotice?.let { Banner("tidied up", it, action = "ok", onAction = onDismissMergeNotice) }
        problem?.let { Banner("couldn't read your setups", it) }

        // MARK: first run — WhereSheetView+FirstRun: no locality picker; "start here" is the section header at title2
        // bold over the hero card (green eyebrow, arrow.down.circle on the download); then "other places" and its footer.
        if (showsFirstRun && recommended != null) {
            val here = installed(recommended)
            Text("start here", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp))
            InsetGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("free · runs offline on your phone", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = TeemoonColors.verified)
                    Text(recommended.displayName.lowercase(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(recommended.blurb.lowercase(), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
                    // The where chip's rule, one tap back: the fixed onboarding orange with a white label (iOS cf251b5) — not the
                    // user's tint with an inverting label, which gave black on orange in dark mode on the button the chip had
                    // just pointed at in white.
                    PrimaryPill(if (here) "use ${recommended.displayName.lowercase()}" else "download · ${downloadSizeLabel(recommended.sizeMB)}", onClick = { onStartDownload(recommended) }, modifier = Modifier.padding(top = 8.dp),
                        tint = ONBOARDING_ORANGE, labelColor = Color.White, glyph = { CircledMark(if (here) "✓" else "↓", Color.White) })
                    Text(if (here) "already downloaded · runs offline" else downloadEstimate(recommended.sizeMB), style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            SectionHeader("other places")
            InsetGroup(dividerInset = 52.dp) {
                // firstRunPlaceRow: the locality's own glyph in the tint, a tertiary chevron.
                AddRow("connect a computer", "ollama, lmstudio, llama.cpp or any llm engine", WhereLocality.HOME, chevron = true) { onFirstRunPush(FirstRunPush.COMPUTER) }
                // One line at phone width — "your key ·" pushed it to two.
                // and it shrinks a little rather than wrap "brave" onto its own line (lineLimit 1, minimumScaleFactor 0.8)
                CompositionLocalProvider(LocalSubtitleShrinksToFit provides true) {
                    AddRow("use a cloud api", "near.ai, grok, fireworks, openrouter, nvidia, brave", WhereLocality.CLOUD, chevron = true) { onFirstRunPush(FirstRunPush.CLOUD_KEY) }
                }
            }
            Caption("whichever you pick, you can switch per message afterwards.")
        } else {
        // MARK: locality picker
        SegmentedPicker(listOf("all") + WhereLocality.entries.map { it.shortLabel }, localities.indexOf(filter), { filter = localities[it] }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        filterFooter(filter, networkSatisfied)?.let { Caption(it) }
        // MARK: show only — under the tab's footer, scrolling with the list; its one empty line under it
        FilterRow(sheet.count, sheet.control, showOnly) { showOnly = it; onShowOnlyChange(it) }
        // a polite live region, filled a beat after the sheet appears so it is a change the screen reader speaks
        announce?.let { words ->
            var said by remember { mutableStateOf("") }
            LaunchedEffect(words) { kotlinx.coroutines.delay(400); said = words; kotlinx.coroutines.delay(1_500); onAnnounced() }
            Box(Modifier.size(1.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite; contentDescription = said }.testTag("where.announce"))
        }
        sheet.line?.let { Caption(it) }
        // MARK: ready now
        if (rows.isEmpty()) {
            val (title, description) = when {
                problem != null -> "couldn't read your setups" to "your setups are still on this device — teemoon couldn't open the file that lists them, and has kept a copy of it. reopening the app may be enough."
                // The second clause is the point and was missing until 2026-09-21: an offline user reading only
                // "reconnect to use cloud or home" learns that being online is required, which is the opposite of
                // what teemoon offers. Reconnecting is also how you stop needing to.
                !networkSatisfied -> "nothing available offline" to "no on-device model is installed. reconnect to use cloud or home — or to download one to this phone, which then works offline."
                else -> policy.emptyTitle to policy.emptyDescription(openToGet = false)
            }
            // the empty state is a card: the locality's glyph, large, over the title and the line under it — iOS 26's
            // ContentUnavailableView, measured: a 35pt glyph, a 22pt bold title, a 15pt description, ~49pt under it
            InsetGroup { Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.padding(bottom = 4.dp)) { LocalityGlyph(filter ?: WhereLocality.PHONE, tint = TeemoonColors.secondaryLabel, sizeDp = 60.dp) }
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(description, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp), color = TeemoonColors.secondaryLabel, textAlign = TextAlign.Center)
            } }
        } else if (sheet.ready.isNotEmpty() || sheet.refused.isNotEmpty()) {
            SectionHeader("ready now", topPadding = 8.dp)
            // a refused phone model is listed in its place (IMAGES.md §4b), with the permanent caption
            InsetGroup(dividerInset = 52.dp) { rows.filter { it in sheet.ready || it in sheet.refused }.forEach { row ->
                val refusedRow = row in sheet.refused
                val provider = row.asActive
                val selected = row.provider.id == selectedId && row.provider.model == row.modelID
                val pull = pulls.firstOrNull { it.id == row.modelID && it.baseUrl == row.provider.openAIBaseUrl && WhereLocality.of(row.provider) == WhereLocality.HOME }
                val localModel = row.provider.localModelID?.let { localModelFor(it) }
                val arrivingLocal = row.provider.localModelID?.let { downloads[it] }
                val missing = localModel?.takeIf { arrivingLocal == null && !installed(it) }
                val arriving = arrivingLocal ?: pull?.takeIf { !it.isFinished }?.let { it.fraction ?: 0.0 }
                val pullFailed = pull?.phase is OllamaDownloadCenter.Download.Phase.Failed
                // A wi-fi-only download parked on mobile data (iOS 3b1c494): the bar is not moving, the row says why, and
                // a tap re-asks — "download now" one tap away instead of behind cancel-and-start-over.
                val parked = localModel?.takeIf { row.provider.localModelID in parkedDownloads && !installed(it) }
                val caption = when {
                    // A download that FAILED says why, in the downloader's own words — "not downloaded" alone reads as an
                    // interruption, and a checksum rejection is not one.
                    missing != null -> downloadFailures[missing.id] ?: "not downloaded — tap to resume"
                    pullFailed -> "download failed — tap to retry"
                    else -> policy.readyCaption(provider)
                }
                // Nor for weights that aren't here: "failed its integrity check · cold" grades a file that doesn't exist.
                val warmth = if (arriving == null && !pullFailed && missing == null) isWarm(row)?.let { if (it) "warm" else "cold" } else null
                // nor "· images" for weights that aren't here, as warmth
                val images = (arriving == null && !pullFailed && missing == null && readsImages(row.asActive)).takeIf { it }?.let { ImageModelChoice.CAPTION_TAG }
                // rowDestructiveActions: ONE action per row, on the model the row names — the trailing swipe and the context
                // menu both offer it. Weights and a server's model ask first; a cloud row unequips on the spot (iOS: forget +
                // haptic). No setup-level delete here in any segment: that lives in places & keys, on a row that IS the setup.
                val destructive: Pair<String, () -> Unit>? = when (policy.destruction(row.provider)) {
                    WhereGetPolicy.RowDestruction.DELETE_LOCAL_WEIGHTS -> localModel?.let { m -> "delete" to { pendingWeightsDelete = m } }
                    WhereGetPolicy.RowDestruction.DELETE_FROM_SERVER -> "delete from ${WhereProviderPresentation.canonicalName(row.provider)}" to { pendingServerDelete = row }
                    WhereGetPolicy.RowDestruction.UNEQUIP_CLOUD -> "unequip" to { onUnequip(row) }
                    WhereGetPolicy.RowDestruction.NONE -> null
                }
                // While a device file lands, the row is the file's name — "gemma 4 e2b · tensor" — and that leaves no room for
                // iOS's "downloading 17%" beside "cancel" on a phone's width (iOS has no device file, so never this squeeze:
                // WhereChip.swift, "three facts in a space for two"). The orange track under the row already says
                // downloading; the right side keeps the number. The short name keeps iOS's wording.
                val fetching = if (arrivingLocal != null) row.provider.localModelID?.let { downloadNames[it] } else null
                WhereRowView(
                    title = fetching?.lowercase() ?: WhereProviderPresentation.modelLabel(provider),
                    // WhereRow (iOS 0e91620): warm/cold is JOINED ONTO THE CAPTION — "ollama · warm" — not the trailing slot,
                    // where on the selected row it shoved left of the tick and read as the tick's word, not the row's.
                    // greyed as iOS's, title and caption: listed, not usable for images here
                    titleColor = if (refusedRow) TeemoonColors.secondaryLabel else TeemoonColors.label,
                    caption = if (refusedRow) ai.teemoon.app.imaging.CantSeeRoute.permanent(WhereProviderPresentation.modelLabel(provider))
                        else listOfNotNull(caption?.takeIf { it.isNotBlank() }, warmth, images).joinToString(" · ").ifEmpty { null }, locality = WhereLocality.of(row.provider),
                    captionWarns = policy.captionWarns(provider, missingDownload = missing != null, pullFailed = pullFailed),
                    // A wi-fi-only job parked on mobile data is not moving: say why (WhereGetSection: "the row says so").
                    trailingText = if (row.provider.localModelID in parkedDownloads) "waiting for wi-fi"
                        else arriving?.let { if (fetching?.contains(" · ") == true) "${(it * 100).toInt()}%" else "downloading ${(it * 100).toInt()}%" },
                    progress = arriving, onCancel = { if (row.provider.isLocal) onCancelDownload(row.modelID) else onCancelPull(row.modelID) },
                    isSelected = arriving == null && selected,
                    e2ee = false,
                    // A failed server pull RETRIES on the tap (WhereSheetView.readyRow: pullCenter.start(ref:baseURL:)) — the
                    // caption says "tap to retry". This opened the pull sheet, empty, and nothing was retried (C8, 2026-10-04).
                    onClick = { if (refusedRow) refusedTapped = row else if (missing != null) onStartDownload(missing) else if (parked != null) onStartDownload(parked) else if (pullFailed) row.provider.openAIBaseUrl?.let { onRetryPull(row.modelID, it) } ?: onDownload(row.provider.id) else onActivate(row) },
                    menu = listOfNotNull("use for chat" to { onActivate(row) }, "edit" to { onEdit(row.provider.id) }, destructive),
                    swipe = destructive,
                )
            } }
        }

        refusedTapped?.let { row ->
            // the nearest smaller phone model that fits (PhoneImageMemory.smallerModel): switched to if it's here, downloaded if not
            val refusedModel = row.provider.localModelID?.let { id -> localModelFor(id) }
            val smaller = refusedModel?.let { m -> ai.teemoon.providers.PhoneImageMemory.smallerModel(policy.catalog, m, phoneFits) }
            val smallerHere = smaller?.let { s -> rows.firstOrNull { it.provider.localModelID == s.id } }
            IosAlert(ai.teemoon.app.imaging.CantSeeRoute.permanent(WhereProviderPresentation.modelLabel(row.asActive)), "",
                listOfNotNull(smaller?.let { s -> ai.teemoon.app.imaging.CantSeeRoute.useSmaller(s.displayName.lowercase()) to {
                        refusedTapped = null; if (smallerHere != null) onActivate(smallerHere) else onStartDownload(s) } },
                    ai.teemoon.app.imaging.CantSeeRoute.OK to { refusedTapped = null }), onDismiss = { refusedTapped = null })
        }

        // MARK: recently used — only on "all", only online
        if (sheet.recents.isNotEmpty()) {
            SectionHeader("recently used")
            InsetGroup(dividerInset = 52.dp) { sheet.recents.forEach { r ->
                val equipped = providers.firstOrNull { it.id == r.providerID }?.let { Equipped(it, r.modelID) }
                WhereRowView(
                    title = WhereProviderPresentation.modelLabel(r.provider), locality = WhereLocality.of(r.provider),
                    // warmth on the caption, as on the ready rows (WhereRow, iOS 0e91620)
                    caption = listOfNotNull((equipped?.let { policy.readyCaption(it.asActive) } ?: WhereProviderPresentation.placeCaption(r.provider)).takeIf { it.isNotBlank() },
                        equipped?.let(isWarm)?.let { if (it) "warm" else "cold" },
                        equipped?.takeIf { readsImages(it.asActive) }?.let { ImageModelChoice.CAPTION_TAG }).joinToString(" · ").ifEmpty { null },
                    onClick = { onActivateRecent(r) },
                    menu = listOf("use for chat" to { onActivateRecent(r) }),
                )
            } }
            Caption("what you've actually run here lately, most recent first.")
        }

        // MARK: get
        // The rows are WhereFilterSheet's, in its order: the tab's offers (the all tab's one recommended download; a
        // device file landing over an existing place shows on that place's row and its get row steps aside), narrowed
        // by the filter. A filter that leaves get empty drops the section; the empty line above says so.
        val getRows = sheet.get
        if (getRows == null || getRows.isNotEmpty() || showOnly.isEmpty()) SectionHeader("get")
        if (getRows == null) {
            Caption("connect to a network to add cloud keys or reach home servers.")
        } else if (getRows.isNotEmpty() || showOnly.isEmpty()) InsetGroup(dividerInset = 52.dp) { getRows.forEach { shown ->
            when (val row = shown.row) {
                is WhereFilter.GetRow.Download -> policy.catalog.firstOrNull { it.id == row.id }?.let { DownloadRow(it, downloads[it.id], downloadFailures[it.id], totalMemBytes, onStartDownload, onCancelDownload, parked = it.id in parkedDownloads) }
                is WhereFilter.GetRow.Pull -> providers.firstOrNull { it.id == row.machine }?.let { PullRow(policy, it, onDownload) }
                WhereFilter.GetRow.Connect -> AddRow("connect a computer", "ollama, lm studio, or any openai-compatible server", WhereLocality.HOME) { onAddPlace(null, AddEditProviderModel.CustomStart.COMPUTER) }
                is WhereFilter.GetRow.Server ->
                    if (row.custom) providers.firstOrNull { it.id == row.name }?.let { p ->
                        // a filter's "k of n" takes the caption's number, where this row says how many it holds
                        val caption = shown.trailing?.takeIf { showOnly.isNotEmpty() }?.let { "$it models on this setup" } ?: policy.browseCaption(p)
                        AddRow("browse ${policy.browseName(p)}", caption, WhereLocality.CLOUD, glyph = Icons.Filled.Search) { browse(p.id) }
                    } else Provider.presets.firstOrNull { it.name.lowercase() == row.name }?.let { PresetRow(it, providers, hasKey, shown.trailing, onAddPlace, browse) }
                WhereFilter.GetRow.AddCloudKey -> AddRow("add a cloud key", "configures an api key for any provider", WhereLocality.CLOUD, leadingGlyph = { KeyGlyph(TeemoonColors.tint) }) { onAddPlace(null, AddEditProviderModel.CustomStart.CLOUD_KEY) }
            }
        } }
        policy.getFooter(openToGet = false, memoryFigure = LocalMemory.weightsHeadroomFigure(availableMB)).takeIf { it.isNotEmpty() }?.let { Caption(it) }
        }

    }

    pendingServerDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingServerDelete = null },
            title = { Text("delete ${row.modelID} from that machine?") },
            text = { Text("removes the weights from ${WhereProviderPresentation.canonicalName(row.provider)}. teemoon can pull it again, which downloads it to the machine from scratch.") },
            confirmButton = { TextButton(onClick = { onDeleteFromServer(row); pendingServerDelete = null }) { Text("delete") } },
            dismissButton = { TextButton(onClick = { pendingServerDelete = null }) { Text("keep") } },
        )
    }
    pendingWeightsDelete?.let { m ->
        AlertDialog(onDismissRequest = { pendingWeightsDelete = null }, title = { Text("delete ${m.displayName}?") },
            // MEASURED, not the catalog's figure — see installedBytesFor.
            text = { Text("frees ${ai.teemoon.providers.byteSizeLabel(installedBytesFor(m))}. it moves back to get, and you can download it again.") },
            confirmButton = { TextButton(onClick = { onDeleteWeights(m); pendingWeightsDelete = null }) { Text("delete") } },
            dismissButton = { TextButton(onClick = { pendingWeightsDelete = null }) { Text("keep") } })
    }
    deleteFailure?.let {
        AlertDialog(onDismissRequest = onDismissDeleteFailure, title = { Text("couldn't delete") }, text = { Text(it) },
            confirmButton = { TextButton(onClick = onDismissDeleteFailure) { Text("ok") } })
    }
}

/** WhereSheetView+Status.filterFooter. */
fun filterFooter(filter: WhereLocality?, networkSatisfied: Boolean): String? {
    if (!networkSatisfied) return "offline — only on-device setups can answer until you're back on a network."
    return when (filter) {
        null -> null
        WhereLocality.PHONE -> "runs entirely on this device. no key; nothing leaves the phone unless web search is on."
        WhereLocality.HOME -> "stays on your machines — laptop, desktop, or home server."
        WhereLocality.CLOUD -> "runs on someone else's hardware. each row says whether it's end-to-end encrypted."
    }
}

@Composable private fun SectionHeader(title: String, topPadding: Dp = 20.dp) = SectionLabel(title, style = MaterialTheme.typography.titleMedium, topPadding = topPadding)
@Composable private fun Caption(text: String) = GroupFooter(text)

@Composable
private fun Banner(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    InsetGroup(Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel)
            action?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TeemoonColors.tint, modifier = Modifier.padding(top = 6.dp).clickable(onClick = onAction)) }
        }
    }
}

/** WhereRow: the locality glyph, the model as the title, the place as the caption, warm/cold or a download at the trailing edge, the check on the equipped row. */
@Composable
private fun WhereRowView(
    title: String, caption: String?, locality: WhereLocality? = null, captionWarns: Boolean = false, trailingText: String? = null,
    progress: Double? = null, onCancel: () -> Unit = {}, isSelected: Boolean = false, e2ee: Boolean = false,
    onClick: () -> Unit, menu: List<Pair<String, () -> Unit>> = emptyList(), titleColor: Color = TeemoonColors.label,
    /** WhereRow.glyph / glyphTint: an action row's glyph (magnifier, key) in the accent; a place's glyph in the accent for machines and clouds, secondary for the phone. */
    glyph: ImageVector? = null, glyphTint: Color? = null,
    /** WhereRow.trailingGlyph: a chevron where the row navigates, a download circle where it fetches. */
    chevron: Boolean = false, download: Boolean = false, isFirstRunRow: Boolean = false,
    /** A drawn leading glyph, when the row's SF symbol is not a locality (arrow.down.circle, key). */
    leadingGlyph: (@Composable () -> Unit)? = null,
    /** `.swipeActions(edge: .trailing)`: the row's one destructive action, revealed from the trailing edge; no leading swipe. */
    swipe: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tint = glyphTint ?: when (locality) { WhereLocality.HOME, WhereLocality.CLOUD -> TeemoonColors.tint; else -> TeemoonColors.secondaryLabel }
    val body: @Composable () -> Unit = { WhereRowBody(title, caption, captionWarns, trailingText, progress, onCancel, isSelected, e2ee, onClick, menu, titleColor, glyph, tint, chevron, download, isFirstRunRow, leadingGlyph, locality, open, { open = it }, modifier) }
    if (swipe != null) TrailingSwipe(swipe.first, swipe.second, content = body) else body()
}

@Composable
private fun WhereRowBody(
    title: String, caption: String?, captionWarns: Boolean, trailingText: String?, progress: Double?, onCancel: () -> Unit, isSelected: Boolean, e2ee: Boolean,
    onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>, titleColor: Color, glyph: ImageVector?, tint: Color, chevron: Boolean, download: Boolean, isFirstRunRow: Boolean,
    leadingGlyph: (@Composable () -> Unit)?, locality: WhereLocality?, open: Boolean, setOpen: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    Column {
        Box {
        InsetRow(
            modifier = modifier,
            title = title.lowercase(), titleColor = titleColor, titleWeight = FontWeight.SemiBold,
            subtitle = if (progress != null) null else caption?.lowercase(), subtitleColor = if (captionWarns) TeemoonColors.orange else TeemoonColors.secondaryLabel,
            onClick = onClick, onLongClick = if (menu.isNotEmpty()) ({ setOpen(true) }) else null,
            leading = { if (leadingGlyph != null) leadingGlyph() else if (glyph != null) Icon(glyph, null, tint = tint, modifier = Modifier.size(20.dp)) else locality?.let { LocalityGlyph(it, tint = tint) } },
            titleTags = if (e2ee) ({ E2EETag() }) else null,
            trailing = {
                trailingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TeemoonColors.secondaryLabel) }
                // WhereRow: an `xmark.circle.fill` in the tertiary colour in the tick's slot, not a word — the arriving row
                // is never the selected one, so the two never compete (parity capture, 2026-10-05: this drew "cancel")
                if (progress != null) Box(Modifier.size(32.dp).clickable(onClick = onCancel).semantics { contentDescription = A11yLabels.CANCEL_DOWNLOAD }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(18.dp).background(TeemoonColors.tertiaryLabel, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, null, tint = TeemoonColors.groupedCard, modifier = Modifier.size(12.dp))
                    }
                }
                if (isSelected) Icon(Icons.Filled.Check, "selected", tint = TeemoonColors.tint, modifier = Modifier.size(18.dp))
                if (download) Box(Modifier.semantics { contentDescription = "download" }) { DownloadGlyph(TeemoonColors.tint, 22.dp) }   // arrow.down.circle, drawn
            },
            chevron = chevron, chevronTint = if (chevron && isFirstRunRow) TeemoonColors.tertiaryLabel else TeemoonColors.tint,
        )
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            menu.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { setOpen(false); act() }) }
        }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 10.dp), color = TeemoonColors.tint, trackColor = TeemoonColors.fill)
    }
}

/** addProviderRow: no trailing glyph — only rows that navigate elsewhere (browse) or fetch (download) carry one. */
@Composable private fun AddRow(label: String, caption: String?, locality: WhereLocality, glyph: ImageVector? = null, chevron: Boolean = false, leadingGlyph: (@Composable () -> Unit)? = null, onClick: () -> Unit) = WhereRowView(title = label, caption = caption, locality = locality, onClick = onClick, glyph = glyph, glyphTint = TeemoonColors.tint, chevron = chevron, isFirstRunRow = chevron, leadingGlyph = leadingGlyph)

@Composable private fun PullRow(policy: WhereGetPolicy, p: Provider, onDownload: (String) -> Unit) =
    WhereRowView(title = "add a model to ${policy.browseName(p)}", caption = "pulls it onto the machine — it appears above when it lands", locality = WhereLocality.HOME, onClick = { onDownload(p.id) }, glyphTint = TeemoonColors.tint, chevron = true, leadingGlyph = { DownloadGlyph(TeemoonColors.tint) })   // arrow.down.circle

/**
 * The where filter's row (IMAGES.md §4e): the tab's count on the left; "show only" on the right, a menu with the two
 * filters and their ticks — the model browser's Controls in its words and glyphs, with no sort and no "clear".
 */
@Composable private fun FilterRow(count: String?, control: String, showOnly: Set<ModelBrowserQuery.Filter>, onChange: (Set<ModelBrowserQuery.Filter>) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 44.dp).testTag("where.filter"), verticalAlignment = Alignment.CenterVertically) {
        Text(count.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TeemoonColors.secondaryLabel, modifier = Modifier.weight(1f).testTag("where.filter.count"))
        Box {
            val on = showOnly.isNotEmpty()
            Text("≡ $control ⌄", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                color = if (on) TeemoonColors.tint else TeemoonColors.secondaryLabel,
                modifier = Modifier.clickable { menu = true }.padding(vertical = 10.dp).testTag("where.filter.control"))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(WhereFilter.MENU_HEADING, style = MaterialTheme.typography.labelSmall, color = TeemoonColors.secondaryLabel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                WhereFilter.offered.forEach { f ->
                    val ticked = f in showOnly
                    DropdownMenuItem(text = { Text((if (ticked) "✓ " else "   ") + f.label) },
                        onClick = { onChange(if (ticked) showOnly - f else showOnly + f) }, modifier = Modifier.testTag("where.filter.${f.summary}"))
                }
            }
        }
    }
}

/** WhereGetSection.downloadRow: a downloadable on-device model — size, progress, cancel, the failure or the memory note. */
@Composable private fun DownloadRow(model: LocalModel, fraction: Double?, failure: String?, totalMemBytes: Long, onStart: (LocalModel) -> Unit, onCancel: (String) -> Unit, parked: Boolean = false) {
    // The phone's RAM against the model's floor, never free memory (iOS f3773d9): an 8 GB Pixel 8a was told it may not fit.
    val note = failure ?: if (fraction == null && LocalMemory.belowFloor(model, totalMemBytes)) "may not fit in memory on this device" else null
    WhereRowView(title = model.displayName, caption = WhereProviderPresentation.metadataRun(model.blurb, note), captionWarns = note != null, locality = WhereLocality.PHONE,
        trailingText = if (fraction == null) model.sizeLabel.lowercase() else if (parked) "waiting for wi-fi" else "downloading ${(fraction * 100).toInt()}%",
        progress = fraction, onCancel = { onCancel(model.id) }, onClick = { if (fraction == null) onStart(model) }, download = fraction == null)
}

@Composable
private fun PresetRow(preset: Provider, providers: List<Provider>, hasKey: (Provider) -> Boolean, trailing: String?, onAddPlace: (String?, AddEditProviderModel.CustomStart?) -> Unit, onBrowse: (String) -> Unit) {
    val configured = providers.firstOrNull { it.sameEndpoint(preset) }
    val provider = configured ?: preset
    val keyed = hasKey(provider)
    if (preset.isFixedAnswerService) {
        // Settings' row — same glyph, caption and two lines — with where's trailing state (iOS cf251b5). The two-key
        // warning this row used to carry is the key form's footer now.
        if (configured == null) WhereRowView(title = preset.name.lowercase(), caption = WhereProviderPresentation.presetCaption(preset), locality = WhereLocality.CLOUD, glyphTint = TeemoonColors.tint, chevron = true, trailingText = trailing, onClick = { onAddPlace(preset.name, null) },
            modifier = Modifier.testTag("where.preset.${preset.name.lowercase()}"))
    } else {
        // The count is WhereFilterSheet's — the length of the list this server last answered with, or "k of n" of it
        // under a filter. Never compute one here: two numbers for one row is how they come to disagree.
        // Settings' add-cloud-key row, with where's trailing state: the model count once keyed, "add key" before. Same
        // glyph, name and caption, so the two screens read as one list (iOS cf251b5).
        WhereRowView(title = preset.name.lowercase(), caption = WhereProviderPresentation.presetCaption(preset), locality = WhereLocality.CLOUD, glyphTint = TeemoonColors.tint, chevron = true,
            trailingText = trailing, e2ee = preset.claimsEndToEndEncryption,
            onClick = { if (keyed) onBrowse((configured ?: preset).id) else onAddPlace(preset.name, null) },
            modifier = Modifier.testTag("where.preset.${preset.name.lowercase()}"))
    }
}

/**
 * The where sheet's reasoning bar (the maintainer 2026-11-13, card composer-reasoning-chip): one inset row, "reasoning", the level
 * and a chevron; it pushes to the levels, which the model's name heads. The list scrolls under it at both sheet heights.
 */
@Composable
fun ReasoningBar(bar: ReasoningUi.Bar, onOpen: () -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints {
    // drawn flat on the sheet's band (GroupedPage's footer), no card: part of the frame, not another row (the maintainer, "Do 1 and 3");
    // InsetRow's own 16 are the side insets, as iOS's. One line wherever "reasoning", the level and the chevron fit; the
    // level goes under the title only where they don't.
    val style = MaterialTheme.typography.bodyLarge
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val need = with(androidx.compose.ui.platform.LocalDensity.current) {
        measurer.measure(bar.title, style, softWrap = false).size.width + measurer.measure(bar.value, style, softWrap = false).size.width +
            (16 + 12 + 12 + 20 + 16).dp.roundToPx()   // the row's insets, two gaps and the chevron
    }
    val shown = bar.trailing(oneLineFits = need <= constraints.maxWidth)
    InsetRow(bar.title, subtitle = shown.subtitle, onClick = onOpen, chevron = true, modifier = Modifier.testTag("where.reasoning"),
        trailing = shown.value?.let { v -> { Text(v, style = style, color = TeemoonColors.secondaryLabel, maxLines = 1, softWrap = false) } })
}

/**
 * The levels, pushed inside the where sheet from its reasoning bar: the model's own, the pick checked, applied at once;
 * the footer says what a higher level costs and, after a refusal, that nothing is sent (ReasoningUi.levelsFooter).
 */
@Composable
fun ReasoningLevelsPage(ui: ReasoningUi, onPick: (String) -> Unit, onBack: () -> Unit) =
    GroupedPage(header = { NavBar(ai.teemoon.inference.ReasoningLevel.TITLE, leading = { BackButton(onBack) }) }) {
        ui.bar?.let { SectionLabel(it.model) }
        InsetGroup {
            ui.levels.forEachIndexed { i, l ->
                // after a refusal nothing is sent: every row greyed and inert, none checked (iOS parity)
                InsetRow(l.label, onClick = if (l.enabled) ({ onPick(l.level) }) else null, divider = i < ui.levels.lastIndex,
                    titleColor = if (l.enabled) TeemoonColors.label else TeemoonColors.tertiaryLabel,
                    modifier = Modifier.testTag("reasoning.level.${l.level}").semantics { if (!l.enabled) disabled() },
                    trailing = { if (l.picked) Icon(Icons.Filled.Check, "selected", tint = TeemoonColors.tint, modifier = Modifier.size(20.dp)) })
            }
        }
        // the refusal, when there is one, its own paragraph first; the page scrolls, so neither is ever cut
        ui.levelsFooter.forEach { GroupFooter(it) }
    }
