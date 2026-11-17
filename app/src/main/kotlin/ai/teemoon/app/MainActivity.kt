@file:OptIn(ExperimentalComposeUiApi::class)

package ai.teemoon.app

import ai.teemoon.app.imaging.chooser
import ai.teemoon.app.imaging.clipData
import ai.teemoon.app.imaging.write
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.confidential.EncryptedModelCTA
import ai.teemoon.providers.ModelBrowserDoor
import ai.teemoon.providers.WhereProviderPresentation
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderPresets
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.search.TranscriptDeepLink
import ai.teemoon.confidential.AuditIndex
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.LlmError
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.CellularDownloadGate
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.LocalModelDownloader
import ai.teemoon.providers.PlacesKeysBadge
import ai.teemoon.providers.ProviderCapability
import ai.teemoon.providers.ProviderHealing
import ai.teemoon.providers.WhereLocality
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

internal sealed interface Route {
    data object Threads : Route
    data class Chat(val threadId: String) : Route
    data object Where : Route
    /** The where sheet's reasoning bar pushes here: the selected model's levels (developer mode, the maintainer 2026-11-13). */
    data object ReasoningLevels : Route
    data class Download(val providerId: String) : Route
    data object Trust : Route
    /** Guided add: a preset name, or a custom start. */
    data class AddPlace(val presetName: String?, val custom: AddEditProviderModel.CustomStart?) : Route
    data class EditPlace(val providerId: String) : Route
    data object Settings : Route
    data object Appearance : Route
    data object ChatsSettings : Route
    data object SystemPrompt : Route
    /** "import from near.ai" (NearImportScreen, Safari style): start, the file card, progress, done. */
    data object NearImport : Route
    data object Search : Route
    data object Credits : Route
    /**
     * [encryptedOnly]: the trust ladder's picker — near.ai filtered to what teemoon can SEAL. The button that opens
     * it reads "switch to an end-to-end encrypted model on near.ai"; the unfiltered browser it opened until 2026-09-22
     * listed near.ai's proxied Claude and GPT rows under that sentence.
     * [imagesOnly]: the paperclip's "find a model that reads images" — the server's browser with "reads images" on
     * (iOS b8457654, WhereSheetView.browseImagesIfAsked); cancel returns to the where sheet, as iOS's browser sheet does.
     * [showOnly]: a where-sheet row tapped with "show only" on opens its browser with the same filters (IMAGES.md §4e).
     */
    data class Browse(val providerId: String, val encryptedOnly: Boolean = false, val imagesOnly: Boolean = false, val showOnly: Set<ModelBrowserQuery.Filter> = emptySet()) : Route
    data object PlacesHub : Route
    /** Step one of "add cloud key": pick the provider. The key form is the next push (CloudKeyProviderPickerScreen). */
    data object CloudKeyPicker : Route
    data class PlacesManage(val locality: WhereLocality) : Route
}

/**
 * A sheet's detents, the first the one it opens at. The where sheet opens at full height at accessibility sizes, for
 * everyone (the maintainer 2026-11-13, "Do 1 and 3"): at half height its header controls left about one model row.
 */
internal fun sheetTops(top: Route?, whereFirstRun: Boolean, fontScale: Float, screenHeightDp: Int = Int.MAX_VALUE): List<Float> = when {
    top == Route.Trust -> listOf(SheetDetents.LARGE)
    (top == Route.Where || top == Route.ReasoningLevels) && (fontScale >= ComposerPanel.ACCESSIBILITY_SCALE || screenHeightDp <= SHORT_PHONE_DP) -> listOf(SheetDetents.LARGE)
    top == Route.Where && whereFirstRun -> listOf(SheetDetents.FIRST_RUN, SheetDetents.LARGE)
    // two thirds, not half (the maintainer 2026-11-13, "Ok make bigger"): 4-5 model rows under the header
    top == Route.Where || top == Route.ReasoningLevels -> listOf(SheetDetents.WHERE, SheetDetents.LARGE)
    else -> listOf(SheetDetents.MEDIUM, SheetDetents.LARGE)
}

/**
 * A phone this short opens the where sheet full for everyone (the maintainer 2026-11-13, "Yes yes"; iOS: height ≤ 667 pt, the SE
 * and mini): at half height its header controls leave about one model row. 640dp is the classic 360×640 phone class.
 */
const val SHORT_PHONE_DP = 640

/** A sheet page's depth: a move to a deeper page slides in from the right (a push), to a shallower one from the left (a pop). */
internal fun routeLevel(r: Route?): Int = when (r) {
    null, is Route.Chat -> 0
    Route.Threads, Route.Where, Route.Settings, Route.Trust -> 1
    is Route.PlacesManage, Route.NearImport -> 3
    // pushed FROM cloud keys (3), so it slides in from the right; at 2 the push animated as a pop (from the left)
    Route.CloudKeyPicker -> 4
    else -> 2
}

/** How a sheet's page changes: a push slides in from the right, a pop from the left, a swap to another stack just appears. */
internal enum class PageSwap { PUSH, POP, SWAP }

/**
 * A page's stack: the sheet root (level 1) its parent chain reaches. "show chats" after an import swapped settings ›
 * chats for the chat list, another stack, and the level drop animated it as a pop: the list slid in from a third off
 * the left while the import sheet closed (the maintainer's Pixel, 2026-11-07). Between stacks nothing slides.
 */
internal fun pageSwap(from: Route, to: Route, parentOf: (Route) -> Route?): PageSwap {
    fun root(r: Route): Route? {
        var at: Route = r
        repeat(8) { if (routeLevel(at) <= 1) return at; at = parentOf(at) ?: return null }
        return null
    }
    if (root(from) != root(to)) return PageSwap.SWAP
    return if (routeLevel(to) > routeLevel(from)) PageSwap.PUSH else PageSwap.POP
}

/**
 * The key form's opener. iOS presents AddEditProviderView as a sheet over the screen that asked for it (WhereSheetView,
 * TrustLadderView, PlacesKeysViews `.sheet(item: $editing)`), so cancel and save return there and that screen stays
 * under the form. Here the form's parent was Where for every opener but Trust: settings › cloud keys › edit › cancel
 * landed on the Where sheet, and sheet A swapped from cloud keys to Where behind the push (the maintainer's Pixel, 2026-11-06).
 */
internal object FormOpener {
    /** Where the form's cancel and save go. No opener recorded: the where sheet, which opens most forms. */
    fun parent(opener: Route?): Route = opener ?: Route.Where
    /**
     * "add cloud key": cloud keys › the provider picker › the form. iOS presents it over cloud keys, so cancel and save
     * return there (pm 2026-11-06). First run's own push keeps its stack — back to the picker, then the where sheet.
     */
    fun fromCloudKeyPicker(firstRunPush: Boolean): Route? = if (firstRunPush) null else Route.PlacesManage(WhereLocality.CLOUD)
    /** Sheet A for a route: under the form, its opener. */
    fun sheetA(route: Route?, opener: Route?): Route? = when (route) {
        null, is Route.Chat -> null
        is Route.AddPlace, is Route.EditPlace -> parent(opener)
        is Route.Browse, is Route.Download -> Route.Where
        // under the import's own sheet, the page that opened it
        Route.NearImport -> Route.ChatsSettings
        else -> route
    }
    /**
     * Sheet B, the full-height sheet over sheet A: the key forms, the browser, a download — and the near.ai import, its
     * own full-height modal sheet (v9; iOS). In sheet A it opened at the settings sheet's half height, where "choose
     * file…" sat below the fold and the page didn't scroll to it (the emulator import run, 2026-11-06).
     */
    fun sheetB(route: Route?): Route? = when (route) {
        is Route.AddPlace, is Route.EditPlace, is Route.Browse, is Route.Download, Route.NearImport -> route
        else -> null
    }
}

/** The route as saved state: the app comes back where it was after process death (docs/ANDROID_NATIVE.md). */
internal object RouteCodec {
    fun encode(r: Route): String = when (r) {
        Route.Threads -> "threads"; Route.Where -> "where"; Route.Trust -> "trust"; Route.Settings -> "settings"
        Route.ReasoningLevels -> "reasoning"
        Route.Appearance -> "appearance"; Route.ChatsSettings -> "chats"; Route.SystemPrompt -> "systemprompt"; Route.NearImport -> "nearimport"; Route.Search -> "search"; Route.Credits -> "credits"; Route.PlacesHub -> "placeshub"; Route.CloudKeyPicker -> "cloudkeypicker"
        is Route.Chat -> "chat:" + r.threadId; is Route.Download -> "download:" + r.providerId; is Route.EditPlace -> "edit:" + r.providerId
        is Route.Browse -> if (r.showOnly.isNotEmpty()) "showonly:" + r.showOnly.joinToString(",") { it.name } + "|" + r.providerId
            else (if (r.encryptedOnly) "sealedbrowse:" else if (r.imagesOnly) "imagebrowse:" else "browse:") + r.providerId
        is Route.PlacesManage -> "manage:" + r.locality.name
        is Route.AddPlace -> "add:" + (r.presetName ?: "") + "|" + (r.custom?.name ?: "")
    }
    fun decode(s: String): Route? {
        val (k, v) = s.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (k) {
            "threads" -> Route.Threads; "where" -> Route.Where; "trust" -> Route.Trust; "settings" -> Route.Settings
            "reasoning" -> Route.ReasoningLevels
            "appearance" -> Route.Appearance; "chats" -> Route.ChatsSettings; "systemprompt" -> Route.SystemPrompt; "nearimport" -> Route.NearImport; "search" -> Route.Search; "credits" -> Route.Credits; "placeshub" -> Route.PlacesHub; "cloudkeypicker" -> Route.CloudKeyPicker
            "chat" -> Route.Chat(v); "download" -> Route.Download(v); "edit" -> Route.EditPlace(v); "browse" -> Route.Browse(v); "sealedbrowse" -> Route.Browse(v, encryptedOnly = true); "imagebrowse" -> Route.Browse(v, imagesOnly = true)
            "showonly" -> v.split("|", limit = 2).takeIf { it.size == 2 }?.let { (fs, id) ->
                Route.Browse(id, showOnly = fs.split(",").mapNotNull { f -> runCatching { ModelBrowserQuery.Filter.valueOf(f) }.getOrNull() }.toSet()) }
            "manage" -> runCatching { Route.PlacesManage(WhereLocality.valueOf(v)) }.getOrNull()
            "add" -> v.split("|").let { Route.AddPlace(it[0].ifEmpty { null }, it.getOrNull(1)?.ifEmpty { null }?.let { c -> runCatching { AddEditProviderModel.CustomStart.valueOf(c) }.getOrNull() }) }
            else -> null
        }
    }
    val saver = Saver<Route?, String>(save = { it?.let(::encode) ?: "" }, restore = { if (it.isEmpty()) null else decode(it) })

    /**
     * One encoded sample of every route, for the screen crawl (RouteCrawlTest).
     *
     * Coverage here comes from the ROUTE TABLE rather than from someone remembering to script a path, which is the
     * difference between this and ParityCaptureTest: add a screen and it is crawled, screenshotted and checked
     * without anyone deciding to. `encode` above is an exhaustive `when`, so a new Route breaks the build a few
     * lines up — which is the prompt to add it here too.
     *
     * The ids are what UiTestSeeding seeds, so a crawl run lands on real rows rather than empty states.
     */
    internal val CRAWL_SAMPLES: List<String> = listOf(
        "threads", "where", "reasoning", "trust", "settings", "appearance", "chats", "nearimport", "search", "credits", "placeshub",
        "manage:CLOUD", "manage:HOME", "cloudkeypicker", "add:|", "add:near.ai|", "browse:A0000000-0000-0000-0000-000000000001", "sealedbrowse:A0000000-0000-0000-0000-000000000001",
        "imagebrowse:A0000000-0000-0000-0000-000000000004",
    )
}

/** Whether the user is looking at the app. Read when a turn ends, to decide whether finishing is news. */
private var foreground = false

class MainActivity : ComponentActivity() {
    /** Set from the `seed_route` launch extra on a debuggable build; null on every normal launch. */
    private var seedRoute: Route? = null

    /**
     * The thread a "reply ready" notification was tapped for (TurnService.open puts it on the intent). Read on a cold
     * start AND on onNewIntent — the notification's SINGLE_TOP|CLEAR_TOP delivers to the running activity — and
     * consumed by Root, which opens that chat. Until 2026-09-24 nothing read it: the tap brought the app forward on
     * whatever screen it had been left on.
     */
    private val openThread = MutableStateFlow<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getStringExtra(TurnService.EXTRA_OPEN_THREAD)?.let { openThread.value = it }
        receiveExport(intent); receiveShare(intent)
    }

    /** A share of photos, text or a URL (ShareIn): the draft a new chat opens with. Root takes it. */
    private val incomingShare = MutableStateFlow<ai.teemoon.app.imaging.ShareIn.Draft?>(null)

    /**
     * Share to teemoon (ShareIn, SHARE_IN.md @ 1f60fe9c): photos are read NOW, while this activity holds the share's read
     * grant, through the picker's path (PickedImages → the isolated decoder); over a cap nothing is read and the new chat
     * says so. The import keeps its intents (receiveExport): a single .json is never a draft.
     */
    private fun receiveShare(intent: Intent?) {
        intent ?: return
        val streams: List<android.net.Uri> = when (intent.action) {
            ai.teemoon.app.imaging.ShareIn.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
            ai.teemoon.app.imaging.ShareIn.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
            else -> emptyList()
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val type = intent.type ?: streams.firstOrNull()?.let { contentResolver.getType(it) }
        val kind = ai.teemoon.app.imaging.ShareIn.kind(intent.action, type, streams.size, streams.firstOrNull()?.scheme, text.isNotEmpty())
        // offered to teemoon but nothing to take: a new chat says so, never the old chat without a word (design review)
        if (ai.teemoon.app.imaging.ShareIn.unusable(intent.action, kind)) { incomingShare.value = ai.teemoon.app.imaging.ShareIn.unusableDraft(); return }
        if (kind != ai.teemoon.app.imaging.ShareIn.Kind.DRAFT) return
        lifecycleScope.launch(Dispatchers.IO) {
            val sizes = streams.map { uri -> runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { n -> n >= 0 } } }.getOrNull() }
            incomingShare.value = if (ai.teemoon.app.imaging.ShareIn.overCap(streams.size, sizes, text.length)) ai.teemoon.app.imaging.ShareIn.overCapDraft(ai.teemoon.app.imaging.ShareIn.overCapNote(streams.size, sizes, text.length))
                else if (streams.isEmpty()) ai.teemoon.app.imaging.ShareIn.draft(text, emptyList())
                // the chat opens NOW with a tile per photo; the reads run on while this activity holds the share's grant
                else ai.teemoon.app.imaging.ShareIn.Draft(text, emptyList(), null, pending = streams.size,
                    later = lifecycleScope.async(Dispatchers.IO) { streams.map { uri -> ai.teemoon.app.imaging.PickedImages.read(contentResolver, uri, shareDecoder) } })
        }
    }
    private val shareDecoder by lazy { ai.teemoon.app.imaging.IsolatedImageDecoder(applicationContext) }

    /** An export opened from Files or shared to teemoon (IncomingExport): how to read its cached copy. Root imports it. */
    private val incomingExport = MutableStateFlow<IncomingExport.Arrival?>(null)

    /**
     * Copies the export to the cache NOW, while this activity holds the URI's read grant (IncomingExport). A copy that
     * fails still reaches the import, as a read that throws: the import's "can't open" card says so, never silence.
     */
    private fun receiveExport(intent: Intent?) {
        intent ?: return
        val uri = (if (intent.action == Intent.ACTION_SEND) androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java) else intent.data) ?: return
        if (!IncomingExport.accepts(intent.action, intent.type ?: contentResolver.getType(uri), uri.scheme)) return
        lifecycleScope.launch(Dispatchers.IO) {
            // the card's name/size/date, asked while the grant holds; the copy's own name is ours, not the user's
            val about = ImportFileInfo.of(contentResolver, uri)
            incomingExport.value = runCatching { IncomingExport.copy({ contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString()) }, cacheDir) }
                .fold(onSuccess = { copy -> IncomingExport.Arrival({ copy.inputStream() }, about, copy) }, onFailure = { e -> IncomingExport.Arrival({ throw e }, about, null) })
        }
    }

    // teemoonApp's scenePhase `.active` (iOS b42f5d8): launch and every return to the foreground. Backgrounding evicts
    // the engine (TeemoonApplication.onTrimMemory), so coming back with the same on-device provider was a cold engine
    // until the first message. Warm it now — idempotent when resident (one build per key), and it stands down behind a
    // running turn (LocalGenerationGate).
    override fun onStart() { super.onStart(); foreground = true; (application as? TeemoonApplication)?.state?.let { it.warmUp(it.selected) } }
    override fun onStop() { super.onStop(); foreground = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with LIGHT system bar icons while the app is pinned dark (Theme.kt): the default style, which reads
        // the system's uiMode, would draw dark icons over the black ground on a light-mode phone.
        applyBars()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getStringExtra(TurnService.EXTRA_OPEN_THREAD)?.let { openThread.value = it }
        if (savedInstanceState == null) { receiveExport(intent); receiveShare(intent) }
        val opened = ChatDatabase.open(this)
        val chatDb = when (opened) { is ChatDatabase.Open.Ready -> opened.db; is ChatDatabase.Open.Failed -> opened.db }
        val dao = chatDb.chatDao()
        // ONE AppState per process, as iOS has one for the app's life: it owns the on-device engine. A second MainActivity
        // (the system recreating the screen, a launch into a fresh task) used to build a second AppState — a second
        // engine cache — and the next on-device turn refused with "not enough free memory (needs 3482 MB, 2506 MB
        // available)" while the first engine was still resident (Pixel 10 Pro, 2026-10-03). Built from the APPLICATION
        // context, so outliving an activity leaks none.
        val holder = application as? TeemoonApplication
        val app = holder?.state ?: AppState(applicationContext, dao).also { holder?.state = it }
        // iOS's `--uitesting` seeding: a debuggable build launched with the extras configures its places from
        // host key files pushed into the app's private directory, before the first frame (UiTestSeeding).
        // once per launch: the scripts' marker is spent by the launch it arms (UiTestSeeding.consumeArm)
        val seedingArmed = UiTestSeeding.consumeArm(this, intent)
        if (seedingArmed && savedInstanceState == null) {
            val extras = intent?.extras?.let { b -> b.keySet().associateWith { k -> @Suppress("DEPRECATION") b.get(k) } }.orEmpty()
            UiTestSeeding.plan(extras)?.let { plan ->
                // Off the main thread, as iOS's `.task` is: five Keystore writes and the attestation refreshes they
                // trigger took the emulator past the input-dispatch deadline when run inline (ANR at launch, 2026-09-04).
                // The harness waits for the "[uitest] seeded" line before it drives the screen.
                val dir = File(filesDir, UiTestSeeding.KEY_DIR)
                // A seeded launch starts the session from nothing: the AppState is the process's now, so the last
                // journey's verdicts, flags and attested context would otherwise carry into this one.
                app.session.uiTestReset()
                if (plan.attestation == "degraded") app.session.uiTestSeededDegraded = true
                if (plan.attestation == "verifyingPending") app.session.uiTestPlantsPendingVerifier = true
                ConfidentialSession.UiTestSeed.of(plan.attestation)?.let { app.session.uiTestSeed = it }
                (extras[UiTestSeeding.EXTRA_MODEL_HOST] as? String)?.takeIf { it.isNotBlank() }?.let { LocalModelDownloader.hostOverride = it }
                (extras[UiTestSeeding.EXTRA_MOBILE_DATA] as? Boolean)?.let { app.network.uiTestOverrideMobileData(it) }   // before any place is selected, so the first refresh sees it
                // every seeded launch sets it, so one journey's offline path never carries into the next in this process
                app.network.uiTestOverrideSatisfied(if (extras[UiTestSeeding.EXTRA_OFFLINE] == true) false else null)
                // seed_config: the planted file is the whole world — synchronous, so the first frame already shows the problem
                val planted = (extras[UiTestSeeding.EXTRA_CONFIG] as? String)?.takeIf { it.isNotBlank() }
                if (planted != null) {
                    app.uiTestReloadConfig { f -> Log.w("teemoon", "[uitest] seeded: config $planted " + (if (UiTestSeeding.plantConfig(planted, f)) "planted" else "unknown")) }
                } else app.uiTestClearConfigNotices()
                if (planted == null) CoroutineScope(Dispatchers.IO).launch {
                    val log = UiTestSeeding.apply(plan, app.providerStore, key = { UiTestSeeding.readKey(dir, it) }, setBraveKey = app::setBraveKey, setGroundingEnabled = app::setGroundingEnabled,
                        installedLocal = { app.localCatalog.firstOrNull(app.localStorage::isInstalled) }) +
                        // seed_download: the general bundle through the app's own downloader (onInstalled then equips it)
                        (if (plan.download) { val m = app.localCatalog.first(); if (app.localStorage.isInstalled(m)) listOf("download: ${m.id} already installed") else { app.localDownloads.start(m); listOf("download started: ${m.id}") } } else emptyList()) + listOfNotNull(plan.attestation?.let { "attestation $it" })
                    if (plan.localKind == "ollama" && plan.localEndpoint != null) app.seedServerKind("uitest-local", ai.teemoon.providers.ServerKind.OLLAMA)
                    Log.w("teemoon", "[uitest] seeded: " + log.joinToString("; ") + (plan.localKind?.let { "; local kind $it" } ?: ""))
                }
            }
        }
        // after a delete the store scrubs its files (ChatDatabase.maintenance): deleted text must not stay readable there
        val attachmentFiles = ai.teemoon.chat.AttachmentFiles(java.io.File(filesDir, "attachments"))
        val repo = ChatRepository(dao, app.search, maintenance = ChatDatabase.maintenance(chatDb, CoroutineScope(Dispatchers.IO)), files = attachmentFiles)
        // Image files no row names, and anything an interrupted import or send left pending (IMAGES.md §1).
        if (savedInstanceState == null) CoroutineScope(Dispatchers.IO).launch { runCatching { repo.sweepAttachmentFiles() } }
        // A record a reply went out on while it was still being checked has settled: the stored marks follow it
        // (SentBeforeConfirmed.settle — a pass clears, a hard fail marks), and so do the chats on screen.
        app.session.onRecordSettled = { key, raw ->
            val outcome = ai.teemoon.chat.SentBeforeConfirmed.Outcome.entries.firstOrNull { it.rawValue == raw }
            if (outcome != null && outcome != ai.teemoon.chat.SentBeforeConfirmed.Outcome.UNSETTLED) {
                app.openChats.values.forEach { it.resolveSentMarks(key, outcome) }
                CoroutineScope(Dispatchers.IO).launch { runCatching { repo.settleSentBeforeConfirmed(key, outcome) } }
            }
        }
        // The long-thread seed needs the repository, so it runs here rather than in the block above.
        if (seedingArmed && savedInstanceState == null) {
            val extras = intent?.extras?.let { b -> b.keySet().associateWith { k -> @Suppress("DEPRECATION") b.get(k) } }.orEmpty()
            // `-scrollTrace`: the transcript's geometry trace, debuggable builds only (ScrollTrace.kt)
            if (extras["scroll_trace"] == true) ScrollTraceFile.enable(this)
            // `--es seed_route <encoded>`: open straight onto one screen, for the crawl. Debuggable builds only,
            // and only a string RouteCodec already knows how to decode — it is the same codec the app restores
            // itself with after process death, so a route the crawl can reach is a route the app can restore.
            (extras["seed_route"] as? String)?.takeIf { it.isNotBlank() }?.let { seedRoute = RouteCodec.decode(it) }
            // A journey starts from an empty store, as a fresh install would; synchronous so the first frame never sees the old threads.
            if (extras[UiTestSeeding.EXTRA_FLAG] == true && extras[UiTestSeeding.EXTRA_NO_BRAVE_KEY] == true) { app.setBraveKey(""); app.setGroundingEnabled(false) }
            // Set either way, as every other seeded setting is: a journey on a phone whose owner keeps developer mode on
            // drew the debug card under every reply, and seven of its twelve step goldens drifted (Pixel, 2026-09-24).
            if (extras[UiTestSeeding.EXTRA_FLAG] == true) app.setDeveloperMode(extras[UiTestSeeding.EXTRA_DEVELOPER] == true)
            if (extras[UiTestSeeding.EXTRA_FLAG] == true && extras[UiTestSeeding.EXTRA_FRESH_STORE] == true) {
                runBlocking { repo.deleteAll() }
                // a fresh store is a fresh install: on iOS every --uitesting launch is a new process, so the session's
                // remembered list search (ChatListSearchMemory) is empty; here the journeys share one, and a search
                // journey's "lighthouse" stood in the chats sheet of every later one ("no results", 2026-10-06)
                app.listSearchMemory.query = ""
            }
            // the phone's free memory, pinned for the get section: the first-run journeys failed on the user's own phone
            // whenever its other apps left under ~4 GB free (the start-here card offers only a model that fits)
            if (extras[UiTestSeeding.EXTRA_FLAG] == true) {
                app.uiTestAvailableMB = (extras[UiTestSeeding.EXTRA_AVAILABLE_MB] as? Int)
                app.uiTestTotalBytes = (extras[UiTestSeeding.EXTRA_RAM_GB] as? Int)?.let { it * 1_073_741_824L }
            }
            // set either way, as above: one seeded launch's scripted reply must not answer the next launch's turns
            if (extras[UiTestSeeding.EXTRA_FLAG] == true) app.uiTestScriptedReply = StreamingReplyFixture.MARKDOWN.takeIf { extras[UiTestSeeding.EXTRA_STREAM_REPLY] == true }
            // Behind the uitesting flag like every other seed: it was read on the debuggable check alone, so any app could
            // point a debug build's Brave key and the model's queries at its own host (the local audit, 2026-10-13).
            // The flag is consistency, not a barrier — a caller can set it too. Debug builds live on test devices and
            // the e2e app is uninstalled after its run (tools/journey_goldens.sh); that is what keeps this off a phone.
            if (extras[UiTestSeeding.EXTRA_FLAG] == true)
                (extras[UiTestSeeding.EXTRA_BRAVE_ENDPOINT] as? String)?.takeIf { it.isNotBlank() }?.let { BraveWebSearchTool.endpoint = it }
            val seedPlan = UiTestSeeding.plan(extras)
            // UITestStoreSeeding: the long thread WINS and returns — it is the scroll fixture, where the whole
            // question is how much transcript there is, and the design fixtures beside it would change the answer.
            if (seedPlan?.longThread != null) {
                CoroutineScope(Dispatchers.IO).launch { Log.w("teemoon", "[uitest] " + UiTestSeeding.seedLongThread(repo, seedPlan.longThread)) }
            } else if (seedPlan?.threads == true) {
                CoroutineScope(Dispatchers.IO).launch { Log.w("teemoon", "[uitest] " + UiTestSeeding.seedFixtureThreads(repo)) }
            }
            // beside either: the macrobenchmark's list fixture (UiTestSeeding.seedManyThreads), older than the long thread
            seedPlan?.manyThreads?.let { n -> CoroutineScope(Dispatchers.IO).launch { Log.w("teemoon", "[uitest] " + UiTestSeeding.seedManyThreads(repo, n)) } }
        }
        // A saved near.ai setup can outlive its model: move it to what near.ai serves (iOS ContentView's .task,
        // guarded the same way — a UI test's world is seeded and must not be rewritten by a live list). Outside the
        // debuggable block above: a release build is where the retired setups are.
        val uiTesting = seedingArmed && intent?.extras?.getBoolean(UiTestSeeding.EXTRA_FLAG) == true
        if (!uiTesting) app.healRetiredNearAIModelsFromLive()
        val storeFailure = (opened as? ChatDatabase.Open.Failed)?.reason
        val startRoute = seedRoute
        setContent {
            val appearance by app.settings.appearanceChanges.collectAsState()
            @Suppress("UNUSED_VARIABLE") val v = appearance
            val dark = !FOLLOWS_SYSTEM_APPEARANCE || isSystemInDarkTheme()
            TeemoonTheme(dark = dark, tint = app.settings.appTintColor, fontDesign = app.settings.appFontDesign, fontSize = app.settings.appFontSize, fontWidth = app.settings.appFontWidth) { Root(app, repo, storeFailure, startRoute, openThread, incomingExport, incomingShare) }
        }
    }

    private companion object {
        /** Transparent bars with light icons, regardless of the system theme. */
        val DARK_BARS: SystemBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        /** Transparent bars whose icons follow the system theme: dark icons on the light ground. */
        val SYSTEM_BARS: SystemBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    }

    private fun applyBars() {
        val style = if (FOLLOWS_SYSTEM_APPEARANCE) SYSTEM_BARS else DARK_BARS
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBars()   // uiMode changed: pinned dark keeps the dark-styled bars; following the system re-reads it
    }
}

/** The visible part of a modal sheet: height = window height − the content's own top, measured as it moves. */
@Composable
private fun SheetContent(bottomInset: Dp, fraction: Float? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    var topPx by remember { mutableFloatStateOf(0f) }
    val visible = with(density) { (windowHeightPx - topPx).coerceAtLeast(0f).toDp() }
    // A fixed-fraction detent (iOS `.fraction(0.76)`): the sheet's whole content is that tall, so "expanded" IS the detent.
    if (fraction != null) {
        // iOS's fraction is of the LARGE detent — the screen less the top gap a large sheet leaves (≈ 88% of the screen;
        // measured: .fraction(0.76) puts the sheet top at 33% of the screen). The sheet's height is the grabber + this
        // box + the navigation-bar inset, so those come off too.
        Box(Modifier.fillMaxWidth().height(with(density) { (windowHeightPx * 0.88f * fraction).toDp() } - bottomInset - 28.dp).padding(bottom = bottomInset).consumeWindowInsets(WindowInsets.statusBars)) { content() }
        return
    }
    // The OUTER box keeps the sheet's measured content height constant (full size): the sheet's detent anchors are
    // computed from it, and a height that moved with the drag re-anchored mid-gesture and dismissed the sheet. Only
    // the inner box, which holds the screen, is sized to the visible part.
    Box(Modifier.fillMaxSize().onGloballyPositioned { topPx = it.boundsInWindow().top }) {
        Box(Modifier.fillMaxWidth().height(visible).padding(bottom = bottomInset).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(app: AppState, repo: ChatRepository, storeFailure: String? = null, startRoute: Route? = null,
                 openThread: MutableStateFlow<String?> = remember { MutableStateFlow(null) },
                 incomingExport: MutableStateFlow<IncomingExport.Arrival?> = remember { MutableStateFlow(null) },
                 incomingShare: MutableStateFlow<ai.teemoon.app.imaging.ShareIn.Draft?> = remember { MutableStateFlow(null) }) {
    // iOS: the chat is the root; the chats list, the Where sheet and settings are sheets over it. The current
    // thread is the newest one on launch (or a fresh one when there is none) and follows the user's picks.
    var currentThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    // ChatsListView's "+" posts .teemoonNewChat so ChatView's private draft clears: a new chat never inherits the
    // composer. Here the chat screen is keyed by (thread, this count) — the draft carried into the new chat before
    // (the B10 journey, 2026-10-04), and "new chat" from an empty chat keeps its thread id, so the id alone is not enough.
    var newChatCount by rememberSaveable { mutableStateOf(0) }
    var route by rememberSaveable(stateSaver = RouteCodec.saver) { mutableStateOf<Route?>(startRoute) }
    JankScreenState(route?.let { RouteCodec.encode(it).substringBefore(':') } ?: "launch")   // debug builds only: names the screen in JankStats lines
    var focusEpoch by remember { mutableStateOf(0) }   // bumped each time the chat comes back in front (ChatScreen.focusEpoch)
    // A tapped "reply ready" notification: open its chat, over whatever was showing.
    val tappedThread by openThread.collectAsState()
    LaunchedEffect(tappedThread) {
        val id = tappedThread ?: return@LaunchedEffect
        currentThreadId = id; route = Route.Chat(id); openThread.value = null
    }
    // An import, from the picker or an export opened/shared to teemoon: one dry run for both, then the page's confirm
    // (IMPORT_NEAR.md §5; iOS ChatImportModel.check, cdea210c). Nothing is written before the tap on "import".
    val importContext = LocalContext.current
    // the activity Play's review card is shown over (StoreReview); LocalContext may be a wrapper around it
    val reviewActivity = remember(importContext) { generateSequence(importContext) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity } }
    fun checkImport(open: () -> java.io.InputStream, about: ImportFile, delete: (() -> Boolean)? = null) = app.chatImport.check(open = open, store = repo, about = about, delete = delete,
        // IMAGES.md §3: an export's data: images through the isolated decoder; payloads set aside while the file streams.
        images = repo.attachmentFiles?.let { ai.teemoon.app.imaging.ImportImageIngest(ai.teemoon.app.imaging.IsolatedImageDecoder(importContext), it) },
        spillDir = java.io.File(importContext.cacheDir, "import-spill"))
    // An export that arrived by intent (IncomingExport) opens the import page at its confirm: the dry run reads it and
    // writes nothing, so a file shared from another app never writes chats without a tap in teemoon (pm 2026-11-01).
    val arrived by incomingExport.collectAsState()
    LaunchedEffect(arrived) {
        val arrival = arrived ?: return@LaunchedEffect
        incomingExport.value = null
        // one import at a time (iOS onOpenURL's `guard !isBusy`): a file arriving mid-check or mid-import is dropped,
        // and the running import's copy is left for it to read
        if (!IncomingExport.receive(arrival.copy, app.chatImport.isBusy, importContext.cacheDir)) return@LaunchedEffect
        app.chatImport.dismiss(); app.chatImport.opened(ChatImportRunner.Opener.ARRIVAL); route = Route.NearImport; checkImport(arrival.open, arrival.about)
    }
    // the offer to delete the export, asked once the import page has closed (v9, D4), over whatever it closed to
    val exportDelete by app.chatImport.exportDelete.collectAsState()
    ExportDeleteAlert(exportDelete, onDelete = app.chatImport::deleteExport, onNotNow = app.chatImport::keepExport, onDismissFailure = app.chatImport::dismissDeleteFailure)
    fun toChat() { route = currentThreadId?.let { Route.Chat(it) } ?: Route.Threads }
    /** The import flow left (cancel, back, or done) back to what opened it: settings › chats, or the chat a file arrived over. */
    fun importLeft(to: ChatImportRunner.Opener) { if (to == ChatImportRunner.Opener.ARRIVAL) toChat() else route = Route.ChatsSettings }
    fun newChat(): String {
        val id = runBlocking { repo.newOrCurrentThread(currentThreadId, System.currentTimeMillis()) { "t-" + System.currentTimeMillis() } }
        currentThreadId = id; return id
    }
    // A share (ShareIn) opens a NEW chat on the last-used place with the shared items as its draft, never sent.
    var sharedDraft by remember { mutableStateOf<ai.teemoon.app.imaging.ShareIn.Draft?>(null) }
    val shared by incomingShare.collectAsState()
    LaunchedEffect(shared) {
        val draft = shared ?: return@LaunchedEffect
        incomingShare.value = null
        sharedDraft = draft; newChatCount++; route = Route.Chat(newChat())
    }
    // WhereSheetView.firstRunPush: a first-run row pushed settings' own screens. While it is up, back returns to the
    // sheet (not places & keys) and a SAVE ends the sheet in chat (iOS cf251b5).
    var firstRunPush by rememberSaveable { mutableStateOf(false) }
    // The screen that opened the key form, which it returns to (FormOpener). TrustLadderView's "add a near.ai key"
    // presents the form OVER THE TRUST SHEET and returns to it, where the button has flipped to "switch" in place (iOS
    // testAddingAKeyFlipsTheEncryptedModelCTAWithoutReopeningTheSheet; the E8 journey, 2026-10-04); settings' cloud keys
    // and home machines do the same (2026-11-06). Unset: Where.
    var formOpener by rememberSaveable(stateSaver = RouteCodec.saver) { mutableStateOf<Route?>(null) }
    fun formParent(): Route = FormOpener.parent(formOpener)
    // ChatView's showActiveModelDetail: the active model's card, presented large over the chat.
    var activeModelCard by remember { mutableStateOf<Pair<KnownModel, ModelBrowserDoor>?>(null) }
    var activeModelShown by remember { mutableStateOf<Pair<KnownModel, ModelBrowserDoor>?>(null) }
    activeModelCard?.let { activeModelShown = it }
    val parentOf: (Route) -> Route? = { r ->
        when (r) {
            Route.CloudKeyPicker -> if (firstRunPush) Route.Where else Route.PlacesManage(WhereLocality.CLOUD)
            is Route.AddPlace -> if (firstRunPush && (r.presetName != null || r.custom == AddEditProviderModel.CustomStart.CLOUD_KEY)) Route.CloudKeyPicker else formParent()
            is Route.Chat -> null
            Route.Appearance, Route.ChatsSettings, Route.Search, Route.Credits, Route.PlacesHub -> Route.Settings
            Route.SystemPrompt, Route.NearImport -> Route.ChatsSettings
            is Route.PlacesManage -> Route.PlacesHub
            is Route.Browse -> if (r.encryptedOnly) Route.Trust else Route.Where
            is Route.EditPlace -> formParent()
            is Route.Download -> Route.Where
            Route.ReasoningLevels -> Route.Where
            else -> currentThreadId?.let { Route.Chat(it) }
        }
    }
    var showStoreFailure by remember { mutableStateOf(storeFailure != null) }
    if (showStoreFailure) AlertDialog(onDismissRequest = { showStoreFailure = false }, title = { Text("couldn't open your conversations") },
        text = { Text("your chat history is still on this device — nothing was deleted — but this build couldn't open it, so it is running without history. error: ${storeFailure ?: "unknown"}") },
        confirmButton = { TextButton(onClick = { showStoreFailure = false }) { Text("ok") } })
    // `remember`, because `.map` in composition builds a NEW Flow on every recomposition, which restarts
    // collectAsState and drops back to the `false` initial — so the "threads have loaded" edge below could fire
    // more than once, and the first-launch branch re-runs `newChat()`. Lint names it FlowOperatorInvokedInComposition.
    val threadsFlow = remember(repo) { repo.threads().map { it to true } }
    val threadsLoaded by threadsFlow.collectAsState(initial = emptyList<ThreadEntity>() to false)
    val threads = threadsLoaded.first
    val visibleThreads by repo.visibleThreads().collectAsState(initial = emptyList())
    LaunchedEffect(threadsLoaded.second) {
        if (!threadsLoaded.second || route != null) return@LaunchedEffect
        currentThreadId = threads.maxByOrNull { it.timestamp }?.id ?: newChat()
        toChat()
    }
    // Back: a sheet pops to its parent; the chat (the root on iOS, which has no back) opens the list; the list exits.
    // Predictive: while the gesture is in flight the current screen follows the finger (a sheet sinks, a push
    // slides toward its edge, the chat recedes); a completed gesture navigates, a cancelled one springs back.
    var backProgress by remember { mutableStateOf(0f) }
    var backActive by remember { mutableStateOf(false) }
    var backFromRight by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = route != null && route != Route.Threads) { progress ->
        try {
            backActive = true
            progress.collect { backProgress = it.progress; backFromRight = it.swipeEdge == androidx.activity.BackEventCompat.EDGE_RIGHT }
            // the import page's gesture leaves it as its back button does: before 2026-11-01 it skipped leave(), holding a
            // confirmed file and the arrival's cached copy past the flow's end
            // the import page's gesture leaves it as its cancel does: back to what opened it
            if (route == Route.NearImport) importLeft(app.chatImport.cancel(importContext.cacheDir))
            else when (val r = route) { is Route.Chat -> route = Route.Threads; null -> Unit; else -> parentOf(r)?.let { route = it } }
        } finally { backActive = false; backProgress = 0f }
    }
    val backOffset by animateFloatAsState(backProgress, label = "back")
    val providers by app.providers.collectAsState()
    val selectedId by app.selectedId.collectAsState()
    val catalogRevision by app.catalogStore.revision.collectAsState()
    val problem by app.configProblem.collectAsState()
    val place = providers.firstOrNull { it.id == selectedId }
    // The session bumps `changes` on every mutation (iOS: @Observable); the snapshot is re-read exactly then.
    val sessionVersion by app.session.changes.collectAsState()
    // The audit index is observable on iOS; here its revision re-keys the snapshot, so a fetch landing redraws the ladder.
    val auditRevision by AuditIndex.shared.revision.collectAsState()
    // TrustLadderView's 8 s "can't reach verifier" timer, re-armed on every attestation-state change (VerifierTimeout).
    var verifierTimedOut by remember { mutableStateOf(false) }
    val attestationState = remember(sessionVersion) { app.session.attestationState }
    LaunchedEffect(attestationState) { VerifierTimeout.run({ app.session.awaitsFirstRecord }) { verifierTimedOut = it } }
    val reverify = { verifierTimedOut = false; app.reverify() }
    // The snapshot reads "now" for its ages ("refreshed 1 min ago", "verified just now"): remembered without a clock it
    // said "1 min ago" for days (pm audit-index sweep 2026-11-06, #3). A minute's tick re-reads it.
    val minute by produceState(0L) { while (true) { kotlinx.coroutines.delay(60_000); value += 1 } }
    val trust = remember(sessionVersion, place, auditRevision, verifierTimedOut, minute) { place?.let { trustSnapshot(app.session, it, timedOut = verifierTimedOut, audit = AuditIndex.shared) } }
    val networkSatisfied by app.network.isSatisfied.collectAsState()
    val mergeNotice by app.mergeNotice.collectAsState()
    val pulls by app.downloads.downloads.collectAsState()
    val localJobs by app.localDownloads.jobs.collectAsState()
    val residentEngines by app.residentEngines.collectAsState()
    val cellularPrompt by app.pendingCellularDownload.collectAsState()
    val localRunnable by app.localRunnable.collectAsState()
    // What is on disk, read once per launch and whenever the downloader's jobs change (a job ending is a file landing or failing).
    LaunchedEffect(localJobs.keys) { app.refreshLocalRunnable() }
    val localFailures by app.localDownloads.failures.collectAsState()
    var deleteFailure by remember { mutableStateOf<String?>(null) }
    val hostContext = LocalContext.current
    val previews by repo.previews().collectAsState(initial = emptyList())
    val developerMode by app.developerMode.collectAsState()
    val reasoningPicks by app.reasoningPicks.collectAsState()
    // developer mode's reasoning for the selected model: the chip's level, the where sheet's bar, the levels page
    val reasoningUi = remember(place, developerMode, reasoningPicks, catalogRevision) { app.reasoningUi(place, developerMode) }
    val groundingEnabled by app.groundingEnabled.collectAsState()

    // Sheets as on iOS (ContentView / ChatView / E2EETitleBlock): the chat is always underneath; the chats list, Where,
    // settings and trust are bottom sheets over it — medium then large detents, trust at large, a grabber on the list
    // and Where; Where's children (add, edit, browse, download) stack a second, full sheet. A push inside the
    // settings sheet slides horizontally.
    val chatRoute = currentThreadId?.let { Route.Chat(it) }
    val sheetA: Route? = FormOpener.sheetA(route, formOpener)
    // The where sheet's "show only" for this open (IMAGES.md §4e): none from the chip, "reads images" from the can't-see
    // routes; kept while the sheet's own screens (a browser, a key form) come and go, cleared when it goes down.
    var whereShowOnly by remember { mutableStateOf(emptySet<ModelBrowserQuery.Filter>()) }
    LaunchedEffect(sheetA == null) { if (sheetA == null) whereShowOnly = emptySet() }
    // WhereSheetView's .task runs on EVERY presentation. Here the sheet's content stays composed while it is hidden, so
    // its LaunchedEffect(Unit) refreshed the machines once per process: a model the server had since unloaded still
    // read "warm" on the next open (the C6 journey, 2026-10-04). Each showing is counted, and the refresh keys on it.
    var whereShowings by remember { mutableIntStateOf(0) }
    LaunchedEffect(sheetA == Route.Where) { if (sheetA == Route.Where) whereShowings++ }
    // the flag belongs to one visit of the form: anywhere else, the next form is where's again
    LaunchedEffect(route) { if (route !is Route.AddPlace && route !is Route.EditPlace) formOpener = null }
    val sheetB: Route? = FormOpener.sheetB(route)
    val backKind = backPreview(route, sheetA, sheetB, parentOf)
    val screen: @Composable (Route?) -> Unit = { r -> NavTrace.section("screen:" + NavTrace.name(r)) { when (r) {
        null -> Unit   // the store has not answered yet
        Route.Threads -> ThreadsScreen(
            threads = visibleThreads,
            onOpen = { currentThreadId = it; route = Route.Chat(it) },
            onNew = { newChatCount++; route = Route.Chat(newChat())
                // ask for a review if appropriate (iOS's "+"): Play's card in the Play bundle, nothing in the GitHub APK
                ChatListBuckets.requestReviewIfAppropriate(app.settings) { reviewActivity?.let(StoreReview::request) } },
            onClose = { toChat() },
            hapticsEnabled = app.settings.shouldPlayHaptics,
            onSearch = app::searchThreads,
            onFallbackSearch = { q -> repo.fallbackThreadsMatching(q) },
            // ChatsListView.deleteThread: deleting the OPEN chat clears it first (a fresh chat, the list dismissed) so the
            // transcript never sits on a thread the store no longer has.
            onDelete = { id ->
                // a chat still answering is told first: its turn stops and writes nothing to the deleted thread (A4)
                app.openChats[id]?.threadDeleted()
                runBlocking { repo.delete(id) }
                app.forgetKeptConversations()   // its text must not outlive it in a kept on-device conversation (iOS fc09a91)
                if (id == currentThreadId) { currentThreadId = null; route = Route.Chat(newChat()) }
            },
            previews = previews.associate { it.threadId to it.content },
            initialQuery = app.listSearchMemory.query, onQueryChanged = { app.listSearchMemory.query = it }, freshOpen = app.listSearchMemory.freshOpens,
            onOpenHit = { id, hit ->
                // The transcript lands where the match is: the fraction is computed against the message's content.
                val content = runBlocking { repo.messages(id).first() }.firstOrNull { it.id == hit.messageID }?.content ?: ""
                app.deepLink.deposit(id, hit.messageID, TranscriptDeepLink.matchFraction(hit.snippet, content))
                currentThreadId = id; route = Route.Chat(id)
            },
        )

        is Route.Chat -> androidx.compose.runtime.key(r.threadId, newChatCount) {
            val vm: ChatViewModel = viewModel(key = r.threadId) {
                // The place is read at SEND time from the store, never captured: this lambda outlives the composition that
                // created the view model, and a captured `place` sent a turn to the previous provider after a switch.
                ChatViewModel(repo = repo, threadId = r.threadId, provider = { app.providers.value.firstOrNull { it.id == app.selectedId.value } }, credential = app::credentialFor, groundingKey = app::groundingKey, searchKeyTurnedOff = app::searchKeyTurnedOff, localContextTokens = app::localContextTokens, session = app.session, onFirstToken = app::recordUse,
                    systemPrompt = { app.settings.systemPrompt }, onBraveKey = { app.setBraveKey(it) }, makeTransport = app::transportFor,
                    reasoningControl = app::reasoningControl, beforeSend = app.firstSendKind::beforeSend,
                    canSeeImages = app::canSeeImages, recheckHomeImages = app::recheckHomeImages, isOnline = { app.network.isSatisfied.value }, imageBytes = { repo.attachmentBytes(it.path) },
                    saveImages = { id, images -> repo.addAttachments(id, images.map { ChatRepository.NewAttachment(it.mime, it.width, it.height, it.image, it.thumbnail) })
                        .map { ChatImage(it.id, it.mime, it.width, it.height, it.path, it.thumbnail) } })
                    .also { created -> app.openChats[r.threadId] = created; created.onCleared = { app.openChats.remove(r.threadId, created) } }
            }
            // A turn outlives the foreground: the service holds the process up while it runs, and says so when it
            // lands. TurnNotificationPolicy decides what is said; this only supplies the facts.
            val appContext = hostContext.applicationContext
            // ASKED IN CONTEXT, at the first send — not at launch. The value is only legible once there is a turn
            // to outlive the foreground, and teemoon has never asked for a permission before, so the first one
            // should arrive attached to the thing it buys. Denying it costs only the telling: the service still
            // runs and the turn still finishes, which is why nothing here re-asks or blocks on the answer.
            val askNotifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()) { }
            var asked by rememberSaveable { mutableStateOf(false) }
            vm.onTurn = { inFlight, outcome, _ ->
                if (TurnNotificationPolicy.serviceRuns(inFlight)) {
                    if (!asked && Build.VERSION.SDK_INT >= 33 &&
                        appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        asked = true
                        askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    TurnService.start(appContext)
                }
                else TurnService.finish(appContext,
                    TurnNotificationPolicy.notification(false, foreground, outcome), r.threadId)
            }
            // null until Room answers: an `emptyList()` initial restored an empty thread first, so every thread opened on the
            // moon for a beat and the cold-open landing had nothing to land on
            val persisted by repo.messages(r.threadId).collectAsState(initial = null)
            LaunchedEffect(persisted?.size) { persisted?.let { vm.restore(it) } }
            val images by repo.attachments(r.threadId).collectAsState(initial = emptyList())
            LaunchedEffect(images, persisted?.size) { if (images.isNotEmpty()) vm.applyAttachments(images) }
            val landing = remember(r.threadId) { app.deepLink.consume(r.threadId) }
            // IMAGES.md §4: the system photo picker (no media permission), each pick decoded in the isolated process.
            val imageDecoder = remember { ai.teemoon.app.imaging.IsolatedImageDecoder(appContext) }
            var pendingPick by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<List<android.net.Uri>>?>(null) }
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ai.teemoon.app.imaging.ImageCaps.CLOUD_PER_SEND)) { uris ->
                pendingPick?.complete(uris); pendingPick = null
            }
            // IMAGES.md §4a: the system camera writes into our FileProvider (no CAMERA permission is declared, so none is
            // asked); the photo is decoded in the isolated process like any pick, and the capture file is deleted after.
            var pendingShot by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
            val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> pendingShot?.complete(ok); pendingShot = null }
            val hasCamera = remember { android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(hostContext.packageManager) != null }
            val saveScope = rememberCoroutineScope()
            ChatScreen(focusEpoch = focusEpoch, vm = vm, sharedDraft = sharedDraft, onSharedDraftTaken = { sharedDraft = null },
                takePhoto = if (!hasCamera) null else ({ taken ->
                    val dir = java.io.File(hostContext.cacheDir, "camera").apply { mkdirs() }
                    val shot = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(hostContext, hostContext.packageName + ".files", shot)
                        val ok = kotlinx.coroutines.CompletableDeferred<Boolean>().also { pendingShot = it }
                        camera.launch(uri)
                        if (ok.await() && shot.length() > 0) { taken(); ai.teemoon.app.imaging.PickedImages.read(hostContext.contentResolver, uri, imageDecoder) } else null
                    } finally { shot.delete() }
                }),
                onShareImage = { img ->
                    repo.attachmentFiles?.resolve(img.path)?.takeIf { it.isFile }?.let { file ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(hostContext, hostContext.packageName + ".files", file)
                        // ClipData, the type and a read grant: the Sharesheet then previews it (ImageShare); the plain sheet, nothing added
                        hostContext.startActivity(ai.teemoon.app.imaging.ImageShare.of(uri.toString(), img.mime).chooser())
                    }
                },
                onCopyImage = { img ->
                    repo.attachmentFiles?.resolve(img.path)?.takeIf { it.isFile }?.let { file ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(hostContext, hostContext.packageName + ".files", file)
                        Clipboard.copy(hostContext, ai.teemoon.app.imaging.ImageClip.of(uri.toString(), img.mime).clipData())
                    }
                },
                onSaveImage = { img ->
                    repo.attachmentFiles?.resolve(img.path)?.takeIf { it.isFile }?.let { file ->
                        saveScope.launch(Dispatchers.IO) {
                            // a file that can't be read is a failed save, as a refused write is: nothing new shows (the maintainer 2026-11-07)
                            val saved = runCatching { file.readBytes() }.getOrNull()?.let { ai.teemoon.app.imaging.ImageSave.of(img.id, img.mime).write(hostContext.contentResolver, it) } != null
                            ai.teemoon.app.imaging.ImageSave.toastAfter(saved)?.let { words ->
                                kotlinx.coroutines.withContext(Dispatchers.Main) { android.widget.Toast.makeText(hostContext, words, android.widget.Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                },
                pasteDataUrl = { url -> kotlinx.coroutines.withContext(Dispatchers.IO) {
                    ai.teemoon.app.imaging.ComposerPaste.dataUrlBytes(url)?.let { imageDecoder.decode(it) }
                        ?: ai.teemoon.app.imaging.ImageDecodeFrame.Rejected(ai.teemoon.app.imaging.ImageRejection.UNSUPPORTED_FORMAT)
                } },
                pasteImages = { uris -> kotlinx.coroutines.withContext(Dispatchers.IO) { ai.teemoon.app.imaging.PickedImages.readPasted(appContext.contentResolver, uris, imageDecoder) } },
                pickImages = { max, onChosen ->
                    val chosen = kotlinx.coroutines.CompletableDeferred<List<android.net.Uri>>().also { pendingPick = it }
                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    // The launcher's ceiling is fixed when it is registered, so it is the larger limit; the composer passes
                    // what is left under the limit that applies (4 on this phone's model). One past it is read so that
                    // ComposerImages says "up to n images per message"; the rest are not read at all.
                    // the count goes to the composer before any read: its tiles show while the photos are read and decoded
                    val uris = chosen.await().take(max + 1); onChosen(uris.size)
                    uris.map { ai.teemoon.app.imaging.PickedImages.read(appContext.contentResolver, it, imageDecoder) }
                },
                // ChatView.title: the first user message, else "chat". The place lives in the where chip.
                title = threads.firstOrNull { it.id == r.threadId }?.title?.takeIf { it.isNotBlank() } ?: "chat",
                whereModel = place?.let { WhereProviderPresentation.modelLabel(it) },
                wherePlace = place?.let { WhereProviderPresentation.chipPlace(it) },
                whereLocality = place?.let { WhereLocality.of(it) },
                whereProgress = place?.localModelID?.let { id -> localJobs[id]?.fraction },
                whereProgressLabel = place?.localModelID?.let { id -> localJobs[id]?.let { j -> CellularDownloadGate.waitingLabel(j.network, j.parked) } },
                // The chip's long-press menu (iOS 835d7c8): the card for the model answering now, and its id.
                onModelInfo = if (place?.model?.isNotEmpty() == true) ({ activeModelCard = app.activeModelCard() }) else null,
                onCopyModelID = { place?.model?.let { Clipboard.copy(hostContext, it) } },
                whereNeedsDownload = place?.localModelID?.let { id -> localJobs[id] == null && id !in localRunnable } ?: false,
                trust = trust, hasProvider = place != null, hasAnySetup = providers.isNotEmpty(),
                // Re-read when the place or the route changes: a key is saved from a settings route, never mid-chat.
                hasKey = remember(place, route) { place?.let(app.credentials::hasCredential) ?: true },
                onBack = { route = Route.Threads },
                onOpenSettings = { route = Route.Settings },
                onOpenWhere = { route = Route.Where },
                singleTurn = place?.answersSingleTurnOnly == true,
                onOpenTrust = { route = Route.Trust },
                onRetryVerification = reverify,
                developerMode = developerMode,
                whereLevel = reasoningUi.chipLevel,
                webSearchState = webSearchChipState(hasProvider = place != null, hasKey = app.hasBraveKey(), groundingEnabled = groundingEnabled),
                modelTakesTools = place?.modelSupportsTools != false,
                // Same as iOS: no key → the search settings; a key → toggle.
                onWebSearchChip = { if (!app.hasBraveKey()) route = Route.Search else app.setGroundingEnabled(!groundingEnabled) },
                // Re-read when a download starts or ends: the general file landing beside the NPU bundle ends the offer.
                imageDownloadOffer = remember(place, localRunnable, localJobs) { place != null && imageDownloadOffered(app.localImagesNeedSecondDownload(place), downloading = place.localModelID?.let { localJobs[it] } != null) },
                onImageDownload = { place?.let(app::startImageModelDownload) },
                consoleRecovery = { e -> ProviderPresets.consoleRecovery(
                    isBraveGrounding = e.source is LlmError.Source.BraveGrounding,
                    errorHost = e.url?.let { runCatching { URI(it).host }.getOrNull() },
                    sourceLabel = (e.source as? LlmError.Source.Provider)?.name, activeProvider = place) },
                groundingAttached = groundingEnabled && place?.let { ProviderCapability.BUILT_IN_GROUNDING in it.capabilities } == false,
                onDisableWebSearchAndRetry = { app.setGroundingEnabled(false); vm.disableWebSearchAndRetry() },
                onCopy = { text -> Clipboard.copy(hostContext, text) },
                scrollToMessageId = landing?.messageID,
                hapticsEnabled = app.settings.shouldPlayHaptics,
                // the can't-see state (IMAGES.md §4d): the where sheet, half size, "reads images" on
                onChooseImageModel = { whereShowOnly = setOf(ModelBrowserQuery.Filter.VISION); route = Route.Where },
                phoneImagesNever = place?.let(app::imagesRefused) == true,
                // the permanent refusal's "use <smaller model>": an equipped phone model that fits this phone
                smallerPhoneModel = place?.takeIf(app::imagesRefused)?.let { here -> providers.firstOrNull { p -> p.isLocal && p.id != here.id && app.canSeeImages(p) } }
                    ?.let { p -> WhereProviderPresentation.modelLabel(p) to { app.select(p.id) } },
            )
        }

        Route.Where -> WhereSheet(
            providers = providers, selectedId = selectedId, problem = problem, onSelect = app::select, hasKey = app::hasKey,
            // read the resident set here so a load or an eviction behind the sheet redraws warm/cold
            phoneWarm = residentEngines.let { { p: Provider -> app.isPhoneWarm(p) } },
            // the catalogue revision: a list that lands behind the sheet adds or drops "· images"
            readsImages = catalogRevision.let { { p: Provider -> app.canSeeImages(p) } },
            imagesOnDevice = ai.teemoon.providers.LocalModelCatalog.phoneReadsImages(waitingOnSecondDownload = providers.any(app::localImagesNeedSecondDownload)),
            imagesRefused = app::imagesRefused, phoneFits = app::phoneImagesFit,
            onDownload = { id -> route = Route.Download(id) },
            recents = app.recentlyUsed(),
            // WhereSheetView.selectRecent: activate, then dismiss — as a ready row does (onActivate below). This only
            // activated, and the sheet stayed up over the chat it had just switched (Pixel, 2026-10-06).
            onActivateRecent = { app.activateRecent(it); toChat() },
            onAddPlace = { preset, custom -> firstRunPush = false; route = Route.AddPlace(preset, custom) },
            onFirstRunPush = { push ->
                firstRunPush = true
                route = if (push == FirstRunPush.CLOUD_KEY) Route.CloudKeyPicker else Route.AddPlace(null, AddEditProviderModel.CustomStart.COMPUTER)
            },
            onEdit = { id -> route = Route.EditPlace(id) },
            // A keyed preset's "browse" row opens the model browser (WhereSheetView.openBrowse); the parameter's default is
            // the edit screen, and leaving it unset sent "browse near.ai" to "edit place" — caught by the screen audit.
            onBrowse = { id -> route = Route.Browse(id) },
            onBrowseShowOnly = { id, f -> route = Route.Browse(id, showOnly = f) },
            initialShowOnly = whereShowOnly, onShowOnlyChange = { whereShowOnly = it },

            // the filter's "k of n": the saved list through the browser's own predicate; the revision redraws it
            listCount = catalogRevision.let { { p: Provider, f: Set<ModelBrowserQuery.Filter> -> app.listCount(p, f) } },
            onBack = { app.dismissProblem(); toChat() },
            // Read the store's revision here so a count that lands behind the sheet redraws its row.
            policyFor = { f -> catalogRevision; app.getPolicy(f) },
            networkSatisfied = networkSatisfied,
            mergeNotice = mergeNotice, onDismissMergeNotice = app::dismissMergeNotice,
            pulls = pulls, onCancelPull = { app.downloads.cancel(it) },
            onActivate = { row -> app.activate(row.modelID, row.provider); toChat() },
            onUnequip = { row -> app.unequip(row.modelID, row.provider) },
            onDeleteFromServer = { row -> app.deleteFromServer(row) { deleteFailure = it } },
            onRefreshHome = app::refreshHome, refreshKey = whereShowings, onRetryPull = { ref, base -> app.downloads.start(ref, base) },
            deleteFailure = deleteFailure, onDismissDeleteFailure = { deleteFailure = null },
            downloads = localJobs.mapValues { it.value.fraction }, downloadNames = localJobs.mapValues { it.value.model.displayName }, downloadFailures = localFailures,
            installed = app.localStorage::isInstalled, localModelFor = app.localStorage::catalogEntry,
            installedBytesFor = { app.localStorage.installedBytes(it.id) }, availableMB = app.availableMBForUi(), totalMemBytes = app.totalMemBytes(),
            // On mobile data the sheet asks first and stays open (WhereSheetView+Actions); otherwise back to the chat.
            onStartDownload = { app.startAndSelect(it); if (app.pendingCellularDownload.value == null) toChat() }, onCancelDownload = { app.localDownloads.cancel(it) }, onDeleteWeights = app::deleteWeights,
            cellularPrompt = cellularPrompt, onAnswerCellular = { net -> app.answerCellularDownload(net); if (net != null) toChat() },
            parkedDownloads = localJobs.filterValues { it.parked }.keys,
            reasoningBar = reasoningUi.bar, onOpenReasoning = { route = Route.ReasoningLevels },
        )

        Route.ReasoningLevels -> {
            val choice = place?.let { app.reasoningChoice(it) }
            // no level here any more (developer mode turned off, another model): back to the where sheet
            if (choice == null || reasoningUi.bar == null) route = Route.Where
            else ReasoningLevelsPage(reasoningUi, onPick = { level -> choice.pick(level.takeIf { it != choice.setup.control.let(ai.teemoon.inference.ReasoningLevel::defaultLevel) }) }, onBack = { route = Route.Where })
        }

        is Route.AddPlace -> {
            val model = remember(r) {
                AddEditProviderModel(AddEditProviderModel.Mode.Add,
                    startsCustom = r.custom != null, customStart = r.custom ?: AddEditProviderModel.CustomStart.COMPUTER,
                    initialPreset = r.presetName?.let { n -> Provider.presets.firstOrNull { it.name == n } }).also { it.store = app.providerStore; it.catalogStore = app.catalogStore }
            }
            // A setup SAVED from a first-run push ends the sheet, not just the push: the user came to start chatting, and
            // the composer with the chip naming their new model is that (the save made it current). Popping to the picker
            // asked them to pick again; popping to the sheet showed a list of one. Cancel still pops (parentOf).
            // opened from a filtered where sheet, its model rows carry the filters (IMAGES.md §4e "Into a provider's models")
            AddEditProviderScreen(model, onDone = { if (firstRunPush) { firstRunPush = false; toChat() } else route = formParent() }, showOnly = whereShowOnly)
        }

        is Route.EditPlace -> {
            val p = providers.firstOrNull { it.id == r.providerId }
            if (p == null) route = Route.Where
            else {
                val model = remember(r) { AddEditProviderModel(AddEditProviderModel.Mode.Edit(p)).also { it.store = app.providerStore; it.catalogStore = app.catalogStore } }
                val pulls by app.downloads.downloads.collectAsState()
                AddEditProviderScreen(model, onDone = { route = formParent() }, onDownload = { route = Route.Download(p.id) }, downloads = pulls, onCancelDownload = { app.downloads.cancel(it) }, showOnly = whereShowOnly)
            }
        }

        Route.Trust -> {
            // TrustLadderView's .task: fetch the audit index so digest-gated miniaudits can appear (no entry → no link).
            LaunchedEffect(Unit) { app.loadAuditIndex() }
            val t = trust
            if (t == null) toChat()
            else {
                val context = LocalContext.current
                TrustScreen(snapshot = t, onBack = { toChat() }, onReverify = reverify,
                    onCopyScript = { script ->
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("teemoon_verify.py", script))
                    },
                    // The way out of a chat that cannot be attested. Resolved here because only this side knows
                    // whether a USABLE near.ai key exists — EncryptedModelCTA gates on exactly that, and a keyless
                    // setup must NOT open the picker: the pick would die at send with "no api key", which is a
                    // worse place to learn it than the button.
                    // Re-read whenever the route comes back here, not only when the list changes: saving a KEY on the
                    // form over this sheet leaves the provider list as it was, and the button kept saying "add a key"
                    // until the sheet was reopened — iOS's build 36-38 regression, reproduced here by the E8 journey.
                    encryptedModelCTA = remember(providers, route) {
                        val nearAI = ProviderHealing.nearAIProvider(providers, selectedId)
                        EncryptedModelCTA.resolve(nearAI, hasKey = nearAI != null && app.credentials.facts(nearAI).byID)   // the sealed yes/no, not the key
                    },
                    onEncryptedModelCTA = { cta ->
                        route = when (cta) {
                            is EncryptedModelCTA.SwitchToNearAI -> Route.Browse(cta.provider.id, encryptedOnly = true)
                            // No usable key: the key form, editing the legacy setup when there is one.
                            is EncryptedModelCTA.AddNearAIKey -> { formOpener = Route.Trust; cta.existing?.let { Route.EditPlace(it.id) } ?: Route.AddPlace(Provider.nearAI.name, null) }
                        }
                    })
            }
        }

        Route.Settings -> SettingsRootScreen(
            managedCount = WhereLocality.managed(providers).size, developerMode = developerMode, onSetDeveloperMode = app::setDeveloperMode,
            onOpenAppearance = { route = Route.Appearance }, onOpenChats = { route = Route.ChatsSettings }, onOpenSearch = { route = Route.Search },
            onOpenPlaces = { route = Route.PlacesHub }, onOpenCredits = { route = Route.Credits }, onBack = { toChat() })
        Route.Appearance -> AppearanceSettingsScreen(app.settings, onBack = { route = Route.Settings })
        // ChatsSettingsView.deleteAll: `currentThread = nil` — the chat under the sheet starts fresh, never left showing a
        // transcript the store no longer has (the delete-all journey caught it still drawn behind "no chats yet", 2026-10-04).
        Route.ChatsSettings -> ChatsSettingsScreen(app.settings, onDeleteAllChats = { app.openChats.values.forEach { it.threadDeleted() }; runBlocking { repo.deleteAll() }; app.forgetKeptConversations(); currentThreadId = null; newChat() }, onBack = { route = Route.Settings },
            onImport = { app.chatImport.opened(ChatImportRunner.Opener.SETTINGS); route = Route.NearImport }, onOpenSystemPrompt = { route = Route.SystemPrompt })
        Route.NearImport -> {
            val context = LocalContext.current
            val importState by app.chatImport.state.collectAsState()
            val importScope = rememberCoroutineScope()
            // The system picker, presented from the page (IMPORT_NEAR.md §5). near's export is .json; a file manager that
            // labels it octet-stream still offers it, and a file that is not an export is refused by its first element.
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    app.chatImport.dismiss()
                    // only a picked file may be offered for deletion; an arrival (share, open-with) never is
                    importScope.launch(Dispatchers.IO) { checkImport({ context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString()) }, ImportFileInfo.of(context.contentResolver, uri),
                        ImportFileInfo.deleter(context, uri)) }
                }
            }
            // An arrival's cached copy goes when the flow ends, as iOS deletes its Inbox copy (7a259ec5); never mid-import.
            fun end() { app.chatImport.close(); IncomingExport.discard(context.cacheDir) }
            NearImportScreen(importState, onChooseFile = { pick.launch(arrayOf("application/json", "application/octet-stream")) },
                // "done" closes the whole flow, back to what opened it (iOS dismiss): Settings › chats, or the chat for an arrival
                onConfirm = app.chatImport::confirm,
                onDone = { app.chatImport.done()?.let { IncomingExport.discard(context.cacheDir); importLeft(it) } },
                onDismissFailure = ::end,
                onBack = { importLeft(app.chatImport.cancel(context.cacheDir)) })
        }
        Route.SystemPrompt -> SystemPromptScreen(app.settings, onBack = { route = Route.ChatsSettings })
        Route.Search -> SearchSettingsScreen(
            activeProviderHasBuiltInGrounding = place?.let { ProviderCapability.BUILT_IN_GROUNDING in it.capabilities } == true,
            storedKey = app.braveKey(), groundingEnabled = groundingEnabled, onSaveKey = { app.setBraveKey(it) }, onSetEnabled = app::setGroundingEnabled,
            onCopySensitive = { Clipboard.copySensitive(hostContext, it) }, onBack = { route = Route.Settings })
        Route.Credits -> CreditsScreen(onBack = { route = Route.Settings })
        Route.PlacesHub -> PlacesKeysHubScreen(
            machineCount = providers.count { WhereLocality.of(it) == WhereLocality.HOME },
            // PlacesKeysBadge COUNTS KEYS, NOT RECORDS (iOS 148a131): a keyless cloud setup cannot send. Both lookups, as the rows use.
            cloudBadge = providers.filter { WhereLocality.of(it) == WhereLocality.CLOUD }.let { cloud ->
                val keyed = cloud.count { app.credentials.hasCredential(it) || !app.credentials.credential(it.endpoint).isNullOrBlank() }
                PlacesKeysBadge.cloud(keyed = keyed, unkeyed = cloud.size - keyed)
            },
            onOpenMachines = { route = Route.PlacesManage(WhereLocality.HOME) }, onOpenCloud = { route = Route.PlacesManage(WhereLocality.CLOUD) },
            onBack = { route = Route.Settings })
        is Route.PlacesManage -> PlacesManageScreen(
            locality = r.locality, providers = providers.filter { WhereLocality.of(it) == r.locality },
            subtitle = { p -> placeManageSubtitle(p, app.storedKind(p), app.hasKey(p) || app.credentialForEndpoint(p.endpoint).isNotBlank()) },
            // CLOUD: pick the provider first (CloudKeyProviderPickerView). What this replaces is a form already
            // filled in for a provider nobody had chosen, so the first thing the user did was undo a choice they
            // had not made. HOME keeps the direct push — there is one kind of machine and nothing to pick.
            onAdd = { if (r.locality == WhereLocality.HOME) { formOpener = r; route = Route.AddPlace(null, AddEditProviderModel.CustomStart.COMPUTER) } else route = Route.CloudKeyPicker },
            onEdit = { formOpener = r; route = Route.EditPlace(it) }, onDelete = app::remove, onBack = { route = Route.PlacesHub })
        Route.CloudKeyPicker -> CloudKeyProviderPickerScreen(
            presetsWithoutKey = app.credentials.presetsWithoutKey(),
            onPickPreset = { name -> formOpener = FormOpener.fromCloudKeyPicker(firstRunPush); route = Route.AddPlace(name, null) },
            onPickCustom = { formOpener = FormOpener.fromCloudKeyPicker(firstRunPush); route = Route.AddPlace(null, AddEditProviderModel.CustomStart.CLOUD_KEY) },
            onBack = { route = if (firstRunPush) Route.Where else Route.PlacesManage(WhereLocality.CLOUD) })
        is Route.Browse -> {
            val p = providers.firstOrNull { it.id == r.providerId } ?: Provider.presets.firstOrNull { it.id == r.providerId }
            if (p == null) route = Route.Where
            else if (r.encryptedOnly) {
                // TrustLadderView+None's picker: what near.ai last answered with, filtered to what teemoon can seal,
                // and the fresh answer behind it so the sheet is right even on a first run (c3fb76d).
                val key = app.browseCredential(p)
                val door = ModelBrowserDoor.encryptedPicker(p, key, app.catalogStore,
                    refresh = { app.catalogStore.refresh(p, key, maxAgeMs = 3_600_000L) })
                // No selectedModel: the picker opens with no pick.
                ModelBrowserScreen(door,
                    onSelect = { m -> app.applyBrowseSelection(p, m); toChat() }, onCancel = { route = Route.Trust },
                    onCopyID = { Clipboard.copy(hostContext, it) }, developerMode = developerMode)
            }
            // What this server last answered with first, the live list behind it (WhereSheetView+Actions.browseSheet).
            // There is no shipped catalogue to fall back to: a server that has never answered opens empty and fills.
            else ModelBrowserScreen(app.browseDoor(p), selectedModel = p.model,
                onSelect = { m -> app.applyBrowseSelection(p, m); toChat() }, onCancel = { route = Route.Where },
                onCopyID = { Clipboard.copy(hostContext, it) },
                initialQuery = ModelBrowserQuery(filters = r.showOnly + (if (r.imagesOnly) setOf(ModelBrowserQuery.Filter.VISION) else emptySet())),
                carriedFilters = r.showOnly, developerMode = developerMode)
        }

        is Route.Download -> {
            val p = providers.firstOrNull { it.id == r.providerId }
            if (p == null) route = Route.Where
            // equipArriving: an arriving pull goes on the list immediately and is selected, as tapping a phone model in `get` does
            else OllamaDownloadScreen(provider = p, center = app.downloads, onBack = { route = Route.Where }, readsImages = ModelBrowserQuery.Filter.VISION in whereShowOnly, onStarted = { ref -> app.activate(ref, p) }, onCompleted = { ref -> app.activate(ref, p) })
        }
    } } }

    // The sheet is its own window and does not see the activity's navigation-bar inset: read it here and pad inside.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The sources sheet (SourceStackChipView's .sheet): the chip asks, the root presents, as every sheet here.
    var sourcesSheet by remember { mutableStateOf<List<GroundingSource>?>(null) }
    // The attach sheet (IMAGES.md §4d): the "+" asks, the root presents; a button dismisses it before acting.
    var attachSheet by remember { mutableStateOf<AttachSheetRequest?>(null) }
    var attachShown by remember { mutableStateOf<AttachSheetRequest?>(null) }
    attachSheet?.let { attachShown = it }
    var sourcesShown by remember { mutableStateOf<List<GroundingSource>>(emptyList()) }
    sourcesSheet?.let { sourcesShown = it }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val screenHeightDp = maxHeight.value
        // UIKit at the large detent: the presenter recedes — scaled to 0.92 from its top edge, pushed down under the
        // sheet's top, 10pt corners — so the chat's bar is not visible above a large sheet (measured on the tag).
        val sheetLift = remember { mutableFloatStateOf(0f) }
        val liftPush = with(LocalDensity.current) { 56.dp.toPx() }
        // Behind a presented sheet the chat is scenery: hidden from accessibility, as UIKit's presentation makes the
        // presenter (TalkBack read the transcript under the chats list; the structure audit saw it too, 2026-09-09).
        Box(Modifier.fillMaxSize().semantics { if (sheetA != null) hideFromAccessibility() }.graphicsLayer {
            val l = sheetLift.floatValue
            // and a back gesture on the chat (it opens the list) recedes it with the finger (motion-pass item 1)
            val b = if (backKind == BackPreview.CHAT) backOffset else 0f
            val s = (1f - 0.08f * l) * (1f - (1f - CHAT_BACK_SCALE) * b)
            scaleX = s; scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0f)
            translationY = liftPush * l
            if (l > 0f || b > 0f) { shape = RoundedCornerShape(10.dp); clip = true }
        }) { CompositionLocalProvider(LocalSourcesPresenter provides { sourcesSheet = it }, LocalAttachPresenter provides { attachSheet = it }) { screen(chatRoute) } }
        // Every sheet is presented on the ELEVATED palette (Theme.kt: what UIKit resolves inside a sheet in dark mode).
        // ContentView: "Resign focus when the sheet opens" — the keyboard leaves before a sheet rises over the composer.
        val focusManager = LocalFocusManager.current
        val sheetOpen = route != null && route !is Route.Chat
        LaunchedEffect(sheetOpen) { if (sheetOpen) focusManager.clearFocus() else focusEpoch++ }
        val elevated = TeemoonColors.palette.elevated()
        var sheetAShown by remember { mutableStateOf<Route?>(null) }; var sheetBShown by remember { mutableStateOf<Route?>(null) }
        sheetA?.let { sheetAShown = it }; sheetB?.let { sheetBShown = it }
        val whereFirstRun = providers.isEmpty()   // WhereSheetView: showsFirstRun → detents [.fraction(0.76), .large]
        // iOS 26 sheets with their measured detents (IosSheet.kt); every sheet on the elevated palette.
        CompositionLocalProvider(LocalTeemoonPalette provides elevated) {
            val top = sheetA
            val tops = sheetTops(top, whereFirstRun, androidx.compose.ui.platform.LocalDensity.current.fontScale, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp)
            IosSheet(visible = top != null, tops = tops, onDismiss = { toChat() }, grabber = top == Route.Threads || top == Route.Where || top == Route.ReasoningLevels, containerColor = TeemoonColors.groupedBackground, lift = sheetLift, back = { if (backKind == BackPreview.SHEET) backOffset else 0f }, traceLabel = "sheet:" + NavTrace.name(top ?: sheetAShown)) {
                // under the child sheet (the add form over the where sheet) this sheet is scenery too
                Box(Modifier.semantics { if (sheetB != null) hideFromAccessibility() }) {
                (top ?: sheetAShown)?.let { page0 ->
                    SheetPages(page0, parentOf, seeking = backActive && backKind == BackPreview.PAGE, progress = backProgress, fromRightEdge = backFromRight) { page -> screen(page) }
                }
                }
            }
            val child = sheetB
            IosSheet(visible = child != null, tops = listOf(SheetDetents.LARGE), onDismiss = { if (route == Route.NearImport) importLeft(app.chatImport.cancel(importContext.cacheDir)) else route = formParent() }, grabber = false, scrimAlpha = 0.3f, containerColor = TeemoonColors.groupedBackground, back = { if (backKind == BackPreview.CHILD) backOffset else 0f }, traceLabel = "sheet:" + NavTrace.name(child ?: sheetBShown)) {
                (child ?: sheetBShown)?.let { screen(it) }
            }
            IosSheet(visible = activeModelCard != null, tops = listOf(SheetDetents.LARGE), onDismiss = { activeModelCard = null }, grabber = false, containerColor = TeemoonColors.groupedBackground, traceLabel = "sheet:modelcard") {
                activeModelShown?.let { (model, door) ->
                    ModelDetailScreen(model, confidentiality = model.confidentialityNote, onCopyID = { Clipboard.copy(hostContext, it) }, onBack = { activeModelCard = null },
                        liveLoader = door.liveLoader?.let { load -> suspend { load()?.firstOrNull { it.id == model.id } } },
                        offersLoader = door.offersLoader?.let { load -> suspend { load(model.id) } },
                        developerMode = developerMode)
                }
            }
            // presentationDragIndicator(.visible); detents by count (sourcesSheetTops)
            IosSheet(visible = sourcesSheet != null, tops = sourcesSheetTops(sourcesShown.size, screenHeightDp), onDismiss = { sourcesSheet = null }, grabber = true, containerColor = TeemoonColors.groupedBackground, traceLabel = "sheet:sources") {
                GroundingSourcesView(sourcesShown, onClose = { sourcesSheet = null })
            }
            // natural height, about half (a detent sized to its content); the drag indicator shown
            IosSheet(visible = attachSheet != null, tops = attachSheetTops(screenHeightDp), onDismiss = { attachSheet = null }, grabber = true, containerColor = TeemoonColors.groupedBackground, traceLabel = "sheet:attach") {
                attachShown?.let { AttachSheet(it, onDismiss = { attachSheet = null }) }
            }
        }
    }
}

