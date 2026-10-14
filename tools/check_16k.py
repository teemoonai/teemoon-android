#!/usr/bin/env python3
"""16 KB page-size check for every native library in an APK (or bundle).

    python3 tools/check_16k.py                                   # the debug APK
    python3 tools/check_16k.py app/build/outputs/bundle/release/app-release.aab

Two rules, both Android's (developer.android.com/guide/practices/page-sizes):
  * every PT_LOAD segment's p_align >= 0x4000 — what Play enforces for uploads;
  * every PT_GNU_RELRO segment ENDS on a 16 KB boundary, (VirtAddr + MemSiz) % 0x4000 == 0 — what Android 17
    checks at launch ("RELRO alignment check failed") and what segfaults on a real 16 KB device.
The manifest's android:pageSizeCompat="enabled" covers the failures meanwhile; when this passes, remove it.
Exits 1 when any library fails.
"""
import pathlib, struct, sys, zipfile

PT_LOAD, PT_GNU_RELRO, PAGE = 1, 0x6474E552, 0x4000

def check(name, d):
    if d[:4] != b"\x7fELF" or d[4] != 2:
        return None
    phoff = struct.unpack_from("<Q", d, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
    problems = []
    for i in range(phnum):
        p_type, _, _, p_vaddr, _, _, p_memsz, p_align = struct.unpack_from("<IIQQQQQQ", d, phoff + i * phentsize)
        if p_type == PT_LOAD and p_align < PAGE:
            problems.append(f"LOAD p_align {p_align:#x}")
        if p_type == PT_GNU_RELRO and (p_vaddr + p_memsz) % PAGE:
            problems.append(f"RELRO ends {(p_vaddr + p_memsz) % PAGE:#x} past a 16 KB boundary")
    return problems

def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk")
    failed = 0
    with zipfile.ZipFile(path) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            if not info.filename.endswith(".so") or "arm64-v8a" not in info.filename:
                continue
            problems = check(info.filename, z.read(info.filename))
            if problems is None:
                continue
            name = info.filename.rsplit("/", 1)[-1]
            print(f"{'FAIL' if problems else 'ok  '}  {name}" + (f"  — {'; '.join(problems)}" if problems else ""))
            failed += bool(problems)
    print(f"{failed} librar{'y' if failed == 1 else 'ies'} not 16 KB compatible in {path.name}")
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
