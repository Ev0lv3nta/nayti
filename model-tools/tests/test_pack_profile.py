from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from nayti_model_tools.pack_format import PackFormatError, generate_key_pair, inspect_pack
from nayti_model_tools.pack_profile import assemble_profile, load_profile


class PackProfileTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.lab = self.root / "lab"
        self.lab.mkdir()
        (self.repository / "LICENSE").write_text("license\n", encoding="utf-8")
        (self.lab / "encoder.ort").write_bytes(b"model")
        self.private_key = self.lab / "private.pem"
        self.public_key = self.lab / "public.pem"
        generate_key_pair(self.private_key, self.public_key)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_profile_assembles_exact_sources_from_separate_roots(self) -> None:
        profile = self._write_profile(self._profile())
        output = self.lab / "alpha.naytipack"

        inspection = assemble_profile(
            profile,
            self.repository,
            self.lab,
            self.private_key,
            self.public_key,
            output,
        )

        self.assertEqual("nayti-alpha", inspection.pack_id)
        self.assertEqual(2, inspection.file_count)
        self.assertEqual(inspection, inspect_pack(output, self.public_key))

    def test_unknown_fields_escape_and_missing_component_artifact_fail(self) -> None:
        unknown = self._profile()
        unknown["unexpected"] = True
        with self.assertRaises(PackFormatError):
            load_profile(self._write_profile(unknown))

        escaping = self._profile()
        escaping["files"][0]["source"] = "../private"
        with self.assertRaises(PackFormatError):
            load_profile(self._write_profile(escaping))

        missing_artifact = self._profile()
        missing_artifact["components"][0]["artifactPath"] = "models/missing.ort"
        with self.assertRaises(PackFormatError):
            load_profile(self._write_profile(missing_artifact))

    def test_repository_alpha_profile_is_well_formed(self) -> None:
        profile = load_profile(
            Path(__file__).parents[1] / "manifests" / "pack-profile.alpha1.json",
        )

        self.assertEqual("nayti-offline-search", profile.raw["packId"])
        self.assertEqual("0.1.0-alpha.1", profile.raw["packVersion"])
        self.assertEqual(7, len(profile.raw["components"]))
        self.assertEqual(39, len(profile.files))
        self.assertEqual(
            "models/siglip2_image.ort",
            profile.raw["components"][0]["artifactPath"],
        )

        alpha2 = load_profile(
            Path(__file__).parents[1] / "manifests" / "pack-profile.alpha2.json",
        )
        self.assertEqual("0.1.0-alpha.2", alpha2.raw["packVersion"])
        self.assertEqual(40, len(alpha2.files))
        self.assertEqual(
            "preprocessing/eslav-recognizer-decoder.json",
            alpha2.raw["components"][-1]["decoderPath"],
        )

    def _profile(self) -> dict:
        return {
            "schemaVersion": 1,
            "packId": "nayti-alpha",
            "packVersion": "0.1.0-alpha.1",
            "compatibility": {"minAppVersionCode": 1, "maxAppVersionCode": 1},
            "runtime": {"format": "ORT", "version": "1.27.0"},
            "components": [
                {"componentId": "encoder", "artifactPath": "models/encoder.ort"},
            ],
            "files": [
                {
                    "path": "licenses/apache-2.0.txt",
                    "role": "license",
                    "sourceRoot": "repository",
                    "source": "LICENSE",
                },
                {
                    "path": "models/encoder.ort",
                    "role": "model",
                    "sourceRoot": "lab",
                    "source": "encoder.ort",
                },
            ],
            "provenance": {"sourceManifest": "sources.v1"},
        }

    def _write_profile(self, value: dict) -> Path:
        path = self.root / "profile.json"
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
