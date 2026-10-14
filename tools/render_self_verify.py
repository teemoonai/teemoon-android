#!/usr/bin/env python3
# ports: tools/render_self_verify.py @ c9498d5 (iOS) — the template is read from the KOTLIN generator here
"""Render the self-verify script from its Kotlin generator, without the app.

`SelfVerifyScript.kt` holds the script as a Kotlin raw string (iOS: `SelfVerifyScript.swift`, a Swift raw string) with the
session's attested values interpolated into a `SESSION` dict. The app is the
only thing that normally produces the file — which meant the only tests the
script had asserted on its TEXT, and it rotted: near.ai's gateway began
requiring authentication, every run failed the gateway half, and 58 passing
string assertions had nothing to say about it (fixed 2026-08-22).

This renders the same file a device would, so the script can be RUN in a test.
Verified faithful by rendering against a shipped `teemoon_verify.py` and
diffing: byte-identical.

    python3 tools/render_self_verify.py            # to stdout, with a stub session
    python3 tools/render_self_verify.py out.py     # to a file
    python3 tools/render_self_verify.py out.py --session-from teemoon_verify.py
"""

import argparse
import pathlib
import re
import sys
import textwrap

KOTLIN = (pathlib.Path(__file__).resolve().parent.parent / "confidential" / "src" / "main" / "kotlin" / "ai" / "teemoon"
          / "confidential" / "SelfVerifyScript.kt")

#: A session shaped like a real one. Values are obviously fake; the point of a
#: rendered script in a test is the LOGIC, and the fixtures decide what the
#: "live" service answers.
STUB_SESSION = '''SESSION = {
    # captured from your teemoon session
    "captured_at":             "2026-01-01T00:00:00Z",
    "model":                   "test/model-1",
    "gateway_base":            "https://gateway.example/v1",
    "direct_base":             "https://model.example/v1",
    "gateway_signing_address": "0xaaaa000000000000000000000000000000000001",
    "gpu_signing_address":     "0xbbbb000000000000000000000000000000000002",
    "compose_hash":            "c0"  + "0" * 62,
    "gpu_node_compose_hash":   "c1"  + "0" * 62,
    "model_ed25519_pubkey":    "ed"  + "0" * 62,
    "mrtd":                    "d0"  + "0" * 94,
    "os_image_hash":           "os"  + "0" * 62,
}
'''


def template() -> str:
    """The python body of the generator, dedented, interpolations intact."""
    source = KOTLIN.read_text()
    # The Kotlin raw string starts on the same line as its shebang, and cannot
    # hold a triple quote: the generator spells each one `${q3}`.
    start = source.index('val python = """') + len('val python = """')
    end = source.index('"""', start)
    return textwrap.dedent(source[start:end]).replace("${q3}", '"""')


def render(session: str = STUB_SESSION) -> str:
    """The template with its `SESSION = {...}` block replaced."""
    body = template()
    rendered, count = re.subn(r"^SESSION = \{.*?^\}\n", lambda _: session,
                              body, flags=re.S | re.M)
    if count != 1:
        raise SystemExit("expected exactly one SESSION block in the template")
    return rendered


def session_from(path: str) -> str:
    """The `SESSION = {...}` block of an already-rendered script."""
    match = re.search(r"^SESSION = \{.*?^\}\n", pathlib.Path(path).read_text(),
                      re.S | re.M)
    if not match:
        raise SystemExit(f"no SESSION block in {path}")
    return match.group(0)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out", nargs="?", help="write here instead of stdout")
    parser.add_argument("--session-from", metavar="SCRIPT",
                        help="reuse the SESSION block of a rendered script")
    args = parser.parse_args(argv)

    session = session_from(args.session_from) if args.session_from else STUB_SESSION
    text = render(session)
    if args.out:
        pathlib.Path(args.out).write_text(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
