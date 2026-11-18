#!/usr/bin/env python3
"""Which store a built app is for, read from its bytes (the maintainer 2026-11-01: the rating prompt in 1.1, the APK free of
Google Play libraries).

    python3 tools/check_store.py github teemoon-1.1.0-3918.apk   # fails if any dex or the manifest names com.google.android.play or .gms
    python3 tools/check_store.py play app/build/outputs/bundle/release/app-release.aab   # fails WITHOUT In-App Review

The GitHub APK is the default build; the Play bundle is the same build with `-Pteemoon.store=play` (app/build.gradle.kts).
A Play bundle built without the flag would ship without the review prompt, and a GitHub APK built with it would carry
Play's library: either way this says so. It reads the dex files and the manifest of an APK or an AAB (base/), each as
bytes: a dex names classes by descriptor, a binary manifest its components dotted, in UTF-16 (UTF-8 in an AAB's). Standard library only.
"""

import sys
import zipfile

PLAY = "com/google/android/play/"
# Play services: none today, and the Play bundle gets only its task library, through In-App Review. Failing on it too
# keeps "no Google Play libraries" true if a later dependency pulls Play services in (release, 2026-11-01).
GMS = "com/google/android/gms/"
REVIEW = "com/google/android/play/core/review/"


def contents(path):
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            base = name.removeprefix("base/").removeprefix("dex/").removeprefix("manifest/")
            if (base.startswith("classes") and base.endswith(".dex")) or base == "AndroidManifest.xml":
                yield name, z.read(name)


def names(name, data, package):
    """A dex names a class by its descriptor (`Lcom/google/android/gms/…;`); a manifest names a component dotted. A
    dotted string inside a dex is NOT a class: AndroidX's photo picker carries the intent action
    "com.google.android.gms.provider.action.PICK_IMAGES" and OkHttp probes "com.google.android.gms.org.conscrypt" by
    name, in every build, with no Google library present."""
    if name.endswith(".dex"):
        return ("L" + package).encode() in data
    dotted = package.replace("/", ".")
    return any(dotted.encode(enc) in data for enc in ("utf-8", "utf-16-le"))


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("github", "play"):
        sys.exit(__doc__)
    store, path = sys.argv[1], sys.argv[2]
    needles = (PLAY, GMS) if store == "github" else (REVIEW,)
    found = [name for name, data in contents(path) if any(names(name, data, n) for n in needles)]
    if store == "github":
        if found:
            sys.exit(f"FAIL {path}: Google Play libraries in {', '.join(found)} (built with -Pteemoon.store=play?)")
        print(f"ok {path}: no Google Play libraries")
    else:
        if not found:
            sys.exit(f"FAIL {path}: no Play In-App Review (built without -Pteemoon.store=play?)")
        print(f"ok {path}: Play In-App Review in {', '.join(found)}")


if __name__ == "__main__":
    main()
