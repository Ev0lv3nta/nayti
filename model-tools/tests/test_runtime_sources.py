from __future__ import annotations

import json
from pathlib import Path
import re
import unittest


MANIFEST = Path(__file__).parents[1] / "manifests" / "runtime-sources.v1.json"
BUILD_SCRIPT = Path(__file__).parents[1] / "scripts" / "build_ortx_validation.sh"
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")


class RuntimeSourcesTest(unittest.TestCase):
    def test_runtime_sources_are_exact_and_licensed(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual("v1.27.0", manifest["onnxRuntime"]["release"])
        for component in ("onnxRuntime", "onnxRuntimeExtensions"):
            source = manifest[component]
            self.assertIsNotNone(SHA_PATTERN.fullmatch(source["revision"]))
            self.assertEqual("MIT", source["license"])

        extensions = manifest["onnxRuntimeExtensions"]
        self.assertEqual("0.15.0+fe4e13f", extensions["pythonBuildVersion"])
        self.assertEqual(["pp-api", "no-opencv"], extensions["buildOptions"])

        build_script = BUILD_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(extensions["revision"], build_script)
        self.assertIn(extensions["pythonBuildVersion"], build_script)


if __name__ == "__main__":
    unittest.main()
