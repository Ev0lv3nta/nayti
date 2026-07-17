#!/usr/bin/env python3
"""Verify that native libraries in an APK can be loaded on 16 KiB Android."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path


ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50


class ElfFormatError(ValueError):
    """Raised when a packaged native library is not a valid supported ELF file."""


def load_segment_alignments(payload: bytes) -> list[int]:
    if len(payload) < 16 or payload[:4] != ELF_MAGIC:
        raise ElfFormatError("missing ELF header")

    elf_class = payload[4]
    byte_order = payload[5]
    if byte_order == 1:
        endian = "<"
    elif byte_order == 2:
        endian = ">"
    else:
        raise ElfFormatError(f"unsupported byte order {byte_order}")

    if elf_class == 1:
        header_size = 52
        phoff_offset = 28
        phentsize_offset = 42
        phnum_offset = 44
        phalign_offset = 28
        phalign_format = "I"
    elif elf_class == 2:
        header_size = 64
        phoff_offset = 32
        phentsize_offset = 54
        phnum_offset = 56
        phalign_offset = 48
        phalign_format = "Q"
    else:
        raise ElfFormatError(f"unsupported ELF class {elf_class}")

    if len(payload) < header_size:
        raise ElfFormatError("truncated ELF header")

    phoff_format = "I" if elf_class == 1 else "Q"
    phoff = struct.unpack_from(f"{endian}{phoff_format}", payload, phoff_offset)[0]
    phentsize = struct.unpack_from(f"{endian}H", payload, phentsize_offset)[0]
    phnum = struct.unpack_from(f"{endian}H", payload, phnum_offset)[0]
    minimum_phentsize = 32 if elf_class == 1 else 56
    if phentsize < minimum_phentsize:
        raise ElfFormatError(f"invalid program header size {phentsize}")
    if phoff + phentsize * phnum > len(payload):
        raise ElfFormatError("truncated program header table")

    alignments: list[int] = []
    for index in range(phnum):
        entry_offset = phoff + index * phentsize
        segment_type = struct.unpack_from(f"{endian}I", payload, entry_offset)[0]
        if segment_type == PT_LOAD:
            alignment = struct.unpack_from(
                f"{endian}{phalign_format}", payload, entry_offset + phalign_offset
            )[0]
            alignments.append(alignment)

    if not alignments:
        raise ElfFormatError("no loadable segments")
    return alignments


def verify_archive(
    archive_path: Path,
    abi: str,
    minimum_alignment: int,
    native_root: str = "lib",
) -> list[str]:
    prefix = f"{native_root}/{abi}/"
    failures: list[str] = []
    checked = 0

    try:
        archive = zipfile.ZipFile(archive_path)
    except (OSError, zipfile.BadZipFile) as error:
        return [f"{archive_path}: cannot read archive: {error}"]

    with archive:
        library_names = sorted(
            name
            for name in archive.namelist()
            if name.startswith(prefix) and name.endswith(".so")
        )
        if not library_names:
            return [
                f"{archive_path}: no native libraries found under "
                f"{native_root}/{abi}"
            ]

        for name in library_names:
            checked += 1
            try:
                alignments = load_segment_alignments(archive.read(name))
            except (KeyError, OSError, ElfFormatError) as error:
                failures.append(f"{name}: {error}")
                continue

            too_small = [value for value in alignments if value < minimum_alignment]
            rendered = ", ".join(f"0x{value:x}" for value in alignments)
            if too_small:
                failures.append(
                    f"{name}: LOAD alignment [{rendered}], expected every segment "
                    f">= 0x{minimum_alignment:x}"
                )
            else:
                print(f"{name}: LOAD alignment [{rendered}]")

    print(f"Checked {checked} native libraries for ABI {abi}.")
    return failures


def verify_apk(apk_path: Path, abi: str, minimum_alignment: int) -> list[str]:
    """Backward-compatible APK entry point used by existing checks and tests."""
    return verify_archive(apk_path, abi, minimum_alignment, native_root="lib")


def verify_zip_alignment(
    archive_path: Path,
    abi: str,
    minimum_alignment: int,
) -> list[str]:
    prefix = f"lib/{abi}/"
    failures: list[str] = []
    try:
        archive = zipfile.ZipFile(archive_path)
        raw_archive = archive_path.open("rb")
    except (OSError, zipfile.BadZipFile) as error:
        return [f"{archive_path}: cannot inspect ZIP alignment: {error}"]

    with archive, raw_archive:
        libraries = sorted(
            (
                entry
                for entry in archive.infolist()
                if entry.filename.startswith(prefix) and entry.filename.endswith(".so")
            ),
            key=lambda entry: entry.filename,
        )
        if not libraries:
            return [f"{archive_path}: no native libraries found under {prefix}"]
        for entry in libraries:
            if entry.compress_type != zipfile.ZIP_STORED:
                failures.append(f"{entry.filename}: native library is compressed")
                continue
            raw_archive.seek(entry.header_offset)
            header = raw_archive.read(30)
            if len(header) != 30:
                failures.append(f"{entry.filename}: truncated local ZIP header")
                continue
            signature = struct.unpack_from("<I", header)[0]
            if signature != LOCAL_FILE_HEADER_SIGNATURE:
                failures.append(f"{entry.filename}: invalid local ZIP header")
                continue
            filename_length, extra_length = struct.unpack_from("<HH", header, 26)
            data_offset = entry.header_offset + 30 + filename_length + extra_length
            if data_offset % minimum_alignment:
                failures.append(
                    f"{entry.filename}: ZIP data offset 0x{data_offset:x} is not aligned "
                    f"to 0x{minimum_alignment:x}",
                )
            else:
                print(f"{entry.filename}: ZIP data offset 0x{data_offset:x}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--page-size", type=int, default=16 * 1024)
    parser.add_argument(
        "--native-root",
        choices=("lib", "jni"),
        default="lib",
        help="Native-library root inside an APK (lib) or AAR (jni).",
    )
    parser.add_argument(
        "--check-zip-alignment",
        action="store_true",
        help="Also require uncompressed, page-aligned APK native entries.",
    )
    args = parser.parse_args()

    failures = verify_archive(
        args.apk,
        args.abi,
        args.page_size,
        native_root=args.native_root,
    )
    if args.check_zip_alignment:
        if args.native_root != "lib":
            parser.error("--check-zip-alignment only applies to APK lib entries")
        failures.extend(verify_zip_alignment(args.apk, args.abi, args.page_size))
    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
