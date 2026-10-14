# Third-Party Notices

teemoon incorporates and builds upon third-party software. The notices below are
preserved to satisfy the license terms of that software. The project as a whole is
licensed under the GNU AGPL-3.0 (see [`LICENSE`](LICENSE)); the notices here cover
portions that originate from, or remain governed by, the licenses of their upstream
authors. MIT and Apache-2.0 are one-way compatible with AGPL-3.0, so those components
may be distributed as part of this AGPL-3.0 work while their original notices ride
along.

---

## fullmoon (design lineage)

teemoon for Android is a port of [teemoon-ios](https://github.com/teemoonai/teemoon-ios),
which began as a fork of **fullmoon** (an iOS app for running local language models),
originally published under the MIT License. The user-facing design this port reproduces in
Compose descends from fullmoon, so its copyright and permission notice are preserved below
as MIT requires.

- Upstream: fullmoon by Mainframe Computer, Inc. (https://github.com/mainframecomputer/fullmoon-ios)

```
MIT License

Copyright (c) 2024 Mainframe Computer, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Dependencies

Resolved through Gradle from Maven Central and Google's Maven repository; versions are
pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Each retains its own
license in its published artifact.

| dependency | license |
|---|---|
| AndroidX (core, lifecycle, activity, navigation, Room, sqlite-bundled, test) and Jetpack Compose | Apache-2.0 |
| OkHttp (com.squareup.okhttp3) | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines | Apache-2.0 |
| LiteRT-LM (com.google.ai.edge.litertlm:litertlm-android) | Apache-2.0 |
| secp256k1-kmp (fr.acinq.secp256k1) | Apache-2.0 |
| Coil (io.coil-kt) | Apache-2.0 |
| multiplatform-markdown-renderer (com.mikepenz) | Apache-2.0 |
| Bouncy Castle (org.bouncycastle:bcprov-jdk18on) | MIT (notice below) |
| dcap-qvl-android (com.phala) | MIT (notice below) |
| JUnit 4 — tests only | Eclipse Public License 1.0 |
| sqlite-jdbc (org.xerial) — JVM tests only | Apache-2.0 |
| Paparazzi (app.cash.paparazzi) — snapshot tests only | Apache-2.0 |

### Bouncy Castle

```
Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### dcap-qvl (Phala Network)

```
MIT License

Copyright (c) 2026 Phala Network

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Vendored native libraries

Prebuilt, checked in under `ondevice/src/main/jniLibs/`, with their sources and checksums in that directory's
README.

| library | source | license |
|---|---|---|
| `libLiteRt.so`, `libLiteRtDispatch_GoogleTensor.so`, `libteemoon_dispatch_qualcomm.so` (`libLiteRtDispatch_Qualcomm.so`) | LiteRT v2.2.0 (google-ai-edge/LiteRT) | Apache-2.0 |
| `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`, `libQnnSystem.so` | Qualcomm AI Engine Direct runtime, `com.qualcomm.qti:qnn-runtime:2.50.0` | Qualcomm's licence and notices, `ondevice/src/main/assets/licenses/qnn/` (`LICENSE.pdf`, `NOTICE.txt`) — packed into the APK too |

## Conformance fixtures

`conformance/vendor/*.json` are golden vectors generated in teemoon-ios from recorded
near.ai, NVIDIA, Intel PCS, Sigstore and provider-catalogue responses. They are test data
owned by ringzero ventures llc and distributed under this repository's license.
