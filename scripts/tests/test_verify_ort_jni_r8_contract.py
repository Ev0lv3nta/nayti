from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).parents[1] / "verify_ort_jni_r8_contract.py"
SPEC = importlib.util.spec_from_file_location("verify_ort_jni_r8_contract", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def preserved_mapping() -> str:
    classes = sorted(MODULE.NATIVE_REFERENCED_CLASSES)
    return "\n".join(
        f"{class_name} -> {class_name}:\n"
        f"    long nativeHandle -> nativeHandle\n"
        f"    1:2:void close():3:4 -> close"
        for class_name in classes
    )


class OrtJniR8ContractTest(unittest.TestCase):
    def test_accepts_preserved_classes_and_members(self) -> None:
        MODULE.verify_mapping(preserved_mapping())

    def test_rejects_renamed_class(self) -> None:
        mapping = preserved_mapping().replace(
            "ai.onnxruntime.TensorInfo -> ai.onnxruntime.TensorInfo:",
            "ai.onnxruntime.TensorInfo -> a.b:",
        )
        with self.assertRaisesRegex(MODULE.OrtJniContractError, "TensorInfo -> a.b"):
            MODULE.verify_mapping(mapping)

    def test_rejects_renamed_member(self) -> None:
        mapping = preserved_mapping().replace(
            "long nativeHandle -> nativeHandle",
            "long nativeHandle -> a",
            1,
        )
        with self.assertRaisesRegex(MODULE.OrtJniContractError, "nativeHandle -> a"):
            MODULE.verify_mapping(mapping)

    def test_rejects_removed_native_referenced_class(self) -> None:
        mapping = preserved_mapping().replace(
            "ai.onnxruntime.OnnxTensor -> ai.onnxruntime.OnnxTensor:\n"
            "    long nativeHandle -> nativeHandle\n"
            "    1:2:void close():3:4 -> close\n",
            "",
        )
        with self.assertRaisesRegex(MODULE.OrtJniContractError, "OnnxTensor"):
            MODULE.verify_mapping(mapping)

    def test_ignores_r8_synthetic_class_in_ort_namespace(self) -> None:
        mapping = (
            preserved_mapping()
            + "\nai.onnxruntime.OnnxTensor$$ExternalSyntheticOutline0 -> a.b:\n"
            + "    int $r8$classId -> a\n"
        )
        MODULE.verify_mapping(mapping)

    def test_ignores_r8_synthetic_member_on_preserved_class(self) -> None:
        mapping = preserved_mapping().replace(
            "    long nativeHandle -> nativeHandle",
            "    java.lang.String $r8$lambda$example(java.lang.String) -> a\n"
            "    long nativeHandle -> nativeHandle",
            1,
        )
        MODULE.verify_mapping(mapping)


if __name__ == "__main__":
    unittest.main()
