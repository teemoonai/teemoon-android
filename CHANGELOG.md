# Changelog

Every public release of teemoon for Android, newest first. Each GitHub release is the source of the Google Play build with the same number, rebuilt from its tag on a clean GitHub Actions runner; the reproducibility line says whether that rebuild matched the published APK. Full detail for 1.1.0 is in [docs/releases/1.1.0.md](docs/releases/1.1.0.md).

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

- Found after release (audit MEDIUM): only the first listed near.ai enclave is verified, but every enclave near.ai's key service admits under the app id holds the same key; a fix for listed enclaves is planned for 1.1.1.
- Accepted for this release: a near.ai model used sealed can turn proxied on a changed model list with no alert; a fix is planned for 1.1.1.
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
