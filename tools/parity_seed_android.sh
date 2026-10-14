#!/bin/sh
# Seeds the debuggable app on the connected AVD the way ParityCaptureUITests seeds iOS: the host key files are
# pushed into the app's private uitest-host-keys/ directory and the app is launched with the seeding extras
# (UiTestSeeding.kt). No key is ever typed on screen. States mirror the iOS runs:
#   none       a home server only (the local endpoint that answers nothing), no keys
#   keyed      the home server + near.ai keyed + the Brave grounding key
#   grok       grok keyed (the keyed browser)
#   fireworks  fireworks keyed
#   brave      every cloud key + brave answers (equipped first) + grounding
#   ondevice   the on-device model, equipped from the weights already staged below
#   download   start the general bundle's download through the app (remote lab devices)
set -eu
A=${ADB:-adb}; STATE=${1:-none}; PKG=ai.teemoon
# 10.0.2.2 is the emulator's alias for the host. A real device reaches the Mac's fake server through
# `adb reverse tcp:8765 tcp:8765` on 127.0.0.1 instead (and the debug network-security config already
# allows cleartext there) — set TEEMOON_LOCAL_ENDPOINT for that, or for any other server.
LOCAL="${TEEMOON_LOCAL_ENDPOINT:-http://10.0.2.2:8765/v1}"
# seeding is armed only by a token in a file no other app can read or write, named again by the launch below
# (UiTestSeeding.consumeArm); run-as is adb into a debug build
ARM=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
$A shell "run-as $PKG sh -c 'mkdir -p files && echo $ARM > files/uitest-armed'"   # spent by the launch below; removed on exit regardless
trap '$A shell "run-as $PKG rm -f files/uitest-armed" >/dev/null 2>&1' EXIT
# Keys only for the states that use them: `ondevice` and `download` run on remote-lab phones too, which are not ours
# and are not reset between sessions, so they must never receive a key.
case "$STATE" in ondevice|download) KEYS="" ;; *) KEYS=".NEAR_AI_API_KEY .XAI_API_KEY .FIREWORKS_API_KEY .BRAVE_ANSWERS_API_KEY .BRAVE_API_KEY" ;; esac
for f in $KEYS; do
  [ -f "$HOME/$f" ] || continue
  $A push "$HOME/$f" /data/local/tmp/$f >/dev/null
  $A shell "run-as $PKG sh -c 'mkdir -p files/uitest-host-keys && rm -f files/uitest-host-keys/$f; cp /data/local/tmp/$f files/uitest-host-keys/$f'"; $A shell rm /data/local/tmp/$f
done
case "$STATE" in
  none)      X="--es seed_local_endpoint $LOCAL --es seed_local_model fake-model" ;;
  keyed)     X="--es seed_local_endpoint $LOCAL --es seed_local_model fake-model --es seed_presets nearai --ez seed_grounding true" ;;
  grok)      X="--es seed_presets grok" ;;
  fireworks) X="--es seed_presets fireworks" ;;
  brave)     X="--es seed_presets braveanswers,nearai,grok,fireworks --ez seed_grounding true" ;;
  gate)      X="--es seed_presets nearai --es seed_attestation degraded" ;;
  # the phone answering: equips the bundle already on disk rather than re-fetching it (UiTestSeeding.EXTRA_ON_DEVICE)
  ondevice)  X="--ez seed_ondevice true" ;;
  # a remote lab device: no bundle pushed; the app downloads the general bundle itself over the device's Wi-Fi
  download)  X="--ez seed_download true" ;;
  *) echo "unknown state $STATE"; exit 2 ;;
esac
$A shell am force-stop $PKG; $A shell cmd uimode night yes >/dev/null
$A logcat -c
# The on-device model survives nothing (`pm clear` wipes it) and the where sheet's goldens show it as downloaded:
# restore it from the host backup when present (TEEMOON_MODEL_BACKUP, the Gemma 4 E2B bundle directory).
MODEL="${TEEMOON_MODEL_BACKUP:?set TEEMOON_MODEL_BACKUP to a local gemma-4-E2B-it-litert-lm}"
# Not for the `download` state: that state exists to exercise the in-app downloader, and over a remote-lab
# tunnel (~0.4 MB/s) the restore is a two-hour push that looks like a hang.
if [ "$STATE" != download ] && [ -d "$MODEL" ] && ! $A shell run-as $PKG ls files/LocalModels/litert/litert-community/gemma-4-E2B-it-litert-lm >/dev/null 2>&1; then
  $A shell rm -rf /data/local/tmp/gemma-bundle; $A push "$MODEL" /data/local/tmp/gemma-bundle >/dev/null
  $A shell chmod -R a+rX /data/local/tmp/gemma-bundle   # pushed as the shell user; the app's uid must read it
  # one quoted remote command: adb shell splits its arguments, and a bare `sh -c '…'` arrives in pieces
  $A shell "run-as $PKG sh -c 'mkdir -p files/LocalModels/litert/litert-community && mkdir -p files/LocalModels/litert/litert-community/gemma-4-E2B-it-litert-lm && cp -r /data/local/tmp/gemma-bundle/. files/LocalModels/litert/litert-community/gemma-4-E2B-it-litert-lm/'"
  $A shell rm -rf /data/local/tmp/gemma-bundle; echo "model: restored from $MODEL"
fi
$A shell am start -W -n $PKG/$PKG.app.MainActivity --ez uitesting true --es uitest_arm $ARM $X >/dev/null   # -W: started (and the marker read) before the EXIT trap removes it
# the seeding runs off the main thread; wait for its log line before anything drives the screen
i=0; while [ $i -lt 30 ]; do L=$($A logcat -d | grep -o "\[uitest\] seeded: .*" | tail -1); [ -n "$L" ] && { echo "$L"; sleep 2; exit 0; }; sleep 1; i=$((i+1)); done
echo "seeding did not report within 30 s"; exit 1

