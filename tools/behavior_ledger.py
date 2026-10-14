#!/usr/bin/env python3
"""The behavior ledger: the non-view half of parity, as a set difference a script computes.

    tools/behavior_ledger.py            # report
    tools/behavior_ledger.py --gate     # exit 1 on any violation  (wired into :app:check)

WHY THIS EXISTS. The 2026-09-20 audit found eight shipping defects. The ported
policy modules were clean — GenerationEngine.kt mirrors the Swift decision for
decision. Every single defect was a CALL SITE in app/: a retry path that skipped
the send gate, a gate argument hardcoded `false`, one tool-round ceiling for
every provider, and a duplicate SendPrep the app called instead of the mapped
one. A unit test cannot see any of that — the tested function was correct and
simply was not reached. See the 2026-09 port review in the development journal.

So this checks WIRING, not logic. Six sections, all pure text analysis over
Kotlin sources, no build, no device, ~100 ms:

  uniqueDefinition   a policy type is declared exactly once           (B4)
  gatedCalls         an entry point is only reached through its gate  (B1)
  noLiteralArgs      a policy input is not a hardcoded constant       (B2)
  exhaustiveWhen     every outcome of an enum is actually handled     (B2)
  sourceContains     a decided platform semantic, pinned verbatim     (B5)
  sourceAbsent       a fix whose Android form is an ABSENCE           (SharedURLCache)
  reachableScreens   a screen composable is reached from production     (the unrouted picker)
  networkDestinations  no URL host outside the §4 list                (CONTRIBUTING.md)

Config lives in ports.json under "behaviorLedger", so it inherits the sha
discipline the porting rules already enforce and there is one manifest, not two.

Kotlin is not parsed. These are deliberately shallow textual rules: a shallow
rule that runs on every build beats a precise one that never does. Each carries
a `why` that is printed on failure, because a wiring gate that fails without
saying which bug it is re-litigating gets deleted.
"""
import argparse, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN_KT = sorted(p for p in ROOT.glob("*/src/main/kotlin/**/*.kt"))


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(s):
    """Block and line comments out, string literals kept (hosts and copy live there)."""
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", s)


def body_of(src, start):
    """Text of the brace-delimited block that opens at or after `start`."""
    i = src.find("{", start)
    if i < 0:
        return ""
    depth, j = 0, i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    return src[i:]


def enclosing_fun(src, pos):
    """(name, body) of the INNERMOST `fun` whose brace-matched body contains pos.

    Innermost matters: a local `fun attemptRetry()` sits inside the `ChatScreen`
    composable, and ChatScreen's body contains the gate call regardless. Asking
    only "does some enclosing function mention the gate" would pass the very
    wiring B1 was — the ungated call lived in a lambda directly under ChatScreen,
    a few lines from an attemptSend that does gate. Hence `onlyWithin` below.
    """
    best = (None, "")
    for m in re.finditer(r"\bfun\s+([A-Za-z_][\w]*)\s*\(", src):
        if m.start() > pos:
            break
        i = src.find("{", m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if i < pos < j:
            best = (m.group(1), src[i:j + 1])   # later declaration = nested deeper
    return best


def named_arg(call_text, param):
    """The raw text of `param = …` in an argument list, or None."""
    m = re.search(rf"\b{re.escape(param)}\s*=\s*", call_text)
    if not m:
        return None
    depth, out = 0, []
    for ch in call_text[m.end():]:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            if depth == 0:
                break
            depth -= 1
        elif ch == "," and depth == 0:
            break
        out.append(ch)
    return "".join(out).strip()


LITERAL = re.compile(r"^(true|false|null|-?\d+(\.\d+)?|\"[^\"]*\"|emptyList\(\)|emptyMap\(\))$")


def check_unique(cfg, fail):
    for row in cfg:
        pat = re.compile(rf"(?m)^\s*(?:\w+\s+)*(?:enum\s+class|data\s+class|class|object|fun)\s+{re.escape(row['symbol'])}\b")
        hits = [p for p in MAIN_KT if pat.search(strip_comments(read(p)))]
        allowed = [ROOT / a for a in row["declaredIn"]]
        extra = [p for p in hits if p not in allowed]
        missing = [a for a in allowed if a not in hits]
        for p in extra:
            fail("uniqueDefinition", f"{row['symbol']} is ALSO declared in {p.relative_to(ROOT)}", row.get("why", ""))
        for a in missing:
            fail("uniqueDefinition", f"{row['symbol']} is NOT declared in {a.relative_to(ROOT)} any more", row.get("why", ""))


def check_gated(cfg, fail):
    """Two conditions, and both are needed.

    `onlyWithin` — every call must sit in one named function, so the call cannot
    drift out to a lambda that happens to be nested under something that gates.
    `gate` — that function must actually consult the policy, so the funnel is not
    merely a rename. Either alone passes the original B1 wiring.
    """
    for row in cfg:
        p = ROOT / row["file"]
        src = strip_comments(read(p))
        hits = list(re.finditer(re.escape(row["callee"]), src))
        if not hits:
            fail("gatedCalls", f"{row['file']}: no call to {row['callee']} found (moved or renamed?)", row.get("why", ""))
        for m in hits:
            name, body = enclosing_fun(src, m.start())
            if name != row["onlyWithin"]:
                where = f"`{name}`" if name else "top level"
                fail("gatedCalls", f"{row['file']}: {row['callee']} is called from {where}, not from `{row['onlyWithin']}`", row.get("why", ""))
            elif row["gate"] not in body:
                fail("gatedCalls", f"{row['file']}: `{name}` reaches {row['callee']} without calling {row['gate']}", row.get("why", ""))


def check_no_literal(cfg, fail):
    for row in cfg:
        p = ROOT / row["file"]
        src = strip_comments(read(p))
        seen = 0
        for m in re.finditer(re.escape(row["call"]), src):
            call_text = body_of(src.replace("(", "{", 1) if False else src, m.end() - 1) or ""
            # argument list, brace-matched on parens
            depth, j, start = 0, src.find("(", m.end() - 1), None
            if j < 0:
                continue
            start = j
            while j < len(src):
                if src[j] == "(":
                    depth += 1
                elif src[j] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            call_text = src[start + 1:j]
            seen += 1
            val = named_arg(call_text, row["param"])
            if val is None:
                fail("noLiteralArgs", f"{row['file']}: {row['call']} does not pass `{row['param']}` by name", row.get("why", ""))
            elif LITERAL.match(val):
                fail("noLiteralArgs", f"{row['file']}: {row['call']} passes `{row['param']} = {val}` — a constant, not state", row.get("why", ""))
        if seen == 0:
            fail("noLiteralArgs", f"{row['file']}: no call to {row['call']} found (moved or renamed?)", row.get("why", ""))


def check_when(cfg, fail):
    for row in cfg:
        decl = next((p for p in MAIN_KT if re.search(rf"(?m)^\s*enum\s+class\s+{re.escape(row['enum'])}\b", strip_comments(read(p)))), None)
        if decl is None:
            fail("exhaustiveWhen", f"enum {row['enum']} not found", row.get("why", ""))
            continue
        src = strip_comments(read(decl))
        body = body_of(src, src.index(f"enum class {row['enum']}"))
        constants = [c.strip() for c in re.split(r"[,;]", body.strip("{}").split("companion")[0]) if re.fullmatch(r"[A-Z][A-Z0-9_]*", c.strip())]
        handled = strip_comments(read(ROOT / row["file"]))
        for c in constants:
            if c not in row.get("exempt", []) and not re.search(rf"\b(?:{re.escape(row['enum'])}\.)?{c}\s*(,|->)", handled):
                fail("exhaustiveWhen", f"{row['file']}: {row['enum']}.{c} is computed but never handled", row.get("why", ""))


def check_contains(cfg, fail):
    """A decided constant, pinned verbatim.

    For choices that are neither a call-site shape nor a literal argument — a
    resolved platform semantic someone had to pick. Textual on purpose: the
    point is that changing it is a decision, so tripping the gate and reading
    the `why` is the whole feature.

    Matched against the code with comments out: a commented-out call is gone, and a substring
    match passed it (the local audit of 5af7d25 commented out `Clipboard.onProcessStart(this)`).
    """
    for row in cfg:
        src = strip_comments(read(ROOT / row["file"]))
        for needle in row["mustContain"]:
            if needle not in src:
                fail("sourceContains", f"{row['file']}: `{needle}` is gone", row.get("why", ""))


def check_absent(cfg, fail):
    """A pattern that must appear NOWHERE.

    The inverse of sourceContains, for a fix whose Android form is an absence
    rather than a line of code — there is nothing to point at, so the only way
    to hold it is to assert the thing that would break it never appears.
    """
    for row in cfg:
        # A bare string here iterates as CHARACTERS and matches everything —
        # it reported 1143 violations on "." before this guard.
        if isinstance(row["mustNotContain"], str):
            raise SystemExit("behavior_ledger: sourceAbsent.mustNotContain must be a list, not a string")
        # `except`: the one file a rule is about (a factory that MUST contain the thing nothing else may).
        allowed = set(row.get("except", []))
        for path in sorted(ROOT.glob(row["glob"])):
            if str(path.relative_to(ROOT)) in allowed:
                continue
            text = strip_comments(read(path))
            for needle in row["mustNotContain"]:
                if needle in text:
                    fail("sourceAbsent", f"{path.relative_to(ROOT)}: contains `{needle}`", row.get("why", ""))


def check_reachable(cfg, fail):
    """Every screen composable is REACHED from somewhere in production.

    The third bug of 2026-09-21 was a screen — CloudKeyProviderPickerScreen —
    written, unit tested and routed from nothing, so "add cloud key" still
    opened the blank form it was built to replace. Nothing could report it: the
    composable compiled, its tests passed by calling it directly, and the
    ports map counted it as ported.

    The check is deliberately weak: SOME production reference outside the
    declaration itself. Not "is in MainActivity's route table" — ModelDetailScreen
    is legitimately nested inside ModelBrowserScreen and is not a route, and a
    rule that demanded a route would have been wrong about it on the first run.
    A weak rule that is right is worth more than a strong one that needs
    exemptions, because the exemption list is where the next unreachable screen
    would hide.

    A test-only reference does not count. That is the whole point: the picker
    had one.
    """
    for row in cfg:
        files = sorted(ROOT.glob(row["glob"]))
        decl = re.compile(rf"(?m)^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:private\s+|internal\s+)?fun\s+({row['suffixPattern']})\s*\(")
        sources = {p: strip_comments(read(p)) for p in files}
        for path, text in sources.items():
            for m in decl.finditer(text):
                name = m.group(1)
                if name in row.get("exempt", []):
                    continue
                used = False
                for other, other_text in sources.items():
                    for hit in re.finditer(rf"\b{re.escape(name)}\s*\(", other_text):
                        # its own declaration is not a use of it
                        if other is path and hit.start() == m.start(1):
                            continue
                        used = True
                        break
                    if used:
                        break
                if not used:
                    fail("reachableScreens", f"{path.relative_to(ROOT)}: {name} is declared and never reached from production code", row.get("why", ""))


def check_hosts(cfg, fail):
    allowed = set(cfg["allowed"])
    for p in MAIN_KT:
        if any(str(p.relative_to(ROOT)).startswith(x) for x in cfg.get("exemptPaths", [])):
            continue
        for m in re.finditer(r"https?://([A-Za-z0-9._-]+)", read(p)):
            host = m.group(1)
            if host not in allowed:
                fail("networkDestinations", f"{p.relative_to(ROOT)}: {host}", cfg["why"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gate", action="store_true", help="exit 1 on any violation")
    args = ap.parse_args()

    cfg = json.loads((ROOT / "ports.json").read_text()).get("behaviorLedger")
    if not cfg:
        print("FAIL: ports.json has no behaviorLedger section")
        return 2

    violations = []

    def fail(section, msg, why):
        violations.append((section, msg, why))

    check_unique(cfg.get("uniqueDefinition", []), fail)
    check_gated(cfg.get("gatedCalls", []), fail)
    check_no_literal(cfg.get("noLiteralArgs", []), fail)
    check_when(cfg.get("exhaustiveWhen", []), fail)
    check_contains(cfg.get("sourceContains", []), fail)
    check_absent(cfg.get("sourceAbsent", []), fail)
    check_reachable(cfg.get("reachableScreens", []), fail)
    if "networkDestinations" in cfg:
        check_hosts(cfg["networkDestinations"], fail)

    counts = {k: len(cfg.get(k, [])) for k in ("uniqueDefinition", "gatedCalls", "noLiteralArgs", "exhaustiveWhen", "sourceContains", "sourceAbsent", "reachableScreens")}
    checked = sum(counts.values()) + len(cfg.get("networkDestinations", {}).get("allowed", []))
    if not violations:
        print(f"behavior ledger: {checked} rules, {len(MAIN_KT)} Kotlin sources — clean")
        return 0

    last = None
    for section, msg, why in violations:
        if section != last:
            print(f"\n{section}:")
            last = section
        print(f"  {msg}")
        print(f"      why: {why}")
    print(f"\n{len(violations)} violation(s) over {checked} rules.")
    return 1 if args.gate else 0


if __name__ == "__main__":
    raise SystemExit(main())
