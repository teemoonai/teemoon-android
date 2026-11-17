#!/usr/bin/env python3
"""Offline checks for check_instrumentation.py and the two device scripts that call it: a clean run passes, and every
shape a crashed or restarted instrumentation leaves behind FAILS the class — even when its XML says failures="0".

    python3 -I tools/test_check_instrumentation.py        (or: python3 -I -m pytest tools/test_check_instrumentation.py)

The fixtures (tools/test_fixtures/instrumentation/) are what a run leaves in
app/build/outputs/androidTest-results/connected/debug/, plus its Gradle output as gradle.log; all were "run" from
FixtureTest.kt there, which has three tests. The script cases run tools/device_suite.sh and tools/journey_goldens.sh
for real, in a scratch tree, with a fake ./gradlew that lays a fixture down and exits 0 and a fake adb that does
nothing: no device, no Gradle.
"""
import os, pathlib, shutil, subprocess, sys, tempfile, unittest

TOOLS = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import check_instrumentation as ci  # noqa: E402

FIX = TOOLS / "test_fixtures" / "instrumentation"
SOURCE = FIX / "FixtureTest.kt"
# fixture → a phrase its finding must carry, so each shape is failed for its own reason
CRASHES = {
    "short_count": "1 tests reported, the class has 3",
    "tests_zero": 'tests="0"',
    "gradle_log_crash": "Process crashed",
    "am_instrument_crash": "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
    "utp_failed_to_complete": "failed to run to completion",
    "restarted": "bTwo reported 2 times",
    "no_xml": "no TEST-*.xml",
    "pb_crash": "test-result.pb",
    "exit_code": "test-result-exit-code.txt: 1",
    "truncated_xml": "does not parse",
    "wrong_names": "never reported: bTwo",
}


def findings(case):
    d = FIX / case
    log = d / "gradle.log"
    return ci.check(d, 3, ci.expected_names(SOURCE), [log] if log.exists() else [])


class CheckerTest(unittest.TestCase):
    def test_the_fixture_class_has_three_tests(self):
        self.assertEqual(["aOne", "bTwo", "cThree"], ci.expected_names(SOURCE))

    def test_a_clean_run_passes(self):
        self.assertEqual([], findings("clean"))

    def test_every_crash_shape_fails_for_its_own_reason(self):
        for case, phrase in CRASHES.items():
            with self.subTest(case=case):
                f = findings(case)
                self.assertTrue(f, f"{case}: passed — a hidden crash")
                self.assertTrue(any(phrase in x for x in f), f"{case}: {f}")

    def test_every_fixture_directory_is_a_case(self):
        dirs = {p.name for p in FIX.iterdir() if p.is_dir()}
        self.assertEqual(dirs, set(CRASHES) | {"clean"})

    def test_one_method_run_expects_one_test(self):
        self.assertEqual(["bTwo"], ci.expected_names(SOURCE, "bTwo"))
        self.assertTrue(ci.check(FIX / "clean", 1, ["bTwo"]))   # three reported where one was asked for

    def test_the_command_line_exits_1_on_a_crash_and_0_when_clean(self):
        run = lambda case: subprocess.run([sys.executable, "-I", str(TOOLS / "check_instrumentation.py"), str(FIX / case),
                                           "--source", str(SOURCE)], capture_output=True, text=True)
        self.assertEqual(0, run("clean").returncode)
        r = run("short_count")
        self.assertEqual(1, r.returncode)
        self.assertIn("CRASH:", r.stdout)

    def test_a_failed_gradle_status_fails(self):
        self.assertIn("gradle exited 1", ci.check(FIX / "clean", 3, gradle_status=1))


FAKE_GRADLEW = """#!/usr/bin/env bash
# lays down $FAKE_FIXTURE as the run's result and "succeeds", the way a crashed run can
r=app/build/outputs/androidTest-results/connected/debug; mkdir -p "$r"
for f in "$FAKE_FIXTURE"/*; do [ "$(basename "$f")" = gradle.log ] || cp "$f" "$r/"; done
[ -f "$FAKE_FIXTURE/gradle.log" ] && cat "$FAKE_FIXTURE/gradle.log"
exit 0
"""


class ScriptTest(unittest.TestCase):
    """The scripts themselves, end to end, in a scratch tree: their exit status is the verdict a person acts on."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        (self.tmp / "tools").mkdir()
        for f in ("device_suite.sh", "journey_goldens.sh", "check_instrumentation.py"):
            if (TOOLS / f).exists():
                shutil.copy(TOOLS / f, self.tmp / "tools" / f)
        src = self.tmp / "app/src/androidTest/kotlin/ai/teemoon/app"; src.mkdir(parents=True)
        for cls in ("FixtureTest", "UserJourneysTest", "GroundedTurnTest"):
            shutil.copy(SOURCE, src / f"{cls}.kt")
        (self.tmp / "gradlew").write_text(FAKE_GRADLEW); (self.tmp / "gradlew").chmod(0o755)
        (self.tmp / "bin").mkdir(); (self.tmp / "bin" / "adb").write_text("#!/bin/sh\nexit 0\n"); (self.tmp / "bin" / "adb").chmod(0o755)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, case, *argv):
        env = dict(os.environ, PATH=f"{self.tmp / 'bin'}:{os.environ['PATH']}", FAKE_FIXTURE=str(FIX / case))
        env.pop("APP_ID", None); env.pop("GRADLE_EXTRA", None); env.pop("OUT", None)
        return subprocess.run(["bash", *argv], cwd=self.tmp, env=env, capture_output=True, text=True, timeout=120)

    def test_device_suite_passes_a_clean_class(self):
        r = self.run_script("clean", "tools/device_suite.sh", "FixtureTest")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_device_suite_fails_every_crash_shape(self):
        for case in CRASHES:
            with self.subTest(case=case):
                r = self.run_script(case, "tools/device_suite.sh", "FixtureTest")
                self.assertNotEqual(0, r.returncode, f"device_suite.sh passed {case}:\n{r.stdout}{r.stderr}")

    def test_device_suite_fails_a_crashed_method_run(self):
        # --record's shape: Class#method, where the class's other tests are not expected
        r = self.run_script("tests_zero", "tools/device_suite.sh", "FixtureTest#bTwo")
        self.assertNotEqual(0, r.returncode, r.stdout)

    def test_journey_goldens_passes_a_clean_run(self):
        r = self.run_script("clean", "tools/journey_goldens.sh", "verify")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)

    def test_journey_goldens_fails_every_crash_shape(self):
        for case in CRASHES:
            with self.subTest(case=case):
                r = self.run_script(case, "tools/journey_goldens.sh", "verify")
                self.assertNotEqual(0, r.returncode, f"journey_goldens.sh passed {case}:\n{r.stdout}{r.stderr}")


if __name__ == "__main__":
    unittest.main()
