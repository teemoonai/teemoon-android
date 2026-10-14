#!/usr/bin/env python3
"""Judge a [scrolltrace] capture from the Android transcript (ScrollTrace.kt) — the same line iOS writes, so
teemoon-ios/tools/analyze_stream_trace.py reads it too. This is the short verdict: the blank metric and the
walk-away hand-off, exit 1 on either.

    python3 tools/check_scroll_trace.py scrolltrace.log [--walkaway]

  blank metric   samples where offset + visible > contentH + 250 (the composer inset puts a healthy pin ~130dp
                 past contentH, hence 250 not 50). Target 0 — the SwiftUI transcript traced ~600 per generation.
  walk-away      with --walkaway: the reader scrolled away mid-turn (intr=1); from the last generating sample to
                 the end of the trace the offset may not move more than 100dp. iOS measured 0 on the fix, 703 before.
"""
import re, sys

def load(path):
    scroll = []
    for line in open(path):
        m = re.search(r'\[scrolltrace\] t=([\d.]+) offset=([-\d.]+) contentH=([-\d.]+) visibleH=([\d.]+)(?: rows=(\d+))? gen=(\d)(?: intr=(\d))?', line)
        if m: scroll.append((float(m.group(1)), float(m.group(2)), float(m.group(3)), float(m.group(4)), int(m.group(6)), int(m.group(7) or 0)))
    return scroll

def main():
    path = sys.argv[1]; walkaway = '--walkaway' in sys.argv
    scroll = load(path)
    if not scroll: sys.exit(f"no [scrolltrace] lines in {path}")
    t0 = scroll[0][0]
    gen = [s for s in scroll if s[4] == 1]
    if not gen: sys.exit("no generation in trace — driver failed?")
    overs = [(t - t0, o + v - c) for t, o, c, v, g, i in scroll if o + v > c + 250]
    worst = max((p for _, p in overs), default=0)
    print(f"== blank metric ==  {len(scroll)} samples, {len(gen)} generating; overscrolled (offset+visible > contentH+250): {len(overs)} | worst {worst:.0f}dp past the end")
    fail = len(overs) > 0
    if walkaway:
        gen_end = max(t for t, *_ in gen)
        at_end = next(o for t, o, *_ in scroll if t == gen_end)
        after = [(t - gen_end, o) for t, o, c, v, g, i in scroll if t > gen_end]
        moved = max((abs(o - at_end) for _, o in after), default=0.0)
        intr = any(i == 1 for *_, i in gen)
        flag = "FAIL" if moved > 100 or not intr else "ok"
        print(f"== walk-away handoff ==  {flag}: interrupted={int(intr)}, viewport moved {moved:.0f}dp across the end of the turn ({len(after)} samples after)")
        fail = fail or flag == "FAIL"
    sys.exit(1 if fail else 0)

if __name__ == '__main__': main()
