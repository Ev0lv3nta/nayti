from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT_PATH = Path(__file__).parents[1] / "verify_native_page_size.py"
SPEC = importlib.util.spec_from_file_location("verify_native_page_size", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def elf64_with_alignments(*alignments: int) -> bytes:
    header_size = 64
    program_header_size = 56
    payload = bytearray(header_size + program_header_size * len(alignments))
    payload[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", payload, 32, header_size)
    struct.pack_into("<H", payload, 54, program_header_size)
    struct.pack_into("<H", payload, 56, len(alignments))
    for index, alignment in enumerate(alignments):
        offset = header_size + index * program_header_size
        struct.pack_into("<I", payload, offset, 1)
        struct.pack_into("<Q", payload, offset + 48, alignment)
    return bytes(payload)


class LoadSegmentAlignmentsTest(unittest.TestCase):
    def test_reads_every_load_segment_alignment(self) -> None:
        self.assertEqual(
            MODULE.load_segment_alignments(elf64_with_alignments(0x4000, 0x10000)),
            [0x4000, 0x10000],
        )

    def test_rejects_truncated_program_header_table(self) -> None:
        payload = elf64_with_alignments(0x4000)[:-1]
        with self.assertRaisesRegex(MODULE.ElfFormatError, "truncated program header table"):
            MODULE.load_segment_alignments(payload)

    def test_rejects_non_elf_input(self) -> None:
        with self.assertRaisesRegex(MODULE.ElfFormatError, "missing ELF header"):
            MODULE.load_segment_alignments(b"not an elf")

    def test_apk_check_accepts_16_kib_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(
                    "lib/arm64-v8a/libexample.so",
                    elf64_with_alignments(0x4000),
                )
            self.assertEqual(MODULE.verify_apk(apk, "arm64-v8a", 0x4000), [])

    def test_apk_check_rejects_4_kib_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(
                    "lib/arm64-v8a/libexample.so",
                    elf64_with_alignments(0x1000),
                )
            failures = MODULE.verify_apk(apk, "arm64-v8a", 0x4000)
            self.assertEqual(len(failures), 1)
            self.assertIn("expected every segment >= 0x4000", failures[0])

    def test_aar_check_reads_jni_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            aar = Path(directory) / "test.aar"
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr(
                    "jni/arm64-v8a/libexample.so",
                    elf64_with_alignments(0x4000),
                )
            self.assertEqual(
                MODULE.verify_archive(
                    aar,
                    "arm64-v8a",
                    0x4000,
                    native_root="jni",
                ),
                [],
            )

    def test_zip_alignment_accepts_stored_page_aligned_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "aligned.apk"
            name = "lib/arm64-v8a/libexample.so"
            base_offset = 30 + len(name.encode("utf-8"))
            padding = (-base_offset) % 0x4000
            self.assertGreaterEqual(padding, 4)
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_STORED
            entry.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(entry, elf64_with_alignments(0x4000))
            self.assertEqual(MODULE.verify_zip_alignment(apk, "arm64-v8a", 0x4000), [])

    def test_zip_alignment_rejects_compressed_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "compressed.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(
                    "lib/arm64-v8a/libexample.so",
                    elf64_with_alignments(0x4000),
                )
            failures = MODULE.verify_zip_alignment(apk, "arm64-v8a", 0x4000)
            self.assertEqual(1, len(failures))
            self.assertIn("compressed", failures[0])


if __name__ == "__main__":
    unittest.main()
