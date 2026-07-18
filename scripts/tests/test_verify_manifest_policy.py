from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = REPO_ROOT / "scripts" / "verify_manifest_policy.py"


class ManifestPolicyTest(unittest.TestCase):
    def verify(
        self,
        *,
        package: str = "app.nayti",
        permission: str = "",
        application_attributes: str = "",
        expected_success: bool,
    ) -> subprocess.CompletedProcess[str]:
        manifest = f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
  {permission}
  <application
      android:allowBackup="false"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules"
      {application_attributes}>
    <activity android:name="app.nayti.MainActivity" android:exported="true" />
  </application>
</manifest>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(manifest, encoding="utf-8")
            result = subprocess.run(
                ["python3", str(VERIFIER), str(path)],
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode == 0, expected_success, result.stderr)
        return result

    def test_accepts_offline_release_manifest(self) -> None:
        self.verify(expected_success=True)

    def test_rejects_internet_permission(self) -> None:
        result = self.verify(
            permission='<uses-permission android:name="android.permission.INTERNET" />',
            expected_success=False,
        )
        self.assertIn("INTERNET", result.stderr)

    def test_rejects_release_debugging(self) -> None:
        result = self.verify(
            application_attributes='android:debuggable="true"',
            expected_success=False,
        )
        self.assertIn("must not be debuggable", result.stderr)

    def test_rejects_missing_backup_rules(self) -> None:
        manifest = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.nayti">
  <application android:allowBackup="false">
    <activity android:name="app.nayti.MainActivity" android:exported="true" />
  </application>
</manifest>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(manifest, encoding="utf-8")
            result = subprocess.run(
                ["python3", str(VERIFIER), str(path)],
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dataExtractionRules", result.stderr)


if __name__ == "__main__":
    unittest.main()
