#!/usr/bin/env python3
"""Merge the aapt2-linked resource APK with classes.dex into one zip whose
stored (uncompressed) entries are 4-byte aligned — a pure-python zipalign.

Android 11+ refuses to install a targetSdk 30+ APK whose resources.arsc is
compressed or unaligned, and the classic zipalign binary comes from the SDK
build-tools we cannot download in this environment.

Usage: pack_apk.py <linked_resources.apk> <classes.dex> <output.apk>
"""

import struct
import sys
import zipfile

ALIGN = 4
ANDROID_ALIGNMENT_EXTRA_ID = 0xD935  # same field zipalign -p writes


def aligned_extra(header_end_offset: int) -> bytes:
    """Build an extra field that pads the entry's data to a 4-byte boundary."""
    misalign = header_end_offset % ALIGN
    if misalign == 0:
        return b""
    pad = (ALIGN - misalign) % ALIGN
    # extra layout: id(2) + size(2) + alignment(2) + zero padding
    # total length must be >= 6 and congruent to `pad` mod 4
    total = 6
    while total % ALIGN != pad:
        total += 1
    payload_len = total - 4
    return struct.pack("<HHH", ANDROID_ALIGNMENT_EXTRA_ID, payload_len, ALIGN) + b"\x00" * (
        payload_len - 2
    )


def add_entry(zout: zipfile.ZipFile, name: str, data: bytes, compress_type: int) -> None:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = compress_type
    info.external_attr = 0o644 << 16
    if compress_type == zipfile.ZIP_STORED:
        # data begins right after the 30-byte local header + name + extra;
        # aligned_extra() returns padding whose length makes that a multiple of 4
        header_end = zout.fp.tell() + 30 + len(name.encode())
        info.extra = aligned_extra(header_end)
    zout.writestr(info, data)


def main() -> None:
    linked_apk, dex_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

    with zipfile.ZipFile(linked_apk) as zin, zipfile.ZipFile(
        out_path, "w", zipfile.ZIP_DEFLATED
    ) as zout:
        # resources.arsc first and always stored, per platform requirements
        names = sorted(zin.namelist(), key=lambda n: (n != "resources.arsc", n))
        for name in names:
            if name.endswith("/"):
                continue
            data = zin.read(name)
            stored = name == "resources.arsc"
            add_entry(
                zout, name, data, zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            )
        with open(dex_path, "rb") as f:
            add_entry(zout, "classes.dex", f.read(), zipfile.ZIP_DEFLATED)

    # verify alignment of stored entries
    with zipfile.ZipFile(out_path) as z, open(out_path, "rb") as raw:
        for info in z.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            raw.seek(info.header_offset)
            header = raw.read(30)
            name_len, extra_len = struct.unpack("<HH", header[26:30])
            data_offset = info.header_offset + 30 + name_len + extra_len
            if data_offset % ALIGN != 0:
                sys.exit(f"ALIGNMENT FAILED for {info.filename} at {data_offset}")
    print(f"packed {out_path} (stored entries 4-byte aligned)")


if __name__ == "__main__":
    main()
