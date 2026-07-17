from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import time

from .sources import GIB, MINIMUM_FREE_BYTES, SourceManifest, verify_sources
from .tokenizer_corpus import corpus_sha256, tokenizer_cases

USER2_COMPONENT_ID = "user2-small"
USER2_SEQUENCE_LENGTH = 128
USER2_OUTPUT_DIMENSION = 384
USER2_PARITY_CASES = 100


def export_user2(
    manifest: SourceManifest,
    lab_root: Path,
    force: bool,
) -> Path:
    _preflight(lab_root)
    sources_root = lab_root / "upstream"
    verify_sources(manifest, sources_root)
    source = sources_root / USER2_COMPONENT_ID
    output_directory = lab_root / "exports" / "user2"
    diagnostics = lab_root / "diagnostics" / "user2-export"
    output_directory.mkdir(parents=True, exist_ok=True)
    diagnostics.mkdir(parents=True, exist_ok=True)
    output = output_directory / "user2_fp32.onnx"
    temporary = output_directory / "user2_fp32.onnx.tmp"
    if output.exists() and not force:
        raise FileExistsError(f"refusing to replace existing export without --force: {output}")
    temporary.unlink(missing_ok=True)

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    os.environ.setdefault("OMP_NUM_THREADS", "2")
    os.environ.setdefault("VECLIB_MAXIMUM_THREADS", "2")

    import numpy as np
    import onnx
    import onnxruntime as ort
    import psutil
    import torch
    from torch import nn
    import transformers
    from transformers import AutoModel, AutoTokenizer

    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)

    class User2Encoder(nn.Module):
        def __init__(self, model: nn.Module):
            super().__init__()
            self.model = model

        def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
            hidden = self.model(
                input_ids=input_ids,
                attention_mask=attention_mask,
                return_dict=False,
            )[0]
            mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
            pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp_min(1.0)
            return torch.nn.functional.normalize(pooled, p=2, dim=1)

    started = time.monotonic()
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True)
    reference_model = AutoModel.from_pretrained(
        source,
        local_files_only=True,
        attn_implementation="eager",
    ).eval()
    encoder = User2Encoder(reference_model).eval()
    sample = _tokenize(tokenizer, "search_query: договор аренды квартиры")

    try:
        with torch.inference_mode():
            torch.onnx.export(
                encoder,
                (sample["input_ids"], sample["attention_mask"]),
                temporary,
                input_names=["input_ids", "attention_mask"],
                output_names=["sentence_embedding"],
                opset_version=18,
                dynamo=True,
                external_data=False,
                optimize=True,
                verify=True,
                report=True,
                artifacts_dir=diagnostics,
            )
        onnx_model = onnx.load(temporary, load_external_data=False)
        onnx.checker.check_model(onnx_model, full_check=True)
        _check_graph_contract(onnx_model)

        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        session = ort.InferenceSession(
            temporary,
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        min_cosine = 1.0
        max_absolute_error = 0.0
        cases = tokenizer_cases()[:USER2_PARITY_CASES]
        for index, case in enumerate(cases):
            prefix = "search_query: " if index % 2 == 0 else "search_document: "
            encoded = _tokenize(tokenizer, prefix + case)
            with torch.inference_mode():
                expected = encoder(encoded["input_ids"], encoded["attention_mask"]).numpy()
            actual = session.run(
                ["sentence_embedding"],
                {
                    "input_ids": encoded["input_ids"].numpy(),
                    "attention_mask": encoded["attention_mask"].numpy(),
                },
            )[0]
            cosine = float(np.sum(expected * actual, axis=1)[0])
            absolute_error = float(np.max(np.abs(expected - actual)))
            min_cosine = min(min_cosine, cosine)
            max_absolute_error = max(max_absolute_error, absolute_error)
            if not np.isfinite(actual).all() or cosine < 0.999:
                raise RuntimeError(f"USER2 parity failed at case {index}: cosine={cosine}")

        temporary.replace(output)
        component = next(
            component for component in manifest.components if component.component_id == USER2_COMPONENT_ID
        )
        report = {
            "schemaVersion": 1,
            "componentId": USER2_COMPONENT_ID,
            "sourceRevision": component.revision,
            "output": {
                "path": output.name,
                "length": output.stat().st_size,
                "sha256": _sha256_file(output),
                "opset": 18,
                "inputs": {
                    "input_ids": [1, USER2_SEQUENCE_LENGTH],
                    "attention_mask": [1, USER2_SEQUENCE_LENGTH],
                },
                "output": {"sentence_embedding": [1, USER2_OUTPUT_DIMENSION]},
            },
            "parity": {
                "cases": USER2_PARITY_CASES,
                "corpusSha256": corpus_sha256(tokenizer_cases()),
                "minimumCosine": min_cosine,
                "maximumAbsoluteError": max_absolute_error,
            },
            "toolchain": {
                "torch": torch.__version__,
                "transformers": transformers.__version__,
                "onnx": onnx.__version__,
                "onnxruntime": ort.__version__,
            },
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "processRssBytesAfterParity": psutil.Process().memory_info().rss,
        }
        report_path = output_directory / "user2_fp32.report.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _preflight(lab_root)
        return output
    finally:
        if temporary.exists():
            temporary.unlink()


def _tokenize(tokenizer: object, text: str) -> dict[str, object]:
    return tokenizer(
        [text],
        padding="max_length",
        truncation=True,
        max_length=USER2_SEQUENCE_LENGTH,
        return_tensors="pt",
    )


def _check_graph_contract(model: object) -> None:
    import onnx

    inputs = {value.name: value for value in model.graph.input}
    outputs = {value.name: value for value in model.graph.output}
    if set(inputs) != {"input_ids", "attention_mask"} or set(outputs) != {"sentence_embedding"}:
        raise RuntimeError(f"unexpected USER2 graph interface: inputs={set(inputs)}, outputs={set(outputs)}")
    for name in ("input_ids", "attention_mask"):
        value = inputs[name]
        dimensions = [dimension.dim_value for dimension in value.type.tensor_type.shape.dim]
        if value.type.tensor_type.elem_type != onnx.TensorProto.INT64 or dimensions != [1, 128]:
            raise RuntimeError(f"unexpected USER2 input contract for {name}: {dimensions}")
    output = outputs["sentence_embedding"]
    dimensions = [dimension.dim_value for dimension in output.type.tensor_type.shape.dim]
    if output.type.tensor_type.elem_type != onnx.TensorProto.FLOAT or dimensions != [1, 384]:
        raise RuntimeError(f"unexpected USER2 output contract: {dimensions}")


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
