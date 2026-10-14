#!/usr/bin/env python3
"""Is this APK the one the source produces?  verify_release.py <released.apk> <rebuilt.apk>

Compares every zip entry of the two APKs byte for byte, ignoring only what a signature adds: the META-INF
signature files (MANIFEST.MF, *.SF, *.RSA/*.EC/*.DSA) and the APK Signing Block, which is not an entry.
Nothing else is excused — a differing resource table, dex file or native library is a FAIL with its name.
Exit 0 on a match, 1 on any difference, 2 on a usage error.  Prints the sha256 of both files so a reader can
quote them. This is the whole reproducibility check; the signed release and an unsigned rebuild must agree.
"""
import hashlib, sys, zipfile

SIG_SUFFIXES = (".MF", ".SF", ".RSA", ".EC", ".DSA")

def entries(path):
    z = zipfile.ZipFile(path)
    return {i.filename: z.read(i.filename) for i in z.infolist()
            if not (i.filename.startswith("META-INF/") and i.filename.upper().endswith(SIG_SUFFIXES))}

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()

def main(argv):
    if len(argv) != 3: print(__doc__); return 2
    released, rebuilt = argv[1], argv[2]
    a, b = entries(released), entries(rebuilt)
    print(f"released: {sha256(released)}  {released}\nrebuilt:  {sha256(rebuilt)}  {rebuilt}")
    only_a, only_b = sorted(set(a) - set(b)), sorted(set(b) - set(a))
    differ = sorted(n for n in set(a) & set(b) if a[n] != b[n])
    for n in only_a: print(f"  only in released: {n}")
    for n in only_b: print(f"  only in rebuilt:  {n}")
    for n in differ: print(f"  differs: {n} ({len(a[n])} vs {len(b[n])} bytes)")
    ok = not (only_a or only_b or differ)
    print(f"{'MATCH' if ok else 'MISMATCH'}: {len(a)} entries compared, signature files set aside")
    return 0 if ok else 1

if __name__ == "__main__": sys.exit(main(sys.argv))
