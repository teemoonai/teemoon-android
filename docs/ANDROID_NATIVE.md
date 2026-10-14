# Where the Android app is deliberately Android

The port is faithful to teemoon-ios in behavior, bytes and design. A handful of things are done the Android way because an
iOS-shaped answer would feel wrong on a phone that has a back gesture, kills
backgrounded apps, and draws its own launcher. This is the complete list; keep
it current when you add one. Everything here was checked on the `teemoon`
AVD (1080x2400, API 35) on 2026-09-03 unless a line says otherwise.

| Behavior | iOS | Android (this app) | Where |
|---|---|---|---|
| Response cache | `URLCache.shared` had to be zeroed: CFNetwork archives a cacheable response together with the request that made it, Authorization header included, and every `.default` session reads it live | nothing to disable — OkHttp has no shared cache and no client here is given one. The fix is an ABSENCE, held by `behavior_ledger.py`'s `sourceAbsent` rule rather than by code | ledger rule |
| Mobile-data prompt | a SwiftUI `.alert` modifier on the view, which resolves the gate itself | the gate is consulted in `AppState`, which exposes `pendingCellularDownload`; the screen renders it with `IosAlert`. Same question, same copy — but the holder decides and the UI draws, which is where Compose puts that split | `AppState.startAndSelect` |
| Wait for wi-fi | URLSession `allowsExpensiveNetworkAccess = false` pauses and resumes the task by itself | Android has no per-request equivalent: `LocalModelDownloader` parks a `WIFI_ONLY` job on `NetworkPathObserver.mobileData` before and during the transfer (the partial survives) and resumes from it on wi-fi; the row reads "waiting for wi-fi". The prompt above was not actually drawn, and the answer was dropped, until 2026-09-24 (journey G3) | `LocalModelDownloader.start`, `SettingsScreen` |
| On-device thinking | declared ON (`LocalLanguageModel.defaultEnableThinking`) but never passed to LiteRT, so gemma does not think; the answer is bounded by `answerReserveTokens` = 4800, not the declared 1024 | the same two values, passed to the runtime explicitly (`LocalDefaults`): thinking off, 4800. The first port passed the declarations instead, so only Android thought, and a tool round's second think block leaked into the bubble (Pixel, 2026-10-12). `emitAnswerOnly` stays as the guard for a bundle that thinks anyway | `LocalDefaults`, `LiteRTTransport.turn` |
| On-device search payload | 2048 tokens of sources on the one bundle it runs (8192-token window) | the same 2048 on the general bundle, a quarter of the window on a smaller one: a Tensor bundle's window is 4096, compiled in, and there 2048 of sources met the ceiling mid-answer (Pixel 10 Pro, 2026-10-12). `GroundingBudget.maxTokens(count, onDevice, contextTokens)` — iOS adopts it unchanged if it ever ships a small-window bundle | `GroundingBudget`, `BraveWebSearchTool(contextTokens)`, `ChatGeneration.makeGroundingTools` |
| Download resume | a background `URLSession` returns opaque resume data, which `downloadTask(withResumeData:)` takes back | a ranged request (`Range: bytes=N-`) against the kept `.part` file; 206 appends, 200 or 416 starts over. No opaque token exists on this side, and none is needed — the bytes on disk **are** the resume state. A remote file that changed underneath a resume yields a mixed file, caught by the checksum that is verified before the file is moved into place | `LocalModelDownloader.liveFetch` |
| Structure | chat is the root; list, Where, settings are sheets | same | `MainActivity.Root` |
| Back gesture / button | none | chat → opens the chats list; list → leaves the app; a sheet → the chat; a settings page → its parent | `MainActivity.Root` (`PredictiveBackHandler`) |
| Predictive back | n/a | the current screen follows the gesture (a sheet sinks, a push slides toward its edge, the chat recedes); release completes, cancel springs back. `enableOnBackInvokedCallback` is on. Verified as a completed gesture only — the AVD has three-button navigation | `MainActivity.Root` |
| Process death | rare; SwiftUI restores | the route and the current thread are saved state (`rememberSaveable` + `RouteCodec`); the app comes back where it was | `MainActivity` |
| Theme change | pinned dark (`UIUserInterfaceStyle: Dark`), so none | pinned dark too (`TeemoonTheme(dark = true)` by default, black window ground, light bar icons forced); `configChanges="uiMode"` is still declared so a system theme switch is a no-op in place rather than a recreate | `Theme.kt`, `MainActivity`, manifest |
| Keyboard appearance | the keyboard takes the appearance of the app it types into, so the pinned-dark app gets a dark keyboard | the keyboard (IME) is a separate app that follows the system theme and its own theme setting; Android has no API for an app to ask for a dark keyboard. On a light-mode phone the keyboard stays light under the dark app — the user sets the keyboard's own theme to dark (or runs the phone dark). Captures force the AVD's AOSP keyboard dark (`tools/parity_capture_compose.sh`) | none (platform) |
| Edge-to-edge | free | `enableEdgeToEdge()`; every screen consumes status, navigation and keyboard insets itself (`imePadding`) | `DesignKit.GroupedPage`, `ChatScreen` |
| Keyboard | rises with the field; scroll/tap dismisses | same: the composer rises with the keyboard; a tap outside a field or a scroll of the transcript dismisses. `adjustResize` alone does nothing under edge-to-edge on API 35+ | `ChatScreen`, `GroupedPage` |
| Keyboard action key | return = newline | the soft keyboard's action key is **send** (Android chat convention) with sentence capitalization; a hardware return inserts a newline on a multi-line field (Compose behavior, not exercised on the AVD) | `DesignKit.Composer` |
| Sheets | UIKit sheet: floating (8pt insets, ~48pt corners), medium / large detents, elevated ground | the same geometry, drawn by `IosSheet` (an anchored-draggable of our own): Material 3's modal bottom sheet has only a fixed 50% half detent and no custom detents, which is why "browse near.ai" fell off the half sheet; the Material look (edge-to-edge, 28dp top corners) is deliberately not used | `IosSheet` |
| Sheet toolbar | pinned above the sheet's scrolling list | the same: `GroupedPage(header = …)` keeps the bar fixed; only the content scrolls | `GroupedPage` |
| Alert | `.alert` with stacked actions | `IosAlert` — a card with stacked pill actions; Material's dialog wraps three text buttons onto two lines | `IosAlert` |
| UI-test seeding | `--uitesting` + UITEST_SEED_* env, keys from staged host files | the `uitesting` launch extra + `seed_*` extras on a debuggable build armed by the instrumentation runner or a run-as token file (`UiTestSeeding.consumeArm`), keys from files in the app's private dir, including `seed_attestation=degraded` | `UiTestSeeding`, `tools/parity_seed_android.sh` |
| Parity captures | XCUITest `ParityCaptureUITests` drives the simulator by accessibility labels and saves attachments | `ParityCaptureTest` (androidTest) — a Compose UI test in the app process: launches `MainActivity` with the seeding extras per state, drives by semantics (`onNodeWithTag` / text), and saves `UiAutomation.takeScreenshot()` PNGs to the external files dir; `tools/parity_capture_compose.sh` clears, stages keys, runs the class, pulls. | in-process semantics beat a uiautomator tree dump per action: every state in ~4 min, and the harness cannot mis-tap a key into the composer |
| Transcript glass edges | `GlassEdgeFadeView`: a progressive `systemThinMaterial` blur under a progressive dim at both edges of the full-bleed transcript | the same stops (`ChatGlassFade`) with the blur weight folded into the dim (alpha = 1 − (1 − dim)(1 − 0.8·blur)), over a full-bleed `LazyColumn` whose content padding is the measured chrome | Compose has no backdrop blur an overlay can sample (`Modifier.blur` blurs its own node, API 31+); on the dim alone the text under the title stayed legible and read as a bug, so the frost becomes ground |
| Markdown parse timing | Textual, patched to resolve during body evaluation (constraint 10) | the renderer's synchronous `parseMarkdown` in composition, never its coroutine `String` entry | the coroutine entry paints an empty document for a frame per chunk; the list re-anchored to the top on every one |
| Blocking network calls | `URLSession` is asynchronous; a request can be issued from anywhere | every synchronous OkHttp call hops to `Dispatchers.IO` inside its own seam, and `MainThreadNetworkTest` calls each seam from Main against the fake server | Android throws `NetworkOnMainThreadException` for a blocking call on Main — the Brave tool swallowed it as "check your network connection" and every search failed instantly (2026-09-08) |
| "all N models" on the add/edit form | opens the catalogue browser as a sheet over the form | the group expands in place ("fewer" folds it back) | the browser is a sheet-over-sheet on iOS; here the form is already the child sheet, and a third layer has no idiom |
| Empty threads | never created (a Thread exists from the first send) | a row exists from "new chat" on (the chat screen needs an id) but the list reads only threads with a message, and "new chat" reuses an empty one | `ChatDao.observeThreadsWithMessages`, `ChatRepository.newOrCurrentThread` |
| Model info in the browser | row context menu | long-press the row; tap equips | `ModelBrowserScreen` |
| Delete a row iOS lets you swipe (a `ready now` model, a place in places & keys, an Ollama model on the form) | trailing swipe, or the context menu where there is one; the same one action | trailing swipe (`TrailingSwipe`, a `SwipeToDismissBox` that springs back) AND a long-press menu item, the same one action; no leading swipe | `DesignKit`, `SettingsScreen`, `PlacesKeysScreens`, `AddEditProviderScreen`; `WhereSheetContractTest`, `PlacesKeysContractTest` |
| Wide markdown tables | horizontal scroll inside the transcript | the table scrolls horizontally in its own container (the renderer's default), the page never does | `MarkdownBody` |
| Delete a chat | swipe action | swipe from the trailing edge (`SwipeToDismissBox`) or long-press; both confirm | `ThreadsScreen` |
| Text selection | `.textSelection(.enabled)` | replies are a `SelectionContainer`: long-press selects, with Android's handles and copy | `ChatScreen` |
| Haptics | impact / selection generators | Compose haptics gated by the chats setting: confirm on send, long-press feedback on the list | `ChatScreen`, `ThreadsScreen` |
| On-device accelerator | one Apple GPU stack; LiteRT is given no backend choice at runtime | a chip-compiled NPU bundle when one is installed (`LocalModelCatalog.npuBundles` → `requireNpu`, one rung); otherwise a ladder, gpu then cpu (4 threads), where a rung is accepted ONLY after it decodes a real token and the verdict is cached. The Tensor dispatcher ships in `jniLibs` and is found by the runtime LISTING `nativeLibraryDir`, which needs `useLegacyPackaging = true`; `<uses-native-library>` names the vendor libraries API 31+ would otherwise refuse. On a Snapdragon 8 Elite the Qualcomm dispatcher (shipped renamed) and the QNN v79 runtime are linked in by `NpuDispatch`; on Android 17 the manifest's `android.hardware.npu` declaration is what lets the NPU Manager schedule the work | `BackendLadder`, `LiteRtLmEngineFactory`, `ondevice/src/main/jniLibs/README.md`, `app/build.gradle.kts`, `AndroidManifest.xml` |
| Launcher icon | the moon on black, light/dark/tinted variants | the same moon as an adaptive icon (foreground disc in the safe zone, black background) with a **monochrome** layer for Android 13+ themed icons; legacy 48dp PNGs for API < 26 | `res/mipmap-*` |
| Splash | LaunchScreen | the Android 12+ system splash: the launcher icon on the window ground, which is black in every system theme since the app is pinned dark (no `values-night`) | `res/values*/themes.xml`, `res/values/colors.xml` |
| Share / clipboard | share sheet; pasteboard | the attestation report goes through Android's chooser; sensitive copies clear after 120 s, as on iOS | `MainActivity`, `Clipboard.kt` |
| Typography | SF, rounded / width variants | Roboto; the scale is matched. "rounded" falls back to the sans; width is drawn on the system's Roboto Flex (`/system/fonts`, its `wdth` axis), standard design only as on iOS; on API < 33, which has no Roboto Flex, it reads as standard | `Theme.kt` |
| Analytics / crash reporting | none | none — no Firebase, no Play Services beyond In-App Review (`CONTRIBUTING.md`) | build files |

## Decided differently, or not ported

- **Material You dynamic color.** The app carries its own tint (the
  appearance setting, orange by default) so the two platforms match. A
  "system" tint option would be a new appearance value on both sides.
- **Notifications**, from 2026-09-21 — teemoon's first, and its first permission
  prompt. This entry used to read "notifications of any kind; none exist on iOS
  either", and the reason it changed is that iOS *cannot* have this one.

  A cloud turn can take minutes, and a suspended iOS app cannot hold a streaming
  connection, so keeping the app open is the only option there. On Android a
  foreground service can. Measured on a Pixel 10 Pro: a backgrounded app reaches
  `oom_score_adj` **900** within a minute — the top of the kill list, surviving
  only because nothing else wanted the memory — and **50** with the service up (a steady **200** once the recent-foreground boost expires).
  The notification is not the price of the feature, it is the mechanism; the
  OS requires one for a foreground service.

  Denying the permission does not break it: the service still runs, the turn
  still completes, only the telling is lost. So it is asked for at the first
  send rather than at launch, and never re-asked.

  **The notification carries nothing of the chat** (2026-10-13). It says "your
  answer is ready", or that the turn didn't finish, and nothing else: no reply,
  no thread title (the title is the user's first message), and the lock screen
  gets the same words. From 2026-09-21 it carried up to 380 characters of the
  reply under the thread's title, decided knowing the exposure: notification
  history (a log the app cannot purge), the lock screen, paired devices, and any
  app holding `BIND_NOTIFICATION_LISTENER_SERVICE` — a watch companion, a desktop
  bridge, an automation app granted access years ago. Reversed after the local
  audit rated it a medium risk: those listener apps are not "the user's own
  devices". The tap still opens the thread; its id rides in the PendingIntent.
  `TurnNotification.kt`.

  **Two channels, from 2026-10-13.** `working` (the ongoing one: DEFAULT with
  sound and vibration off, so a status-bar icon and no noise, since it starts at
  every send) and `replies`, shown as "answers" (the result: HIGH with the
  default sound, so a heads-up banner). The status-bar icon is the launcher's
  monochrome moon (`ic_stat_moon`), with a dot for an answer
  (`ic_stat_moon_reply`) so the bar changes when it lands, and a "!" for a
  failure (`ic_stat_moon_failed`). They replaced one LOW channel, `turns`, that
  filed a finished answer under Silent with no icon; a DEFAULT `answers` lasted
  an hour, chiming with nothing on screen. New ids because Android never raises
  an installed channel's importance; `turns` and `answers` are deleted.
  `TurnChannels` in `TurnNotification.kt`.

  Not WorkManager: a cloud turn cannot survive process death at all — the answer
  IS the response stream, from a server teemoon does not own — so the need is to
  keep THIS process alive, which is a plain foreground service. `androidx.work`
  stays off the dependency list.
- **`DesignTour`, `OnboardingStyle`, `PlatformAssets`** (decided 2026-09-08,
  re-measured 2026-09-21). The tour is a debug launch route that exists because
  Xcode Previews under-style a capture; Paparazzi has no such gap, so the
  snapshot suites are the Android form. The onboarding style's one live token
  (`ML.accent`) is in `Theme.kt` and the rest — all of `Timing`, most of `ML` —
  is referenced by nothing in the iOS tree either. The asset lookup serves vendor
  logos, and **iOS cannot show them either**: the tile asks for
  `provider-logo-<name>` while the catalogue ships `logo-brave` and three
  siblings, so both platforms render the monogram. Reported upstream.

- **The truncated-prompt warning reaches the USER, where iOS only logs it.**
  When a server reports evaluating far fewer input tokens than were sent, it is
  silently discarding the front of the prompt — the system prompt and the
  question go first, so the model answers something the user never asked.
  iOS writes `logger.warning`; here it rides `StreamCallbacks.onWarning` and is
  shown. The remedy is that machine's context window (`OLLAMA_CONTEXT_LENGTH`
  for Ollama), which only the user can change, and a log they cannot read
  cannot ask them to. The warning names the provider as iOS's does — someone
  with a phone model, a home server and three cloud keys configured cannot act
  on "the server". `TurnReport.providerName` carries the name across, because
  the warning is raised in the engine and the name lives on the transport.

- **No frame pacer for streaming text.** iOS runs `DisplayLinkPacer`, a
  `CADisplayLink` that samples the growing reply once a frame so the renderer
  does not re-layout per token. Compose's recomposition is already frame-clocked
  — state written between frames coalesces into one recomposition on the next
  `MonotonicFrameClock` tick — so a pacer on top would buy a frame of latency
  and nothing else. **Carry the measured warning, though**: iOS's first version
  capped at 15–24 fps and *that ceiling*, not the markdown renderer, was what
  made streaming look chunky (p95 of 87 ms between updates). Do not add a
  throttle to the transcript tail. The expensive half — not re-rendering settled
  blocks — is `MarkdownStreamSplitter.kt`, and that one IS ported.

- **`OkHttpClient` is the HTTP seam.** iOS has an `HTTPClient` protocol so tests
  can plant bytes. Here the client is already passed as a parameter with a lazy
  default (`EndpointDirectory`, `LMStudioAdapter`, `OllamaAdapter`,
  `ProviderKeyValidation`, `EndpointModelCatalog`) and tests hand it a
  MockWebServer-backed one. A Kotlin interface wrapping it would be a second
  seam over the first.

- **No background-task bracketing.** `BackgroundWork` brackets an in-flight
  request against iOS suspending the app. A coroutine keeps running when an
  Android activity stops, so there is nothing to bracket at this scale. A model
  download that outlives the app runs under `DownloadService`, a foreground service.

- **`LiveCatalogStore` is owned, not shared** (2026-09-22). iOS reaches
  `LiveCatalogStore.shared`, an `@Observable` singleton under Application
  Support/ModelLists excluded from backup. Here `AppState.catalogStore` is the
  one instance, in `noBackupFilesDir/ModelLists` (Android's name for the same
  promise), and is passed to whatever reads it, so a test drives a real store
  over a temp folder. The redraw `@Observable` gives for free is a `revision`
  StateFlow that the Where sheet reads. The near.ai `/endpoints` copy that iOS
  keeps in UserDefaults is `files/endpoint-directory.json`, read lazily on first
  use as iOS does.

## How to check it

`./gradlew :app:assembleDebug`, install on an emulator or a phone, then: back from the chat shows the list; back again
leaves; the keyboard's action key sends; a swipe on a chat row offers delete; toggle dark mode in the system settings
and the app stays where it was.

## The device catalog: on an NPU phone, the E2B row is the chip's bundle

iOS has no NPU bundle to choose (nothing of the kind exists for Apple silicon), so `LocalModelCatalog` is one list
there. Android's is resolved per device: `LocalModelCatalog.forDevice(Build.SOC_MODEL)` maps each model through
`LocalModel.forDevice`, and on a chip Google publishes a Gemma 4 E2B bundle for (Tensor G5, Tensor G6, Snapdragon 8
Elite) the E2B entry keeps its id and row but becomes that chip's file — its size, checksum, revision, and a name that
says which file it is ("Gemma 4 E2B · Tensor", "· Snapdragon"). No extra row, no badge. E4B is unchanged (Google
publishes no NPU build of it). The storage runs the chip's file when it is on disk and falls back to the general one;
the get policy keeps offering the device file until it lands (`WhereGetPolicy.deviceFileMissing`), and the finished
download renames the place. An NPU file that refuses to start is marked (`.npu-unusable` beside it) and never run
again on this phone: the general file runs instead, or the get row offers its download; a delete of the weights
clears the mark.

## Data Saver constrains only a metered path

iOS's Low Data Mode is a per-network setting, so `isConstrained` there is a fact about the current path. Android's
Data Saver is one system switch that the platform applies only on metered networks. `NetworkPathObserver` therefore
calls the path constrained when Data Saver is on AND the path is metered (`dataSaverConstrains`): on unmetered home
wi-fi a download starts without the low-data prompt, as Android itself restricts nothing there. Reading the switch
alone put the prompt in front of every download on unmetered home wi-fi.

## No engine is loaded behind the user's back

iOS warms the on-device engine on every selection and checks nothing, because iOS suspends a backgrounded app and
it cannot reach the GPU there. Android keeps the process running, and a download holds it up with a foreground
service, so a phone model that finished downloading in the background selected itself and loaded about 4 GB onto
the GPU while the user was in another app. It stayed resident: the UI_HIDDEN trim that evicts engines had fired
before the load (Pixel 10 Pro, 2026-10-05). `BackgroundWarmUp` skips the warm-up while the app is not in the
foreground (MainActivity.onStart warms up on every return), and evicts an engine whose build finished after the
app left, unless a turn is using it.

