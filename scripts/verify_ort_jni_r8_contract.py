#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


NATIVE_REFERENCED_CLASSES = {
    "ai.onnxruntime.MapInfo",
    "ai.onnxruntime.NodeInfo",
    "ai.onnxruntime.OnnxMap",
    "ai.onnxruntime.OnnxModelMetadata",
    "ai.onnxruntime.OnnxSequence",
    "ai.onnxruntime.OnnxSparseTensor",
    "ai.onnxruntime.OnnxTensor",
    "ai.onnxruntime.OnnxValue",
    "ai.onnxruntime.OrtException",
    "ai.onnxruntime.SequenceInfo",
    "ai.onnxruntime.TensorInfo",
}
CLASS_MAPPING = re.compile(r"^(?P<source>\S+) -> (?P<target>[^:]+):$")
MEMBER_MAPPING = re.compile(r"^\s+(?P<source>.+) -> (?P<target>\S+)$")


class OrtJniContractError(ValueError):
    pass


def _member_name(signature: str) -> str:
    before_parameters = signature.split("(", maxsplit=1)[0]
    return before_parameters.rsplit(maxsplit=1)[-1]


def verify_mapping(mapping: str) -> None:
    seen_classes: set[str] = set()
    renamed: list[str] = []
    current_ort_class: str | None = None

    for line in mapping.splitlines():
        class_match = CLASS_MAPPING.match(line)
        if class_match:
            source = class_match.group("source")
            target = class_match.group("target")
            current_ort_class = (
                source if source in NATIVE_REFERENCED_CLASSES else None
            )
            if current_ort_class is not None:
                seen_classes.add(source)
                if source != target:
                    renamed.append(f"class {source} -> {target}")
            continue

        if current_ort_class is None:
            continue
        member_match = MEMBER_MAPPING.match(line)
        if member_match is None:
            continue
        source_name = _member_name(member_match.group("source"))
        if "$r8$" in source_name or "$$Nest$" in source_name:
            continue
        target_name = member_match.group("target")
        if source_name != target_name:
            renamed.append(
                f"member {current_ort_class}.{source_name} -> {target_name}",
            )

    missing = sorted(NATIVE_REFERENCED_CLASSES - seen_classes)
    if missing or renamed:
        problems: list[str] = []
        if missing:
            problems.append(
                "native-referenced classes missing from the minified mapping: "
                + ", ".join(missing),
            )
        if renamed:
            problems.append(
                "JNI-visible ONNX Runtime symbols were renamed: "
                + "; ".join(renamed[:20]),
            )
        raise OrtJniContractError("\n".join(problems))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify that R8 preserved the ONNX Runtime Java/JNI ABI.",
    )
    parser.add_argument("mapping", type=Path)
    args = parser.parse_args()

    try:
        verify_mapping(args.mapping.read_text(encoding="utf-8"))
    except (OSError, OrtJniContractError) as failure:
        print(f"ONNX Runtime JNI/R8 contract failed: {failure}", file=sys.stderr)
        return 1

    print(f"ONNX Runtime JNI/R8 contract verified: {args.mapping}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
