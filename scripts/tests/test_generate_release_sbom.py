from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from generate_release_sbom import (  # noqa: E402
    Component,
    apply_policy,
    cyclonedx,
    parse_gradle_report,
)


class ReleaseSbomTest(unittest.TestCase):
    def test_parses_selected_versions_and_ignores_projects_and_platforms(self) -> None:
        report = """
+--- androidx.core:core:1.18.0 -> 1.19.0
|    +--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.3.21} -> 2.3.21 (c)
+--- androidx.compose:compose-bom:2026.06.01
+--- project :indexer
\\--- /tmp/onnxruntime.aar
"""
        self.assertEqual(
            [
                Component("androidx.core", "core", "1.19.0"),
                Component("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21"),
            ],
            parse_gradle_report(report),
        )

    def test_fails_closed_for_unreviewed_group(self) -> None:
        policy = {
            "allowedLicenses": ["Apache-2.0"],
            "groupRules": [],
            "localComponents": [],
        }
        with self.assertRaisesRegex(ValueError, "no reviewed license rule"):
            apply_policy([Component("example.invalid", "library", "1")], policy)

    def test_cyclonedx_contains_root_and_runtime_dependency(self) -> None:
        component = Component(
            "com.microsoft",
            "onnxruntime-android-reduced",
            "1.27.0",
            "MIT",
            "pkg:github/microsoft/onnxruntime@v1.27.0",
        )
        document = cyclonedx([component])
        self.assertEqual("CycloneDX", document["bomFormat"])
        self.assertEqual("1.6", document["specVersion"])
        self.assertEqual(1, len(document["components"]))
        self.assertIn(component.purl, document["dependencies"][0]["dependsOn"])

    def test_repository_policy_is_valid_json(self) -> None:
        policy = json.loads((SCRIPTS / "release_license_policy.json").read_text())
        self.assertIn("MIT", policy["allowedLicenses"])
        self.assertEqual(2, len(policy["localComponents"]))


if __name__ == "__main__":
    unittest.main()
