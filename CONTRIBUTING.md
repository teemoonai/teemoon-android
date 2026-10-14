# contributing

## building and testing

See [`README.md`](README.md#building-and-testing) — the JDK and
SDK requirements, the offline JVM suite, the snapshot suite and the device suites all live
there rather than being repeated here. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) is the
map of the code: read it before grepping.

## the CLA

teemoon is AGPL-3.0 and is also distributed through Google Play by the copyright holder,
ringzero ventures llc. Play's terms and the AGPL can only coexist for a distribution
ringzero itself controls the copyright of — a pull request that added someone else's
copyrighted code without an agreement in place would break that. The CLA is what lets
outside contributions in without giving that up: it assigns or licenses your contribution
so ringzero can keep distributing the whole work, Play included, under both.

You don't have to sign anything up front. Open your first pull request and cla-assistant
will comment with the agreement and a link to sign it electronically — takes a minute, and
it's remembered for every PR after that.

## opening a pull request

- **Bug fixes need a regression test.** A fix without a test that would have caught the bug
  will get sent back for one. If the bug is in ported logic, it is probably an iOS bug too:
  file it in [teemoon-ios](https://github.com/teemoonai/teemoon-ios) as well.
- **Keep claims accurate.** A comment, doc, or UI string that says the code verifies or
  checks more than it actually does is treated as a bug here, same severity as a functional
  one — this matters most on the attestation and E2EE paths, but it applies everywhere.
- **Keep the unit suite offline** (no keys, no network). `./gradlew test` is the loop and it
  has to stay green; Kotlin warnings are errors.
- **Large or speculative changes: open an issue first.** It's a faster path than a PR that
  has to be re-architected in review.

## house rules

These are the rules the code is held to. Each one is the receipt of a bug.

**This is a port, and the Swift is normative.** Every ported file names its Swift source
and the commit it was ported from in a `// ports:` header, and the same pair lives in
[`ports.json`](ports.json). Move both together (`tools/bump_port_sha.py`). Carry the
rationale comments — the measured, load-bearing ones that justify a behavior — wherever
that behavior lands; a port that drops them re-buys the bug. Tests move with code. The
conformance kit under `conformance/` is vendored from teemoon-ios and is not editable here:
when a fixture and an implementation disagree, the fixture wins.

**Protect the user's messages.** Chat history is Room. `fallbackToDestructiveMigration()`
is forbidden — it is the line that makes a failing schema change compile and pass, and it
deletes every thread to do it. Adding a nullable column or a new entity is fine. Removing
or renaming a column, changing a type or a key needs an explicit `Migration` with a
`MigrationTestHelper` test. A store migration failure must crash, never wipe.

**Fail loud.** A decrypt failure kills the stream and never passes ciphertext through.
Config corruption quarantines and surfaces. Silent fallback is a bug class here, not a
convenience.

**Trust copy is data.** The UI never composes trust language; it renders `TrustVerdict` and
`AttestationSummary` values. A new claim means a new field on those types, with tests,
first. Every user-visible trust string must trace to a code path that establishes it.

**Dependencies.** New third-party dependencies need a justification in the PR. The current
set is OkHttp, kotlinx.serialization, Room and the rest of AndroidX/Compose, BouncyCastle,
kotlinx.coroutines, secp256k1-kmp, dcap-qvl-android, litertlm-android, Coil, the markdown
renderer and Paparazzi (test), plus the vendored LiteRT and QNN runtimes
(`ondevice/src/main/jniLibs`). Forbidden: Firebase, any analytics or crash-reporting SDK, Play Services
beyond In-App Review, Retrofit, okhttp-sse. "No analytics" is a product claim and this
list is its enforcement. **No new network destinations:** the hosts this app talks to are
the provider presets, the attestation services, the model downloads and Brave web search. Adding one is a
product decision, not an implementation detail.

**UI work.** Write the composable and its state holder; state-holder tests always, a
Paparazzi snapshot for anything with layout risk (`./gradlew recordPaparazziDebug` and
commit the baseline with the change). Strings lowercase, tint semantic, 8pt grid,
dark-first. Debug surfaces sit behind the same developer-mode setting as iOS. Build on
`app/Theme.kt` and `app/DesignKit.kt`; anything done the Android way rather than the iOS
way is listed in [`docs/ANDROID_NATIVE.md`](docs/ANDROID_NATIVE.md) — add to it when you
add one. When a screen changes on purpose, re-record the journey goldens on each device the
journeys run on (`tools/journey_goldens.sh record`).

## reporting a security issue

Don't file it as an issue or a PR. See [`SECURITY.md`](SECURITY.md) for the private
reporting channel and what's in scope.
