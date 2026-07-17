from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import time

from .export_user2_tokenizer import ORTX_BUILD_VERSION, ORTX_REVISION
from .sources import GIB, MINIMUM_FREE_BYTES, SourceManifest, verify_sources
from .tokenizer_corpus import corpus_sha256, tokenizer_cases

SIGLIP2_COMPONENT_ID = "siglip2-base-patch16-256"
SIGLIP2_SEQUENCE_LENGTH = 64
SIGLIP2_CONTENT_LENGTH = SIGLIP2_SEQUENCE_LENGTH - 1
SIGLIP2_EOS_ID = 1
SIGLIP2_PAD_ID = 0


def export_siglip2_tokenizer(
    manifest: SourceManifest,
    lab_root: Path,
    force: bool,
) -> Path:
    _preflight(lab_root)
    sources_root = lab_root / "upstream"
    verify_sources(manifest, sources_root)
    source = sources_root / SIGLIP2_COMPONENT_ID
    output_directory = lab_root / "exports" / "siglip2"
    output_directory.mkdir(parents=True, exist_ok=True)
    output = output_directory / "siglip2_tokenizer.onnx"
    temporary = output_directory / "siglip2_tokenizer.onnx.tmp"
    if output.exists() and not force:
        raise FileExistsError(f"refusing to replace existing export without --force: {output}")
    temporary.unlink(missing_ok=True)

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

    import numpy as np
    import onnx
    import onnxruntime as ort
    import onnxruntime_extensions as ortx
    import psutil
    from transformers import AutoProcessor

    if ortx.__version__ != ORTX_BUILD_VERSION:
        raise RuntimeError(
            f"SigLIP2 tokenizer requires ORT Extensions {ORTX_BUILD_VERSION} "
            f"from {ORTX_REVISION}; found {ortx.__version__}",
        )

    started = time.monotonic()
    tokenizer_json = (source / "tokenizer.json").read_text(encoding="utf-8")
    tokenizer_config = (source / "tokenizer_config.json").read_text(encoding="utf-8")
    reference = AutoProcessor.from_pretrained(source, local_files_only=True)
    model = _build_graph(onnx, tokenizer_json, tokenizer_config)

    try:
        onnx.checker.check_model(model, full_check=True, check_custom_domain=False)
        temporary.write_bytes(model.SerializeToString())
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.register_custom_ops_library(ortx.get_library_path())
        session = ort.InferenceSession(
            temporary,
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )

        cases = tokenizer_cases()
        for index, case in enumerate(cases):
            actual = session.run(["input_ids"], {"text": np.asarray([case])})[0]
            expected = reference(
                text=[case],
                padding="max_length",
                truncation=True,
                max_length=SIGLIP2_SEQUENCE_LENGTH,
                return_tensors="np",
            )
            if set(expected) != {"input_ids"}:
                raise RuntimeError(f"unexpected official SigLIP2 tokenizer outputs: {set(expected)}")
            if not np.array_equal(actual, expected["input_ids"]):
                raise RuntimeError(f"SigLIP2 tokenizer parity failed at case {index}")

        temporary.replace(output)
        component = next(
            component
            for component in manifest.components
            if component.component_id == SIGLIP2_COMPONENT_ID
        )
        report = {
            "schemaVersion": 1,
            "componentId": SIGLIP2_COMPONENT_ID,
            "sourceRevision": component.revision,
            "runtime": {
                "onnxruntime": ort.__version__,
                "onnxruntimeExtensions": ortx.__version__,
                "onnxruntimeExtensionsRevision": ORTX_REVISION,
            },
            "inputContract": {
                "name": "text",
                "shape": [1],
                "dtype": "string",
                "normalization": "official tokenizer JSON; no host rewrite",
            },
            "output": {
                "path": output.name,
                "length": output.stat().st_size,
                "sha256": _sha256_file(output),
                "inputIds": [1, SIGLIP2_SEQUENCE_LENGTH],
            },
            "specialTokens": {"eos": SIGLIP2_EOS_ID, "pad": SIGLIP2_PAD_ID},
            "parity": {
                "cases": len(cases),
                "exactMatches": len(cases),
                "corpusSha256": corpus_sha256(cases),
            },
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "processRssBytesAfterParity": psutil.Process().memory_info().rss,
        }
        (output_directory / "siglip2_tokenizer.report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _preflight(lab_root)
        return output
    finally:
        temporary.unlink(missing_ok=True)


def _build_graph(onnx: object, tokenizer_json: str, tokenizer_config: str) -> object:
    helper = onnx.helper
    tensor = onnx.TensorProto
    constants = [
        helper.make_tensor("slice_starts", tensor.INT64, [1], [0]),
        helper.make_tensor("slice_ends", tensor.INT64, [1], [SIGLIP2_CONTENT_LENGTH]),
        helper.make_tensor("slice_axes", tensor.INT64, [1], [1]),
        helper.make_tensor("eos", tensor.INT64, [1, 1], [SIGLIP2_EOS_ID]),
        helper.make_tensor("target_length", tensor.INT64, [1], [SIGLIP2_SEQUENCE_LENGTH]),
        helper.make_tensor("shape_axis", tensor.INT64, [1], [1]),
    ]
    nodes = [
        helper.make_node(
            "HfJsonTokenizer",
            ["text"],
            ["raw_ids"],
            domain="ai.onnx.contrib",
            tokenizer_vocab=tokenizer_json,
            tokenizer_config=tokenizer_config,
        ),
        helper.make_node(
            "Slice",
            ["raw_ids", "slice_starts", "slice_ends", "slice_axes"],
            ["content_ids"],
        ),
        helper.make_node("Concat", ["content_ids", "eos"], ["unpadded_ids"], axis=1),
        helper.make_node("Shape", ["unpadded_ids"], ["unpadded_shape"]),
        helper.make_node("Gather", ["unpadded_shape", "shape_axis"], ["unpadded_length"], axis=0),
        helper.make_node("Sub", ["target_length", "unpadded_length"], ["pad_length"]),
        helper.make_node(
            "ConstantOfShape",
            ["pad_length"],
            ["pad_row"],
            value=helper.make_tensor("pad_value", tensor.INT64, [1], [SIGLIP2_PAD_ID]),
        ),
        helper.make_node("Unsqueeze", ["pad_row", "slice_starts"], ["pad_ids"]),
        helper.make_node("Concat", ["unpadded_ids", "pad_ids"], ["input_ids"], axis=1),
    ]
    graph = helper.make_graph(
        nodes,
        "nayti_siglip2_tokenizer_v1",
        [helper.make_tensor_value_info("text", tensor.STRING, [1])],
        [helper.make_tensor_value_info("input_ids", tensor.INT64, [1, SIGLIP2_SEQUENCE_LENGTH])],
        initializer=constants,
    )
    model = helper.make_model(
        graph,
        producer_name="nayti-model-tools",
        opset_imports=[
            helper.make_operatorsetid("", 18),
            helper.make_operatorsetid("ai.onnx.contrib", 1),
        ],
    )
    model.ir_version = 10
    return model


def _preflight(lab_root: Path) -> None:
    import psutil

    lab_root.mkdir(parents=True, exist_ok=True)
    free_bytes = shutil.disk_usage(lab_root).free
    if free_bytes < MINIMUM_FREE_BYTES:
        raise RuntimeError(f"less than {MINIMUM_FREE_BYTES / GIB:.0f} GiB free")
    rss = psutil.Process().memory_info().rss
    if rss >= 18 * GIB:
        raise RuntimeError(f"process RSS safety limit reached: {rss / GIB:.1f} GiB")
    swap = psutil.swap_memory()
    if swap.used >= 512 * 1024**2:
        raise RuntimeError(f"swap safety limit reached: {swap.used / GIB:.1f} GiB")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
