from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from nayti_model_tools.pack_format import (
    HEADER,
    MAGIC,
    PackFormatError,
    assemble_pack,
    canonical_json,
    generate_key_pair,
    inspect_pack,
    key_id,
    parse_manifest,
)


class PackFormatTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.private_key = self.root / "private.pem"
        self.public_key = self.root / "public.pem"
        generate_key_pair(self.private_key, self.public_key)
        self.sources = {
            "models/encoder.ort": self._payload("encoder.bin", b"encoder" * 100),
            "provenance/notices.json": self._payload("notices.json", b"{}\n"),
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_round_trip_is_deterministic_and_exact(self) -> None:
        manifest = self._manifest()
        first = self.root / "first.naytipack"
        second = self.root / "second.naytipack"

        inspection = assemble_pack(
            manifest,
            self.sources,
            self.private_key,
            self.public_key,
            first,
        )
        assemble_pack(
            manifest,
            self.sources,
            self.private_key,
            self.public_key,
            second,
        )

        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual("nayti-alpha", inspection.pack_id)
        self.assertEqual(2, inspection.file_count)
        self.assertEqual(sum(path.stat().st_size for path in self.sources.values()), inspection.payload_bytes)
        self.assertEqual(inspection, inspect_pack(first, self.public_key))

    def test_tampered_payload_and_trailing_bytes_are_rejected(self) -> None:
        pack = self.root / "pack.naytipack"
        assemble_pack(
            self._manifest(),
            self.sources,
            self.private_key,
            self.public_key,
            pack,
        )
        original = bytearray(pack.read_bytes())
        original[-1] ^= 1
        tampered = self.root / "tampered.naytipack"
        tampered.write_bytes(original)
        with self.assertRaisesRegex(PackFormatError, "SHA-256"):
            inspect_pack(tampered, self.public_key)

        trailing = self.root / "trailing.naytipack"
        trailing.write_bytes(pack.read_bytes() + b"hidden")
        with self.assertRaisesRegex(PackFormatError, "trailing"):
            inspect_pack(trailing, self.public_key)

    def test_manifest_signature_and_key_allowlist_are_enforced(self) -> None:
        pack = self.root / "pack.naytipack"
        assemble_pack(
            self._manifest(),
            self.sources,
            self.private_key,
            self.public_key,
            pack,
        )
        raw = bytearray(pack.read_bytes())
        manifest_length = HEADER.unpack(raw[: HEADER.size])[1]
        raw[HEADER.size + manifest_length] ^= 1
        bad_signature = self.root / "bad-signature.naytipack"
        bad_signature.write_bytes(raw)
        with self.assertRaisesRegex(PackFormatError, "signature"):
            inspect_pack(bad_signature, self.public_key)

        other_private = self.root / "other-private.pem"
        other_public = self.root / "other-public.pem"
        generate_key_pair(other_private, other_public)
        with self.assertRaisesRegex(PackFormatError, "keyId"):
            inspect_pack(pack, other_public)

    def test_noncanonical_duplicate_and_unsafe_paths_are_rejected(self) -> None:
        manifest = self._manifest()
        pretty = json.dumps(manifest, indent=2).encode()
        with self.assertRaisesRegex(PackFormatError, "canonical"):
            parse_manifest(pretty)

        duplicate = canonical_json(manifest).replace(
            b'"schemaVersion":1',
            b'"schemaVersion":1,"schemaVersion":1',
        )
        with self.assertRaisesRegex(PackFormatError, "duplicate"):
            parse_manifest(duplicate)

        for unsafe in ("../model.ort", "/model.ort", "models\\model.ort", "Models/model.ort"):
            changed = self._manifest()
            changed["files"][0]["path"] = unsafe
            changed["files"] = sorted(changed["files"], key=lambda item: item["path"])
            with self.assertRaises(PackFormatError):
                parse_manifest(canonical_json(changed))

    def test_invalid_header_and_truncation_are_rejected(self) -> None:
        truncated = self.root / "truncated.naytipack"
        truncated.write_bytes(HEADER.pack(MAGIC, 10, 64, 0) + b"{}")
        with self.assertRaisesRegex(PackFormatError, "truncated"):
            inspect_pack(truncated, self.public_key)

        reserved = self.root / "reserved.naytipack"
        reserved.write_bytes(HEADER.pack(MAGIC, 1, 64, 1) + b"\n" + bytes(64))
        with self.assertRaisesRegex(PackFormatError, "reserved"):
            inspect_pack(reserved, self.public_key)

    def _manifest(self) -> dict:
        files = [
            {
                "path": path,
                "role": "model" if path.endswith(".ort") else "provenance",
                "length": source.stat().st_size,
                "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            }
            for path, source in sorted(self.sources.items())
        ]
        return {
            "schemaVersion": 1,
            "packId": "nayti-alpha",
            "packVersion": "0.1.0-alpha.1",
            "keyId": key_id(self.public_key),
            "compatibility": {"minAppVersionCode": 1, "maxAppVersionCode": 1},
            "runtime": {"format": "ORT", "version": "1.27.0"},
            "components": [{"componentId": "encoder", "artifactPath": "models/encoder.ort"}],
            "files": files,
            "provenance": {"profileSha256": "0" * 64},
        }

    def _payload(self, name: str, value: bytes) -> Path:
        path = self.root / name
        path.write_bytes(value)
        return path


if __name__ == "__main__":
    unittest.main()
