# The image decoder: provenance

teemoon 1.1.0 decodes images that come from outside the app: `data:` images in a near.ai Private Chat export, photos from the system photo picker and the camera, and images pasted into the composer or shared in from another app. The maintainer, 2026-10-25: "Security is important as we are now allowing large binary inputs. I dont want parsing errors to throw access to process." This page records what decodes them, where it came from, and why we believe a malformed image cannot reach the app.

## Two walls

1. **Process.** Untrusted bytes are parsed only in `ImageDecodeService`, which runs as `android:isolatedProcess="true"`, `android:process=":decode"`, `android:exported="false"`. An isolated process gets its own UID, no permissions, no access to the app's files or preferences, and no network. Bytes go in through one pipe; re-encoded pixels come back through another (`IImageDecodeService.aidl`). If the decoder crashes, hangs (15 s) or is exploited, that process ends, and the app stores a placeholder (`IsolatedImageDecoder`, `ImageDecodeServiceTest.theAppSurvivesTheDecoderDying`).
2. **Language.** Inside that process the decoder is **Wuffs**, which is memory-safe by construction, for JPEG, PNG, GIF and BMP. One exception, the maintainer's decision of 2026-11-11 ("any images copied to clipboard should work"): a **picked or pasted WebP, HEIC/HEIF, AVIF or TIFF** (iOS S42) is decoded by Android's `ImageDecoder` (libwebp, the platform HEIF codec) **inside the same isolated process** (`ImageDecodeService.platformFrame`), after the 20 MiB byte cap, with the pixel caps checked from its header before a pixel is allocated, the decoder's own detected type required to be the sniffed family (a lying container is refused), decoded in software at the stored size and re-encoded to JPEG/PNG. Those parsers are not memory-safe (libwebp's CVE-2023-4863 was exploited in the wild); for them the process is the only wall. The import never reaches them: `DataUrl` takes only JPEG/PNG/GIF. The ledger rule keeps `ImageDecoder` out of every other file. `BitmapFactory` still reads only bytes our own process encoded. **Measured on the AVD, 2026-11-11:** WebP of both kinds decodes there; HEIC does NOT — ImageDecoder's HEIF path looks up the media.player service, which SELinux denies `isolated_app` (`avc: denied { find } … mediaserver_service`), so a HEIC is the "couldn't be read" note (`ImageDecodeServiceTest.aHeicInTheIsolatedProcessIsTheNote`). AVIF goes the same way (HeifDecoderImpl → media.player), so it is the note too (`anAvifInTheIsolatedProcessIsTheNote`); TIFF has no Android codec and is the note.

## Wuffs

| | |
|---|---|
| Project | Wuffs, "Wrangling Untrusted File Formats Safely", https://github.com/google/wuffs |
| Version | tag `v0.4.0-alpha.9`, commit `3387e19512ba52957f29fa3a357f11360b415b08` (2024-09-14); `WUFFS_VERSION_STRING` "0.4.0-alpha.9+3837.20240914" |
| Form | source, the upstream single-file C release `release/c/wuffs-v0.4.c`, vendored unmodified at `app/src/main/cpp/third_party/wuffs/` |
| sha256 | `wuffs-v0.4.c` 51d2f73b757adfab105386fd3641aebd69dabc3a19126d5e060b6996ede94714 (3,289,964 bytes); `LICENSE` f8ac2c75e7f629a87565c80d61ff9fe5aa15cdea0bda997d52a4dc13fdbd19cb (Apache-2.0) |
| Fetched | 2026-10-25 from raw.githubusercontent.com at that commit, on the maintainer's yes; the same pin as teemoon-ios |
| Built | NDK r27c (27.2.12479018), CMake 3.22.1, both pinned in `app/build.gradle.kts`; `app/src/main/cpp/CMakeLists.txt` with deterministic flags (no build id, source paths mapped to `.`), so the reproducible build still matches |
| Modules compiled | base, adler32, crc32, deflate, zlib, png, jpeg, lzw, gif, bmp (bmp since 2026-11-11, picked or pasted only) (`WUFFS_CONFIG__MODULES` in `wuffs_jni.c`); nothing else is linked |
| Glue | `app/src/main/cpp/wuffs_jni.c`: two JNI calls, `probe` (the header only) and `decodeRgba` (the first frame, premultiplied RGBA). It checks the pixel and work-buffer caps before it allocates. |

**The memory-safety claim, and its limits.** Wuffs' decoders are written in the Wuffs language, whose compiler proves at build time that every array index is in bounds and that no arithmetic overflows. Code that cannot be proved does not compile. The C file is generated output of that compiler, not hand-written C, and it does no dynamic allocation: the caller supplies every buffer. What the claim does **not** cover: our ~100 lines of JNI glue (reviewed, with every size checked), the Wuffs runtime's few hand-written base functions, and the v0.4 line being labelled alpha upstream. The isolated process is the wall for all of those.

## The allow-list and the gates

Accepted on import, sniffed from the bytes (`ImageFormat.sniff`) and required to match the declared type: **JPEG, PNG, GIF (first frame)**. On import, WebP, HEIC, AVIF, SVG, BMP, TIFF, PDF and anything unknown become a placeholder (`[image not imported]`) and are counted. A picked or pasted image may also be a BMP (Wuffs) or one of the platform-decoded types in Two walls; SVG, PDF and anything unknown are refused there too.

Before the decoder (`ImageGate.kt`, plain bounds-checked Kotlin):
- a `data:` URL must be `data:image/<type>;base64,…`. An http(s) URL is never fetched, and `data:text/html` is refused. An oversized payload is refused before its base64 is decoded.
- 20 MiB of encoded bytes, checked again in the service as it reads (`readCapped`).

In the service, before pixels: the header's width × height ≤ 50 MP and no side over 16,384 px (`ImageCaps.MAX_SIDE`), so a "bomb" declaring 100k × 100k is refused from its header (`aDecompressionBombIsRefusedFromItsHeader`). In the glue, the work buffer is ≤ 256 MiB. The client abandons a decode after 15 s.

## What comes out

Only pixels this process produced, re-encoded: JPEG at quality 85, or PNG when the image is transparent, long side ≤ 2048 (short side rounded half up), plus a ≤ 256-px JPEG thumbnail. EXIF orientation is read by our own bounded parser (`ExifOrientation`) and applied first. Then nothing of the input remains: no EXIF, XMP or ICC profile, and no camera GPS (`aJpegIsDecodedReencodedAndStrippedOfItsMetadata`). Those re-encoded bytes are what the store keeps, what a provider is sent, and what the on-device model reads.

## Updating it

A new Wuffs version is a new pin: fetch the release file at a tagged commit and record both sha256s here. The image corpus must pass on both platforms, and iOS takes the same pin.
