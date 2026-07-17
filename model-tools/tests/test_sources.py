from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from nayti_model_tools.sources import load_manifest, verify_sources


MANIFEST = Path(__file__).parents[1] / "manifests" / "sources.v1.json"


class SourceManifestTest(unittest.TestCase):
    def test_real_manifest_is_valid_and_pinned(self) -> None:
        manifest = load_manifest(MANIFEST)
        self.assertEqual(4, len(manifest.components))
        self.assertEqual(1_699_992_137, manifest.total_bytes)
        self.assertTrue(all(len(component.revision) == 40 for component in manifest.components))
        self.assertTrue(all(component.license_spdx == "Apache-2.0" for component in manifest.components))

    def test_manifest_rejects_parent_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = json.loads(MANIFEST.read_text(encoding="utf-8"))
            raw["components"][0]["files"][0]["path"] = "../weights"
            bad_manifest = root / "bad.json"
            bad_manifest.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unsafe source path"):
                load_manifest(bad_manifest)

    def test_verify_rejects_wrong_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"known source"
            manifest_path = _write_fixture_manifest(root, payload, "0" * 64)
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                verify_sources(load_manifest(manifest_path), root / "sources")

    def test_verify_accepts_exact_regular_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"known source"
            digest = hashlib.sha256(payload).hexdigest()
            manifest_path = _write_fixture_manifest(root, payload, digest)
            verify_sources(load_manifest(manifest_path), root / "sources")


def _write_fixture_manifest(root: Path, payload: bytes, sha256: str) -> Path:
    raw = {
        "schemaVersion": 1,
        "components": [
            {
                "componentId": "fixture",
                "repository": "owner/repository",
                "revision": "a" * 40,
                "license": {
                    "spdx": "Apache-2.0",
                    "evidenceUrl": "https://example.invalid/license",
                },
                "files": [
                    {
                        "path": "model.bin",
                        "role": "weights",
                        "length": len(payload),
                        "sha256": sha256,
                    },
                ],
            },
        ],
    }
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(raw), encoding="utf-8")
    artifact = root / "sources" / "fixture" / "model.bin"
    artifact.parent.mkdir(parents=True)
    artifact.write_bytes(payload)
    return manifest_path


if __name__ == "__main__":
    unittest.main()
