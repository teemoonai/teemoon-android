#!/bin/sh
# G2 (process kill): a phone-model download the system kills mid-transfer resumes from its partial on the next tap.
#   tools/download_kill_resume.sh            (ANDROID_SERIAL / APP_ID as for device_suite.sh; the dev app by default)
# Runs DownloadKillResumeTest twice with the kill between: `-e g2 start` leaves the transfer running (the test returns
# while it is in flight), `am kill` is what the system does to a backgrounded app, then `-e g2 resume` checks it picks up.
set -eu
A=${ADB:-adb}; PKG=${APP_ID:-ai.teemoon.dev}
case "$PKG" in ai.teemoon) echo "refusing: the journeys clear the store — run it against the dev app" >&2; exit 2 ;; esac
GX=""; [ "$PKG" = ai.teemoon.dev ] && GX=-Pteemoon.devId=true
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest $GX
$A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
RUN="$PKG.test/ai.teemoon.app.TeemoonTestRunner"   # the runner arms seeding (UiTestSeeding.armed)
CLS=ai.teemoon.app.DownloadKillResumeTest

echo "== start a download"
# -w waits for the test, not the transfer: the process lives on with the download until the kill below
$A shell am instrument -w -e class $CLS -e g2 start "$RUN" | tail -3
$A shell "pidof $PKG" || true
echo "== kill the process mid-transfer"
$A shell am force-stop "$PKG"
sleep 1
echo "== relaunch and resume"
$A shell am instrument -w -e class $CLS -e g2 resume "$RUN" | tail -4
