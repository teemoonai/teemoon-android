# User journeys

**The list moved to `docs/USE_CASES.md` (2026-10-04)**, which covers iOS and Android in one table: each use case,
where it runs (sim / device / live), and the test that walks it on each platform. `tools/use_case_coverage.py
--ios ../teemoon-ios` checks the names and prints the gaps. This file keeps only the notes below.

A **journey** is the acceptance test of one user story, run on a device: it launches the real app, drives it by its
UI as a user would, and asserts the OUTCOME (a reply arrived, the thread is gone, the sheet names the model), with
step goldens at the screens a user would notice changing. It is not a crawl and not a contract.

## Removed or not journeys

- `SmokeTest` — removed: its one live assertion, thread search, is F3 (`searchingTheChatsOpensTheThreadWithTheHit`).
- `RouteCrawlTest`, `ParityCaptureTest` — stay as captures, not journeys. The crawl does not yet reach the `download:`
  and `edit:` routes.
- `FollowContractTest`, `ScreenInvariantsTest`, `ChatControlsContractTest`, `WhereSheetContractTest`,
  `PlacesKeysContractTest` — stay as contracts (they pin one behaviour precisely). Where a contract already walks a
  story end to end (B2, B5, C4, H3) it is promoted into the journey suite rather than duplicated.
- iOS captures, Mac-only tests, benchmarks and live probes stay waived (`ports.json` `testWaivers`).

## Totals

`tools/use_case_coverage.py` counts them from `docs/USE_CASES.md`. The offline journeys run in `tools/journey_goldens.sh`
on an emulator and a phone; `live` in `tools/live_cloud_suite.sh`; `device` on a phone.
