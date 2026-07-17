from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from nayti_model_tools.convert_ort import (
    _prepare_conversion_staging,
    _publish_conversion,
    _normalize_operator_config,
)


class ConvertOrtPublicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_force_publication_replaces_both_directories_as_one_set(self) -> None:
        first = self._directory("first", "old-first")
        second = self._directory("second", "old-second")
        first_staging = self._directory("first.tmp", "new-first")
        second_staging = self._directory("second.tmp", "new-second")

        _publish_conversion(
            ((first_staging, first), (second_staging, second)),
            force=True,
        )

        self.assertEqual("new-first", (first / "value").read_text())
        self.assertEqual("new-second", (second / "value").read_text())
        self.assertFalse((self.root / ".first.backup").exists())
        self.assertFalse((self.root / ".second.backup").exists())

    def test_partial_publication_failure_restores_previous_set(self) -> None:
        first = self._directory("first", "old-first")
        second = self._directory("second", "old-second")
        first_staging = self._directory("first.tmp", "new-first")
        missing_staging = self.root / "missing.tmp"

        with self.assertRaises(FileNotFoundError):
            _publish_conversion(
                ((first_staging, first), (missing_staging, second)),
                force=True,
            )

        self.assertEqual("old-first", (first / "value").read_text())
        self.assertEqual("old-second", (second / "value").read_text())
        self.assertEqual("new-first", (first_staging / "value").read_text())

    def test_staging_preflight_preserves_existing_verified_set(self) -> None:
        first = self._directory("first", "old-first")
        second = self._directory("second", "old-second")
        first_staging = self.root / "first.tmp"
        second_staging = self.root / "second.tmp"

        with self.assertRaises(FileExistsError):
            _prepare_conversion_staging(
                (first_staging, second_staging),
                (first, second),
                force=False,
            )

        self.assertEqual("old-first", (first / "value").read_text())
        self.assertEqual("old-second", (second / "value").read_text())
        self.assertFalse(first_staging.exists())
        self.assertFalse(second_staging.exists())

    def test_operator_config_removes_ephemeral_conversion_paths(self) -> None:
        config = self.root / "operators.config"
        config.write_text(
            "# Generated from model/s:\n"
            "# - /tmp/.ort-models.123.tmp/zeta.ort\n"
            "ai.onnx;13;Add\n",
        )

        _normalize_operator_config(config, {"zeta.ort", "alpha.ort"})

        self.assertEqual(
            "# Generated from Nayti deployment model set:\n"
            "# - alpha.ort\n"
            "# - zeta.ort\n"
            "ai.onnx;13;Add\n",
            config.read_text(),
        )

    def _directory(self, name: str, value: str) -> Path:
        directory = self.root / name
        directory.mkdir()
        (directory / "value").write_text(value)
        return directory


if __name__ == "__main__":
    unittest.main()
