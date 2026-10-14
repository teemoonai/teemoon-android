#!/bin/sh
# The fast Android side of the screen audit: ParityCaptureTest (a Compose UI test, in-process) against the connected
# AVD. Clears the app once (first run needs nothing configured), pushes the host key files, runs the class, pulls
# the shots into <outdir>. ~1 minute for every state; the old adb/uiautomator driver took ten.
#   tools/parity_capture_compose.sh <outdir>
set -eu
A=${ADB:-adb}; OUT=${1:?outdir}; PKG=ai.teemoon; mkdir -p "$OUT"
$A shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
$A shell cmd uimode night yes >/dev/null
$A shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1   # hw.keyboard=yes for host typing; the soft keyboard still shows
# The AVD's AOSP LatinIME does not follow night mode (Gboard does); its theme is a pref that defaults to
# "LXX Light". Set "LXX Dark" (4) in its device-protected prefs — needs adb root, which the
# google_apis image gives — or every chat capture carries a light keyboard against iOS's dark one.
if $A root >/dev/null 2>&1; then sleep 2
  $A shell 'd=/data/user_de/0/com.android.inputmethod.latin/shared_prefs; f=$d/com.android.inputmethod.latin_preferences.xml; o=$(stat -c "%u" $d)
    grep -q pref_keyboard_theme_20140509 $f 2>/dev/null || { printf "%s\n" "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>" "<map>" "    <string name=\"pref_keyboard_theme_20140509\">4</string>" "</map>" > $f; chown $o:$o $f; chmod 660 $f; am force-stop com.android.inputmethod.latin; }' 2>/dev/null
fi
./gradlew -q :app:installDebug 2>&1 | grep -E "FAILED|error" || true   # the app must be there before its data is cleared and its keys pushed
$A shell pm clear $PKG >/dev/null
for f in .NEAR_AI_API_KEY .XAI_API_KEY .FIREWORKS_API_KEY .BRAVE_ANSWERS_API_KEY .BRAVE_API_KEY; do
  [ -f "$HOME/$f" ] || continue
  $A push "$HOME/$f" /data/local/tmp/$f >/dev/null
  $A shell "run-as $PKG sh -c 'mkdir -p files/uitest-host-keys && rm -f files/uitest-host-keys/$f && cp /data/local/tmp/$f files/uitest-host-keys/$f'"; $A shell rm /data/local/tmp/$f
done
# leaveApksInstalledAfterRun: the uninstall Gradle does after a run deletes the external files dir the shots sit in
./gradlew -q :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.ParityCaptureTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true 2>&1 | grep -E "FAILED|tests completed|Test.*failed|There were failing" || true
$A pull /sdcard/Android/data/$PKG/files/parity/. "$OUT" >/dev/null
grep -o 'testcase name="[a-z_A-Z]*"[^/]*time="[0-9.]*"' "app/build/outputs/androidTest-results/connected/debug/"TEST-*.xml | sed 's/testcase name=//; s/ classname="[^"]*"//' | tr -d '"'
grep -o '<failure[^>]*' "app/build/outputs/androidTest-results/connected/debug/"TEST-*.xml || true
echo "pulled: $(ls "$OUT" | grep -c png) captures in $OUT"
