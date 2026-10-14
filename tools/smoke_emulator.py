#!/usr/bin/env python3
"""End-to-end smoke on the `teemoon` AVD: install the debug APK, save a provider
key through the settings screen (exercises the Android Keystore cipher), force-stop
and relaunch to prove the key persists, then stream one turn from the fake SSE server.

    python3 ../teemoon-ios/tools/fake_sse_server.py --delay 0.006 &
    $ANDROID_SDK/emulator/emulator -avd teemoon -no-window -no-audio -no-boot-anim &
    ./gradlew :app:assembleDebug && python3 tools/smoke_emulator.py

Exits non-zero on the first failed check. Drives the UI by label through
uiautomator dumps, so it is only as stable as the labels in app/src.
"""
import os, re, subprocess, sys, time

# `--near`: after the fake-server smoke, add near.ai with NEAR_AI_API_KEY from the
# environment, wait for the trust chip to read "end-to-end encrypted", send one
# sealed turn and expect a decrypted reply. Costs one tiny request.
NEAR = "--near" in sys.argv

APK = "app/build/outputs/apk/debug/app-debug.apk"
ACTIVITY = "ai.teemoon/ai.teemoon.app.MainActivity"

def sh(cmd, check=False):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and r.returncode != 0: sys.exit(f"FAILED: {cmd}\n{r.stdout}{r.stderr}")
    return r.stdout

def dump():
    sh("adb shell uiautomator dump /sdcard/ui.xml")
    x = sh("adb shell cat /sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*>', x):
        n = m.group(0)
        tm = re.search(r'text="([^"]*)"', n); t = tm.group(1) if tm else ""
        cm = re.search(r'class="([^"]*)"', n); c = cm.group(1).split('.')[-1] if cm else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b: out.append((t, c, tuple(map(int, b.groups()))))
    return out

def tap(pred, what):
    for t, c, b in dump():
        if pred(t, c):
            sh(f"adb shell input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); time.sleep(1.2); return
    sys.exit(f"FAILED: no element for {what}")

def expect(pred, what, timeout=15):
    for _ in range(timeout):
        if any(pred(t, c) for t, c, _ in dump()): print(f"ok: {what}"); return
        time.sleep(1)
    sys.exit(f"FAILED: never saw {what}")

def launch():
    sh("adb shell am force-stop ai.teemoon"); time.sleep(1)
    sh(f"adb shell am start -W -n {ACTIVITY}"); time.sleep(3)

sh("adb wait-for-device", check=True)
for _ in range(120):
    if sh("adb shell getprop sys.boot_completed").strip() == "1": break
    time.sleep(3)
else: sys.exit("FAILED: emulator never booted")
sh("adb uninstall ai.teemoon")   # a clean install: a previous run's places and keys must not leak into this one
sh(f"adb install -r {APK}", check=True)
sh("adb logcat -c")
launch()
expect(lambda t, c: t == "where", "threads screen")
tap(lambda t, c: t == "where", "where")
tap(lambda t, c: c == "EditText", "the provider's api key field")
sh("adb shell input text sk-smoke-test-key-123"); time.sleep(0.5)
tap(lambda t, c: t == "save", "save")
launch()
tap(lambda t, c: t == "where", "where")
expect(lambda t, c: t == "api key · saved", "key persisted across a relaunch (Android Keystore)")
tap(lambda t, c: t == "done", "done")
tap(lambda t, c: t == "new chat", "new chat")
tap(lambda t, c: c == "EditText", "message field")
sh("adb shell input text smoke%sturn"); tap(lambda t, c: t == "send", "send")
expect(lambda t, c: t.startswith("Paragraph 0"), "a streamed assistant turn from the fake server", timeout=30)
# Chat search: the assistant turn was indexed by the write hook; a term from it must find the thread with a highlighted snippet.
tap(lambda t, c: t == "chats", "back to chats")
tap(lambda t, c: c == "EditText", "search field")
sh("adb shell input text attestation-00")
expect(lambda t, c: "attestation-00" in t and c == "TextView" and not t.startswith("smoke"), "a search hit with the matched snippet", timeout=20)
if NEAR:
    key = os.environ.get("NEAR_AI_API_KEY", "").strip()
    if not key: sys.exit("FAILED: --near needs NEAR_AI_API_KEY in the environment")
    W, H = [int(v) for v in re.search(r"(\d+)x(\d+)", sh("adb shell wm size")).groups()]
    def scroll(down=True, n=8):
        y0, y1 = (H - 40, 60) if down else (60, H - 40)
        for _ in range(n): sh(f"adb shell input swipe {W//2} {y0} {W//2} {y1} 250"); time.sleep(0.3)
        time.sleep(0.5)
    def form_field(index):
        # Compose text fields surface as bare EditTexts. The add form is the page's tail: scrolled to the
        # bottom, its four fields are the LAST four EditTexts on screen, in order.
        scroll(down=True)
        fields = sorted([b for t, c, b in dump() if c == "EditText"], key=lambda b: b[1])[-4:]
        if len(fields) < 4: sys.exit(f"FAILED: the add form's fields are not on screen ({len(fields)} seen)")
        return fields[index]
    def type_into(index, value, clear=0):
        b = form_field(index)
        sh(f"adb shell input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); time.sleep(0.8)
        sh("adb shell input keyevent KEYCODE_MOVE_END")
        for _ in range(clear): sh("adb shell input keyevent KEYCODE_DEL")
        subprocess.run(["adb", "shell", "input", "text", value.replace(" ", "%s")], check=True); time.sleep(0.5)
        sh("adb shell input keyevent KEYCODE_BACK"); time.sleep(0.5)   # keyboard down, so the next field is on screen
    launch()   # a clean threads screen: the search keyboard is down and the list is unfiltered
    tap(lambda t, c: t == "where", "where")
    scroll(down=True)
    expect(lambda t, c: t == "add a place", "the add form")
    type_into(0, "near.ai")
    type_into(1, "https://cloud-api.near.ai/v1/chat/completions", clear=len("https://"))
    type_into(2, "z-ai/glm-5.2")
    type_into(3, key)
    scroll(down=True); tap(lambda t, c: t == "add", "add")
    # Make it the active place: scroll until the near.ai card's title is on screen, then tap the "use" on that row.
    scroll(down=False)
    for _ in range(12):
        nodes = dump()
        title = next((b for t, c, b in nodes if t.endswith(" near.ai") and c == "TextView"), None)
        if title:
            mid = (title[1] + title[3]) // 2
            use = next((b for t, c, b in nodes if t == "use" and b[1] <= mid <= b[3]), None)
            if use: sh(f"adb shell input tap {(use[0]+use[2])//2} {(use[1]+use[3])//2}"); time.sleep(1.2); break
        sh(f"adb shell input swipe {W//2} {H - 60} {W//2} {H//2} 250"); time.sleep(0.6)
    else: sys.exit("FAILED: the near.ai card never came on screen")
    scroll(down=False)
    tap(lambda t, c: t == "done", "done")
    tap(lambda t, c: t == "new chat", "new chat")
    expect(lambda t, c: t == "end-to-end encrypted", "the trust chip reading end-to-end encrypted (attestation, DCAP, provenance, NRAS, TLS all green)", timeout=180)
    tap(lambda t, c: c == "EditText", "message field")
    sh("adb shell input text Reply%swith%sexactly%sone%sword:%spong"); tap(lambda t, c: t == "send", "send")
    expect(lambda t, c: "pong" in t.lower() and c == "TextView" and not t.startswith("Reply"), "a decrypted reply from the sealed model", timeout=180)
    if any(t.startswith("error:") for t, c, _ in dump()): sys.exit("FAILED: an error card is showing")
    sh("adb shell input keyevent KEYCODE_BACK"); time.sleep(0.8)   # keyboard down before the chip is tapped
    tap(lambda t, c: t == "end-to-end encrypted", "the trust chip")
    expect(lambda t, c: t == "who can read this?", "the trust screen")
    texts = [t for t, c, _ in dump() if c == "TextView" and t]
    print("trust screen:", " | ".join(x[:70] for x in texts[:8]))
    if not any(t.startswith("encrypted to") for t in texts): sys.exit("FAILED: the hero verdict is not 'encrypted to …'")
    print("ok: the hero verdict on the trust screen")
    print("ok: near.ai attested E2EE turn")
fatal = sh("adb logcat -d | grep -c 'FATAL EXCEPTION'").strip()
if fatal not in ("", "0"): sys.exit(f"FAILED: {fatal} fatal exception(s) in logcat")
print("SMOKE PASSED")
