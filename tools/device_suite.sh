#!/usr/bin/env bash
# The device suites, one class per Gradle call (the runner's comma-separated `class` list runs only the first), with an
# optional screen recording per test kept only when that test fails.
#   tools/device_suite.sh                 — every suite
#   tools/device_suite.sh --record        — every suite, each @Test recorded (screenrecord; ≤3 min per test)
#   tools/device_suite.sh FollowContractTest UserJourneysTest
# Results: build/device-suite/summary.txt (+ <Class.method>.mp4 for recorded failures). A run over ten minutes must
# not sit in a foreground tool call — the emulator does not survive the kill.
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
record=0; classes=()
for a in "$@"; do case "$a" in --record) record=1 ;; *) classes+=("$a") ;; esac; done
[ ${#classes[@]} -eq 0 ] && classes=(UserJourneysTest GroundedTurnTest ScreenInvariantsTest MainThreadNetworkTest FollowContractTest WhereSheetContractTest PlacesKeysContractTest TurnServiceRaceTest TurnChannelsTest ForegroundTimeLimitTest KeyCacheCleanupTest ClipboardClearTest SeedGateTest CleartextGateDeviceTest DownloadServiceTest)
out=build/device-suite; mkdir -p "$out"; summary="$out/summary.txt"; : > "$summary"; failed=0
results=app/build/outputs/androidTest-results/connected/debug
app_id="${APP_ID:-ai.teemoon}"   # on the dev phone: ai.teemoon.e2e with GRADLE_EXTRA=-Pteemoon.e2eId=true
# The e2e app is a debuggable build that takes test seeds, so it does not outlive the run, pass or fail (the local
# audit, 2026-10-13: the uninstall had lived only in comments). EXIT runs after the pulls below.
if [ "$app_id" = ai.teemoon.e2e ]; then
  trap 'adb uninstall ai.teemoon.e2e.test >/dev/null 2>&1; adb uninstall ai.teemoon.e2e >/dev/null 2>&1; echo "uninstalled ai.teemoon.e2e"' EXIT
fi
run_one() {  # $1 = class[#method]
  rm -f "$results"/TEST-*.xml
  # NotificationPermissionJourneyTest needs the permission NOT granted, and revoking it kills the app — so from here
  case "$1" in NotificationPermissionJourneyTest*) adb shell pm revoke "$app_id" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 ;; esac
  # GRADLE_EXTRA=-Pteemoon.e2eId=true on the dev phone: the test app installs as ai.teemoon.e2e, beside the store
  # build and the owner's release-signed teemoon dev (which a debug build cannot replace without wiping it)
  ./gradlew -q :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.$1" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true ${GRADLE_EXTRA:-} >/dev/null 2>&1
  case "$1" in NotificationPermissionJourneyTest*) adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 ;; esac
  local f; f=$(ls "$results"/TEST-*.xml 2>/dev/null | head -1)
  [ -z "$f" ] && { echo "$1: NO RESULT (build failed?)" | tee -a "$summary"; return 1; }
  local line; line="$1: $(grep -o '<testsuite [^>]*>' "$f" | grep -o 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"\|time="[0-9.]*"' | tr '\n' ' ')"
  echo "$line" | tee -a "$summary"
  # each class's report is kept (the next run_one clears the directory), and every failure is listed with its first line
  # and the test's own line: grep -o cannot see a <failure> on the line after its <testcase>, so this listed nothing
  cp "$f" "$out/${1//#/.}.xml"
  python3 - "$f" <<'PY' | tee -a "$summary"
import html, re, sys
s = open(sys.argv[1]).read()
for m in re.finditer(r'<testcase name="([^"]*)"[^>]*>\s*<failure[^>]*>(.*?)</failure>', s, re.S):
    t = html.unescape(m.group(2)); at = [l.strip()[3:] for l in t.splitlines() if 'Test.kt:' in l and 'ai.teemoon.app.' in l][:1]
    print(f"    {m.group(1)}: {t.strip().splitlines()[0][:220]} {' @ ' + at[0] if at else ''}")
PY
  grep -q 'failures="0"' "$f" && grep -q 'errors="0"' "$f"
}
for c in "${classes[@]}"; do
  if [ $record -eq 1 ]; then
    for m in $(grep -o '@Test fun [A-Za-z0-9_]*' "app/src/androidTest/kotlin/ai/teemoon/app/$c.kt" | awk '{print $3}'); do
      adb shell rm -f /sdcard/rec.mp4; adb shell "screenrecord --time-limit 180 /sdcard/rec.mp4" >/dev/null 2>&1 &
      rec=$!; sleep 1
      if run_one "$c#$m"; then :; else failed=1; fi
      adb shell pkill -INT screenrecord >/dev/null 2>&1; wait $rec 2>/dev/null; sleep 2
      if [ $failed -eq 1 ] && ! grep -q "$c#$m: .*failures=\"0\"" "$summary"; then adb pull /sdcard/rec.mp4 "$out/$c.$m.mp4" >/dev/null 2>&1 && echo "    recording: $out/$c.$m.mp4" | tee -a "$summary"; fi
    done
  else
    run_one "$c" || failed=1
  fi
done
echo; echo "== $summary"; cat "$summary"; exit $failed
