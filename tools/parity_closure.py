#!/usr/bin/env python3
"""Closure and drift over the iOS source tree: is every Swift file accounted for,
and has anything we ported moved since?

    tools/parity_closure.py --ios ../teemoon-ios              # both, as a report
    tools/parity_closure.py --ios ../teemoon-ios --closure    # unaccounted files only
    tools/parity_closure.py --ios ../teemoon-ios --drift      # moved sources only
    tools/parity_closure.py --ios ../teemoon-ios --agenda <kotlin-path>
    tools/parity_closure.py --ios ../teemoon-ios --deps <module|swift-path>
    tools/parity_closure.py --ios ../teemoon-ios --tests     # the TEST target's closure

This replaces tools/check_ports_drift.py (deleted 2026-09-20), which answered only half
the question.
Drift asks "has a file I ALREADY PORTED moved?" — so a Swift file that was never
ported is invisible to it, by construction. On 2026-09-20 that was seven real
surfaces, ChatComposer.swift among them: 173 lines, the most-used screen in the
app, with no ports.json row at all. Nothing in the repo could have said so.

Closure is the other half: iterate the iOS tree, not the map. Every production
Swift file must join a ports.json entry, or an explicitly dated waiver in the
map's `closureWaivers`. Unaccounted is a FAILURE, not a silence.

Rule 1 from the old checker is kept verbatim because it is the subtle one: a
renamed or deleted path makes `git log` return NOTHING, which reads identically
to "no drift". Existence at HEAD is asserted first and a missing path is a hard
error. (It was already correct, and exiting 1 — it simply was never run. That is
why --agenda exists: a one-line state report that is cheap to run at the start of
any work on a module, instead of relying on someone remembering to.)

--tests is the same question asked of `teemoonTests/` and `teemoonUITests/`, and it
was added on 2026-09-21 because nothing here had ever asked it. CONTRIBUTING.md says
"tests move with code", the source closure never checked, and the first run found
55 of 146 unit-test files and 52 of 54 UI-test files unaccounted — including
SchemaMigrationFixtureTests.swift, against a repo where `MigrationTestHelper`
appears nowhere and the loudest rule in CONTRIBUTING.md is about not losing the user's
threads.

It reports; it does not gate. Most of the gap is structural rather than owed — a
live test needs a key, a Mac test needs a Mac, a benchmark measures a chip this
repo cannot reach — and those carry dated `testWaivers` rows with a reason, the
same discipline as `closureWaivers`. What is left is an agenda.

Needs the iOS checkout. Absent, every mode says so and exits 0 — it is a report
over another repo, never a build gate. The offline gate is tools/behavior_ledger.py.
"""
import argparse, json, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# The app target only. Vendor/ is third-party (Textual, whose Android answer is the
# allowlisted markdown renderer), Playgrounds/ is scratch, and the test targets are
# tracked per-entry as `tests`, not as surface to port. Widen with --root when a new
# target appears — the default is deliberately the thing a user can reach.
DEFAULT_ROOT = "teemoon/"
SKIP_PREFIXES = ("teemoon/Vendor/",)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout.strip()


def load_map():
    return json.loads((ROOT / "ports.json").read_text())


def ios_sources(ios, root=DEFAULT_ROOT):
    out = git(ios, "ls-tree", "-r", "--name-only", "HEAD")
    return sorted(p for p in out.splitlines()
                  if p.endswith(".swift") and p.startswith(root) and not p.startswith(SKIP_PREFIXES))


def mapped_paths(m):
    """Every Swift path the map claims, from `sources` and from `tests`."""
    seen = {}
    for e in m["entries"]:
        for key in ("sources", "tests"):
            for s in e.get(key, []):
                if s.get("path"):
                    seen.setdefault(s["path"], []).append(e["kotlin"])
    return seen


def check_closure(m, ios, root=DEFAULT_ROOT, quiet=False):
    """Three ways a Swift file can be accounted for, and they are not the same claim.

    `mapped`    — there is Kotlin for it, named in an entry.
    `waived`    — it is not Android surface AT ALL (the Mac target, an App Intent, SwiftUI previews).
    `diverged`  — Android reaches the same end by different means, and the row says which.

    Divergences used to be invisible here: `acceptedDivergences` already held
    DesignTour, OnboardingStyle and PlatformAssets on 2026-09-08, and all three
    still printed as UNACCOUNTED because only `closureWaivers` was consulted. A
    decision recorded in the map and ignored by the tool that reports on the map
    is worse than no decision — it gets made twice.
    """
    waivers = {w["path"]: w for w in m.get("closureWaivers", {}).get("rows", [])}
    diverged_rows = {d["path"]: d for d in m.get("acceptedDivergences", []) if d.get("path")}
    mapped = mapped_paths(m)
    unaccounted, waived, diverged = [], [], []
    files = ios_sources(ios, root)
    for p in files:
        if p in mapped:
            continue
        if p in waivers:
            waived.append(p)
        elif p in diverged_rows:
            diverged.append(p)
        else:
            unaccounted.append(p)
    if not quiet:
        print(f"\nCLOSURE over {ios}/{root}")
        print(f"  swift files: {len(files)}   mapped here: {len(files) - len(unaccounted) - len(waived) - len(diverged)}   "
              f"waived: {len(waived)}   diverged: {len(diverged)}   UNACCOUNTED: {len(unaccounted)}")
        for p in unaccounted:
            print(f"  UNACCOUNTED  {p}")
        for p in waived:
            w = waivers[p]
            print(f"  waived       {p}  — {w.get('why','?')} ({w.get('decided','?')})")
        for p in diverged:
            d = diverged_rows[p]
            print(f"  diverged     {p}  — {d.get('why','?')} ({d.get('decided','?')})")
        if not unaccounted:
            print("  closed — every Swift file is mapped, waived, or an accepted divergence")
        # A PARTIAL entry is mapped, so it never shows as unaccounted above — and that is how the background half of
        # LocalModelDownloadSession sat from 2026-09-10 ("NOT ported … tracked separately", tracked nowhere) until a
        # user's download died on the Pixel (2026-10-02). Every partial entry prints, with its note, every run.
        partial = [e for e in m["entries"] if e.get("status") == "partial"]
        print(f"\n  PARTIAL entries: {len(partial)} — mapped, but the port says it is not finished")
        for e in partial:
            notes = "; ".join(s.get("note", "") for s in e.get("sources", []) if s.get("note"))
            print(f"  PARTIAL      {e.get('kotlin')}  — {(e.get('note') or notes)[:220]}")
    return unaccounted


def check_headers(ios, quiet=False):
    """Every Swift file a Kotlin `// ports:` header cites still EXISTS at iOS HEAD.

    The map and the file headers are two records of the same lineage, and only one of them was ever checked.
    `--drift` reads ports.json, so a header could cite a deleted file indefinitely and nothing would say so — which
    it did: AddEditProviderScreen.kt named `AddEditProviderView+Sections.swift` and `+Actions.swift`, and
    TrustLadderViews.kt named `TrustLadderKnownCode.swift`, all three deleted upstream on 2026-08-30 — two days
    BEFORE the 2026-09-01 baseline those same headers pin to. They never existed at the sha they claimed, and a
    reader following them found nothing.

    A header is the first thing anyone reads about a ported file. A citation that leads nowhere is worse than none,
    because it looks like provenance.
    """
    tracked = set(git(ios, "ls-tree", "-r", "--name-only", "HEAD").split())
    bad = []
    for k in sorted(ROOT.glob("*/src/*/kotlin/**/*.kt")):
        head = []
        for line in k.read_text(encoding="utf-8", errors="replace").split("\n"):
            if line.startswith("package "):
                break
            head.append(line)
        for p in sorted(set(re.findall(r"(teemoon[\w]*/[\w+./-]+\.swift)", "\n".join(head)))):
            if p not in tracked:
                bad.append((k.relative_to(ROOT), p))
    if not quiet:
        print(f"\nHEADER CITATIONS   kotlin files checked: {len(list(ROOT.glob('*/src/*/kotlin/**/*.kt')))}   dangling: {len(bad)}")
        for k, p in bad:
            print(f"  GONE   {k} cites {p}, which is not at iOS HEAD")
        if not bad:
            print("  every `// ports:` citation resolves at HEAD")
    return bad


TEST_ROOTS = ("teemoonTests/", "teemoonUITests/")


def check_tests(m, ios, quiet=False):
    """Closure over the iOS TEST targets: is every Swift test file ported, or waived with a reason?

    Deliberately separate from the source closure and deliberately not a gate.
    A source file that is Android surface must be ported; a test file may be
    unportable for reasons that have nothing to do with this port — it needs a
    vendor key, a Mac, or a specific chip. Those are waived. Everything else is
    work, and the point of this mode is that the work is visible.
    """
    mapped = set()
    for e in m["entries"]:
        for key in ("sources", "tests"):
            for s in e.get(key, []):
                if s.get("path", "").startswith(TEST_ROOTS):
                    mapped.add(s["path"])
    # A Kotlin test may also name its Swift origin only in its own `// ports:` header.
    for p in ROOT.glob("*/src/*[tT]est*/kotlin/**/*.kt"):
        for hit in re.finditer(r"(teemoonU?I?Tests/[\w+./-]+\.swift)", p.read_text(encoding="utf-8", errors="replace")):
            mapped.add(hit.group(1))

    waivers = {w["path"]: w for w in m.get("testWaivers", {}).get("rows", [])}
    out = git(ios, "ls-tree", "-r", "--name-only", "HEAD")
    files = sorted(p for p in out.splitlines() if p.endswith(".swift") and p.startswith(TEST_ROOTS))

    owed, waived, done = [], [], []
    for p in files:
        (done if p in mapped else waived if p in waivers else owed).append(p)
    if not quiet:
        print(f"\nTEST CLOSURE over {ios}/{{{', '.join(r.rstrip('/') for r in TEST_ROOTS)}}}")
        print(f"  swift test files: {len(files)}   ported: {len(done)}   waived: {len(waived)}   OWED: {len(owed)}")
        by_reason = {}
        for p in waived:
            by_reason.setdefault(waivers[p].get("why", "?"), []).append(p)
        for why, ps in sorted(by_reason.items(), key=lambda kv: -len(kv[1])):
            print(f"  waived  {len(ps):>3}  — {why}")
        for p in owed:
            print(f"  OWED         {p}")
        if not owed:
            print("  closed — every Swift test is ported or waived")
    return owed


def check_drift(m, ios, quiet=False, only=None):
    tag = m.get("followedTag")
    if tag and not git(ios, "tag", "-l", tag):
        print(f"FAIL: {tag} does not exist in {ios}")
        return None, None
    errors, drifted, checked = [], [], 0
    for entry in m["entries"]:
        if only and entry.get("kotlin") != only:
            continue
        for src in entry.get("sources", []):
            path, sha = src.get("path"), src.get("sha")
            if not path or not sha:
                continue
            checked += 1
            # RULE 1: assert the path still exists at HEAD. git log on a
            # renamed/deleted path is empty, and empty means "no drift".
            if not git(ios, "ls-tree", "-r", "--name-only", "HEAD", path):
                errors.append(f"{path} — GONE at HEAD (renamed or deleted); ported by {entry['kotlin']}")
                continue
            log = git(ios, "log", "--oneline", f"{sha}..HEAD", "--", path)
            if log:
                drifted.append((path, entry["kotlin"], log.splitlines()))
    if not quiet:
        print(f"\nDRIFT   baseline {tag} ({m.get('baselineCommit')})   sources checked: {checked}")
        for e in errors:
            print(f"  ERROR   {e}")
        for path, kotlin, commits in drifted:
            print(f"  DRIFTED {path}  →  {kotlin}")
            for c in commits[:5]:
                print(f"            {c}")
            if len(commits) > 5:
                print(f"            … and {len(commits) - 5} more")
        if not errors and not drifted:
            print("  clean — nothing ported has moved since the baseline")
    return errors, drifted


def agenda(m, ios, kotlin):
    """One module's state, printed before work on it starts."""
    errors, drifted = check_drift(m, ios, quiet=True, only=kotlin)
    if errors is None:
        return
    for e in errors:
        print(f"    ERROR   {e}")
    for path, _, commits in drifted:
        print(f"    drifted {path} — {len(commits)} commit(s) since the port")
        for c in commits[:3]:
            print(f"              {c}")



DECL = re.compile(r"^\s*(?:public |internal |private |fileprivate |final |indirect )*"
                  r"(?:struct|class|enum|actor|protocol)\s+([A-Z]\w*)", re.M)
TYPEALIAS = re.compile(r"^\s*(?:public |internal )?typealias\s+([A-Z]\w*)", re.M)


def decl_index(ios, root=DEFAULT_ROOT):
    """Swift type name → the file that declares it.

    Only names declared inside the app target, which is exactly the filter we
    want: anything from Foundation or the stdlib never enters the index, so the
    reference scan below needs no keyword blocklist to maintain.
    """
    index = {}
    for path in ios_sources(ios, root):
        text = git(ios, "show", f"HEAD:{path}")
        for m in list(DECL.finditer(text)) + list(TYPEALIAS.finditer(text)):
            index.setdefault(m.group(1), path)
    return index


def dependencies_of(ios, path, index):
    """The other app-target Swift files this one names.

    A file's OWN declarations are excluded by name, not just by owner. Nested
    type names collide across files — several adapters declare a `Pricing` and a
    `Model` — and the index keeps one owner per name, so without this the
    OpenRouter adapter "depended on" FireworksAdapter and ModelOffer purely
    because they happened to declare a `Pricing` first. A false dependency in a
    tool built to find real ones is worse than no tool.
    """
    text = git(ios, "show", f"HEAD:{path}")
    text = re.sub(r"//[^\n]*", "", text)          # comments name things they do not use
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
    own = {m.group(1) for m in DECL.finditer(text)} | {m.group(1) for m in TYPEALIAS.finditer(text)}
    deps = set()
    for token in set(re.findall(r"\b[A-Z]\w*\b", text)) - own:
        owner = index.get(token)
        if owner and owner != path:
            deps.add(owner)
    return sorted(deps)


def check_deps(m, ios, target, root=DEFAULT_ROOT):
    """Drift on what a module is built ON, not just on the module itself.

    This is the check that was missing. A post-baseline addition to a file that
    is already `status: complete` is invisible to closure — it is not an
    unmapped file — and shows only as drift on THAT file, which nobody reads
    when they are porting a different one. It cost three false starts in a row
    on 2026-09-20: EndpointDirectory needed ConfidentialSession, LiveCatalogStore
    needed Provider.openRouter, OpenRouterAdapter needed six KnownModel fields
    three later commits added. Every one was discoverable before opening a file.
    """
    files = ios_sources(ios, root)
    path = target if target in files else next(
        (f for f in files if Path(f).stem.lower() == target.lower()), None)
    if path is None:
        print(f"  (no Swift file named {target} under {root} — a new module with no upstream source?)")
        return []
    index = decl_index(ios, root)
    deps = dependencies_of(ios, path, index)
    mapped = mapped_paths(m)
    # A Swift file can be mapped by SEVERAL Kotlin entries at DIFFERENT shas — one
    # may track it for a value type and another for a policy. Reporting whichever
    # came first in the file is arbitrary and was wrong: Provider.swift read as 9
    # commits behind while the entry that matters here was 2. Report the best-ported
    # claim, and say when the entries disagree so the difference is visible rather
    # than averaged away.
    shas_of = {}
    for e in m["entries"]:
        for src in e.get("sources", []):
            if src.get("path") and src.get("sha"):
                shas_of.setdefault(src["path"], set()).add(src["sha"])
    blockers = []
    print(f"\nDEPENDENCIES of {path} — {len(deps)} app-target file(s)")
    for d in deps:
        shas = shas_of.get(d)
        if not shas:
            state = "UNMAPPED" if d not in mapped else "mapped, no sha"
        else:
            counts = {sha: len(git(ios, "log", "--oneline", f"{sha}..HEAD", "--", d).splitlines())
                      for sha in shas}
            best = min(counts, key=counts.get)
            n = counts[best]
            spread = "" if len(set(counts.values())) == 1 else \
                f" (another entry tracks it {max(counts.values())} behind)"
            state = f"DRIFTED {n} commit(s) since {best}{spread}" if n else None
        if state:
            blockers.append((d, state))
            print(f"  {state:<34} {d}")
    if not blockers:
        print("  all current — nothing this module is built on has moved")
    return blockers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ios", default="../teemoon-ios")
    ap.add_argument("--root", default=DEFAULT_ROOT, help="iOS subtree to demand closure over")
    ap.add_argument("--closure", action="store_true")
    ap.add_argument("--drift", action="store_true")
    ap.add_argument("--tests", action="store_true", help="closure over the iOS TEST targets (reports; never gates)")
    ap.add_argument("--agenda", metavar="KOTLIN_PATH")
    ap.add_argument("--deps", metavar="MODULE_OR_SWIFT_PATH",
                    help="drift on what this module is built ON")
    ap.add_argument("--gate", action="store_true", help="exit 1 on unaccounted files or GONE paths")
    args = ap.parse_args()

    ios = (ROOT / args.ios).resolve() if not Path(args.ios).is_absolute() else Path(args.ios)
    if not (ios / ".git").exists():
        # A report over another repo. Its absence is not a build failure.
        if not args.agenda:
            print(f"(no iOS checkout at {ios} — closure and drift skipped)")
        return 0

    m = load_map()
    if args.deps:
        blockers = check_deps(m, ios, args.deps, args.root)
        return 1 if (args.gate and blockers) else 0
    if args.agenda:
        agenda(m, ios, args.agenda)
        return 0

    if args.tests:
        check_tests(m, ios)
        check_headers(ios)
        return 0   # a REPORT: the gap is an agenda, not a build failure

    both = not (args.closure or args.drift)
    unaccounted, errors = [], []
    if args.closure or both:
        unaccounted = check_closure(m, ios, args.root)
    if args.drift or both:
        errors, _ = check_drift(m, ios)
        errors = errors or []
    if both:
        check_tests(m, ios, quiet=False)
        check_headers(ios)
    dangling = check_headers(ios, quiet=True) if (args.closure or both) else []
    return 1 if (args.gate and (unaccounted or errors or dangling)) else 0


if __name__ == "__main__":
    sys.exit(main())
