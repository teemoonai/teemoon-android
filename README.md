# teemoon for Android

**private AI chat, your key.**

teemoon is a private AI chat app with no teemoon server: your phone talks directly to the model you pick — on the
phone, on a computer you own, or in a cloud with your own key. no account, no analytics; keys live in the Android
keystore and chats stay on the device.

this is the Android client, a direct port of [teemoon-ios](https://github.com/teemoonai/teemoon-ios): the same code
paths, screens and checks. where Android does something its own way, [`docs/ANDROID_NATIVE.md`](docs/ANDROID_NATIVE.md)
says so. [teemoon.ai](https://teemoon.ai) · Android 10+, 64-bit.

## install

**[get it on google play](https://play.google.com/store/apps/details?id=ai.teemoon).**

**without google play** (e.g. GrapheneOS): download the apk from
[releases](https://github.com/teemoonai/teemoon-android/releases/latest) or its mirror on [teemoon.ai](https://teemoon.ai).
it's signed with the Play build's key:

```
8A:20:9E:D1:B6:94:AA:B9:B4:8E:1F:C2:10:C0:79:FB:12:44:C1:63:D5:8D:BB:9C:50:58:A8:E0:64:B7:4C:A8
```

updates through [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/teemoonai/teemoon-android).
each release is rebuilt from its tag and compared with the apk: [`docs/REPRODUCIBLE.md`](docs/REPRODUCIBLE.md).

## features

- **one app, three places to run a model.** on the phone, on a computer you own, or in the cloud — end-to-end
  encrypted to near.ai's attested hardware.
- **on the phone.** Gemma 4 via LiteRT-LM, offline, on the GPU or the NPU, with speculative decoding.
- **on your own computer.** ollama, LM Studio, llama.cpp or any OpenAI-compatible server on your network. pull
  models onto your ollama server from the app.
- **in the cloud.** any OpenAI-compatible endpoint with presets for near.ai, Grok, Fireworks, OpenRouter, NVIDIA or
  Brave Answers. live model lists with prices and per-model capabilities.
- **verified end-to-end encryption.** near.ai's enclaves are checked on the phone, not trusted from the server: the
  Intel TDX quote via DCAP, the GPU's evidence via NVIDIA, the certificate bound to the attestation. a failed check
  blocks sending; what is and isn't proven: [`ATTESTATION.md`](https://github.com/teemoonai/teemoon-ios/blob/main/ATTESTATION.md).
- **audited clients.** Android and iOS source reviewed with claude fable for any way the app could leak your keys or
  your plaintext, published in [teemoonai/audits](https://github.com/teemoonai/audits/tree/main/client) and keyed to
  the release commit.
- **audited models.** near.ai's confidential path is reviewed the same way, taking advantage of TEE (trusted
  execution environment) manifests to crawl near.ai backend infrastructure, all open-source. if a claude fable review
  finds near.ai copies your plaintext, teemoon warns you and names the components at fault.
- **web search.** Brave's LLM context API, streaming, with sources beside the reply. on near.ai the tool call, query
  and results stay end-to-end encrypted; Brave sees only the search itself, sent from your phone with your key.
- **chat search.** on the phone and offline.
- **the request, as sent.** developer mode shows each reply's request, keys masked.
- **one permission.** notifications. nothing else.

### only on Android

- **fast.** on-phone Gemma 4 E2B runs at up to 70 tok/s decode on a Galaxy S26 Ultra's GPU, and prefills nearly
  5,000 tok/s (a ~1,900-token prompt) on a Galaxy S25 Ultra's NPU. teemoon uses LiteRT 2.2 with speculative decoding
  (Gemma's own drafter) where it measured faster — Snapdragon GPUs and the Snapdragon 8 Elite's NPU — and picks the
  NPU, GPU or CPU for each phone, proving each with a test token before using it. NPU support follows the Gemma 4
  bundles Google publishes: Google Tensor G5 and G6 (Pixel 10 and 11 series, except the Pixel 10a) and the Snapdragon
  8 Elite (Galaxy S25, S25+, S25 Ultra, S25 Edge, Z Fold7, OnePlus 13, Xiaomi 15 series and other 8 Elite phones; not
  the S25 FE or Z Flip7, which use Exynos). every other phone runs on its GPU or CPU.
- **answers that finish in the background.** even on fast infrastructure like Fireworks, large models (like GLM 5.3
  FP8) can take minutes to generate a response. leave the app and it keeps going, then a notification says it's
  ready — never what it says. (iOS can't hold a streaming connection in the background.)
- **reproducible builds.** every release is rebuilt from its tag on a clean machine and compared with the published
  apk, byte for byte; the verdict is in the release notes, and [`docs/REPRODUCIBLE.md`](docs/REPRODUCIBLE.md) shows
  how to check it yourself. so the apk you install is provably this source — an App Store build can't be checked that
  way.

## building and testing

JDK 25 and the Android SDK (platform 37); no keys.

```
./gradlew :app:assembleDebug           # a debug build, beside the Play app
./gradlew test                         # the JVM suite, offline
./gradlew :app:verifyPaparazziDebug    # every screen against its snapshot
tools/device_suite.sh UserJourneysTest # the user journeys on a device
```

tests that call the real model providers (near.ai, OpenRouter and the rest, with your own keys, a few cents a run)
are opt-in; see [`tools/live_cloud_suite.sh`](tools/live_cloud_suite.sh). [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
is the map of the code, and [`ports.json`](ports.json) names the Swift file and teemoon-ios tag each Kotlin file was
ported from.

## contributing

bug reports and pull requests are welcome; fixes come with a regression test. copy that overstates what the code
verifies is treated as a bug. teemoon is AGPL-3.0 and also distributed on Google Play by the copyright holder, so
contributions may need a CLA — see [`CONTRIBUTING.md`](CONTRIBUTING.md). security issues:
[private vulnerability reporting](https://github.com/teemoonai/teemoon-android/security/advisories/new), not a public
issue.

## cryptography notice

this distribution includes cryptographic software. the country in which you currently reside may have restrictions on
the import, possession, use, and/or re-export to another country of encryption software. before using it, check your
country's laws and regulations concerning the import, possession, use, and re-export of encryption software. teemoon
uses only standard, published algorithms (X25519/Ed25519, XChaCha20-Poly1305, HKDF, SHA-2, ECDSA, AES-GCM, TLS).

## license

copyright 2026 ringzero ventures llc, licensed under the [GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html).
third-party notices are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
