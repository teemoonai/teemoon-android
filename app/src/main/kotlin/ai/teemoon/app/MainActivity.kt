@file:OptIn(ExperimentalComposeUiApi::class)

package ai.teemoon.app

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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.confidential.EncryptedModelCTA
import ai.teemoon.providers.ModelBrowserDoor
import ai.teemoon.providers.WhereProviderPresentation
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
    data class Download(val providerId: String) : Route
    data object Trust : Route
    /** Guided add: a preset name, or a custom start. */
    data class AddPlace(val presetName: String?, val custom: AddEditProviderModel.CustomStart?) : Route
    data class EditPlace(val providerId: String) : Route
    data object Settings : Route
    data object Appearance : Route
    data object ChatsSettings : Route
    data object Search : Route
    data object Credits : Route
    /**
     * [encryptedOnly]: the trust ladder's picker — near.ai filtered to what teemoon can SEAL. The button that opens
     * it reads "switch to an end-to-end encrypted model on near.ai"; the unfiltered browser it opened until 2026-09-22
     * listed near.ai's proxied Claude and GPT rows under that sentence.
     */
    data class Browse(val providerId: String, val encryptedOnly: Boolean = false) : Route
    data object PlacesHub : Route
    /** Step one of "add cloud key": pick the provider. The key form is the next push (CloudKeyProviderPickerScreen). */
    data object CloudKeyPicker : Route
    data class PlacesManage(val locality: WhereLocality) : Route
}

/** A sheet page's depth: a move to a deeper page slides in from the right (a push), to a shallower one from the left (a pop). */
internal fun routeLevel(r: Route?): Int = when (r) {
    null, is Route.Chat -> 0
    Route.Threads, Route.Where, Route.Settings, Route.Trust -> 1
    is Route.PlacesManage -> 3
    // pushed FROM cloud keys (3), so it slides in from the right; at 2 the push animated as a pop (from the left)
    Route.CloudKeyPicker -> 4
    else -> 2
}

/** The route as saved state: the app comes back where it was after process death (docs/ANDROID_NATIVE.md). */
internal object RouteCodec {
    fun encode(r: Route): String = when (r) {
        Route.Threads -> "threads"; Route.Where -> "where"; Route.Trust -> "trust"; Route.Settings -> "settings"
        Route.Appearance -> "appearance"; Route.ChatsSettings -> "chats"; Route.Search -> "search"; Route.Credits -> "credits"; Route.PlacesHub -> "placeshub"; Route.CloudKeyPicker -> "cloudkeypicker"
        is Route.Chat -> "chat:" + r.threadId; is Route.Download -> "download:" + r.providerId; is Route.EditPlace -> "edit:" + r.providerId
        is Route.Browse -> (if (r.encryptedOnly) "sealedbrowse:" else "browse:") + r.providerId; is Route.PlacesManage -> "manage:" + r.locality.name
        is Route.AddPlace -> "add:" + (r.presetName ?: "") + "|" + (r.custom?.name ?: "")
    }
    fun decode(s: String): Route? {
        val (k, v) = s.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (k) {
            "threads" -> Route.Threads; "where" -> Route.Where; "trust" -> Route.Trust; "settings" -> Route.Settings
            "appearance" -> Route.Appearance; "chats" -> Route.ChatsSettings; "search" -> Route.Search; "credits" -> Route.Credits; "placeshub" -> Route.PlacesHub; "cloudkeypicker" -> Route.CloudKeyPicker
            "chat" -> Route.Chat(v); "download" -> Route.Download(v); "edit" -> Route.EditPlace(v); "browse" -> Route.Browse(v); "sealedbrowse" -> Route.Browse(v, encryptedOnly = true)
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
        "threads", "where", "trust", "settings", "appearance", "chats", "search", "credits", "placeshub",
        "manage:CLOUD", "manage:HOME", "cloudkeypicker", "add:|", "add:near.ai|", "browse:A0000000-0000-0000-0000-000000000001", "sealedbrowse:A0000000-0000-0000-0000-000000000001",
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
    }

    // teemoonApp's scenePhase `.active` (iOS b42f5d8): launch and every return to the foreground. Backgrounding evicts
    // the engine (TeemoonApplication.onTrimMemory), so coming back with the same on-device provider was a cold engine
    // until the first message. Warm it now — idempotent when resident (one build per key), and it stands down behind a
    // running turn (LocalGenerationGate).
    override fun onStart() { super.onStart(); foreground = true; (application as? TeemoonApplication)?.state?.let { it.warmUp(it.selected) } }
    override fun onStop() { super.onStop(); foreground = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with LIGHT system bar icons always: the app is pinned dark (Theme.kt), so the default style, which
        // reads the system's uiMode, would draw dark icons over the black ground on a light-mode phone.
        enableEdgeToEdge(statusBarStyle = DARK_BARS, navigationBarStyle = DARK_BARS)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getStringExtra(TurnService.EXTRA_OPEN_THREAD)?.let { openThread.value = it }
        val opened = ChatDatabase.open(this)
        val dao = when (opened) { is ChatDatabase.Open.Ready -> opened.db; is ChatDatabase.Open.Failed -> opened.db }.chatDao()
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
                    Log.w("teemoon", "[uitest] seeded: " + log.joinToString("; "))
                }
            }
        }
        val repo = ChatRepository(dao, app.search)
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
            if (extras[UiTestSeeding.EXTRA_FLAG] == true) app.uiTestAvailableMB = (extras[UiTestSeeding.EXTRA_AVAILABLE_MB] as? Int)
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
            TeemoonTheme(tint = app.settings.appTintColor, fontDesign = app.settings.appFontDesign, fontSize = app.settings.appFontSize, fontWidth = app.settings.appFontWidth) { Root(app, repo, storeFailure, startRoute, openThread) }
        }
    }

    private companion object {
        /** Transparent bars with light icons, regardless of the system theme. */
        val DARK_BARS: SystemBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge(statusBarStyle = DARK_BARS, navigationBarStyle = DARK_BARS)   // uiMode changed; the bars stay dark-styled
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
                 openThread: MutableStateFlow<String?> = remember { MutableStateFlow(null) }) {
    // iOS: the chat is the root; the chats list, the Where sheet and settings are sheets over it. The current
    // thread is the newest one on launch (or a fresh one when there is none) and follows the user's picks.
    var currentThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    // ChatsListView's "+" posts .teemoonNewChat so ChatView's private draft clears: a new chat never inherits the
    // composer. Here the chat screen is keyed by (thread, this count) — the draft carried into the new chat before
    // (the B10 journey, 2026-10-04), and "new chat" from an empty chat keeps its thread id, so the id alone is not enough.
    var newChatCount by rememberSaveable { mutableStateOf(0) }
    var route by rememberSaveable(stateSaver = RouteCodec.saver) { mutableStateOf<Route?>(startRoute) }
    var focusEpoch by remember { mutableStateOf(0) }   // bumped each time the chat comes back in front (ChatScreen.focusEpoch)
    // A tapped "reply ready" notification: open its chat, over whatever was showing.
    val tappedThread by openThread.collectAsState()
    LaunchedEffect(tappedThread) {
        val id = tappedThread ?: return@LaunchedEffect
        currentThreadId = id; route = Route.Chat(id); openThread.value = null
    }
    fun toChat() { route = currentThreadId?.let { Route.Chat(it) } ?: Route.Threads }
    fun newChat(): String {
        val id = runBlocking { repo.newOrCurrentThread(currentThreadId, System.currentTimeMillis()) { "t-" + System.currentTimeMillis() } }
        currentThreadId = id; return id
    }
    // WhereSheetView.firstRunPush: a first-run row pushed settings' own screens. While it is up, back returns to the
    // sheet (not places & keys) and a SAVE ends the sheet in chat (iOS cf251b5).
    var firstRunPush by rememberSaveable { mutableStateOf(false) }
    // TrustLadderView's "add a near.ai key" presents the key form OVER THE TRUST SHEET and returns to it, where the button
    // has flipped to "switch" in place (iOS testAddingAKeyFlipsTheEncryptedModelCTAWithoutReopeningTheSheet). Here the
    // form stacked over WHERE and a save landed there — the user never saw the flip (the E8 journey, 2026-10-04).
    var formOverTrust by rememberSaveable { mutableStateOf(false) }
    fun formParent(): Route = if (formOverTrust) Route.Trust else Route.Where
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
            is Route.PlacesManage -> Route.PlacesHub
            is Route.Browse -> if (r.encryptedOnly) Route.Trust else Route.Where
            is Route.EditPlace -> formParent()
            is Route.Download -> Route.Where
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
    PredictiveBackHandler(enabled = route != null && route != Route.Threads) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            when (val r = route) { is Route.Chat -> route = Route.Threads; null -> Unit; else -> parentOf(r)?.let { route = it } }
        } finally { backProgress = 0f }
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
    val trust = remember(sessionVersion, place, auditRevision, verifierTimedOut) { place?.let { trustSnapshot(app.session, it, timedOut = verifierTimedOut, audit = AuditIndex.shared) } }
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
    val groundingEnabled by app.groundingEnabled.collectAsState()

    // Sheets as on iOS (ContentView / ChatView / E2EETitleBlock): the chat is always underneath; the chats list, Where,
    // settings and trust are bottom sheets over it — medium then large detents, trust at large, a grabber on the list
    // and Where; Where's children (add, edit, browse, download) stack a second, full sheet. A push inside the
    // settings sheet slides horizontally.
    val chatRoute = currentThreadId?.let { Route.Chat(it) }
    val sheetA: Route? = when (val r = route) { null, is Route.Chat -> null; is Route.AddPlace, is Route.EditPlace -> formParent(); is Route.Browse, is Route.Download -> Route.Where; else -> r }
    // WhereSheetView's .task runs on EVERY presentation. Here the sheet's content stays composed while it is hidden, so
    // its LaunchedEffect(Unit) refreshed the machines once per process: a model the server had since unloaded still
    // read "warm" on the next open (the C6 journey, 2026-10-04). Each showing is counted, and the refresh keys on it.
    var whereShowings by remember { mutableIntStateOf(0) }
    LaunchedEffect(sheetA == Route.Where) { if (sheetA == Route.Where) whereShowings++ }
    // the flag belongs to one visit of the form: anywhere else, the next form is where's again
    LaunchedEffect(route) { if (route !is Route.AddPlace && route !is Route.EditPlace) formOverTrust = false }
    val sheetB: Route? = when (val r = route) { is Route.AddPlace, is Route.EditPlace, is Route.Browse, is Route.Download -> r; else -> null }
    val screen: @Composable (Route?) -> Unit = { r -> when (r) {
        null -> Unit   // the store has not answered yet
        Route.Threads -> ThreadsScreen(
            threads = visibleThreads,
            onOpen = { currentThreadId = it; route = Route.Chat(it) },
            onNew = { newChatCount++; route = Route.Chat(newChat()) },
            onClose = { toChat() },
            hapticsEnabled = app.settings.shouldPlayHaptics,
            onSearch = app::searchThreads,
            onFallbackSearch = { q -> repo.fallbackThreadsMatching(q) },
            // ChatsListView.deleteThread: deleting the OPEN chat clears it first (a fresh chat, the list dismissed) so the
            // transcript never sits on a thread the store no longer has.
            onDelete = { id ->
                runBlocking { repo.delete(id) }
                if (id == currentThreadId) { currentThreadId = null; route = Route.Chat(newChat()) }
            },
            previews = previews.associate { it.threadId to it.content },
            initialQuery = app.listSearchMemory.query, onQueryChanged = { app.listSearchMemory.query = it },
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
                ChatViewModel(repo = repo, threadId = r.threadId, provider = { app.providers.value.firstOrNull { it.id == app.selectedId.value } }, credential = app::credentialFor, groundingKey = app::groundingKey, localContextTokens = app::localContextTokens, session = app.session, onFirstToken = app::recordUse,
                    systemPrompt = { app.settings.systemPrompt }, onBraveKey = { app.setBraveKey(it) }, makeTransport = app::transportFor)
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
            val landing = remember(r.threadId) { app.deepLink.consume(r.threadId) }
            ChatScreen(focusEpoch = focusEpoch, vm = vm,
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
                trust = trust, hasProvider = place != null,
                // Re-read when the place or the route changes: a key is saved from a settings route, never mid-chat.
                hasKey = remember(place, route) { place?.let(app.credentials::hasCredential) ?: true },
                onBack = { route = Route.Threads },
                onOpenSettings = { route = Route.Settings },
                onOpenWhere = { route = Route.Where },
                singleTurn = place?.answersSingleTurnOnly == true,
                onOpenTrust = { route = Route.Trust },
                onRetryVerification = reverify,
                developerMode = developerMode,
                webSearchState = webSearchChipState(hasProvider = place != null, hasKey = app.hasBraveKey(), groundingEnabled = groundingEnabled),
                // Same as iOS: no key → the search settings; a key → toggle.
                onWebSearchChip = { if (!app.hasBraveKey()) route = Route.Search else app.setGroundingEnabled(!groundingEnabled) },
                consoleRecovery = { e -> ProviderPresets.consoleRecovery(
                    isBraveGrounding = e.source is LlmError.Source.BraveGrounding,
                    errorHost = e.url?.let { runCatching { URI(it).host }.getOrNull() },
                    sourceLabel = (e.source as? LlmError.Source.Provider)?.name, activeProvider = place) },
                groundingAttached = groundingEnabled && place?.let { ProviderCapability.BUILT_IN_GROUNDING in it.capabilities } == false,
                onDisableWebSearchAndRetry = { app.setGroundingEnabled(false); vm.disableWebSearchAndRetry() },
                onCopy = { text -> Clipboard.copy(hostContext, text) },
                scrollToMessageId = landing?.messageID,
                hapticsEnabled = app.settings.shouldPlayHaptics,
            )
        }

        Route.Where -> WhereSheet(
            providers = providers, selectedId = selectedId, problem = problem, onSelect = app::select, hasKey = app::hasKey,
            // read the resident set here so a load or an eviction behind the sheet redraws warm/cold
            phoneWarm = residentEngines.let { { p: Provider -> app.isPhoneWarm(p) } },
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
            installedBytesFor = { app.localStorage.installedBytes(it.id) }, availableMB = app.availableMBForUi(),
            // On mobile data the sheet asks first and stays open (WhereSheetView+Actions); otherwise back to the chat.
            onStartDownload = { app.startAndSelect(it); if (app.pendingCellularDownload.value == null) toChat() }, onCancelDownload = { app.localDownloads.cancel(it) }, onDeleteWeights = app::deleteWeights,
            cellularPrompt = cellularPrompt, onAnswerCellular = { net -> app.answerCellularDownload(net); if (net != null) toChat() },
            parkedDownloads = localJobs.filterValues { it.parked }.keys,
        )

        is Route.AddPlace -> {
            val model = remember(r) {
                AddEditProviderModel(AddEditProviderModel.Mode.Add,
                    startsCustom = r.custom != null, customStart = r.custom ?: AddEditProviderModel.CustomStart.COMPUTER,
                    initialPreset = r.presetName?.let { n -> Provider.presets.firstOrNull { it.name == n } }).also { it.store = app.providerStore; it.catalogStore = app.catalogStore }
            }
            // A setup SAVED from a first-run push ends the sheet, not just the push: the user came to start chatting, and
            // the composer with the chip naming their new model is that (the save made it current). Popping to the picker
            // asked them to pick again; popping to the sheet showed a list of one. Cancel still pops (parentOf).
            AddEditProviderScreen(model, onDone = { if (firstRunPush) { firstRunPush = false; toChat() } else route = formParent() })
        }

        is Route.EditPlace -> {
            val p = providers.firstOrNull { it.id == r.providerId }
            if (p == null) route = Route.Where
            else {
                val model = remember(r) { AddEditProviderModel(AddEditProviderModel.Mode.Edit(p)).also { it.store = app.providerStore; it.catalogStore = app.catalogStore } }
                val pulls by app.downloads.downloads.collectAsState()
                AddEditProviderScreen(model, onDone = { route = formParent() }, onDownload = { route = Route.Download(p.id) }, downloads = pulls, onCancelDownload = { app.downloads.cancel(it) })
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
                        EncryptedModelCTA.resolve(nearAI, hasKey = nearAI != null && app.credentialFor(nearAI).isNotEmpty())
                    },
                    onEncryptedModelCTA = { cta ->
                        route = when (cta) {
                            is EncryptedModelCTA.SwitchToNearAI -> Route.Browse(cta.provider.id, encryptedOnly = true)
                            // No usable key: the key form, editing the legacy setup when there is one.
                            is EncryptedModelCTA.AddNearAIKey -> { formOverTrust = true; cta.existing?.let { Route.EditPlace(it.id) } ?: Route.AddPlace(Provider.nearAI.name, null) }
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
        Route.ChatsSettings -> ChatsSettingsScreen(app.settings, onDeleteAllChats = { runBlocking { repo.deleteAll() }; currentThreadId = null; newChat() }, onBack = { route = Route.Settings })
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
            onAdd = { route = if (r.locality == WhereLocality.HOME) Route.AddPlace(null, AddEditProviderModel.CustomStart.COMPUTER) else Route.CloudKeyPicker },
            onEdit = { route = Route.EditPlace(it) }, onDelete = app::remove, onBack = { route = Route.PlacesHub })
        Route.CloudKeyPicker -> CloudKeyProviderPickerScreen(
            presetsWithoutKey = app.credentials.presetsWithoutKey(),
            onPickPreset = { name -> route = Route.AddPlace(name, null) },
            onPickCustom = { route = Route.AddPlace(null, AddEditProviderModel.CustomStart.CLOUD_KEY) },
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
                    onCopyID = { Clipboard.copy(hostContext, it) })
            }
            // What this server last answered with first, the live list behind it (WhereSheetView+Actions.browseSheet).
            // There is no shipped catalogue to fall back to: a server that has never answered opens empty and fills.
            else ModelBrowserScreen(app.browseDoor(p), selectedModel = p.model,
                onSelect = { m -> app.applyBrowseSelection(p, m); toChat() }, onCancel = { route = Route.Where },
                onCopyID = { Clipboard.copy(hostContext, it) })
        }

        is Route.Download -> {
            val p = providers.firstOrNull { it.id == r.providerId }
            if (p == null) route = Route.Where
            // equipArriving: an arriving pull goes on the list immediately and is selected, as tapping a phone model in `get` does
            else OllamaDownloadScreen(provider = p, center = app.downloads, onBack = { route = Route.Where }, onStarted = { ref -> app.activate(ref, p) }, onCompleted = { ref -> app.activate(ref, p) })
        }
    } }

    // The sheet is its own window and does not see the activity's navigation-bar inset: read it here and pad inside.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The sources sheet (SourceStackChipView's .sheet): the chip asks, the root presents, as every sheet here.
    var sourcesSheet by remember { mutableStateOf<List<GroundingSource>?>(null) }
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
            scaleX = 1f - 0.08f * l; scaleY = 1f - 0.08f * l
            transformOrigin = TransformOrigin(0.5f, 0f)
            translationY = liftPush * l
            if (l > 0f) { shape = RoundedCornerShape(10.dp); clip = true }
        }) { CompositionLocalProvider(LocalSourcesPresenter provides { sourcesSheet = it }) { screen(chatRoute) } }
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
            val tops = when {
                top == Route.Trust -> listOf(SheetDetents.LARGE)
                top == Route.Where && whereFirstRun -> listOf(SheetDetents.FIRST_RUN, SheetDetents.LARGE)
                else -> listOf(SheetDetents.MEDIUM, SheetDetents.LARGE)
            }
            IosSheet(visible = top != null, tops = tops, onDismiss = { toChat() }, grabber = top == Route.Threads || top == Route.Where, containerColor = TeemoonColors.groupedBackground, lift = sheetLift) {
                // under the child sheet (the add form over the where sheet) this sheet is scenery too
                Box(Modifier.semantics { if (sheetB != null) hideFromAccessibility() }) {
                (top ?: sheetAShown)?.let { page0 ->
                    AnimatedContent(targetState = page0, label = "sheetPage", transitionSpec = {
                        val spec = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
                        if (routeLevel(targetState) > routeLevel(initialState)) slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 }
                        else slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it }
                    }) { page -> screen(page) }
                }
                }
            }
            val child = sheetB
            IosSheet(visible = child != null, tops = listOf(SheetDetents.LARGE), onDismiss = { route = formParent() }, grabber = false, scrimAlpha = 0.3f, containerColor = TeemoonColors.groupedBackground) {
                (child ?: sheetBShown)?.let { screen(it) }
            }
            IosSheet(visible = activeModelCard != null, tops = listOf(SheetDetents.LARGE), onDismiss = { activeModelCard = null }, grabber = false, containerColor = TeemoonColors.groupedBackground) {
                activeModelShown?.let { (model, door) ->
                    ModelDetailScreen(model, confidentiality = model.confidentialityNote, onCopyID = { Clipboard.copy(hostContext, it) }, onBack = { activeModelCard = null },
                        liveLoader = door.liveLoader?.let { load -> suspend { load()?.firstOrNull { it.id == model.id } } },
                        offersLoader = door.offersLoader?.let { load -> suspend { load(model.id) } })
                }
            }
            // presentationDragIndicator(.visible); detents by count (sourcesSheetTops)
            IosSheet(visible = sourcesSheet != null, tops = sourcesSheetTops(sourcesShown.size, screenHeightDp), onDismiss = { sourcesSheet = null }, grabber = true, containerColor = TeemoonColors.groupedBackground) {
                GroundingSourcesView(sourcesShown, onClose = { sourcesSheet = null })
            }
        }
    }
}

