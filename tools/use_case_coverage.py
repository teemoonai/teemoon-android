#!/usr/bin/env python3
"""Coverage of docs/USE_CASES.md: which use cases have an end-to-end test on each platform, and which named tests are gone.

    python3 tools/use_case_coverage.py                                # Android only (iOS column listed, not checked)
    python3 tools/use_case_coverage.py --ios ../teemoon-ios           # also check iOS names, at its main
    python3 tools/use_case_coverage.py --ios ../teemoon-ios --ref HEAD
    python3 tools/use_case_coverage.py --gaps                         # list every uncovered case per platform

A cell names tests as `Class#method`, comma-separated, each optionally followed by `(part)` or `(live)`. `—` is a gap;
`n/a: …` is a case that does not exist on that platform. Android tests are looked up in app/src/androidTest; iOS
tests in teemoonUITests/<Class>.swift at --ref (default `main`, read through git so the checkout's own branch does
not matter — the iOS working tree is often on a bench branch).

Exit 1 when a named test does not exist — a rename or deletion that silently uncovers a case is the failure this
exists to catch. Uncovered cases are reported, never fatal: the list is the plan, and the plan is allowed to be ahead.
"""

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "docs/USE_CASES.md"
ANDROID_TESTS = ROOT / "app/src/androidTest/kotlin/ai/teemoon/app"
ROW = re.compile(r"^\| ([A-Z]\d+) \| (.+?) \| (sim|device|live) \| (.+?) \| (.+?) \|$")
REF = re.compile(r"([A-Za-z0-9_]+)#([A-Za-z0-9_]+)( \((?:part|live)[^)]*\))?")


def rows():
    out = []
    for line in DOC.read_text().splitlines():
        m = ROW.match(line.strip())
        if m:
            out.append(dict(id=m[1], case=m[2], runs=m[3], android=m[4].strip(), ios=m[5].strip()))
    return out


def refs(cell):
    """[(class, method, partial)] for a cell; [] for a gap; None for n/a."""
    if cell.startswith("n/a"):
        return None
    return [(c, m, bool(p and "part" in p)) for c, m, p in REF.findall(cell)]


def android_source(cls):
    f = ANDROID_TESTS / f"{cls}.kt"
    return f.read_text() if f.is_file() else None


def ios_source(ios, ref, cls, cache={}):
    if cls not in cache:
        r = subprocess.run(["git", "-C", str(ios), "show", f"{ref}:teemoonUITests/{cls}.swift"], capture_output=True, text=True)
        cache[cls] = r.stdout if r.returncode == 0 else None
    return cache[cls]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ios", type=pathlib.Path, help="a teemoon-ios checkout")
    ap.add_argument("--ref", default="main", help="the iOS ref to read tests at (default main)")
    ap.add_argument("--gaps", action="store_true", help="list every uncovered case")
    a = ap.parse_args()

    cases = rows()
    if not cases:
        print(f"no rows parsed from {DOC} — the table format changed"); return 2
    missing = []
    platforms = {"android": "android"} | ({"ios": "ios"} if a.ios else {})
    tally = {p: {"full": 0, "part": 0, "none": 0, "n/a": 0} for p in ("android", "ios")}
    gaps = {p: [] for p in ("android", "ios")}
    for c in cases:
        for p in ("android", "ios"):
            r = refs(c[p])
            if r is None:
                tally[p]["n/a"] += 1; continue
            if p in platforms:
                for cls, method, _ in r:
                    src = android_source(cls) if p == "android" else ios_source(a.ios, a.ref, cls)
                    pattern = rf"fun {method}\b" if p == "android" else rf"func {method}\b"
                    if src is None or not re.search(pattern, src):
                        missing.append(f"{c['id']} {p}: {cls}#{method} does not exist")
            if not r:
                tally[p]["none"] += 1; gaps[p].append(c)
            elif all(part for _, _, part in r):
                tally[p]["part"] += 1; gaps[p].append(c)
            else:
                tally[p]["full"] += 1

    print(f"{len(cases)} use cases ({sum(c['runs'] == 'sim' for c in cases)} sim, {sum(c['runs'] == 'device' for c in cases)} device, {sum(c['runs'] == 'live' for c in cases)} live)")
    for p in ("android", "ios"):
        t = tally[p]; applicable = len(cases) - t["n/a"]
        note = "" if p in platforms else "  (names not checked: pass --ios)"
        print(f"  {p:8} covered {t['full']:3} · partly {t['part']:3} · none {t['none']:3} · n/a {t['n/a']:2}   "
              f"({100 * t['full'] // max(applicable, 1)}% of {applicable}){note}")
    both = sum(1 for c in cases if refs(c["android"]) and refs(c["ios"])
               and not all(x[2] for x in refs(c["android"])) and not all(x[2] for x in refs(c["ios"])))
    print(f"  covered on both: {both}")
    if a.gaps:
        for p in ("android", "ios"):
            print(f"\n{p} gaps:")
            for c in gaps[p]:
                print(f"  {c['id']:4} [{c['runs']}] {c['case'][:100]}" + ("  (part)" if refs(c[p]) else ""))
    if missing:
        print("\nNAMED TESTS THAT DO NOT EXIST:")
        for m in missing:
            print("  " + m)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
