# Reproducible builds

The APK on the GitHub release is built from the tagged source on a clean GitHub Actions runner, and a clean
rebuild of the same tag matches it byte for byte once the signature is set aside. Every release carries the
result of that check in its notes, written by the `release-verify` workflow, with a link to the run.

The claim is made per release, not in general: a release whose notes do not say **verified** has not been
checked, and one that says **MISMATCH** should not be trusted until the mismatch is explained.

## Verify a release yourself

You need a JDK 25 (the build declares it as a Gradle toolchain and downloads one if the machine has none),
the Android SDK (`ANDROID_HOME` or `local.properties`), and Python 3. Then, for release `vX.Y.Z-NNNN`:

    git clone https://github.com/teemoonai/teemoon-android && cd teemoon-android
    git checkout vX.Y.Z-NNNN
    ./gradlew :app:bundleRelease -Pteemoon.abi=arm64            # unsigned: you do not have the signing key
    curl -sLO https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
    echo "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  bundletool-all-1.18.3.jar" | sha256sum -c
    java -jar bundletool-all-1.18.3.jar build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
        --output=rebuilt.apks --mode=universal
    unzip -p rebuilt.apks universal.apk > rebuilt.apk
    curl -sLO https://github.com/teemoonai/teemoon-android/releases/download/vX.Y.Z-NNNN/teemoon-X.Y.Z-NNNN.apk
    python3 tools/verify_release.py teemoon-X.Y.Z-NNNN.apk rebuilt.apk

`verify_release.py` compares every zip entry of the two files and excuses only the signature: the
`META-INF` signature files and the APK Signing Block, which is not an entry. A differing resource, dex file
or native library fails with its name. `apksigcopier compare` or `diffoscope` reach the same verdict.

If bundletool finds `~/.android/debug.keystore` it signs the rebuilt APK with it; that signature is set
aside by the comparison like any other.

## What makes the build deterministic

- Gradle is pinned by the wrapper, with the distribution's checksum in `gradle-wrapper.properties`.
- The JDK is a toolchain declaration (25) in the root build; bytecode targets 17.
- The Android Gradle Plugin normalizes zip entry order and timestamps; no code shrinker runs, so there is no
  minification map to reorder; there is no `BuildConfig` and no build timestamp in the tree.
- The only metadata AGP embeds is the git commit (`META-INF/version-control-info.textproto`), which a tag
  checkout reproduces, and the AGP version.
- Native libraries are packed exactly as their upstream releases ship them: the build never strips them, because
  stripping depends on which NDK a machine has, or whether it has one at all, and a stripped build fails this check.
- bundletool's universal-APK step is deterministic for a given bundle.

Measured before the first verified release (2026-10-07): two clean builds of the same tag on a Mac were
byte-identical, and a GitHub Actions build on Linux matched the Mac build on every content entry.

## What is not covered

- The Play Store bundle is signed with a different key and carries two ABIs; the same source and the same
  build steps produce it, but the check above is written for the GitHub APK.
- The native libraries (LiteRT-LM, the Tensor and Qualcomm NPU dispatchers, the QNN runtime, dcap-qvl, SQLite) are prebuilt
  binaries from their upstream releases, taken as they are published. The build reproduces their packaging;
  it does not rebuild them.

## How a release is cut

1. Tag the public commit `vX.Y.Z-NNNN`; `release-build.yml` builds the unsigned universal APK on a clean
   runner and prints its sha256.
2. The maintainer downloads that artifact, signs it with the app-signing key (`apksigner sign`, v2 and v3
   only; v1 would add META-INF entries), verifies the signature, and attaches the signed APK and its
   `.sha256` to a GitHub release.
3. Publishing the release triggers `release-verify.yml`, which rebuilds the tag, compares, and writes the
   verdict into the release notes.

4. A verified release is then mirrored by the same job to the download bucket teemoon.ai serves from
   (`releases/<tag>/<apk>` and its `.sha256`, byte-identical to the GitHub assets, then `latest.json`), so the
   mirror never carries a build that failed its check. A download from teemoon.ai verifies exactly as one from
   GitHub: same bytes, same fingerprint, same hash.

The signing key never leaves the maintainer's machine; nothing on GitHub can sign a release.
