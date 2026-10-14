# teemoon-android — the map

One page per module: what it owns, the spine a change travels along, the
names that mislead, and what guards it. Read this before `grep`. Each file
names the teemoon-ios source it ports in a `// ports:` header, and `ports.json`
is the same map as data.

## Modules

```
app ──────────┬── chat          Room store, FTS search            (ai.teemoon.chat, .chat.search)
              ├── providers     places, keys, catalogues, config  (ai.teemoon.providers)
              ├── inference ─┬─ confidential  attestation, E2EE, trust verdicts
              │             └─ providers      (TeemoonHttp: every client carries the cleartext gate)
              └── ondevice ─── inference      the LiteRT-LM binding
conformance                     vendored byte fixtures from iOS; test-only, not editable here
```

Every module is one flat package. File names are keys in `ports.json`, so
renaming or moving a file costs a map edit; prefer moving a body between
existing files. Rationale comments (the ones that cite an iOS commit, a
measurement or a bug) are load-bearing: a port that drops them re-buys the
bug.

Conventions that hold everywhere: Kotlin warnings are errors (root
`build.gradle.kts`); user-visible strings are lowercase; trust copy is data
(the UI renders `TrustVerdict` / `AttestationSummary`, never composes a trust
sentence); `fallbackToDestructiveMigration()` is forbidden.

## app — the wiring layer

Layering rule: a **pure object** (testable on the JVM: `TrustPresentation`,
`ChatCards.ErrorCardText`, `WhereGetPolicy` …) feeds a **composable**, and
`MainActivity.Root` is the one place that **wires** state holders to screens.
A new value goes in the pure object first, with a test; the composable lays
it out; `Root` passes it. Historically the bugs lived in `Root`, not in the
policy it wires, so the behavior ledger (`tools/behavior_ledger.py`) pins
call sites there.

Routes (`MainActivity.kt`, `sealed interface Route`) and how they present:

| level | routes | presented as |
|---|---|---|
| 0 | `Chat` | the ground |
| 1 | `Threads`, `Where`, `Settings`, `Trust` | sheet A over the chat |
| 2 | `AddPlace`, `EditPlace`, `Browse`, `Download`, settings sub-pages, `PlacesHub` | sheet B over sheet A (forms), or a push inside the sheet |
| 3, 4 | `PlacesManage`, `CloudKeyPicker` | pushes; the level decides the slide direction |

`sheetA` / `sheetB` are derived from the route in `Root`; the chat under a
sheet is hidden from accessibility, as UIKit's presentation does.

File → screen (the names that mislead):

| file | holds |
|---|---|
| `SettingsScreen.kt` | the **Where** sheet (`WhereSheet` composable, `WhereRowView`). Not settings. |
| `AppSettingsScreens.kt` | the real settings pages (appearance, chats, search, credits) |
| `PlacesKeysScreens.kt` | places hub, manage, cloud-key picker |
| `TrustScreen.kt`, `TrustLadderViews.kt`, `TrustPresentation.kt` | the trust sheet: presentation (pure) → screen |
| `ChatScreen.kt`, `ChatCards.kt`, `MessageViews.kt`, `ChatViewModel.kt`, `ChatGeneration.kt` | the chat |
| `AppState.kt` | the process's one state holder: providers, keys, session, catalogues, downloads, engines |
| `DesignKit.kt`, `Theme.kt`, `IosSheet.kt` | the iOS design system in Compose; sheets that track the finger |
| `UiTestSeeding.kt` | what an armed debuggable launch may plant (journeys only) |

CompositionLocals: `LocalTeemoonPalette` (DesignKit), `LocalSheetBottomInset`,
`LocalOnGroupedGround`, `LocalSubtitleShrinksToFit` (IosSheet),
`LocalRenderRecorder`, `LocalSourcesPresenter` (ChatCards).

Guards: unit tests per pure object; Paparazzi snapshots for every screen
(`./gradlew :app:verifyPaparazziDebug`); the device journeys
(`app/src/androidTest`, `tools/device_suite.sh`) with step goldens per screen
profile. Anything that moves a sheet or changes a wait is a device change.

## providers — places, keys, catalogues

The spine, in the order a change travels:

```
Provider (a place)                              Provider.kt, ProviderPresets.kt, EndpointScheme.kt
 → ConfigStore / ProviderConfigProjection       persistence; servers, equipped rows, recents
 → ProviderCredentials / Keystore               secrets (AES-GCM under the Android keystore)
 → EndpointModelCatalog.Source                  which upstream a host is
 → ModelCatalog.liveCatalog                     THE ONE routing table source → adapter
 → *Adapter (OpenRouter, xAI, Fireworks, NVIDIA, Ollama, LMStudio), NearAIModelCatalog
 → LiveCatalogStore                             THE ONE cache of fetched lists
 → WhereProviderPresentation / WhereGetPolicy / WhereReadyRows / ModelBrowserDoor   what the Where sheet shows
 → AddEditProviderModel / EndpointProbe         the add/edit form's state and probes
```

The seam rule: every network call sits behind an injected
`fetch(url, headers, timeoutMs) → (status, body)`; `EndpointModelCatalog.liveFetch`
is the live one and `ModelCatalog.liveCatalog` passes it. Tests never open a
socket.

Vocabulary: `*Adapter` = one vendor's list fetcher; `NearAIModelCatalog` is
near.ai's; `ModelCatalog` is the shared shaping + routing; `LocalModelCatalog`
is the static on-device table; `EndpointModelCatalog` is the generic probe and
the HTTP seam. Three near-synonyms answer "is there a key": `hasCredential`
(by record), `browseCredential` (record, then endpoint), `WhereGetPolicy.hasKey`
(the policy's view of the same). Two server-kind detectors exist
(`LocalServerKind.detect`, `AddEditProviderModel.liveCatalog().detectKind`);
`HomeServerProbe.Info` and `WhereGetPolicy.HomeInfo` carry the same three
fields.

Guards: `ConfigStoreTest`, `WhereSheetLogicTest` (presentation + policy +
probe), the kit-driven `CatalogsKitTest`, one test per adapter.

## inference — the generation loop

Type → file (the file names do not say):

| type | file |
|---|---|
| `PromptBudget`, `ToolCallRecord`, `TurnReport`, `RequestResult` | `GenerationReport.kt` |
| `WireMessage`, `ToolCallState`, `RecoveryToolSpec` | `ToolCallState.kt` |
| `LocalEngineCache`, `LocalEngineResidency`, then the transport | `LiteRTTransport.kt` |
| `LocalDefaults`, `LocalEngineFactory`, `LocalInferenceError` | `LocalRuntime.kt` |
| `StreamCallbacks`, `GenerationTransport`, the loop | `GenerationEngine.kt` |

The loop (`GenerationEngine.run`) owns tool rounds for every hosted provider;
`ToolRoundPolicy` is its budget. The one exception is on device:
`LiteRTToolBridge` lets LiteRT-LM run the loop itself, and its error
semantics differ (a rejected key comes back as result text, not a thrown
`LlmError`) — the header of that file says what is lost.

Messages on the wire are `List<Map<String, Any>>` in three shapes:
`{role, content}`, `{role: assistant, content, tool_calls}`, and
`{role: tool, tool_call_id, content}`. `OpenAiTransport.makeBody` and
`LiteRTTransport.turn` are the only places that read or write them.

Guards: `GenerationEngineTest`, `TurnTextBeforeVerifyTest` and
`TruncatedPromptWarningTest` together pin the loop — run all three when
`run()` changes. `SseStreamParserTest` pins framing, `[DONE]`, the reasoning
keys and first-token timing; `OpenAiTransportE2EETest` pins that a decrypt
failure kills the stream and never renders ciphertext.

## confidential — attestation, E2EE, the verdict

The pipeline a near.ai session runs, and where each step lives:

```
AttestationService      fetch the record (gateway, model, GPU legs; independent retries)
 → TdxQuote             parse the TDX quote
 → DcapService          dcap-qvl over Intel PCS collateral (Collateral.kt)      — the native engine runs only on device
 → NrasService          NVIDIA remote attestation
 → TlsAttestationVerifier   the TLS probe against the attested leaf
 → ProvenanceService → ProvenanceVerifier   GitHub attestation → Sigstore / Rekor
 → ConfidentialSession.attestationState      the one verdict the UI reads
 → TrustVerdict / AttestationSummary / VerificationRun   every trust sentence the app may show
 → E2EE / E2EEStreamCodec                   sealing and the stream decrypt hook
```

Invariant to keep by hand: `ConfidentialSession` has four mirrored gate
ladders (`attestationState`, `e2eeDegradedReason`, `e2eeBindingIntact`,
`degradeIsHardFailure`) plus `AttestationSummary.e2eePassed`. A new verifier
is an edit to all of them, to `TrustVerdict.Input`, to `AppState` wiring and
to a `VerificationRun` section. They mirror the Swift, so do not merge them;
add to each.

Vocabulary: eleven status-like enums exist (`AttestationState`, `StepState`,
`RunStepStatus`, `SectionResult`, `PillarState`, `HeaderSeverity`,
`TrustChipMode`, `TrustClaim.Status`, `EverydayAuditState`,
`AuditVerdictClass`, `MeasuredConfig.Line.State`); `StepState`,
`RunStepStatus` and `SectionResult` encode the same moods for different
surfaces (`VerificationRun.kt` converts). "Verdict" names three things:
`TrustVerdict` (sentences), `AuditVerdictClass` (a reviewer's class) and the
NRAS verdict string. An unparseable quote reads as "not checked" (null), not
as a failure; the hard failure comes from `DcapService`.

Guards: `ConfidentialSessionTest` (three Swift suites in one file),
`AttestationServiceTest`, `AttestationLegIndependenceTest`, the kit classes
(`CryptoKitVectorsTest`, quotes, collateral, sigstore, NRAS).
`DcapQvlDeviceTest` in `:app` is the only run of the real engine.

## chat — the store

Room entities and DAO (`ChatModels.kt`, `ChatDao.kt`, `ChatDatabase.kt`),
`ChatRepository`, and FTS search under `chat.search` (`ChatSearchIndex`,
`SearchSql` with the bundled SQLite driver). `ChatListSession.kt` holds
`ChatListSearchMemory` and `TranscriptDeepLink`. Migration failure crashes;
any other open failure quarantines to an in-memory store and surfaces
(`ChatDatabase.open`). Exported schemas under `chat/schemas` are the paper
trail.

## ondevice — the LiteRT-LM binding

`LiteRtLmEngineFactory` builds engines behind `inference`'s
`LocalEngineFactory` seam; `BackendLadder` orders CPU/GPU/NPU per SoC and
decides where speculative decoding is used. LiteRT finds an NPU dispatcher by
listing a directory, so the APK keeps legacy packaging; `NpuDispatch` hands a
Snapdragon 8 Elite a directory of links to the renamed Qualcomm dispatcher and
the QNN runtime. Measured only on hardware (`OnDeviceDecodeBenchTest`).

## How to verify a change

| what | command |
|---|---|
| everything JVM, offline | `./gradlew test` (runs `behaviorLedger` first) |
| every screen's pixels | `./gradlew :app:verifyPaparazziDebug` (`recordPaparazziDebug` to accept) |
| the ports map still resolves, drift against iOS | `python3 tools/parity_closure.py` |
| wiring parity in `app/` | `python3 tools/behavior_ledger.py` |
| copy still says what the Swift said | `python3 tools/copy_audit.py --ios ../teemoon-ios` |
| use-case coverage | `python3 tools/use_case_coverage.py` |
| a journey on a device | `tools/device_suite.sh` (one class per call; `--record` keeps video) |
| move a file's citation after porting an iOS commit | `python3 tools/bump_port_sha.py` |
