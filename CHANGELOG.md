# Changelog

Every public release of teemoon for Android, newest first. Each GitHub release is the source of the Google Play build with the same number, rebuilt from its tag on a clean GitHub Actions runner; the reproducibility line says whether that rebuild matched the published APK. Full detail for 1.1.1 is in [docs/releases/1.1.1.md](docs/releases/1.1.1.md), and for 1.1.0 in [docs/releases/1.1.0.md](docs/releases/1.1.0.md).

## 1.1.1 (3919) — 2026-11-21

### Changed

- The trust screens say who holds the key: "near.ai's key service decides which secure enclaves get that key". They also say what teemoon found for the enclaves near.ai listed: checked, couldn't load the list, checking, couldn't finish, or one failed.
- Lines that claimed more than the app checks are gone: no trust string says only the model can read the chat, or that near.ai's gateway can't decrypt it.
- "send unencrypted" is now "send without encryption".
- A sent question glides to the top of the chat over 0.4 s (was 0.25 s).
- List prices for your own OpenAI, Google, DeepSeek and Mistral keys are refreshed (snapshot 2026-11-19).
- The release workflows run `tools/check_store.py` from the tag's own tree again, not from `main`.

### Security

- Every model enclave near.ai lists for a model holds the key a sealed message goes to, and each is now checked before a message is sealed to it: its quote with Intel (DCAP: the quote's chain, collateral and TCB status), its GPU evidence with NVIDIA, its compose manager's action log and its boot log. A listed enclave that fails blocks the send; one that couldn't be checked asks first. The trust screen's "reviewed" row now covers what every listed enclave runs. This fixes the 1.1.0 MEDIUM for the enclaves near.ai lists.
- A quote's attestation key must be Intel's TD Quoting Enclave's: the QE report is signed by the PCK leaf, its report_data is sha256 of the key and its auth data, and the TD QE identity matches, MISCSELECT included.
- The "reviewed" row is the worst across every listed enclave, each scoped by everything its host runs.
- A held send names its reason: a listed enclave that failed its check, one that holds a different key, or listed enclaves that name different key services.
- near.ai's list of enclaves is capped at 4 MB and 8 entries, and its request is cancelled at its time limit.
- A near.ai model you've sent to sealed asks once before its first plain send (the 1.1.0 audit's accepted LOW).
- A model key with no readable quote blocks the send; it used to read "still checking" and offer to send anyway.
- Each listed-enclave check is pinned by a test that fails when the check is broken (mutation-tested).

### Moved to 1.1.2

- The 1.1.0 audit's remaining INFO items: the share and paste URI gate, a size cap on the import staging copy, bounds on the isolated decoder's reply, an allow-list for unsealed request fields, validation of action-log strings before they go into a URL, log hygiene, the credits list, and screenshot seams.
- Explicit backup excludes for the list of models sent to sealed (backup is already off for the whole app).
- A fail-safe for that list when the app can't read it: in 1.1.1 an unreadable list counts as empty, so the ask above doesn't come.
- A TLS pin for near.ai's gateway.

### Known issues

- near.ai's key service decides which enclaves get a model's key. teemoon checks the enclaves near.ai lists, not an enclave the key service admits that near.ai doesn't list; near.ai has to fix that (audit MEDIUM, narrowed).
- near.ai's quotes are not bound to the caller.
- The TLS connection to near.ai's gateway is not bound to an attested enclave.
- Re-verifying `v1.1.0-3918` needs the release workflows as they were on `main` at `35766b6`, because that tag's tree has no `tools/check_store.py`; its verified verdict stands.
- xAI retired `grok-build-0.1`. A Grok place still set to it fails every send with "Grok endpoint not found (http 404). check the server address in settings → places & keys."; pick another Grok model. A clearer message is planned for 1.1.2.
- R8 shrinking is still off, which draws Play's obfuscation notice; to be fixed before February 2027.

[`v1.1.1-3919`](https://github.com/teemoonai/teemoon-android/tree/v1.1.1-3919) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.1.1-3919) · [audit](https://github.com/teemoonai/audits/tree/main/client/teemoon-android) · [full notes](docs/releases/1.1.1.md) · reproducible: the verdict is on the [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.1.1-3919).

## 1.1.0 (3918) — 2026-11-18

### Added

- Images in chats: take a photo, choose photos, paste, or share them in; up to 10 per message, resized on the phone with location and other metadata removed.
- Every image is decoded in an isolated process, so the original image bytes never meet a parser in the main process; the main process decodes only the re-encoded copy.
- Phone models (Gemma 4 E2B and E4B) read images when the phone has the memory for it.
- Import from near.ai Private Chat, read on the phone with no network request; chat search also matches titles.
- Reply markers say how each reply ended: didn't finish, stopped, or cut short.

### Changed

- The composer is one panel, with "+", the where chip, the web chip and send in one row.

### Fixed

- Every attached image reaches the phone model, and stopping, retrying or deleting a chat mid-answer no longer writes to the wrong turn.

### Security

- The 1.0.6 audit's MEDIUM and two display LOWs are fixed; nothing leaves while the chip reads verifying.

### Known issues

- Found after release (audit MEDIUM): only the first listed near.ai enclave is verified, but every enclave near.ai's key service admits under the app id holds the same key. Fixed in 1.1.1 for the enclaves near.ai lists; an enclave the key service admits that near.ai doesn't list is still not checked.
- Accepted for this release: a near.ai model used sealed can turn proxied on a changed model list with no alert. Fixed in 1.1.1: it asks once before the first plain send.
- R8 shrinking is off, which draws Play's obfuscation notice; to be fixed before February 2027.

[`v1.1.0-3918`](https://github.com/teemoonai/teemoon-android/tree/v1.1.0-3918) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.1.0-3918) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.1.0-3918-ee2c8b3.md) (QUALIFIED-PASS) · [full notes](docs/releases/1.1.0.md) · reproducible: **verified**, 198 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/44283096289)).

## 1.0.6 (3917) — 2026-10-23

### Changed

- Reasoning defaults to low, for up to 11× faster first token (near.ai GLM 5.3 Flash 14.6 s → 1.3 s, Fireworks 5×); long-press a reply and tap think harder to re-ask at high.
- Prompt caching fixed: Fireworks GLM 5.3 Flash reused about 0% of earlier input, now up to 90% per message.
- NVIDIA's default model is Nemotron 3.5 Lightning; a setup on the retired Nemotron 3 Super moves to it and says so once.

### Security

- near.ai reply signatures are checked against a key bound in a DCAP-verified model quote.
- NVIDIA GPU attestation tokens and the TLS probe's quote are verified too.

### Fixed

- Deleted chats stay deleted.

### Known issues

- MEDIUM (audit addendum 2026-11-06): an unpublished image beside a recipe-hash mismatch sent unasked. Fixed in 1.1.0.
- Two display LOWs (same addendum). Fixed in 1.1.0.
- MEDIUM (audit addendum 2026-11-19): only the first listed near.ai enclave is verified, and the listed enclaves share one key. Open in 1.1.0.
- LOW first recorded by the 1.1.0 audit, present here: a model used sealed can turn proxied with no alert.

[`v1.0.6-3917`](https://github.com/teemoonai/teemoon-android/tree/v1.0.6-3917) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.0.6-3917) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.0.6-3917-63cef55.md) · reproducible: **verified**, 194 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/39933128464)).

## 1.0.5 (3916) — 2026-10-15

### Changed

- A Fireworks setup saved on Kimi K2.6, which Fireworks retired, moves to Kimi K3 at launch and shows the new price once.

[`v1.0.5-3916`](https://github.com/teemoonai/teemoon-android/tree/v1.0.5-3916) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.0.5-3916) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.0.5-3916-34e5566.md) · reproducible: **verified**, 193 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/38820724755)).

## 1.0.5 (3915) — 2026-10-15

### Added

- Developer mode shows on-device speed for every on-device reply: decode, prefill and the backend.

### Changed

- Fireworks starts on Kimi K3; OpenRouter list prices refreshed.

[`v1.0.5-3915`](https://github.com/teemoonai/teemoon-android/tree/v1.0.5-3915) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.0.5-3915) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.0.5-3915-d8e6854.md) · reproducible: **verified**, 193 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/38806489873)).

## 1.0.4 (3913) — 2026-10-15

### Changed

- On-device engine updated to LiteRT-LM 0.18, with LiteRT 2.3 for the Pixel and Snapdragon NPU libraries; no UI, copy, network or data changes.

[`v1.0.4-3913`](https://github.com/teemoonai/teemoon-android/tree/v1.0.4-3913) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.0.4-3913) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.0.4-3913-317b040.md) · reproducible: **verified**, 193 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/38757406278)).

## 1.0.4 (3912) — 2026-10-14

### Added

- Snapdragon 8 Elite NPU support through LiteRT 2.2.0's Qualcomm dispatcher and QNN 2.50.0, with speculative decoding.
- The Pixel 11 (Tensor G6) NPU is used, through the `android.hardware.npu` manifest declaration.
- Notification channels; a notification never shows reply text.

### Security

- Copied keys expire from the clipboard.

[`v1.0.4-3912`](https://github.com/teemoonai/teemoon-android/tree/v1.0.4-3912) · [GitHub release](https://github.com/teemoonai/teemoon-android/releases/tag/v1.0.4-3912) · [audit](https://github.com/teemoonai/audits/blob/main/client/teemoon-android/v1.0.4-3912-7f504ca.md) · reproducible: **verified**, 193 entries matched, signature set aside ([run](https://github.com/teemoonai/teemoon-android/actions/runs/38652795965)).
