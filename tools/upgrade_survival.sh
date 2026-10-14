#!/bin/sh
# I8: an update installed over the previous release keeps every chat, key, place and model.
#   tools/upgrade_survival.sh <previous-release.apk>
# The previous release must be built with the same id this run targets — on the dev phone that is the dev app
# (`./gradlew :app:assembleDebug -Pteemoon.devId=true` in a worktree at the release commit), so the store build
# beside it is never touched. Steps: install the old build, seed it through its own UiTestSeeding (four chats, a
# keyed Fireworks place, a named home server, selected), stop it, install THIS build over it with `adb install -r`
# (what the store does: same package, data kept), then UpgradeSurvivalTest reads it all back from a plain launch.
set -eu
OLD=${1:?previous release apk}
A=${ADB:-adb}; PKG=${APP_ID:-ai.teemoon.dev}
case "$PKG" in ai.teemoon) echo "refusing: this replaces the app's data — run it against the dev app" >&2; exit 2 ;; esac

echo "== install the previous release"
$A install -r "$OLD" >/dev/null
$A shell am force-stop "$PKG"
ARM=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
$A shell "run-as $PKG sh -c 'mkdir -p files/uitest-host-keys && echo uitest-upgrade-key > files/uitest-host-keys/.FIREWORKS_API_KEY && echo $ARM > files/uitest-armed'"   # arms the launch that names it (UiTestSeeding.consumeArm)
trap '$A shell "run-as $PKG rm -f files/uitest-armed" >/dev/null 2>&1' EXIT   # and never outlives the script
$A logcat -c
$A shell am start -W -n "$PKG/ai.teemoon.app.MainActivity" --ez uitesting true --es uitest_arm "$ARM" --ez seed_fresh_store true \
  --es seed_local_endpoint http://127.0.0.1:9/v1 --es seed_local_model fake-model --es seed_local_name "upgrade\ box" \
  --es seed_presets fireworks --ez seed_threads true >/dev/null
for i in $(seq 1 60); do
  $A logcat -d | grep -q "fixture threads seeded" && $A logcat -d | grep -q "\[uitest\] seeded:" && break; sleep 1
done
$A logcat -d | grep "\[uitest\]" | tail -3
sleep 2
$A shell am force-stop "$PKG"
# the key must come back from the app's own store, not be re-read from the host file
$A shell "run-as $PKG rm -rf files/uitest-host-keys"

echo "== install this build over it"
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest -Pteemoon.devId=true
$A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

echo "== verify"
$A shell am instrument -w -e class ai.teemoon.app.UpgradeSurvivalTest -e upgrade verify "$PKG.test/ai.teemoon.app.TeemoonTestRunner" | tail -6
