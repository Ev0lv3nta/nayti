from __future__ import annotations

import json
from pathlib import Path
import re
import unittest


MANIFEST = Path(__file__).parents[1] / "manifests" / "runtime-sources.v1.json"
BUILD_SCRIPT = Path(__file__).parents[1] / "scripts" / "build_ortx_validation.sh"
ANDROID_BUILD_SCRIPT = Path(__file__).parents[1] / "scripts" / "build_reduced_ort_android.sh"
ANDROID_VERIFY_SCRIPT = Path(__file__).parents[1] / "scripts" / "verify_reduced_ort_aar.sh"
ANDROID_SMOKE_SCRIPT = Path(__file__).parents[1] / "scripts" / "run_reduced_ort_android_smoke.sh"
SIGNED_PACK_SMOKE_SCRIPT = (
    Path(__file__).parents[1] / "scripts" / "run_signed_pack_android_smoke.sh"
)
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

        android_build_script = ANDROID_BUILD_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(manifest["onnxRuntime"]["revision"], android_build_script)
        self.assertIn(extensions["revision"], android_build_script)
        self.assertIn("--parallel \"$workers\"", android_build_script)
        self.assertIn("--compile_no_warning_as_error", android_build_script)
        self.assertIn("ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", android_build_script)
        self.assertIn("--minimal_build custom_ops", android_build_script)
        self.assertIn('print "ai.onnx.contrib;1;GPT2Tokenizer"', android_build_script)
        self.assertIn("OCOS_ENABLE_C_API=OFF", android_build_script)
        self.assertIn('cmp -s "$build_operator_config_tmp"', android_build_script)
        self.assertIn(
            'Release/java/build/android/outputs/aar/onnxruntime-release.aar',
            android_build_script,
        )

        android_verify_script = ANDROID_VERIFY_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("HfJsonTokenizer", android_verify_script)
        self.assertIn("OrtGetApiBase", android_verify_script)
        self.assertIn("--native-root jni", android_verify_script)
        self.assertIn("llvm-readobj", android_verify_script)

        android_smoke_script = ANDROID_SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(":model-runtime-proof:assembleDebug", android_smoke_script)
        self.assertIn("--check-zip-alignment", android_smoke_script)
        self.assertIn("NAYTI_EXPECTED_PAGE_SIZE", android_smoke_script)
        self.assertIn("OK (1 test)", android_smoke_script)

        signed_pack_smoke_script = SIGNED_PACK_SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("SignedModelPackInstrumentedTest", signed_pack_smoke_script)
        self.assertIn("expected_pack_sha256", signed_pack_smoke_script)
        self.assertIn("pack_kib * 3 + 524288", signed_pack_smoke_script)
        self.assertIn("OK (1 test)", signed_pack_smoke_script)


if __name__ == "__main__":
    unittest.main()
