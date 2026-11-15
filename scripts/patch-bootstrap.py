#!/usr/bin/env python3
"""
Patch official Termux bootstrap zips for the Xyz.IDE rebrand.

The app ships with applicationId "com.xyzide" instead of "com.termux" so it
can be installed next to the real Termux app. Termux bootstrap binaries and
scripts contain the absolute prefix "/data/data/com.termux" compiled/written
in, so every byte occurrence of "com.termux" inside the bootstrap zips is
rewritten to "com.xyzide". The replacement is deliberately the SAME LENGTH
(10 chars), so no ELF offsets, string tables or length fields can break.

Usage:
    python3 scripts/patch-bootstrap.py <dir-with-official-zips> <output-dir>

The official zips are verified against the SHA-256 checksums published in
app/build.gradle before patching. After patching, the script prints the new
SHA-256 of each zip — paste those into the downloadBootstraps task in
app/build.gradle and place the patched zips in app/src/main/cpp/ so the
build uses them instead of re-downloading the official ones.
"""
import gzip
import hashlib
import io
import os
import sys
import zipfile

OLD = b"com.termux"
NEW = b"com.xyzide"
assert len(OLD) == len(NEW), "replacement must stay the same length"

OFFICIAL_SHA256 = {
    "aarch64": "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
    "arm": "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
    "i686": "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
    "x86_64": "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a",
}


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def patch_bytes(data):
    """Replace OLD with NEW; also look inside single gzip members (man pages)."""
    count = data.count(OLD)
    if count:
        data = data.replace(OLD, NEW)
    return data, count


def patch_entry(name, data):
    if name.endswith(".gz"):
        try:
            raw = gzip.decompress(data)
            raw, count = patch_bytes(raw)
            if count:
                buf = io.BytesIO()
                with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0) as g:
                    g.write(raw)
                return buf.getvalue(), count
            return data, 0
        except Exception:
            return data, 0
    return patch_bytes(data)


def patch_zip(src, dst):
    total = 0
    files_changed = 0
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if not info.is_dir():
                data, count = patch_entry(info.filename, data)
                if count:
                    total += count
                    files_changed += 1
            # Preserve name, timestamp and unix permissions of every entry.
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new_info.external_attr = info.external_attr
            new_info.create_system = info.create_system
            new_info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)
    return total, files_changed


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for arch, expected in OFFICIAL_SHA256.items():
        src = os.path.join(src_dir, f"bootstrap-{arch}.zip")
        actual = sha256_of(src)
        if actual != expected:
            print(f"{arch}: OFFICIAL CHECKSUM MISMATCH (got {actual}) — refusing to patch")
            sys.exit(1)
        dst = os.path.join(out_dir, f"bootstrap-{arch}.zip")
        total, files_changed = patch_zip(src, dst)
        print(f"{arch}: patched {total} occurrences in {files_changed} files")
        print(f"{arch}: new sha256 = {sha256_of(dst)}")


if __name__ == "__main__":
    main()
