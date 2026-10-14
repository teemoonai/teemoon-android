# Vendored native libraries

Prebuilt libraries we did not build, checked in on purpose. arm64-v8a only: no NPU exists on an x86_64 emulator, and
the official releases ship no x86_64 build. The APK packs them byte for byte (never stripped; see
`app/build.gradle.kts`), so a release stays reproducible.

| file | what it is | source | sha256 (first 16) |
|---|---|---|---|
| `libLiteRt.so` | the LiteRT C API | `com.google.ai.edge.litert:litert:2.2.0` (Google Maven) | `97355a36cb8ac762` |
| `libLiteRtDispatch_GoogleTensor.so` | the Google Tensor NPU dispatcher | `litert_npu_runtime_libraries.zip`, LiteRT v2.2.0, `google_tensor_runtime/` | `35b59265eb8595a1` |
| `libteemoon_dispatch_qualcomm.so` | the Qualcomm NPU dispatcher (`libLiteRtDispatch_Qualcomm.so`, renamed — see below) | same zip, `qualcomm_runtime_v79/` | `c4abfff6c99ec218` |
| `libQnnHtp.so` | Qualcomm QNN runtime | `com.qualcomm.qti:qnn-runtime:2.50.0` (Maven Central) | `30e2981965e687d7` |
| `libQnnHtpV79Skel.so` | QNN runtime, Hexagon v79 (Snapdragon 8 Elite) | same | `860c9d2e7c937c9f` |
| `libQnnHtpV79Stub.so` | same | same | `d9f5eca67297125c` |
| `libQnnSystem.so` | same | same | `ca9e5429c77380e1` |

LiteRT is Apache-2.0. The QNN runtime is under Qualcomm's licence; its licence and notices ship in the APK, from
`ondevice/src/main/assets/licenses/qnn/`. QNN 2.50.0 is the build the Snapdragon 8 Elite bundle runs on; only the v79
pieces are packed (the AAR carries every Hexagon generation and an on-device compiler an ahead-of-time bundle does
not use).

## Why the APK extracts them (`useLegacyPackaging = true`)

LiteRT-LM finds a vendor NPU dispatcher by LISTING the directory `Backend.NPU` is handed (`litert_dispatch.cc`:
"No dispatch library found in …"), not by loading it by name. Modern packaging leaves `.so` files inside the APK and
`nativeLibraryDir` empty, so a dispatcher present in the APK would never be found. Legacy packaging extracts them at
install.

## Why the Qualcomm dispatcher is renamed

LiteRT takes the FIRST `libLiteRtDispatch*.so` its listing finds, in directory order ("Multiple dispatch libraries
found" is only a warning). Side by side, either phone could be handed the other vendor's dispatcher. Under a name the
listing does not match, a Tensor phone's `nativeLibraryDir` holds only its own; a Snapdragon 8 Elite is handed a
directory of symlinks instead (`ai.teemoon.ondevice.NpuDispatch`): the dispatcher under its real name, the QNN
runtime beside it. If those links cannot be made, it is handed an empty directory, never `nativeLibraryDir`.

## Which phones reach an NPU

Google publishes Gemma 4 E2B NPU bundles for Tensor G5, Tensor G6 and the Snapdragon 8 Elite (SM8750); every other
phone runs the general bundle on its GPU or CPU. On Android 17 the app must also declare `android.hardware.npu`
(`AndroidManifest.xml`), or the Tensor G6 refuses its NPU work.

## Measured

Gemma 4 E2B, raw runtime, a 1,938-token prompt, single runs (2026-10-14):

| phone | backend | prefill | decode |
|---|---|---|---|
| Galaxy S25 Ultra (Snapdragon 8 Elite) | NPU | 4,939 tok/s | 35 tok/s |
| Galaxy S25 Ultra | GPU | 2,537 tok/s | 24 tok/s |
| Pixel 11 (Tensor G6) | NPU | 2,678 tok/s | 31 tok/s |

Speculative decoding (the bundle's own drafter) is set for the GPU rung on Qualcomm silicon, where it more than doubles
decode, and for the Snapdragon 8 Elite's NPU (`BackendLadder.wantsSpeculativeDecoding`); on PowerVR, the CPU and the
Tensor NPU it measured slower.
