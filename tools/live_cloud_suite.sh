#!/usr/bin/env bash
# The live cloud cases (LiveCloudTurnsTest): the OpenRouter and NVIDIA presets over the REAL wire, with the host's own
# keys. Kept out of journey_goldens.sh on purpose — those journeys are offline and must stay so; these spend a few cents
# of OpenRouter credit and a few NVIDIA free-tier requests per run, and need the network.
#   tools/live_cloud_suite.sh              the teemoon AVD (emulator-5554, or $ANDROID_SERIAL if it names an emulator)
#   tools/live_cloud_suite.sh --device ID  a real device, ONLY when named. A connected run without
#                                          leaveApksInstalledAfterRun wiped the user's Pixel on 2026-09-17; this passes
#                                          it, but the phone is the user's, so it is never the default.
# Keys: ~/.OPENROUTER_API_KEY, ~/.NVIDIA_API_KEY, ~/.NEAR_AI_API_KEY, ~/.XAI_API_KEY, ~/.FIREWORKS_API_KEY, ~/.BRAVE_API_KEY, ~/.BRAVE_ANSWERS_API_KEY (the names UiTestSeeding.keyFileNames reads), pushed into the app's
# private uitest-host-keys/ and never printed. A missing file makes its cases skip, not fail — the run exits 0. NOTE: the
# JUnit XML under app/build/outputs/androidTest-results writes an assumption skip as <failure> with
# AssumptionViolatedException (tests=3 failures=3, exit code 0); trust the exit code, not that count.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
# On a real device the app goes in as the E2E app (ai.teemoon.e2e), beside the store build and teemoon dev — never over either.
PKG=ai.teemoon; GX=""
# On a device: the e2e app (ai.teemoon.e2e), never teemoon dev — that one is release-signed with the owner's data, so a
# debug build cannot replace it without wiping it. The e2e app, with the staged keys in it, is uninstalled on exit.
if [ "${1:-}" = "--device" ]; then export ANDROID_SERIAL="${2:?--device needs a serial}"; PKG=ai.teemoon.e2e; GX=-Pteemoon.e2eId=true
  trap 'adb uninstall ai.teemoon.e2e.test >/dev/null 2>&1; adb uninstall ai.teemoon.e2e >/dev/null 2>&1; echo "uninstalled ai.teemoon.e2e"' EXIT
else
  export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
  case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "refusing $ANDROID_SERIAL: not an emulator. Name a real device with --device." >&2; exit 2 ;; esac
fi
adb get-state >/dev/null || { echo "no device $ANDROID_SERIAL" >&2; exit 2; }
# The app must exist before its private directory can take the keys.
./gradlew -q :app:installDebug $GX
# The notification prompt (asked at the first send) covers the app on a fresh install, and every case after it finds
# no compose hierarchy — ten "failures" on a fresh AVD (the local audit, 2026-10-13). Granted up front.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
staged=0
for f in .OPENROUTER_API_KEY .NVIDIA_API_KEY .NEAR_AI_API_KEY .XAI_API_KEY .FIREWORKS_API_KEY .BRAVE_API_KEY .BRAVE_ANSWERS_API_KEY; do
  [ -s "$HOME/$f" ] || { echo "no ~/$f — its cases will skip"; continue; }
  adb push "$HOME/$f" /data/local/tmp/$f >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p files/uitest-host-keys && rm -f files/uitest-host-keys/$f && cp /data/local/tmp/$f files/uitest-host-keys/$f'"
  adb shell rm /data/local/tmp/$f; staged=$((staged+1))
done
echo "staged $staged key file(s) on $ANDROID_SERIAL"
# LIVE_TEST=<method> runs one case; OLLAMA_ENDPOINT/OLLAMA_MODEL point L2 and the Ollama search case elsewhere
# (default: the Mac's own Ollama at 10.0.2.2; a phone with Tailscale takes the tailnet name).
./gradlew -q :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.LiveCloudTurnsTest${LIVE_TEST:+#$LIVE_TEST}" \
  ${OLLAMA_ENDPOINT:+"-Pandroid.testInstrumentationRunnerArguments.ollamaEndpoint=$OLLAMA_ENDPOINT"} \
  ${OLLAMA_MODEL:+"-Pandroid.testInstrumentationRunnerArguments.ollamaModel=$OLLAMA_MODEL"} \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true $GX
