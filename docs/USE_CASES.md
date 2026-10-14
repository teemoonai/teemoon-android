# Use cases — the end-to-end test plan for iOS and Android

One list of what a person does with teemoon, and for each use case the end-to-end test that walks it **on each
platform**. A use case is "tested end to end" on a platform only when a test here launches the real app, drives it
through its UI, and asserts the OUTCOME the person sees. A screenshot capture, a JVM test or a contract on one widget
does not count, however useful it is.

`tools/use_case_coverage.py` reads this table and checks every named test exists in its repo (Android here, iOS at
`--ios <checkout>`). It prints the coverage per platform and the gaps, and **fails when a named test is gone** (a
rename or a deletion that would silently uncover a case). Run it with every change to a test or to this file.

The Mac's own UI tests (menus, ⌘ shortcuts, windows) are not rows here: they are a third platform's use cases.

## Columns

- **Runs**: `sim` is the simulator or emulator, offline (a fake SSE server, a fake Brave, seeds). `device` needs a
  real phone (NPU weights, notifications, cellular, background limits). `live` uses real hosts and keys (opt-in, a
  few cents: `tools/live_cloud_suite.sh` here, `ProviderLiveUITests` and friends on iOS).
- **Android / iOS**: `Class#method` of the test that walks it. Several are comma-separated. `(part)` means the test
  walks some of the case but not its whole outcome. `—` means no test yet, and that's a gap.
  `n/a: <why>` means the case doesn't exist on that platform.

**On a phone** the test app installs BESIDE the installed store build, as `ai.teemoon.e2e`, so its app and data are
never touched, and it is uninstalled when the run ends: `ANDROID_SERIAL=<phone> GRADLE_EXTRA=-Pteemoon.e2eId=true
APP_ID=ai.teemoon.e2e tools/device_suite.sh OnDeviceThinkingJourneyTest NotificationPermissionJourneyTest`.

The `sim` / `device` split is the answer to "where does this run": everything that only needs the app and a fake
world runs on the simulator/emulator on every change; `device` is the short list a simulator cannot settle and is
run on a real phone before a release; `live` is run before a release and when a
provider's wire changes.

## A. First run and setup

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| A1 | Nothing configured: the chip says "choose where"; send is off while the composer is empty, and a typed send asks for a provider | sim | UserJourneysTest#withNothingConfiguredSendWaitsForAPlace | ProductE2ETests#testLaunchShowsComposerAndDisabledSend |
| A2 | Connect a computer from Where: host, fetch models, pick, save; the next message goes to it | sim | UserJourneysTest#connectAComputerFromWhereAndChatOnIt | UseCaseJourneysTests#testConnectingAComputerFromWhereAndChattingOnIt |
| A3 | Connect a computer, failure paths: bad url, no models served, http refused without the insecure opt-in | sim | UserJourneysTest#connectingAComputerSaysWhyItFailed | UseCaseJourneysTests#testConnectingAComputerSaysWhyItFailed |
| A4 | Add a cloud key from Where (any filter): preset → paste → save; the row reads keyed, the filter is kept, the place is ready | sim | UserJourneysTest#addingACloudKeyFromWhereLeavesTheRowKeyedAndTheFilterKept, UserJourneysTest#addingACloudKeyFromTheAllFilterLeavesTheRowKeyed | UseCaseJourneysTests#testAddingACloudKeyFromWhereLeavesTheRowKeyedAndTheFilterKept, FirstRunCaptureUITests#test0StartHereCapture |
| A5 | Add a key, rejected: the host says 401 and the form says so; a home server with a refused key is not saved (a cloud key the provider refused may still be saved, by design) | sim | UserJourneysTest#aRejectedKeyIsSaidAndNothingIsSaved, LiveCloudTurnsTest#aRejectedCloudKeyIsSaidByTheForm | UseCaseJourneysTests#testARejectedKeyIsSaidAndNothingIsSaved, ProductLiveE2ETests#testARejectedCloudKeyIsSaidByTheForm |
| A6 | First run on a phone: "start here" names the device's model (Tensor bundle on a Tensor phone), "use" when it is on disk | device | OnDeviceThinkingJourneyTest#firstRunOnThePhoneOffersTheModelAlreadyOnIt | OnDeviceUseCaseTests#testFirstRunOffersTheModelAlreadyOnThePhone |
| A7 | First run via "use a cloud api": picker → key → save ends in the chat with the chip naming the model | sim | UserJourneysTest#firstRunThroughACloudKeyEndsInTheChat | FirstRunCaptureUITests#test0StartHereCapture |
| A8 | A keyed setup with no key: send is gated with a clear ask; an empty key cannot be saved | sim | UserJourneysTest#aKeylessSetupAsksForAKeyInsteadOfSending, UserJourneysTest#aKeyedSetupRefusesToSaveWithItsKeyEmptied | NoKeySendGateCaptureUITests#testKeylessBraveAnswersIsGatedAndBadged |

## B. Chatting

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| B1 | Send, stream, settle; a second send gets a second reply in the same thread | sim | UserJourneysTest#aSecondSendGetsASecondReplyInTheSameThread | ProductE2ETests#testSendThenSettleShowsAReply, ProductE2ETests#testSecondSendGetsASecondReply |
| B2 | Stop mid-stream: no error card, what streamed stays, the next send works | sim | ChatControlsContractTest#stopMidStreamShowsNoErrorCardAndTheNextSendWorks | UseCaseJourneysTests#testStopMidStreamShowsNoErrorAndTheNextSendWorks |
| B3 | A failed turn (HTTP 500): the error card sits under the prompt; retry resends and the reply lands | sim | UserJourneysTest#aFailedTurnRetriesAndTheReplyLands, ChatControlsContractTest#aFailedTurnShowsTheErrorCardRightUnderThePrompt | ProductE2ETests#testRetryAfterErrorResends |
| B4 | Error recoveries: 401 → "manage api key", 402 → "add credits", 400 with search on → "turn off & retry" re-asks WITHOUT the search tool and answers | sim | UserJourneysTest#aKeyOrBillingFailureOffersTheVendorsConsole, UserJourneysTest#aToolRefusalOffersSearchOffAndRetryAnswers | ProductE2ETests#testTurnOffSearchAndRetryAnswersWithoutTheTool, UseCaseJourneysTests#testAKeyOrBillingFailureOffersTheVendorsConsole |
| B5 | Thinking model: "thinking… Ns" while it reasons, "thought for Ns" after, the disclosure toggles | sim | ChatControlsContractTest#theThinkingDisclosureToggles | ProductE2ETests#testThinkingChipStaysLeadingAndDebugCardAppears, UseCaseJourneysTests#testAThinkingReplySaysSoAndItsDisclosureToggles |
| B6 | A markdown reply renders: headings, list, table, code block, a link that opens the browser (a chat code block has no copy control on either platform) | sim | UserJourneysTest#aMarkdownReplyRendersWithoutItsSyntax, UserJourneysTest#aMarkdownReplysLinkOpensTheBrowser | MarkdownFixtureFollowUITests#testHeadingHeavyReplyEndsPinnedAndTheTurnEnds, UseCaseJourneysTests#testAMarkdownReplyRendersAndItsLinkOpensTheBrowser |
| B7 | Copy: long-press the question → copied; select and copy part of an answer | sim | ChatControlsContractTest#theUserBubbleLongPressOffersCopy, UserJourneysTest#longPressingTheQuestionCopiesIt, UserJourneysTest#partOfAnAnswerCanBeSelectedAndCopied | UseCaseJourneysTests#testLongPressingTheQuestionCopiesIt, UseCaseJourneysTests#testPartOfAnAnswerCanBeSelectedAndCopied |
| B8 | The system prompt from settings reaches the wire, `{{datetime}}` expanded | sim | UserJourneysTest#theSystemPromptFromSettingsReachesTheWire | UseCaseJourneysTests#testTheSystemPromptFromSettingsReachesTheWire |
| B9 | A single-turn provider (Brave Answers) starts fresh each message | sim | UserJourneysTest#aSingleTurnPlaceStartsFreshEachMessage | UseCaseJourneysTests#testASingleTurnPlaceStartsFreshEachMessage |
| B10 | New chat clears the composer; the draft does not ride into it | sim | UserJourneysTest#aNewChatStartsWithAnEmptyComposer | ProductE2ETests#testNewChatClearsTheComposer |
| B11 | The title names the model and the place ("on your own machine"); a GGUF path reads as a name | sim | UserJourneysTest#theTitleNamesTheModelAndTheMachine, UserJourneysTest#aGgufPathIsShownAsAName | LocalProviderUITests#testTitleBarNamesTheModelAndSaysItIsLocal, LocalProviderUITests#testAGgufPathIsShownAsAName |

## C. Where, switching, places

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| C1 | Two places: the chip names the selected model; pick the other; the reply comes from it | sim | UserJourneysTest#switchingThePlaceFromTheChip | ProductE2ETests#testWhereChipOpensAndPickingARowChangesThePlace |
| C2 | Verifying on near.ai → switch to local → the send succeeds | sim | UserJourneysTest#switchingFromAVerifyingAttestedSessionToALocalProviderSends | ProductE2ETests#testSwitchingFromAVerifyingAttestedSessionToALocalProviderSends |
| C3 | Filters (all / phone / home / cloud) show exactly that locality's rows; recently used lists what has answered (and nothing before) | sim | UserJourneysTest#theWhereFiltersShowExactlyTheirLocality, UserJourneysTest#aModelThatAnsweredIsRecentlyUsed | UseCaseJourneysTests#testTheWhereFiltersShowExactlyTheirLocality, UseCaseJourneysTests#testAModelThatAnsweredIsRecentlyUsed |
| C4 | Unequip a cloud model (swipe, long-press) and it leaves the list; re-equip from browse | sim | WhereSheetContractTest#aTrailingSwipeOnACloudRowUnequipsIt, WhereSheetContractTest#theLongPressMenuOffersTheSameAction, UserJourneysTest#anUnequippedCloudModelIsReEquippedFromBrowse | UseCaseJourneysTests#testUnequippingACloudModelTakesItOffTheList |
| C5 | Edit a place: rename it; delete it from its form (asks; cancel keeps it); a keyed setup refuses to save with its key emptied (revoking is deleting) | sim | UserJourneysTest#aPlaceIsRenamedAndDeletedFromPlacesAndKeys, UserJourneysTest#aKeyedSetupRefusesToSaveWithItsKeyEmptied | UseCaseJourneysTests#testAPlaceIsRenamedAndDeletedFromPlacesAndKeys, UseCaseJourneysTests#testAKeyedSetupRefusesToSaveWithItsKeyEmptied |
| C6 | Home server: warm/cold rows, refresh, delete a model from the server (and its failure) | sim | UserJourneysTest#aHomeServerShowsWarmthAndDeletesAModel | UseCaseJourneysTests#testAHomeServerShowsWarmthAndDeletesAModel |
| C7 | Model browser: search, pick → equipped and selected; "use typed id"; load failure → retry | sim | UserJourneysTest#browsingACloudPicksAModelForTheChat, UserJourneysTest#aTypedModelIdIsUsedWhenTheListHasNoMatch, UserJourneysTest#aCatalogThatFailsToLoadSaysSoAndTryAgainFetchesAgain | UseCaseJourneysTests#testBrowsingACloudPicksAModelForTheChat, UseCaseJourneysTests#testATypedModelIdCanBeUsedFromTheBrowser (part: load failure → retry) |
| C8 | Ollama pull: paste a model, progress, stop, retry | sim | UserJourneysTest#pullingAModelOntoTheMachineLandsIt, UserJourneysTest#aPullShowsProgressAndStopEndsIt | UseCaseJourneysTests#testPullingAModelOntoTheMachineLandsIt, UseCaseJourneysTests#testAPullShowsProgressAndCancelEndsIt |
| C9 | Config problems: offline banner; "tidied up" merge notice; unreadable setups quarantined and surfaced | sim | UserJourneysTest#anUnreadableConfigIsKeptAndSurfacedNotReadAsAFirstRun, UserJourneysTest#aDuplicateConnectionIsMergedAndAnnounced, UserJourneysTest#offlineTheWhereSheetSaysWhyNothingCanBeAdded | UseCaseJourneysTests#testAnUnreadableConfigIsKeptAndSurfacedNotReadAsAFirstRun, UseCaseJourneysTests#testADuplicateConnectionIsMergedAndAnnounced, UseCaseJourneysTests#testOfflineTheWhereSheetSaysWhyNothingCanBeAdded |
| C10 | The phone's model reads warm while loaded and cold after, like a home server | device | OnDeviceThinkingJourneyTest#thePhonesModelReadsWarmOnceItHasAnsweredAndColdAfterTheAppWasAway | OnDeviceUseCaseTests#testThePhonesModelReadsWarmOnceItHasAnswered, OnDeviceUseCaseTests#testThePhonesModelReadsColdAfterTheAppWasAway |

## D. Web search

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| D1 | Search on: tool round → Brave → reply with a sources chip; the sheet lists the source | sim | GroundedTurnTest#aGroundedTurnCarriesItsSource | ToolCallLiveUITests#testNearAIGLM52CallsWebSearchAndShowsSources (live) |
| D2 | A preamble then a slow second round: the trailing activity chip covers the gap | sim | GroundedTurnTest#theActivityChipCoversTheSecondRoundAfterAPreamble | ActivityChipUITests#testTheChipCoversTheGapAfterAToolRound (live) |
| D3 | No key: the model calls search → the offer card → paste key → the question is re-asked and answered | sim | UserJourneysTest#anOfferedSearchKeyIsCheckedAndTheQuestionIsAskedAgain | ProductE2ETests#testScriptedToolRoundFollowsUpWithoutLeakingMarkup, UseCaseJourneysTests#testAnOfferedSearchKeyIsCheckedAndTheQuestionIsAskedAgain |
| D4 | Offer dismissed: the card goes, and that thread is not offered again | device | OnDeviceThinkingJourneyTest#aDismissedSearchOfferIsNotOfferedAgainInThatThread | WebOfferCaptureUITests#testOfferSurvivesLaterTurnsThenDeclines |
| D5 | Web chip: without a key opens search settings; with one toggles, and the next turn does (not) search | sim | ChatControlsContractTest#theWebChipWithoutAKeyOpensWebSearchSettings, ChatControlsContractTest#theWebChipWithAKeyTogglesInPlace, UserJourneysTest#theWebChipWithAKeyTogglesWhetherTheNextTurnSearches | WebOfferCaptureUITests#testSetItUpReachesSearchSettings, UseCaseJourneysTests#testTheWebChipWithAKeyTogglesWhetherTheNextTurnSearches |
| D6 | Search settings: paste a key → validated / rejected; a provider with built-in search says so | sim | UserJourneysTest#searchSettingsChecksTheKeyWithBrave, UserJourneysTest#searchSettingsSaysWhenTheProviderSearchesItself | UseCaseJourneysTests#testSearchSettingsChecksTheKeyWithBrave, UseCaseJourneysTests#testSearchSettingsSaysWhenTheProviderSearchesItself |
| D7 | Search per provider, live: each cloud's model calls web_search and the reply carries sources | live | LiveCloudTurnsTest#grokSearchesAndTheReplyCarriesSources, LiveCloudTurnsTest#fireworksSearchesAndTheReplyCarriesSources, LiveCloudTurnsTest#openRouterSearchesAndTheReplyCarriesSources, LiveCloudTurnsTest#ollamaSearchesAndTheReplyCarriesSources | ToolCallLiveUITests#testGrokBuildCallsWebSearchAndShowsSources, ToolCallLiveUITests#testFireworksGptOss20BCallsWebSearchAndShowsSources, ToolCallLiveUITests#testOllamaGemma4CallsWebSearchAndShowsSources |

## E. Trust and sending safely

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| E1 | Degraded: the first send asks; cancel keeps the draft; send anyway sends | sim | UserJourneysTest#theSendGateAsksWhenAttestationIsDegraded, UserJourneysTest#theDegradedGatesSendAnywaySends | UseCaseJourneysTests#testTheDegradedGateCancelKeepsTheDraftAndTheBypassSends |
| E2 | Hard failure: the send is blocked, no bypass; "retry verification" is offered | sim | UserJourneysTest#aHardFailureBlocksTheSend | ProductE2ETests#testHardFailureBlocksSend, ProductE2ETests#testHardFailureBlocksSendWithDebugPanelOn |
| E3 | E2EE unavailable: the refusal shows as an error with retry, nothing leaves the phone | sim | UserJourneysTest#anUnavailableE2EEKeyRefusesTheSendAndSaysNothingWasSent | ProductE2ETests#testE2EEUnavailableRefusalShowsRetryAffordance |
| E4 | Mismatch: the caption says a reply didn't check out, sending still works, the sheet explains | sim | UserJourneysTest#aMismatchIsSaidAndSendingStillWorks, UserJourneysTest#theMismatchCaptionOpensTheSheetThatExplainsIt | ProductE2ETests#testMismatchCaptionDoesNotBlockSend, ProductE2ETests#testReplyDidntCheckOutOpensEverydaySheet |
| E5 | The title opens the ladder in every state; everyday ↔ expert; both scroll to their last line | sim | ChatControlsContractTest#theTitleBlockOpensTheTrustLadder, UserJourneysTest#theTitleOpensTheLadderInEveryStateAndEachRungScrollsToItsEnd | ProductE2ETests#testEverydaySheetOpensFromTitleBlock, UseCaseJourneysTests#testTheTitleOpensTheLadderInEveryStateAndEachRungScrollsToItsEnd |
| E6 | Verified near.ai, live: the sheet names the model, shows the reviews and the gateway card | live | LiveCloudTurnsTest#theNearAISheetNamesTheModelAndShowsTheReviews | ProductLiveE2ETests#testLiveAttestationSheetNamesTheModel |
| E7 | Switch near.ai model: the sheet never shows the previous model's attestation | live | LiveCloudTurnsTest#switchingTheNearAIModelNeverShowsTheOldAttestation | ModelSwitchAttestationUITests#testSwitchingModelNeverShowsOldAttestation |
| E8 | Not-encrypted cloud model: the sheet offers a near.ai model; no near.ai key opens the key form; adding the key flips the button in place | sim | UserJourneysTest#theEncryptedModelOfferBecomesASwitchOnceANearAIKeyIsAdded | ProductE2ETests#testAddingAKeyFlipsTheEncryptedModelCTAWithoutReopeningTheSheet |
| E9 | Self-hosted and on-device sheets: "on your own machine" / "nothing leaves this phone", no rung picker | sim | UserJourneysTest#aHomeServersSheetSaysOnYourOwnMachineWithNoRungs, OnDeviceThinkingJourneyTest#thePhonesModelSheetSaysNothingLeavesThisPhone (phone) | LocalProviderUITests#testTrustSheetShowsTheSelfHostedViewAndNoRungPicker |
| E10 | Re-verify from the sheet refreshes the stamp; copy script puts the script on the clipboard | live | LiveCloudTurnsTest#reverifyRefreshesTheStampAndCopyPutsTheScriptOnTheClipboard | ProductLiveE2ETests#testReverifyRefreshesTheStampAndCopyPutsTheScriptOnTheClipboard |
| E11 | Two live near.ai turns: both replies' signatures check out, the debug card shows the sealed body | live | LiveCloudTurnsTest#twoLiveNearAITurnsCheckOutAndStampE2EE | ProductLiveE2ETests#testLiveTwoTurnReplySignatureChecksOut, ProductLiveE2ETests#testLiveTwoTurnWithDebugPanelShowsE2EE |
| E12 | Each near.ai model's expert sheet shows its own enclave image and engine | live | LiveCloudTurnsTest#eachNearAIModelsSheetShowsItsOwnEnclave | AttestationCompletenessUITests#testExpertSurface, ModelSwitchAttestationUITests#testGlm53FlashSheetNamesOnlyGlm, ModelSwitchAttestationUITests#testQwen36ShowsSglangNotVllm, ModelSwitchAttestationUITests#testQwen38SheetShowsItsOwnImage |

## F. Chats

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| F1 | A turn persists: new chat (composer cleared) → the list brings it back with its reply | sim | UserJourneysTest#aChatPersistsReopensAndCanBeDeleted | UseCaseJourneysTests#testAChatPersistsAndTheListBringsItBack |
| F2 | Delete by swipe and by long-press; deleting the open chat leaves a fresh one | sim | UserJourneysTest#aChatPersistsReopensAndCanBeDeleted, UserJourneysTest#deletingTheOpenChatLeavesAFreshOne | ProductE2ETests#testDeleteThreadFromList |
| F3 | Search: a word → the hit → tap → the thread opens; a word nowhere says "no results" | sim | UserJourneysTest#searchingTheChatsOpensTheThreadWithTheHit | UseCaseJourneysTests#testSearchingTheChatsOpensTheThreadWithTheHit |
| F4 | Delete all chats from settings: confirm → the list is empty and the open chat is a fresh one | sim | UserJourneysTest#deletingAllChatsFromSettingsEmptiesTheList | UseCaseJourneysTests#testDeletingAllChatsEmptiesTheListAndTheOpenChat |
| F5 | A long thread opens on its newest message; dragging up reaches the first | sim | UserJourneysTest#aLongThreadOpensOnItsNewestMessage | LongThreadScrollUITests#testLongThreadOpensOnTheNewestMessage, LongThreadScrollUITests#testDraggingUpReachesTheFirstMessage |

## G. Phone models

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| G1 | Download a phone model from Where: progress, done → "use" → it is selected | sim | UserJourneysTest#aPhoneModelDownloadFinishesAndIsSelected, UserJourneysTest#aSecondPhoneModelDownloadIsSelectedAndTheFirstKeepsItsPlace | DownloadLifecycleUITests#testStartDownloadAndKill (live), DownloadLifecycleUITests#testAPhoneModelDownloadFinishesAndIsTheChatsModel (fake_hub.py) |
| G2 | The download survives backgrounding and a process kill, and resumes | sim | UserJourneysTest#aDownloadKeepsGoingWhileTheAppIsAway, DownloadKillResumeTest#aDownloadKilledMidwayResumesFromItsPartial (tools/download_kill_resume.sh) | DownloadLifecycleUITests#testDownloadSurvivesBackgroundKillAndResumes (live) |
| G3 | On mobile data: the prompt asks; "wait for wi-fi" parks it; wi-fi resumes it | sim | UserJourneysTest#onMobileDataADownloadAsksAndWaitingForWifiParksIt | DownloadLifecycleUITests#testMobileDataPromptAndWaitingForWifi, DownloadLifecycleUITests#testWifiOffParksAndWifiOnResumes |
| G4 | A checksum failure is shown on the row, and retry starts over | sim | UserJourneysTest#aDownloadThatFailsItsChecksumSaysSoAndRetryStartsOver | DownloadLifecycleUITests#testIntegrityFailureIsShownOnTheRow |
| G5 | Delete a model's weights (confirm) → the row offers the download again | sim | UserJourneysTest#deletingAModelsWeightsOffersTheDownloadAgain | UseCaseJourneysTests#testDeletingAModelsWeightsOffersTheDownloadAgain |
| G6 | Chat on the phone model: a plain question answers, a follow-up answers too, no markup on screen | device | OnDeviceThinkingJourneyTest#aThinkingReplyNeverShowsItsMarkupAndEndsWithAnAnswer | OnDeviceInferenceUITests#testPlainChatAnswersWithoutSearching, OnDeviceInferenceUITests#testAFollowUpTurnAnswersInsteadOfEmittingOneToken |
| G7 | On the phone model with search: a time-sensitive question searches and carries its source; a plain one does not | device | OnDeviceThinkingJourneyTest#aSearchOnTheDeviceCarriesItsSourceAndShowsNoToolSyntax, OnDeviceThinkingJourneyTest#aPlainQuestionOnThePhoneDoesNotSearch | OnDeviceInferenceUITests#testAGroundedQuestionActuallyCallsTheSearchTool, OnDeviceInferenceUITests#testKeylessPlainPromptRaisesNoOffer |
| G8 | Stop mid-thought on the phone model: the turn ends, the next one answers | device | OnDeviceThinkingJourneyTest#stoppingMidThoughtEndsTheTurnAndTheNextOneAnswers | OnDeviceUseCaseTests#testStoppingMidThoughtEndsTheTurnAndTheNextOneAnswers |
| G9 | No key, phone model: a time-sensitive question raises the search offer | device | OnDeviceThinkingJourneyTest#aKeylessTimeSensitiveQuestionRaisesTheSearchOffer | OnDeviceInferenceUITests#testKeylessTimeSensitiveQuestionRaisesTheOffer |

## H. Settings

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| H1 | Appearance: tint, font, size and width → the app reflects it, and it survives a relaunch | sim | UserJourneysTest#appearanceChoicesStickAcrossARelaunch, UserJourneysTest#everyAppearanceChoiceSurvivesARelaunch | ProductE2ETests#testAppearanceChangeSticksInSession, UseCaseJourneysTests#testAppearanceChoicesSurviveARelaunch |
| H2 | Developer mode on → the debug card appears under replies and expands; off → gone | sim | ChatControlsContractTest#theDebugCardStandsUnderTheReplyWithNoBandBeneathIt, UserJourneysTest#developerModeShowsAndHidesTheDebugCard | ProductE2ETests#testDebugCardSectionExpands, DeveloperModeLocalUITests#testDebugCardRendersAndScrollsIntoViewForALocalModel |
| H3 | Places & keys: the hub lists computers and cloud keys; add a cloud key from it; delete a place (asks; keep leaves it; delete removes it) | sim | PlacesKeysContractTest#aTrailingSwipeOnAPlaceRowAsksBeforeDeleting, UserJourneysTest#placesAndKeysAddsACloudKeyToItsList, UserJourneysTest#aPlaceIsRenamedAndDeletedFromPlacesAndKeys | UseCaseJourneysTests#testPlacesAndKeysAddsACloudKeyToItsList, UseCaseJourneysTests#testAPlaceIsRenamedAndDeletedFromPlacesAndKeys |
| H4 | Credits and every outbound link open the browser at the right host | sim | UserJourneysTest#outboundLinksOpenTheBrowserAtTheRightHost | UseCaseJourneysTests#testACreditOpensTheBrowserAtItsHost, UseCaseJourneysTests#testEveryPresetsKeyLinkOpensTheBrowserAtItsVendor |
| H5 | Settings opens, lists its sections, and dismisses back to the chat | sim | ChatControlsContractTest#theGearOpensSettings | ProductE2ETests#testSettingsRoundTrip |

## I. The app as a phone app

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| I1 | Send, then leave the app: the turn finishes in the background and posts "your answer is ready" | device | OnDeviceThinkingJourneyTest#aTurnThatGoesToTheBackgroundFinishesWhileAway, OnDeviceThinkingJourneyTest#aTurnFinishedWhileAwayPostsItsNotification | OnDeviceInferenceUITests#testBackgroundingMidAnswerAbandonsAndResumes |
| I2 | Tap the notification → the app opens on that thread at the reply | sim | UserJourneysTest#tappingAReplyNotificationOpensItsThread | n/a: iOS does not post a reply notification |
| I3 | First send asks for notification permission once; denying still finishes the turn | device | NotificationPermissionJourneyTest#denyingNotificationsStillFinishesTheTurn | n/a: iOS does not post a reply notification |
| I4 | Process death on every route: the app comes back on the same screen | sim | UserJourneysTest#aRecreatedActivityComesBackOnTheSameScreen, RouteRestoreTest#everyRouteComesBackAfterTheActivityIsRebuilt | n/a: iOS keeps its scene through rotation and relaunches fresh; it has no route restoration to test |
| I5 | Rotation mid-stream: the stream continues, the transcript keeps its place | sim | UserJourneysTest#rotatingMidStreamKeepsTheStream | UseCaseJourneysTests#testRotatingMidStreamKeepsTheStream |
| I6 | Back gesture from every sheet returns to its parent, and from the chat to the list | sim | UserJourneysTest#systemBackPopsEachScreenToItsParent | n/a: no system back on iOS |
| I7 | Screen reader: every control on every route has a label; nothing behind a sheet is reachable | sim | AccessibilityCrawlTest#everyControlSaysWhatItIsAndNothingBehindASheetIsReachable | UseCaseJourneysTests#testTheMainScreensPassTheAccessibilityAudit |
| I8 | An update from the store installs over the old version and keeps every chat, key and model | device | UpgradeSurvivalTest#everythingTheOldBuildWroteSurvivesTheUpdate | UpgradeSurvivalUITests#testSeedTheOldBuild, UpgradeSurvivalUITests#testEverythingTheOldBuildWroteSurvivesTheUpdate |

## K. The transcript follows

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| K1 | A send glides the prompt up and anchors it near the top; it stays put while a short reply grows | sim | FollowContractTest#sendingAnchorsThePromptNearTheTop, FollowContractTest#sendingGlidesThePromptUpRatherThanJumping | LongThreadStreamingUITests#testSendingAnchorsThePromptNearTheTop |
| K2 | A long reply streams pinned to the bottom and settles without bouncing | sim | FollowContractTest#streamingAtTheEndOfALongThreadStaysPinnedToTheBottom | LongThreadStreamingUITests#testStreamingAtTheEndOfALongThreadStaysPinnedToTheBottom |
| K3 | Scrolling away mid-answer stops the follow, and the reader is not moved when the turn ends | sim | FollowContractTest#scrollingAwayMidAnswerStopsTheFollow, FollowContractTest#scrollingAwaySurvivesTheEndOfTheTurn | LongThreadStreamingUITests#testScrollingAwayMidAnswerStopsTheFollow, LongThreadStreamingUITests#testScrollingAwaySurvivesTheEndOfTheTurn |
| K4 | A tap or a small drag mid-answer does not stop the follow | sim | FollowContractTest#aTapMidAnswerDoesNotStopTheFollow, FollowContractTest#aSmallDragUnderTheThresholdDoesNotStopTheFollow | LongThreadStreamingUITests#testATapMidAnswerDoesNotStopTheFollow, LongThreadStreamingUITests#testASmallDragUnderTheThresholdDoesNotStopTheFollow |
| K5 | The next send re-arms the follow after a scroll-away | sim | FollowContractTest#theFollowReArmsOnTheNextSend | LongThreadStreamingUITests#testTheFollowReArmsOnTheNextSend |
| K6 | Raising the keyboard mid-answer keeps the follow | sim | FollowContractTest#raisingTheKeyboardMidAnswerKeepsTheFollow | LongThreadStreamingUITests#testRaisingTheKeyboardMidAnswerKeepsTheFollow |
| K7 | Scrolling up mid-stream or mid-thought leaves the app responsive, and the transcript never goes blank | sim | ScreenInvariantsTest#aStreamingReplyPaintsOnlyAtItsTail, UserJourneysTest#scrollingUpMidStreamLeavesTheAppResponsiveAndTheTranscriptDrawn | ScrollUpMidStreamFreezeUITests#testScrollingUpMidStreamLeavesTheMainThreadAlive, ScrollUpMidStreamFreezeUITests#testScrollingUpDuringThinkingLeavesTheMainThreadAlive, StreamingBlankUITests#testTranscriptNeverGoesBlankDuringGeneration |
| K8 | A mid-stream tool call does not break the follow; the last paragraph lands in view | sim | FollowContractTest#theFollowSurvivesAMidStreamToolCall | ToolRoundFollowUITests#testTheFollowSurvivesAMidStreamToolCall |

## L. Live providers

| ID | Use case | Runs | Android | iOS |
|---|---|---|---|---|
| L1 | One real turn per cloud provider settles without an error | live | LiveCloudTurnsTest#anOpenRouterTurnAnswers, LiveCloudTurnsTest#anNvidiaTurnAnswers | ProviderLiveUITests#testNearAIGemma4SendsFromChat, ProviderLiveUITests#testFireworksGptOss20BSendsFromChat, ProviderLiveUITests#testGrokBuildSendsFromChat, ProviderLiveUITests#testBraveAnswersSendsFromChat |
| L2 | A real home server (Ollama over the network) answers a turn | live | LiveCloudTurnsTest#aRealHomeServerAnswersATurn | ProviderLiveUITests#testOllamaRingzeroSendsFromChat |
| L3 | OpenRouter's model card lists who can serve a model | live | LiveCloudTurnsTest#theOpenRouterCardListsWhoCanServeAModel | n/a: OpenRouter is Android-ahead |

## Not use cases

These stay, and they are not counted above:
- **Captures** (screenshots, traces, benchmarks): iOS has 65 of them, including `MarketingCapture`, `MacBaselineCapture`,
  the trace drivers and `OnDeviceDecodeBench`. On Android these are `ParityCaptureTest`, `RouteCrawlTest` and `OnDeviceDecodeBenchTest`.
- **Contracts below the journey**: one widget or one rule, pinned precisely. Examples are `ScreenInvariantsTest`,
  `MainThreadNetworkTest`, `CleartextGateDeviceTest`, `DcapQvlDeviceTest` and iOS's `StreamingLag`. A contract that
  already walks a whole story is cited in its row above.
- **Below the device**: the JVM suites and the Paparazzi snapshots. These cover layout details such as the
  places & keys alignment, the toolbar tint and the push direction, and they run on every build.
