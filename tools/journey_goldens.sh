#!/usr/bin/env bash
# Step goldens for the user journeys (app/src/androidTest/.../Goldens.kt).
#   tools/journey_goldens.sh record   — run the journeys in record mode and pull every capture into the assets
#   tools/journey_goldens.sh verify   — run the journeys against the recorded goldens; on drift, pull actual + diff
# Baselines are per AVD: record and verify on the same `teemoon` AVD (see tools/parity_capture_compose.sh), with the
# gemma bundle pushed (~/bin/teemoon-emulator does that) — the where sheet golden shows it as already downloaded.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
# On the dev phone: APP_ID=ai.teemoon.e2e with GRADLE_EXTRA=-Pteemoon.e2eId=true — a third app beside the store build
# AND the owner's release-signed teemoon dev, which a debug build cannot replace without wiping it. Copy the Tensor
# bundle into the e2e app first (run-as); the script uninstalls ai.teemoon.e2e and ai.teemoon.e2e.test when it exits.
app_id="${APP_ID:-ai.teemoon}"
# The e2e app is a debuggable build that takes test seeds, so it does not outlive the run, pass or fail (the local
# audit, 2026-10-13: the uninstall had lived only in comments). EXIT runs after the pulls below.
if [ "$app_id" = ai.teemoon.e2e ]; then
  trap 'adb uninstall ai.teemoon.e2e.test >/dev/null 2>&1; adb uninstall ai.teemoon.e2e >/dev/null 2>&1; echo "uninstalled ai.teemoon.e2e"' EXIT
fi
mode="${1:-verify}"; assets=app/src/androidTest/assets/goldens; remote=/sdcard/Android/data/$app_id/files/journeys/goldens
adb shell rm -rf "$remote" >/dev/null 2>&1 || true
# Baselines are per screen profile (goldens/<w>x<h>/): record on EACH device the journeys run on — the teemoon AVD
# (1080x2400) and the Pixel 10 Pro (1080x2410) each keep their own set. GroundedTurnTest carries a golden too.
# ONE CLASS PER GRADLE CALL, as tools/device_suite.sh does. A comma list in the runner argument ran only the first
# class: GroundedTurnTest's golden was never verified from 2026-09-16 until 2026-09-22, and drifted 14% unseen.
# Pin ANDROID_SERIAL when a phone is plugged in too — a connected run targets every attached device.
classes=(ai.teemoon.app.UserJourneysTest ai.teemoon.app.GroundedTurnTest)
status=0
# The notification prompt (POST_NOTIFICATIONS, asked at the first send) covers the app on a fresh install, and every
# later step then finds no compose hierarchy — two "failed" classes on 2026-10-02 that were a system dialog. Granted
# up front; harmless when the app is not installed yet (the first class installs it, and the grant then follows).
adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
for cls in "${classes[@]}"; do
  args=(-Pandroid.testInstrumentationRunnerArguments.class=$cls -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true ${GRADLE_EXTRA:-})
  [ "$mode" = record ] && args+=(-Pandroid.testInstrumentationRunnerArguments.goldens=record)
  set +e; ./gradlew -q :app:connectedDebugAndroidTest "${args[@]}"; s=$?; set -e
  adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  [ $s -ne 0 ] && { echo "$cls FAILED"; status=$s; }
done
case "$mode" in
  record)
    mkdir -p "$assets"; adb pull "$remote/." "$assets/" >/dev/null
    echo "recorded $(find "$assets" -name '*.png' | wc -l | tr -d ' ') goldens across $(ls "$assets" | tr '\n' ' ') — review them, then commit"; exit $status ;;
  verify)
    out="${OUT:-build/journey-goldens}"; rm -rf "$out"; mkdir -p "$out"
    adb pull "$remote/." "$out/" >/dev/null 2>&1 || true
    if [ $status -ne 0 ]; then echo "journeys FAILED — actuals and diffs (if any step drifted) are in $out:"; ls "$out" 2>/dev/null; else echo "journeys green: every step matched its golden"; fi
    exit $status ;;
  *) echo "usage: $0 record|verify"; exit 2 ;;
esac
