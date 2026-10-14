#!/usr/bin/env python3
"""Move one port pair's citation to a newer iOS sha, in ports.json AND the Kotlin file's `// ports:` header.

    python3 tools/bump_port_sha.py SWIFT_PATH KOTLIN_PATH NEW_SHA [--note "what was ported / why nothing was"]

A bump is a CLAIM: "this Kotlin file now reflects the Swift file as of NEW_SHA". Make it only after reading every
iOS commit between the old sha and the new one on that Swift file and either porting it or recording why it needs
nothing (comment-only, iOS-only). The note lands on the source entry so the next audit sees the judgement.
The header edit only rewrites `<swift path> @ <old sha>` to `@ <new sha>` where that exact pair appears.
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main() -> int:
    args = sys.argv[1:]
    note = None
    if "--note" in args:
        i = args.index("--note"); note = args[i + 1]; del args[i:i + 2]
    if len(args) != 3:
        print(__doc__); return 2
    swift, kotlin, new = args
    p = ROOT / "ports.json"
    d = json.loads(p.read_text())
    hits = [s for e in d["entries"] if e.get("kotlin") == kotlin for s in e.get("sources", []) if s.get("path") == swift]
    if not hits:
        print(f"no ports.json source {swift} on {kotlin}"); return 1
    old = {s["sha"] for s in hits}
    for s in hits:
        s["sha"] = new
        if note: s["note"] = (s.get("note", "") + ("; " if s.get("note") else "") + f"@{new}: {note}")
    p.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n")
    kf = ROOT / kotlin
    if kf.is_file():
        src = kf.read_text()
        for o in old:
            src = re.sub(re.escape(swift) + r" @ " + re.escape(o) + r"\b", f"{swift} @ {new}", src)
        kf.write_text(src)
    print(f"{swift} → {kotlin}: {', '.join(sorted(old))} → {new}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
