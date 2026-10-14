#!/usr/bin/env python3
"""Copy parity: does the Kotlin still say what the Swift it was ported from said?

    tools/copy_audit.py --ios ../teemoon-ios              # report
    tools/copy_audit.py --ios ../teemoon-ios --gate       # exit 1 on a missing string
    tools/copy_audit.py --ios ../teemoon-ios --symbols    # the glyph half only
    tools/copy_audit.py --ios ../teemoon-ios --since 81107be   # calibration: audit a RANGE

WHY. The behavior ledger gates wiring and the conformance kit pins bytes; neither
looks at a sentence. The complaint that started all of this was that "many things
in the views are different across platforms" — and the things that differed were
words and glyphs, which nothing in this repo could see.

AGAINST THE ENTRY'S SHA, NEVER iOS MAIN. This is the whole design. 137 source
pairs are drifted, so auditing against HEAD would report every unported 1.0.2
string as a defect — which is exactly the mistake that made 11 of 11 claimed
divergences false on 2026-09-20. Reading the Swift at the sha the port RECORDS
asks a different and answerable question: did the port faithfully carry what it
claimed to carry? Drift is `parity_closure.py --drift`'s job and stays separate.

WHAT IT EXTRACTS. Only strings in constructs that put words on screen — Text,
Label, Button, navigationTitle, alert, confirmationDialog, prompt, placeholder —
so log lines, keys, URLs and identifiers never enter. An interpolated string is
split on its `\\(…)` holes and the literal fragments are checked, because
"$(name) is 61% downloaded" is copy even though it is not a literal.

Comparison is REPO-WIDE, not file-to-file. ports.json is many-to-one and a
string declared in one Kotlin file is often rendered by another — WhereGetSection
and WhereChip both land in DesignKit. Asking "does this sentence exist in the
Kotlin at all" is the question with a low false-positive rate; asking "is it in
THIS file" is not.
"""
import argparse, difflib, json, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Constructs that put words on a screen. Anything else a Swift file says is not copy.
UI_STRING = re.compile(
    r"""(?:Text|Label|Button|Toggle|TextField|SecureField|Section|Link|NavigationLink)\s*\(\s*"((?:[^"\\]|\\.)*)"|"""
    r"""\.(?:navigationTitle|alert|confirmationDialog|accessibilityLabel)\s*\(\s*"((?:[^"\\]|\\.)*)"|"""
    r"""(?:prompt|placeholder|title|message|label|caption|subtitle)\s*:\s*"((?:[^"\\]|\\.)*)\"""",
    re.X)
SYMBOL = re.compile(r"""(?:systemName|systemImage)\s*:\s*"([^"]+)\"""")
# Interpolation holes NEST — `\(count > 0 ? f(x) : "")` — so a [^)]* hole stops at
# the first inner paren and leaves Swift expression text looking like prose. Balance them.
def split_interpolations(raw):
    out, buf, depth, i = [], [], 0, 0
    while i < len(raw):
        if raw.startswith("\\(", i):
            if depth == 0:
                out.append("".join(buf)); buf = []
            depth += 1; i += 2; continue
        if depth:
            if raw[i] == "(":
                depth += 1
            elif raw[i] == ")":
                depth -= 1
        else:
            buf.append(raw[i])
        i += 1
    out.append("".join(buf))
    return out


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout


def norm(s):
    """Copy compares case- and whitespace-insensitively; everything else is literal.

    ESCAPES ARE DECODED FIRST, and skipping that manufactured false positives on
    both sides (found 2026-09-21):

      Swift `\\u{201C}`  a smart quote written as an escape. Undecoded, the
                        fragment keeps the literal characters `\\u{201C}`, which
                        cannot match anything in the Kotlin, so every string
                        around it reads as missing. WebSearchOffer's whole brave
                        paragraph was reported absent while sitting in
                        WebSearchOffer.kt word for word.
      Kotlin `\\$`       Kotlin's escape for a literal dollar, which Swift does
                        not need. Undecoded, no price or cost sentence can ever
                        match — "you get \\$5" against "you get $5".

    A copy audit whose misses are mostly its own encoding is worse than none: it
    trains the reader to skim the list.
    """
    s = re.sub(r"\\u\{([0-9A-Fa-f]{1,6})\}", lambda m: chr(int(m.group(1), 16)), s)   # Swift
    s = s.replace("\\$", "$")                                                          # Kotlin
    return re.sub(r"\s+", " ", s.replace("\\n", " ").replace('\\"', '"')).strip().lower()


def fragments(raw):
    """Literal prose from a possibly-interpolated string, long enough to be a sentence."""
    out = []
    for part in split_interpolations(raw):
        # A hole leaves punctuation behind on both sides — "…(\(size)). teemoon can…"
        # splits to "). teemoon can…", and the leading "). " is not copy.
        n = norm(part).strip(" .,;:!?—-()[]\u201c\u201d\"'")
        # Two words or a long one: "of" and "%" are not copy, "not downloaded" is.
        if len(n) >= 8 and (" " in n or len(n) >= 12):
            out.append(n)
    return out


def strip_previews(text):
    """Drop `#Preview { … }`, `…_Previews: PreviewProvider { … }`, and `preview*`/`sample*` declarations.

    Previews live INSIDE production files, not only in the *Previews.swift ones a
    path filter catches, and they carry invented sample content — a dragonball
    question, a flour comparison, a fake tweet handle. None of it ships, and all
    of it outnumbered the real findings in the first run.

    The named-declaration rule was added 2026-09-21, after eight of the last
    eleven untriaged findings turned out to be fixtures that no `#Preview` block
    contained: `private let previewSources` in GroundingSourcesView (a reddit
    thread about deleting tweets) and `static var sampleVerified` in TrustLadder
    (`v0.4.2 → a1b2c3d`, `sha256:7a3f9c2e1b8d…`, an example.com link). Triaging
    them one by one would have left the NEXT fixture to be triaged again; the
    convention is what to match. Both `{ … }` and `= [ … ]` bodies, because a
    computed var and an array literal are both used for this.
    """
    out, i = [], 0
    marks = sorted(
        [m.start() for m in re.finditer(r"#Preview\b|:\s*PreviewProvider\b", text)]
        + [m.start() for m in re.finditer(
            r"(?m)^\s*(?:private\s+|fileprivate\s+|internal\s+|static\s+)*(?:let|var|func)\s+(?:preview|sample)\w*\b", text)]
    )
    for start in marks:
        if start < i:
            continue
        # A `{ … }` body or an `= [ … ]` one. The bracket only counts when an `=`
        # comes first, because a TYPE ANNOTATION has brackets too: matching the
        # nearest `[` in `static var sampleVerified: [ChainNode] {` strips
        # `[ChainNode]` and leaves the whole fixture behind, which is what the
        # first version of this did.
        brace = text.find("{", start)
        eq = text.find("=", start)
        bracket = text.find("[", eq) if 0 <= eq < (brace if brace >= 0 else len(text)) else -1
        candidates = [(pos, ch) for pos, ch in ((brace, "{"), (bracket, "[")) if pos >= 0]
        if not candidates:
            continue
        open_at, opener = min(candidates)
        closer = "}" if opener == "{" else "]"
        depth, j = 0, open_at
        while j < len(text):
            if text[j] == opener:
                depth += 1
            elif text[j] == closer:
                depth -= 1
                if depth == 0:
                    break
            j += 1
        # back up to the declaration's own start so its signature goes too
        decl = text.rfind("\n", 0, start) + 1
        out.append(text[i:decl])
        i = j + 1
    out.append(text[i:])
    return "".join(out)


def swift_copy(ios, path, sha):
    """(strings, symbols) a Swift file showed at the sha the port recorded."""
    text = git(ios, "show", f"{sha}:{path}")
    if not text:
        return None, None
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    text = strip_previews(text)
    strings = set()
    for m in UI_STRING.finditer(text):
        raw = next(g for g in m.groups() if g is not None)
        strings.update(fragments(raw))
    return strings, set(SYMBOL.findall(text))


def kotlin_index():
    """Every string literal in the Kotlin production sources, normalised."""
    blob = []
    for p in ROOT.glob("*/src/main/kotlin/**/*.kt"):
        text = p.read_text(encoding="utf-8", errors="replace")
        text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
        blob.append(text)
    joined = "\n".join(blob)
    lits = set()
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', joined):
        lits.add(norm(m.group(1)))
    # Also index the raw text, so a sentence split across a template or a
    # concatenation is still findable as a run of words.
    return lits, norm(joined)


def load_baseline():
    """Known divergences, as a RATCHET: a new one fails, a recorded one is tracked.

    Not a waiver list. Every row carries a class and a reason, because a bare
    path-and-string with neither is how structure_audit_allow.json grew the
    blanket entry that hid the web chip vanishing. `platform` and `template` rows
    are correct as they stand; `todo` rows are real work that is being tracked
    rather than excused, and --gate stays red-free only because they are known,
    not because they are fine.
    """
    p = ROOT / "tools" / "copy_baseline.json"
    if not p.exists():
        return {}, {}
    data = json.loads(p.read_text())
    known = {(r["file"], r["ios"]): r for r in data.get("rows", [])}
    return known, data


def load_symbol_map():
    """Only the rows that actually PAIR something. An `unknown` row does not count as mapped.

    symbol_map.json's own contract says so — "`unknown` means nobody has checked, and the audit REPORTS those
    rather than passing them; a wrong pairing is worse than an admitted gap" — and the loader did not honour it,
    because until 2026-09-21 the map contained no unknown rows for it to get wrong. Adding eight admitted gaps
    would have silenced all eight, turning the honest record into the silence it was written to prevent.
    """
    p = ROOT / "tools" / "symbol_map.json"
    if not p.exists():
        return {}
    return {r["sf"]: r for r in json.loads(p.read_text()).get("rows", [])
            if str(r.get("android", "")).strip() not in ("", "unknown")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ios", default="../teemoon-ios")
    ap.add_argument("--gate", action="store_true")
    ap.add_argument("--symbols", action="store_true")
    ap.add_argument("--strings", action="store_true")
    ap.add_argument("--since", help="calibration: audit each entry against this sha instead of its own")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--suggest", action="store_true",
                    help="show the closest Kotlin string, so a REWORDING reads differently from an ABSENCE")
    args = ap.parse_args()

    ios = (ROOT / args.ios).resolve() if not Path(args.ios).is_absolute() else Path(args.ios)
    if not (ios / ".git").exists():
        print(f"(no iOS checkout at {ios} — copy audit skipped)")
        return 0

    m = json.loads((ROOT / "ports.json").read_text())
    lits, blob = kotlin_index()
    symbol_map = load_symbol_map()
    known, _ = load_baseline()
    both = not (args.symbols or args.strings)

    missing_strings, missing_symbols, checked = [], [], 0
    seen_source = set()
    new_strings = []
    for e in m["entries"]:
        kotlin = e.get("kotlin", "")
        if "/test/" in kotlin or "/androidTest/" in kotlin:
            continue
        for src in e.get("sources", []):
            path, sha = src.get("path"), args.since or src.get("sha")
            if not path or not sha or not path.startswith("teemoon/Views/"):
                continue
            # Previews and the design tour carry invented sample content — a
            # dragonball question, a flour comparison — which is not product copy
            # and which closure already waives. Auditing it would bury the real
            # findings under fixtures nobody ships.
            if any(x in path for x in ("Preview", "DesignTour")):
                continue
            if (path, sha) in seen_source:
                continue          # one Swift file, several Kotlin entries: audit it once
            seen_source.add((path, sha))
            strings, symbols = swift_copy(ios, path, sha)
            if strings is None:
                continue
            checked += 1
            if args.strings or both:
                for s in sorted(strings):
                    if s not in lits and s not in blob:
                        missing_strings.append((path, kotlin, s))
                        if (path, s) not in known:
                            new_strings.append((path, s))
            if args.symbols or both:
                for sym in sorted(symbols):
                    if sym not in symbol_map:
                        missing_symbols.append((path, kotlin, sym))

    if not args.quiet:
        print(f"copy audit: {checked} Swift view file(s), each at the sha its port records")
        by_file = {}
        for path, kotlin, s in missing_strings:
            by_file.setdefault(path, []).append(s)
        for path in sorted(by_file):
            print(f"\n  {path}")
            for s in by_file[path]:
                print(f'    iOS      "{s}"')
                if args.suggest:
                    # A REWORDING and an ABSENCE need different fixes, and the flat
                    # list could not tell them apart: "no provider configured" is
                    # reworded to "no place configured", while "restart download" is
                    # simply not there. The closest Kotlin string says which.
                    near = difflib.get_close_matches(s, lits, n=1, cutoff=0.6)
                    print(f'    Android  "{near[0]}"' if near else "    Android  (nothing close — absent)")
        for path, kotlin, sym in missing_symbols:
            print(f"  UNMAPPED GLYPH  {sym:<34} {path}")
        if not missing_strings and not missing_symbols:
            print("  clean — every ported sentence and glyph is accounted for")
        else:
            todo = sum(1 for (p_, s_) in {(a, c) for a, _, c in missing_strings}
                       if known.get((p_, s_), {}).get("class", "todo") == "todo")
            print(f"\n{len(missing_strings)} missing string(s) — {todo} untriaged, "
                  f"{len(missing_strings) - todo} recorded as correct (platform/template/sample). "
                  f"{len(missing_symbols)} unmapped glyph(s).")
        if new_strings:
            print("\n  NEW since the baseline — these are not in tools/copy_baseline.json:")
            for path, s in new_strings:
                print(f'    {path}\n      "{s}"')
    # The gate fires on a NEW divergence only. The known set is an agenda, and
    # failing on it every run would train everyone to ignore the command.
    return 1 if (args.gate and (new_strings or missing_symbols)) else 0


if __name__ == "__main__":
    sys.exit(main())
