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
| Predictive back | n/a | the screen being left follows the finger: a pushed page slides toward its edge at the finger's speed with its parent coming in (the pop, seeked linearly), a sheet narrows 48 dp and sinks 24 dp from its bottom edge (Material 3's bottom-sheet preview), the chat recedes to 95%; release runs on from there, cancel springs back. `enableOnBackInvokedCallback` is on. Until motion-pass item 1 (2026-11-14) the progress was read and never used. Verified on the JVM (`MotionCheckBackTest`, the gesture played frame by frame); not yet on a device with gesture navigation | `MainActivity.Root`, `PredictiveBack.kt`, `IosSheet` (`back`) |
| Pages inside a sheet | the system push: the page slides the whole width over a parallaxed parent | Material's shared axis X: the page moves 30 dp and fades through (the old one out in the first 35% of 300 ms); a back swipe from either edge takes it the finger's way. The maintainer's "do all for 1.1.0" on pm's recommendation (motion-pass item 10, 2026-11-14) | `PredictiveBack.SheetPages`, `Motion.axis*` |
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
| Transcript edges | `GlassEdgeFadeView`: a progressive `systemThinMaterial` blur under a progressive dim at both edges of the full-bleed transcript (`ChatGlassFade`'s stops) | Material's default top bar and a solid bottom (Google Messages, Signal): the title bar is solid, the page's own black, with no hairline and no scroll tint (the maintainer 2026-11-14, "try the default android"), its ground 8 dp below the round buttons only while content is scrolled beneath; from 12 dp above the composer panel to the bottom of the screen, across the full width, the ground is solid, the 12 dp a fade that is whole at the panel's top. No partial dim anywhere. `ChatFadeBand.kt` stays ported (and tested) but nothing draws from it | Compose has no backdrop blur an overlay can sample, and the dim alone left text readable under the title and around the panel — reported 2026-09-08 and again on the one-panel composer; the maintainer chose the Android pattern on 2026-11-13. `ChatEdgesTest` fails on any transcript pixel inside either band (412/360 dp, light, font 2.0, keyboard up), and on a hairline over a chat with nothing scrolled under the title |
| Markdown parse timing | Textual, patched to resolve during body evaluation (constraint 10) | the renderer's synchronous `parseMarkdown` in composition, never its coroutine `String` entry | the coroutine entry paints an empty document for a frame per chunk; the list re-anchored to the top on every one |
| Blocking network calls | `URLSession` is asynchronous; a request can be issued from anywhere | every synchronous OkHttp call hops to `Dispatchers.IO` inside its own seam, and `MainThreadNetworkTest` calls each seam from Main against the fake server | Android throws `NetworkOnMainThreadException` for a blocking call on Main — the Brave tool swallowed it as "check your network connection" and every search failed instantly (2026-09-08) |
| "all N models" on the add/edit form | opens the catalogue browser as a sheet over the form | the group expands in place ("fewer" folds it back) | the browser is a sheet-over-sheet on iOS; here the form is already the child sheet, and a third layer has no idiom |
| Empty threads | never created (a Thread exists from the first send) | a row exists from "new chat" on (the chat screen needs an id) but the list reads only threads with a message, and "new chat" reuses an empty one | `ChatDao.observeThreadsWithMessages`, `ChatRepository.newOrCurrentThread` |
| Model info in the browser | row context menu | long-press the row; tap equips | `ModelBrowserScreen` |
| Delete a row iOS lets you swipe (a `ready now` model, a place in places & keys, an Ollama model on the form) | trailing swipe, or the context menu where there is one; the same one action | trailing swipe (`TrailingSwipe`, a `SwipeToDismissBox` that springs back) AND a long-press menu item, the same one action; no leading swipe | `DesignKit`, `SettingsScreen`, `PlacesKeysScreens`, `AddEditProviderScreen`; `WhereSheetContractTest`, `PlacesKeysContractTest` |
| Wide markdown tables | horizontal scroll inside the transcript | the table scrolls horizontally in its own container (the renderer's default), the page never does | `MarkdownBody` |
| Delete a chat | swipe action | swipe from the trailing edge (`SwipeToDismissBox`) or long-press; both confirm | `ThreadsScreen` |
| Text selection | `.textSelection(.enabled)`; a reply's long press is its context menu (Think Harder, Copy) and a double tap selects a word (8c19327) | the same: a reply's long press opens the menu — "think harder" (newest finished reply), "copy" — and a double tap selects the word under the finger in a `SelectionContainer`, with Android's handles and copy toolbar. The container's own long press is moved out of reach with a `LocalViewConfiguration` whose long-press timeout is an hour, so the menu is the only long press. The thinking block is not selectable. Until 1.0.6 the long press WAS the selection; the maintainer chose the menu on 2026-10-19 so Think Harder has a home, and the double tap (iOS build 57) on 2026-10-21 | `MessageViews.AssistantMessageView`, `ReplySelection`, `ReplyContextMenu` |
| Haptics | impact / selection generators | Compose haptics gated by the chats setting: confirm on send, long-press feedback on the list | `ChatScreen`, `ThreadsScreen` |
| On-device accelerator | one Apple GPU stack; LiteRT is given no backend choice at runtime | a chip-compiled NPU bundle when one is installed (`LocalModelCatalog.npuBundles` → `requireNpu`, one rung); otherwise a ladder, gpu then cpu (4 threads), where a rung is accepted ONLY after it decodes a real token and the verdict is cached. The Tensor dispatcher ships in `jniLibs` and is found by the runtime LISTING `nativeLibraryDir`, which needs `useLegacyPackaging = true`; `<uses-native-library>` names the vendor libraries API 31+ would otherwise refuse. On a Snapdragon 8 Elite the Qualcomm dispatcher (shipped renamed) and the QNN v79 runtime are linked in by `NpuDispatch`; on Android 17 the manifest's `android.hardware.npu` declaration is what lets the NPU Manager schedule the work | `BackendLadder`, `LiteRtLmEngineFactory`, `ondevice/src/main/jniLibs/README.md`, `app/build.gradle.kts`, `AndroidManifest.xml` |
| Launcher icon | the moon on black, light/dark/tinted variants | the same moon as an adaptive icon (foreground disc in the safe zone, black background) with a **monochrome** layer for Android 13+ themed icons; legacy 48dp PNGs for API < 26 | `res/mipmap-*` |
| Splash | LaunchScreen | the Android 12+ system splash: the launcher icon on the window ground, which is black in every system theme since the app is pinned dark (no `values-night`) | `res/values*/themes.xml`, `res/values/colors.xml` |
| Share / clipboard | share sheet; pasteboard | the attestation report goes through Android's chooser; sensitive copies clear after 120 s, as on iOS | `MainActivity`, `Clipboard.kt` |
| Share to teemoon | a share extension writes the items to the App Group; teemoon opens a new chat with them as a draft when it next becomes active (iOS cannot open its app from a share extension) | the share sheet opens teemoon itself (`ACTION_SEND` / `SEND_MULTIPLE`, cold start and `onNewIntent`): a new chat on the last-used place, the photos in the strip through the picker's intake (the isolated decoder, metadata stripped, 20 MB and 10 photos), text or a link in the field with the caret after it and the keyboard up; never sent. The maintainer 2026-11-14, as other chat apps do it, in 1.1.0 on both apps. A single near.ai `.json` is still the import | `imaging/ShareIn`, `MainActivity.receiveShare` |
| Image paste (IMAGES.md §4c) | the field pastes, then the composer reads the change back: an inserted span that is one URL, an image item on the pasteboard → the URL comes back out and the item's image attaches | the paste is decided BEFORE anything goes in: the field's `contentReceiver` sees the clip's items (URI and text each), the image items go to the composer and the whole clip is consumed, so no string reaches the field (the maintainer 2026-11-09, like Messages; iOS still keeps a text-only item's URL until its 1.1.0 change). A clip naming no image type and holding no text is read too, its bytes deciding (an image or the note, never a silent paste). The same cases hold; typing, autocorrect, suggestions and dictation never reach the receiver, so they can't read the clipboard. An image item is a URI that is not `http(s):` or `data:` (a ClipData item has no type of its own; the clip's types name image) | `ComposerPaste`, `DesignKit.Composer` |
| Keyboard stickers (IMAGES.md §4d) | a Genmoji, memoji or sticker from the keyboard becomes its own text description, as plain text where it was typed; no glyph or attachment reaches the draft (spec 2be015a2) | a keyboard's sticker, Emoji Kitchen, Bitmoji or GIF arrives through `commitContent` as a content URI, the same route as Gboard's copied-photo chip, so it attaches as an image (a GIF as its first frame) through the paste rule above; on a model that can't see images it takes the can't-see route. Android hands no description with it (only a label the keyboard chooses), and the route can't tell a sticker from a photo, so text in its place would drop real photos too. The gap is accepted (the maintainer 2026-11-11, via pm); Gboard is checked on the Pixel at the end | `DesignKit.Composer` |
| Thumbnail motion (IMAGES.md §4d) | a new composer thumbnail grows from 0.6 with a spring while it fades in; a removed one fades and its slot closes; Reduce Motion: fade only | the same motion (a keyed `LazyRow`'s item animation plus the grow), but Android has no "reduce motion" with a fade left: "remove animations" (animator scale 0) makes Compose's animations instant, the fade included | `DesignKit.Composer` |
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

- **Importing chats** (1.1.0, IMPORT_NEAR.md §5). The picker is the system document picker
  (`ActivityResultContracts.OpenDocument`) asked for `application/json` and `application/octet-stream`: some file
  managers label a downloaded `.json` as octet-stream, and a file that is not an export is refused by its first element
  anyway. The bytes are read through the content resolver, one top-level element at a time (`TopLevelArrayScanner`),
  where iOS memory-maps the file. Row ids are the key strings themselves (`near:conv_x`, `near:conv_x:msg_y`); iOS
  derives v5 UUIDs from the same strings. The summary is an alert on the chats page, not a sheet. A chat with no title of
  its own takes its first user message's first line, where iOS stores none and derives the row.

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


## Debug builds run StrictMode and JankStats

iOS has no StrictMode, and its frame-drop tooling lives in Instruments rather than the app. A DEBUG build of this one
installs both at launch (`DebugDiagnostics`, from `TeemoonApplication.onCreate`): a thread policy for disk reads and
writes, network and custom slow calls, a VM policy for leaked closeables, and JankStats on every activity window, with
the current route as its `screen` state. penaltyLog only (`adb logcat -s StrictMode JankStats`). Everything is behind
`BuildConfig.DEBUG`, so release and benchmark builds carry none of it.

## A Tensor phone's images run a second model file

iOS has one Gemma file. Android swaps it for the Tensor NPU bundle on a Tensor G5 (`LocalModel.forDevice`): about
twice the decode of the phone's GPU, but no vision encoder. the maintainer chose on 2026-10-26 to keep text on the NPU and
offer the general file (2.6 GB) as a second download at the first image (`AppState.startImageModelDownload`, through
the usual download flow and mobile-data question; the composer's wording waits on the review page, entry
ondevice-image-download). With both files present, `LocalRef.imageBundleFile` names the general one and
`LiteRTTransport` runs image turns on it with vision, closing the NPU engine first (one engine resident at a time);
text turns go back to the NPU. Behind `LocalModelCatalog.imagesOnDevice`, off on both platforms until the maintainer's yes.

## The Pixel 10's general file runs on the CPU

iOS has one GPU. On the Pixel 10 family (Tensor G5, a PowerVR GPU) LiteRT's cached GPU kernels decode at half speed and
GPU loads are slow (13.7 s cached, 56.5 s cold, measured 2026-10-26), so `BackendLadder.order` puts the general Gemma
file on the CPU rung there, as Google AI Edge Gallery does on the same phone (the maintainer, 2026-10-26). Text on a Pixel 10
runs the Tensor NPU bundle anyway; the general file serves its image turns. Mali Pixels keep the GPU.

## The CPU rung runs a 4096-token window

iOS runs one window per file. On Android the general Gemma file's window depends on the rung it lands on:
`BackendLadder.contextTokens` gives the CPU rung 4096 and leaves the GPU at 8192. That covers the Pixel 10's image
turns and any phone that falls back to the CPU. Measured on a Pixel 10 Pro on 2026-10-26, a long CPU reply took 7.8 s
at 4096 against 10.4 s at 8192, prefill ran 45% faster, and memory fell by 180 MB (the maintainer, "processor only").

The cost is half the history. The search payload follows the window: `AppState.localContextTokens` asks the factory
for the window of the rung it would try first (the backend memo's proven one), and `LiteRTTransport` budgets each turn
by the window the engine was built with. GPU phones keep 8192 until an A/B there is measured.

## The error card's network lines read exception classes, and ask the system whether it is offline

iOS picks the card's line from a URLError code (`NetworkFailure`, conformance/errorcopy.json, kit 0.5.3). The JVM has
no codes, so `NetworkFailure.of` reads the exception classes OkHttp throws along the cause chain: an
`InterruptedIOException` is a timeout (the stall line once text was shown), `UnknownHostException` /
`ConnectException` / `NoRouteToHostException` unreachable, `SSLHandshakeException` / `SSLPeerUnverifiedException` /
a `CertificateException` insecure, a reset socket, EOF or stream reset a dropped connection.
`ConformanceErrorCopyTest` plays every fixture row as the exception Android sees for it.

Offline has no exception of its own on Android: a phone with no network fails DNS exactly like an unknown host. So the
view model asks `NetworkPathObserver.isSatisfied` and an unreachable or dropped send with no network up reads iOS's
offline line ("no connection, so nothing was sent…").

## The photo picker's ceiling is the cloud limit; the composer trims to the one that applies

iOS opens `PhotoPicker(limit:)` with what is left under the limit that applies (10 to a cloud model, 4 on the phone's
model; IMAGES.md draft 3 §4). Android's `PickMultipleVisualMedia(maxItems)` takes its ceiling once, when the launcher is
registered, so it is `ImageCaps.CLOUD_PER_SEND` and the composer passes the remaining count to the pick: the URIs past
it are never read, except one, so `ComposerImages.add` can say "up to 4 images per message" as iOS does.

## "May not fit" reads total RAM; the load gate reads availMem

iOS f3773d9 (pm and the maintainer, 2026-10-30): the download-time label and the start-here hero compare the phone's RAM in
marketed GB (total memory rounded up to the next GiB) with the model's floor (E2B 6, E4B 8, provisional). Android reads
`ActivityManager.MemoryInfo.totalMem`. Before, the label compared the download size with free memory at the moment the
sheet drew, and an 8 GB Pixel 8a that runs E2B was told it may not fit (Test Lab, 2026-10-30).

The load gate (LiteRTTransport's memoryCheck: peak, or file size until measured, plus 512 MB) has no per-process budget
to read on Android — iOS reads `os_proc_available_memory`. It reads `availMem`, not `availMem − threshold`: the
threshold is where lmkd starts killing cached background apps, and a load that crosses it makes the system free memory
that way. Measured on Test Lab (2026-10-30, matrix 9000657022189770859) the threshold is small — 216 MB on a Pixel 8a
and a Pixel 6a — so the choice matters little; what tripped the 8a was the old LABEL (download size against free
memory at launch, 2468 > 2645 − 216).

What a load costs is NOT its PSS. E2B's vision load and three image turns took availMem down by about 2.49 GB on both
phones (8a 3399 → 917 MB, 6a 3103 → 612 MB) — about the file size — while PSS read 3.0–3.3 GB (it counts mapped file
pages and the GPU's share). A peak measured as PSS + 512 would refuse both phones, which run the model; Android's
"peak" for the gate is the availMem drop, and E2B's (≈ 2490 MB) matches its file size within 1%, so the peak stays
null.

Android's headroom (pm 2026-10-30, within the maintainer's "Yes" to fixing the false warnings): at or above the model's floor
the gate asks the FILE SIZE alone (`LocalMemory.androidHeadroomMB` is 0); below it, file size + 512. With 512, availMem
at launch (2645 MB on the 8a, 2539 on the 6a) was under the 2980 MB need, so a warm-up in the first seconds declined on
phones that run the model; at 2468 both pass. And the gate looks twice (`LoadMemoryGate`): still short, it waits ~2 s
for the system to trim caches and reads again before declining with the second figure. iOS keeps + 512 against its
per-process budget, which has no launch-time dip. The peak's plumbing into the gate
(LocalRef) lands with the first measured Android peak; until then every model's peak is null on both platforms and the
gate asks file size + 512, as iOS does.

## Two Docker Hub hosts, for near.ai verification only

`auth.docker.io` and `registry-1.docker.io` joined the host list on 2026-10-30 (the maintainer, via pm; the same hosts as
iOS). The measured compose names the compose-manager launcher by its linux/amd64 CHILD digest, and near.ai's GitHub
attestation exists only for the multi-platform INDEX; OCI has no child → parent lookup. `ProvenanceService.resolveIndex`
takes an anonymous pull token, lists the tags newest first, HEADs each for its index digest and fetches only new
candidates, stopping at the first index whose bytes hash to its digest and list the child as linux/amd64
(`ImageIndexResolution`). The tag list is followed page by page (Link rel="next") up to 200 tags; past that it is the
ordinary provenance failure, remembered for 24 hours like "no index". The answer is cached by child digest, so a warm cache asks Docker Hub nothing; "no
index" is cached for 24 hours (a new launcher build is a new child, so it never hides one). No
credentials and nothing of the user's go to either host; they are called only while verifying a near.ai image. Only the
launcher's repository is looked up (`INDEX_REPOS`, pull scope `repository:nearaidev/compose-manager-launcher:pull`):
the maintainer's approval, recorded by audit on 2026-10-31, covers that index alone, so any other image without its own
attestation never reaches Docker Hub.

## Opening an export

A near.ai export opened from Files ("open with") or shared to teemoon reaches the app as an `ACTION_VIEW` or
`ACTION_SEND` intent (pm 2026-10-30, import redesign (f)); iOS gets the same file through its document types. The
filters take `application/json` over `content:` only: no octet-stream (the picker's fallback, not an intent's), no
`file:`, no `BROWSABLE`, so no web link opens teemoon. `MainActivity.receiveExport` copies the file into
`cache/import-incoming/` on arrival, because a content URI's read grant ends with the activity that received it, and a
copy that fails reaches the import as a read that throws, so the "can't open" alert says so. An arrival opens the import
page (iOS NearImportView, cdea210c) at its confirm: the dry run reads the file and writes nothing, and nothing is
imported until "import" is tapped (pm 2026-11-01). One import at a time, as iOS's `onOpenURL` (`guard !isBusy`): a file
arriving while one is checked or imported is dropped (`IncomingExport.receive`), and each arrival has its own copy, so
the dropped one never deletes the file the running import reads again to write.
`IncomingExportTest` (JVM) and `IncomingExportColdStartTest` (device: view, share, and what resolves) hold it.

The import page's file card (Safari style, pm 2026-11-01) names the file, its size and its date as the file's provider
reports them: `ImportFileInfo.of` asks `OpenableColumns` for the name and size and `DocumentsContract`'s
`COLUMN_LAST_MODIFIED` for the date, which only a documents provider (the picker's) gives. An arrival is asked on
receipt, while the grant holds, since the cached copy's own name is teemoon's. A part the provider doesn't give is left
off the card; a provider that refuses the query never fails the import. iOS reads the same from the URL's resource values.

After an import that wrote chats, closing the page ("done", "show chats" or back) asks whether to delete the export, never over "import finished" (iOS 478b3bc3, pm 2026-11-01; v9). iOS asks the
file's `isWritable` and deletes it through `NSFileCoordinator`; Android offers it only for a file the user picked, whose
provider sets `FLAG_SUPPORTS_DELETE` and on which the picker gave a write grant (`ImportFileInfo.deleter`), and deletes
it with `DocumentsContract.deleteDocument`. An export that arrived by share or open-with is never offered: its grant is
the sender's, and teemoon's own cached copy goes anyway. A delete that doesn't happen says so in an alert.

The store review prompt (iOS ChatsListView.requestReviewIfAppropriate, StoreKit) is Play In-App Review on Android, on
the same "+" tap and the same rule: five visits since the last ask, the ask counted whatever the store shows. the maintainer
(2026-11-01) wants it in the Play build and the GitHub APK free of Google Play libraries, so it is a build property,
not a runtime check: `-Pteemoon.store=play` compiles `src/store-play` and `src/play-review` with
`com.google.android.play:review` (which brings Play's task library with it); the default build compiles
`src/store-github`, a no-op, with no Google Play library on the classpath. A property rather than product flavours
keeps every task name, output path and the REPRODUCIBLE.md recipe as they were. `tools/check_store.py` reads the
built bytes: the release workflows fail a GitHub APK that names `com.google.android.play`, and the Play upload step
refuses a bundle without In-App Review. Play shows the card only to a copy it installed, within its own quota.

The paperclip's "find a model that reads images" (iOS b8457654) opens the model browser as a route,
`Route.Browse(id, imagesOnly = true)`, with "reads images" on. iOS presents its browser as a second sheet over the
where sheet. Cancel goes back to the where sheet, as closing iOS's browser sheet does. On a phone model iOS opens the
where sheet onto "get"; Android's where sheet has no open-to-get mode, so it opens the where sheet, whose "get"
section is the same list. At 150% text and up, a browser row puts its price and context under the name: beside the
name they left it as "ki…" at 200% (pm 2026-11-04 found the same squeeze on iOS at AX5).

**Trust scope's guest-OS suffix on its own line** (near-trust-scope, 2026-11-06). iOS appends " · in the quote ✓" to the
guest OS's os_image value pill; Android's ValuePill is one line and ellipsizes a long value, which cut the suffix off, so
the words ("in the quote ✓") are drawn as a line under the pill. Same words, same condition (BootLogReplay.Matches).

**Image turns on the Snapdragon 8 Elite's NPU** (the maintainer 2026-11-12, "i say we just use snapdragon npu for
images"). iOS has no NPU path. Google's sm8750 Gemma 4 E2B bundle carries the vision encoder, so a Galaxy S25-class
phone's image turns run on the NPU bundle with the encoder on the GPU beside it, and need no second download
(NpuBundle.seesImages, BackendLadder.visionLadder). Galaxy S25 Ultra, 2026-11-12: 1 and 4 pictures named right, 10 in
0.32 s to the first token but 6 of 10 named right in one run (the GPU: 10 of 10 in 1.35 s) — the device pass keeps
measuring it. The Tensor G5's bundle reads images too (Firebase's Pixel 10, 2026-11-12: 10 of 10, 1.50 s to the first token; its GPU 6.17 s), so a G5 phone's image turns stay on it with no second download; so does the Tensor G6's (Pixel 11 and 11 Pro, 2026-11-12: 10 of 10, 0.85 / 0.97 s). A bundle not yet measured reading images would run its image turns on the general file beside it.

**An image's copy and save** (review items android-viewer-copy, android-viewer-save-zoom, the maintainer 2026-11-07;
android-viewer-bar and the long press, 2026-11-12). iOS's viewer has done and a share icon, and its share sheet already
offers copy and save to photos. Android's has neither for an image — the maintainer's Pixel, Android 17, 2026-11-12:
Edit, Quick Share and apps, no Copy, nothing that saves to the gallery; GrapheneOS has no Play services. A long press on
an image — in the chat or the viewer — opens Material 3's own menu on the photo, on every Android: "copy" (an image
ClipData of the FileProvider URI, `ImageClip`), "save" (MediaStore to Pictures/teemoon, no storage permission at minSdk
29, `ImageSave`; a brief "saved to photos" toast when the gallery took it) and "share", each with its Material icon
leading (content_copy, download, share), with the platform's long-press haptic (`ImageMenu`, `ImageContextMenu`). Share
is the plain system sheet, nothing added (`ImageShare`): the maintainer 2026-11-12, "copy and share dont belong in the
share view", which replaced e0735cfb's copy and save inside the sheet. The viewer's bar is iOS's: done, and one share icon (`ViewerBar`). The double tap (1× ↔ 2.5×)
and the 1×–6× pinch are iOS's own (`ViewerZoom`). Opening differs, a known gap owed in 1.1.1: iOS's viewer zooms out of the tapped thumbnail, Android's opens as a plain dialog over the chat; the shared-element open (motion-pass item 7 part 2) touches the back gesture, the system bars, TalkBack, the long-press menu and the viewer's goldens, and the maintainer put it in 1.1.1 on 2026-11-14 ("Yes yes", answering that recommendation). 1.1.0 ships the animated double tap only.

**The import page's bottom button over the navigation bar** (teemoon-ios a919ff53, S26). iOS stands the button's bottom
34pt above the screen's edge — its home-indicator inset. Android keeps the 34 dp where the gesture bar is shorter, and
over a taller three-button bar the button stands on the bar instead (`ImportLayout.bottomGap`); the 30 dp sides, the
54 dp pill and the 1.5 dp tile outlines are iOS's.

**The one-panel composer** (teemoon-ios docs/COMPOSER_PANEL.md @ 85c544c6; the maintainer 2026-11-12, "One panel, in
1.1.0"; review card composer-one-panel). Android follows the spec's panel — photos strip, note, text, then "+" · where
chip · web chip … send/stop — with these differences:
- **Targets:** the "+", send and stop are 32dp glyphs in 48dp targets (Android's minimum; iOS 44 pt). The targets' own
  margins make the row's ends and most of its gaps (`ComposerSpacing.ROW_GAP` 4dp).
- **Where chip in the row:** the glyph, the model's name and the chevrons — no place text (the spec's
  "[▣ gemma 4 e2b ⌃]"); the locality glyph says where and the content description carries the place. Its ends are
  10dp and its gaps 4dp so "gemma 4 e2b" stays whole at 360dp, Android's smallest common width (the spec's budget is
  iOS's 375 pt). While a download reports the name keeps its width and the progress gives way; a model that needs a
  download keeps "not downloaded" whole (it blocks the send) and its name gives way.
- **Accessibility sizes:** chips go icon-only from font scale 1.5 (`ComposerPanel.ACCESSIBILITY_SCALE`) and the text
  stops at 4 lines. Android has no Large Content Viewer: the icon-only chips carry their full label as the content
  description, and the where chip's long press keeps its menu (model info, copy model id).
- **TalkBack order** is set with `traversalIndex` inside a traversal group: the strip, the text, "+", where, web, send.

- **The GitHub fetch budget** (iOS 9d21baba) is one `FetchBudget` of 200 per action log, as on iOS, but Android's (c) fetch is one session job
  over the read scope and every listed one, with one fetched-files map between them (iOS runs `TrustScope.run` per log with its own
  cache). Each log's budget is kept by its place in that list; a file a spent log could not fetch is left for another log that names it,
  and one no log could read ends as `ComposeFileFetch.Failed` (couldn't-check), never kept on disk.
