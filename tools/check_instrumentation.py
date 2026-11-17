#!/usr/bin/env python3
"""Fail a device class whose instrumentation crashed or restarted, whatever its result XML says.

    tools/check_instrumentation.py RESULTS_DIR (--source Foo.kt [--method m] | --expected N)
                                   [--log gradle.log ...] [--gradle-status N]

tools/device_suite.sh and tools/journey_goldens.sh call it after every Gradle call (one class, or one Class#method).
A crash in the app's process takes the instrumentation down with it (no orchestrator here: app/build.gradle.kts's
testOptions sets none), and what is left behind can still read green: a <testsuite> with failures="0" and errors="0"
whose tests="…" is short of the class's @Test count — the tests after the crash never ran, so they never failed —
or tests="0", or no XML at all. The run's own words are the other half: "Process crashed", "Instrumentation run
failed due to 'Process crashed.'", `am instrument`'s "INSTRUMENTATION_RESULT: shortMsg=Process crashed.", a test
host that restarted. Any one of these FAILS the class:

  - no TEST-*.xml under RESULTS_DIR, or one that does not parse
  - tests="0", or a count other than the expected one (short: tests never ran; long: a restart re-ran some)
  - a test name reported twice (a restarted host re-reports the test it was on)
  - expected tests missing by name (with --source)
  - a crash phrase in the XML, the UTP result (test-result.pb), or any --log text
  - test-result-exit-code.txt or --gradle-status not 0 (Gradle knew; the XML may not say)

Exit 0 and one "ok" line when clean; exit 1 with one "CRASH: …" line per finding otherwise.
"""
import argparse, pathlib, re, sys
import xml.etree.ElementTree as ET

CRASH = re.compile(
    r"process crashed"
    r"|instrumentation run failed"
    r"|test run failed to complete"
    r"|failed to run to completion"
    r"|INSTRUMENTATION_RESULT: shortMsg="
    r"|INSTRUMENTATION_(?:FAILED|ABORTED)"
    r"|INSTRUMENTATION_STATUS: Error="
    r"|test host (?:process )?(?:crashed|restarted|died)"
    r"|instrumentation (?:process )?(?:crashed|restarted|died)"
    r"|Instrumentation run failed",
    re.I)
TEST_FUN = re.compile(r"@Test\s+fun\s+`?([A-Za-z0-9_ ]+?)`?\s*\(")


def expected_names(source, method=None):
    names = TEST_FUN.findall(pathlib.Path(source).read_text(errors="replace"))
    if method:
        return [method]
    return names


def crash_lines(text, where):
    out = []
    for line in text.splitlines():
        if CRASH.search(line):
            out.append(f"{where}: {line.strip()[:200]}")
    return out


def check(results, expected, names=None, logs=(), gradle_status=None):
    """Every finding as a line; an empty list is a clean run."""
    results = pathlib.Path(results); found = []; xml_failed = False
    xmls = sorted(results.glob("TEST-*.xml")) if results.is_dir() else []
    if not xmls:
        found.append(f"no TEST-*.xml in {results} (the run left no result)")
    for f in xmls:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            found.append(f"{f.name}: does not parse ({e}) — a run cut off mid-write"); continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        if not suites:
            found.append(f"{f.name}: no <testsuite>"); continue
        cases = [c for s in suites for c in s.findall("testcase")]
        xml_failed |= any(c.find("failure") is not None or c.find("error") is not None for c in cases)
        declared = sum(int(s.get("tests", "0") or 0) for s in suites)
        if declared == 0 or not cases:
            found.append(f'{f.name}: tests="{declared}" — nothing ran')
        if declared != len(cases):
            found.append(f'{f.name}: tests="{declared}" but {len(cases)} <testcase> elements')
        if expected is not None and len(cases) != expected:
            found.append(f"{f.name}: {len(cases)} tests reported, the class has {expected} "
                         + ("(short: the rest never ran)" if len(cases) < expected else "(more: a restart re-ran some)"))
        seen = {}
        for c in cases:
            seen[c.get("name")] = seen.get(c.get("name"), 0) + 1
        for n, k in sorted(seen.items()):
            if k > 1:
                found.append(f"{f.name}: {n} reported {k} times (the test host restarted)")
        if names:
            missing = [n for n in names if n not in seen]
            if missing:
                found.append(f"{f.name}: never reported: {', '.join(missing[:8])}{' …' if len(missing) > 8 else ''}")
        found += crash_lines(f.read_text(errors="replace"), f.name)
    if results.is_dir():
        for pb in sorted(results.rglob("test-result.pb")):
            # protobuf: the platform error is stored as plain UTF-8 inside it
            found += crash_lines(pb.read_bytes().decode("utf-8", "replace"), str(pb.relative_to(results)))
        # Gradle's and UTP's verdicts count only where the XML shows no failure: a failed test is its own report, and
        # "exited 1" beside it is not a crash. Where the XML is green, a red exit is something the XML did not say.
        code = results / "test-result-exit-code.txt"
        if not xml_failed and code.is_file() and code.read_text().strip() not in ("", "0"):
            found.append(f"test-result-exit-code.txt: {code.read_text().strip()}")
    for log in logs:
        p = pathlib.Path(log)
        if p.is_file():
            found += crash_lines(p.read_text(errors="replace"), p.name)
    if not xml_failed and gradle_status not in (None, 0):
        found.append(f"gradle exited {gradle_status}")
    return found


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("results")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--source", help="the class's .kt file: expected tests are its @Test funs")
    g.add_argument("--expected", type=int)
    ap.add_argument("--method", help="one Class#method run: expect exactly that test")
    ap.add_argument("--log", action="append", default=[], help="Gradle / am instrument output (repeatable)")
    ap.add_argument("--gradle-status", type=int)
    a = ap.parse_args(argv)
    names = expected_names(a.source, a.method) if a.source else None
    if a.source and not names:
        print(f"CRASH: {a.source} declares no @Test fun — cannot say what should have run"); return 1
    expected = len(names) if names is not None else a.expected
    found = check(a.results, expected, names, a.log, a.gradle_status)
    for f in found:
        print("CRASH:", f)
    if not found:
        print(f"ok: {expected} tests reported, no crash or restart")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
