from __future__ import annotations

import unittest

from nayti_model_tools.convert_ort import (
    ANDROID_KAT_SCHEMA_VERSION,
    ARTIFACTS,
    _require_runtime_versions,
)


class ConvertOrtTest(unittest.TestCase):
    def test_deployment_artifact_set_is_exact_and_unique(self) -> None:
        names = [artifact.name for artifact in ARTIFACTS]
        self.assertEqual(7, len(names))
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(4, ARTIFACTS[0].deployment_ir_version)
        self.assertTrue(all(artifact.source.endswith(".onnx") for artifact in ARTIFACTS))

    def test_runtime_versions_are_strict(self) -> None:
        _require_runtime_versions("1.27.0", "0.15.0+fe4e13f")
        with self.assertRaises(ValueError):
            _require_runtime_versions("1.26.0", "0.15.0+fe4e13f")
        with self.assertRaises(ValueError):
            _require_runtime_versions("1.27.0", "0.15.2")

    def test_android_kat_schema_is_explicit(self) -> None:
        self.assertEqual(1, ANDROID_KAT_SCHEMA_VERSION)


if __name__ == "__main__":
    unittest.main()
