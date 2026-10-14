#!/usr/bin/env python3
"""Vendor the conformance kit from teemoon-ios (§7, §13.6).

    python3 tools/sync_conformance.py --ios ../teemoon-ios

Copies every `conformance/*.json` the iOS repo emitted into `conformance/vendor/`,
refuses a kit whose files disagree on `kitVersion`, refuses a file whose
`sourceOfTruth` is not teemoon-ios, and writes the stamp into ports.json.
The kit is minted in Swift, never here: this script only moves bytes.
"""
import argparse, json, pathlib, shutil, sys

ap = argparse.ArgumentParser()
ap.add_argument("--ios", required=True, help="path to the teemoon-ios checkout")
args = ap.parse_args()

src = pathlib.Path(args.ios) / "conformance"
dst = pathlib.Path(__file__).resolve().parent.parent / "conformance" / "vendor"
files = sorted(src.glob("*.json"))
if not files:
    sys.exit(f"no vectors under {src} — run `make conformance` in teemoon-ios first")

versions, classes = set(), []
for f in files:
    doc = json.loads(f.read_text())
    if doc.get("sourceOfTruth") != "teemoon-ios":
        sys.exit(f"{f.name}: sourceOfTruth is {doc.get('sourceOfTruth')!r}, refusing — vectors come from iOS only")
    versions.add(doc["kitVersion"]); classes.append((doc["fixtureClass"], f))
if len(versions) != 1:
    sys.exit(f"kit files disagree on kitVersion: {sorted(versions)} — regenerate the whole kit")
version = versions.pop()

dst.mkdir(parents=True, exist_ok=True)
for cls, f in classes:
    shutil.copyfile(f, dst / f.name)
    print(f"vendored {cls:12s} ← {f.name} ({f.stat().st_size} bytes)")

ports = pathlib.Path(__file__).resolve().parent.parent / "ports.json"
p = json.loads(ports.read_text())
p["kitVersion"] = version
ports.write_text(json.dumps(p, indent=2, ensure_ascii=False) + "\n")
print(f"kitVersion {version} → ports.json")
