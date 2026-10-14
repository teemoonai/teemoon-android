#!/bin/sh
# The transcript's overscroll metric and walk-away hand-off, on the connected AVD: runs the two trace drivers of
# FollowContractTest one at a time, pulls scrolltrace.log after each, and judges it with tools/check_scroll_trace.py.
# Not a gate (needs the AVD). ~2 minutes.
#   tools/scroll_trace_check.sh [outdir]
set -u
A=${ADB:-adb}; OUT=${1:-/tmp/teemoon-scrolltrace}; PKG=ai.teemoon; mkdir -p "$OUT"; status=0
./gradlew -q :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.FollowContractTest#streamingAtTheEndOfALongThreadStaysPinnedToTheBottom -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true >/dev/null 2>&1 || echo "pinned driver reported a failure; judging the trace anyway"
$A shell "run-as $PKG cat files/scrolltrace.log" > "$OUT/pinned.log"; echo "-- pinned ($(wc -l < "$OUT/pinned.log" | tr -d ' ') lines)"
python3 tools/check_scroll_trace.py "$OUT/pinned.log" || status=1
./gradlew -q :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.teemoon.app.FollowContractTest#aReaderInsideTheAnswerDrivesAWalkAwayHandoff -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true >/dev/null 2>&1 || echo "walk-away driver reported a failure; judging the trace anyway"
$A shell "run-as $PKG cat files/scrolltrace.log" > "$OUT/walkaway.log"; echo "-- walk-away ($(wc -l < "$OUT/walkaway.log" | tr -d ' ') lines)"
python3 tools/check_scroll_trace.py "$OUT/walkaway.log" --walkaway || status=1
exit $status
