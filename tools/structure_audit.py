#!/usr/bin/env python3
"""The structure audit: the element trees both parity captures write, paired by state name and compared per element.

    tools/structure_audit.py <ios_dir> <android_dir> [--allow tools/structure_audit_allow.json] [--md out.md]

iOS writes `<state>.tree.txt` (XCUIApplication.debugDescription: `Type, 0x…, {{x, y}, {w, h}}, identifier: '…', label: '…'`);
Android writes `<state>.tree.json` (every accessibility node with a text or description and its screen bounds).
For each state present on both sides: labels only on iOS, labels only on Android, and labels present on both whose
position differs — the horizontal register (left / centre / right third of the screen) or the vertical centre by more
than 8% of the height. Pixels cannot say "the title is left-aligned" or "there is no close button"; this can.
Exit 1 on any gap not in the allow list. A report, meant to become a gate.
"""
import json, os, re, sys

TIME = re.compile(r"^\d{1,2}:\d{2}(\s?[ap]m)?$", re.I)
IOS_LINE = re.compile(r"^\s*→?(\w[\w ()]*?), 0x[0-9a-f]+,(?: pid: \d+,)? \{\{(-?[\d.]+), (-?[\d.]+)\}, \{([\d.]+), ([\d.]+)\}\}(.*)$")
IOS_ATTR = re.compile(r"(identifier|label|value|placeholderValue): '((?:[^'\\]|\\.)*)'")
IGNORE_LABELS = {"", "teemoon.ai", "teemoon"}

def norm(label):
    l = label.strip().lower().replace("…", "").replace("...", "").strip()
    return "" if TIME.match(l) else l

def ios_elements(path):
    """The elements a person sees. In an XCUI tree every presentation is a top-level sibling under the Window — the
    chat first, then each sheet over it, then the keyboard — so the frontmost is the LAST top-level container that
    holds labelled content and is not the keyboard. Images are narrated by SF Symbol name (Android labels no
    decorative glyph) and Keys are the soft keyboard — both dropped."""
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    def indent(l): return len(l) - len(l.lstrip(" →"))
    win = next((i for i, l in enumerate(lines) if re.search(r"^\s*→?Window", l)), None)
    if win is None: return []
    m = re.search(r"\{\{(-?[\d.]+), (-?[\d.]+)\}, \{([\d.]+), ([\d.]+)\}\}", lines[win]); W, H = float(m.group(3)), float(m.group(4))
    top = indent(lines[win]) + 2
    # top-level siblings: (start line, end line)
    starts = [i for i in range(win + 1, len(lines)) if IOS_LINE.match(lines[i]) and indent(lines[i]) == top]
    blocks = [(st, starts[k + 1] if k + 1 < len(starts) else len(lines)) for k, st in enumerate(starts)]
    def labelled(blk): return any(re.search(r"(StaticText|Button|TextField|Cell|Switch|SecureTextField)", lines[i]) and "label: '" in lines[i] for i in range(*blk))
    def is_keyboard(blk): return any(re.search(r"^\s*(Keyboard|Key),", lines[i]) for i in range(*blk))
    front = [blk for blk in blocks if labelled(blk) and not is_keyboard(blk)]
    if not front: return []
    lo, hi = front[-1]
    els = []
    for i in range(lo, hi):
        m = IOS_LINE.match(lines[i])
        if not m: continue
        kind, x, y, w, h, rest = m.group(1), float(m.group(2)), float(m.group(3)), float(m.group(4)), float(m.group(5)), m.group(6)
        if kind in ("Image", "Key", "Keyboard"): continue
        attrs = dict(IOS_ATTR.findall(rest))
        label = attrs.get("label") or attrs.get("value") or attrs.get("placeholderValue") or ""
        if kind in ("Other", "Cell", "ScrollView", "Table", "CollectionView", "NavigationBar", "Toolbar", "Sheet") and not attrs.get("label"): continue
        if w <= 0 or h <= 0: continue
        # XCUI lists a scrolling page's off-screen rows too; Android's tree holds what is on screen. Keep the visible.
        if y + h <= 0 or y >= H or x + w <= 0 or x >= W: continue
        n = norm(label)
        if n in IGNORE_LABELS or len(n) == 1 or re.search(r"scroll bar|sheet grabber", n): continue
        els.append({"kind": kind, "label": n, "cx": (x + w / 2) / W, "cy": (y + h / 2) / H, "w": w / W})
    return els

def android_elements(path):
    d = json.load(open(path)); W, H = d["screen"]; els = []
    for e in d["elements"]:
        label = e["text"] or e["desc"]; n = norm(label)
        if n in IGNORE_LABELS: continue
        els.append({"kind": e["class"].split(".")[-1], "label": n, "cx": (e["l"] + e["r"]) / 2 / W, "cy": (e["t"] + e["b"]) / 2 / H, "w": (e["r"] - e["l"]) / W})
    return els

def register(cx): return "left" if cx < 0.36 else "right" if cx > 0.64 else "centre"

SYMBOL = re.compile(r"^[a-z0-9]+(\.[a-z0-9]+)+$")   # an SF Symbol name narrated as a label ("moonphase.waning.crescent")

def compare(ios, andr, aliases=None, ignore=()):
    """[aliases] maps an iOS VoiceOver label to the Android label for the same control ("list" -> "chats");
    [ignore] names labels that exist on one platform only by nature (a glyph iOS narrates, a popup scrim).
    Android captures carry the soft keyboard; when it is up (many one-character keys), vertical positions are not
    compared and the keys are dropped. A composite VoiceOver label counts as present when any of its parts is.
    A label contained in a longer label on the other side counts as present (Android's markdown splits blocks that
    iOS narrates as one). A label that appears more than once is paired with its nearest twin."""
    aliases = aliases or {}
    def canon(l):
        l = re.sub(r"\.? ?double tap to .*$", "", l).strip().rstrip(".")
        return aliases.get(l, l)
    keyboard = sum(1 for e in andr if len(e["label"]) == 1) >= 20
    andr = [e for e in andr if not (keyboard and len(e["label"]) == 1)]
    by_i = {}; by_a = {}
    for e in ios:
        l = canon(e["label"])
        if not l or l in ignore or SYMBOL.match(l): continue
        by_i.setdefault(l, []).append(e)
    for e in andr:
        l = canon(e["label"])
        if not l or l in ignore: continue
        by_a.setdefault(l, []).append(e)
    def parts(l): return [canon(x) for x in re.split(r", | · ", l) if canon(x)]
    def present(l, other):
        if l in other: return True
        if len(l) >= 4 and any(l in o or (len(o) >= 4 and o in l) for o in other): return True
        ps = parts(l)
        return len(ps) > 1 and any(p in other or any(p in o for o in other if len(p) >= 4) for p in ps)
    gaps = []
    # iOS names a back button after the page it returns to ("settings"); Android names it "back" or "close". An
    # unmatched iOS Button in the left register level with an Android back/close control is that control.
    nav = [a for l in by_a for a in by_a[l] if l in ("back", "close")]
    def is_back(e): return e["kind"] == "Button" and register(e["cx"]) == "left" and any(abs(e["cy"] - a["cy"]) < 0.08 for a in nav)
    for l in sorted(by_i):
        if not present(l, by_a) and not all(is_back(e) for e in by_i[l]): gaps.append(("only on iOS", l, ""))
    for l in sorted(by_a):
        if not present(l, by_i): gaps.append(("only on Android", l, ""))
    for l in sorted(set(by_i) & set(by_a)):
        best = None
        for i in by_i[l]:
            for a in by_a[l]:
                wide = i["w"] > 0.6 or a["w"] > 0.6
                # by LEFT edge: a hugging pill's centre moves with its own width, its edge says where it was placed
                li, la = i["cx"] - i["w"] / 2, a["cx"] - a["w"] / 2
                reg = 0 if wide or register(li) == register(la) or abs(li - la) < 0.12 else 1
                dy = 0 if keyboard else abs(i["cy"] - a["cy"])
                score = (reg, dy)
                if best is None or score < best[0]: best = (score, i, a)
        (reg, dy), i, a = best
        if reg: gaps.append(("register", l, f"iOS left edge {i['cx'] - i['w'] / 2:.0%}, Android {a['cx'] - a['w'] / 2:.0%}"))
        elif dy > 0.08: gaps.append(("vertical", l, f"iOS y={i['cy']:.0%}, Android y={a['cy']:.0%}"))
    return gaps

def main(argv):
    ios_dir, and_dir = argv[1], argv[2]
    allow = {}; md = None
    if "--allow" in argv: allow = json.load(open(argv[argv.index("--allow") + 1]))
    if "--md" in argv: md = argv[argv.index("--md") + 1]
    states = sorted({f[:-9] for f in os.listdir(ios_dir) if f.endswith(".tree.txt")} & {f[:-10] for f in os.listdir(and_dir) if f.endswith(".tree.json")})
    out = ["# structure audit", "", f"{len(states)} states paired", ""]; total = 0
    for s in states:
        gaps = compare(ios_elements(os.path.join(ios_dir, s + ".tree.txt")), android_elements(os.path.join(and_dir, s + ".tree.json")), allow.get("aliases"), set(allow.get("ignore", [])))
        allowed = set(allow.get(s, [])) | set(allow.get("*", []))
        gaps = [g for g in gaps if g[1] not in allowed]
        total += len(gaps)
        out.append(f"## {s} — {'clean' if not gaps else f'{len(gaps)} gaps'}")
        for kind, label, why in gaps: out.append(f"- {kind}: `{label}` {why}")
        out.append("")
    text = "\n".join(out); print(text)
    if md: open(md, "w").write(text)
    return 1 if total else 0

if __name__ == "__main__": sys.exit(main(sys.argv))
