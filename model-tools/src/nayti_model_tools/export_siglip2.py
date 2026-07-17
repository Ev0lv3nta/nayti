from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import time

from .sources import GIB, MINIMUM_FREE_BYTES, SourceManifest, verify_sources
from .tokenizer_corpus import corpus_sha256, tokenizer_cases

SIGLIP2_COMPONENT_ID = "siglip2-base-patch16-256"
SIGLIP2_TEXT_LENGTH = 64
SIGLIP2_IMAGE_SIZE = 256
SIGLIP2_OUTPUT_DIMENSION = 768
SIGLIP2_TEXT_PARITY_CASES = 100
SIGLIP2_IMAGE_PARITY_CASES = 100


def export_siglip2(
    manifest: SourceManifest,
    lab_root: Path,
    force: bool,
) -> tuple[Path, Path]:
    _preflight(lab_root)
    sources_root = lab_root / "upstream"
    verify_sources(manifest, sources_root)
    source = sources_root / SIGLIP2_COMPONENT_ID
    output_directory = lab_root / "exports" / "siglip2"
    diagnostics = lab_root / "diagnostics" / "siglip2-export"
    output_directory.mkdir(parents=True, exist_ok=True)
    diagnostics.mkdir(parents=True, exist_ok=True)
    text_output = output_directory / "siglip2_text_fp32.onnx"
    image_output = output_directory / "siglip2_image_fp32.onnx"
    text_temporary = output_directory / "siglip2_text_fp32.onnx.tmp"
    image_temporary = output_directory / "siglip2_image_fp32.onnx.tmp"
    for output in (text_output, image_output):
        if output.exists() and not force:
            raise FileExistsError(f"refusing to replace existing export without --force: {output}")
    text_temporary.unlink(missing_ok=True)
    image_temporary.unlink(missing_ok=True)

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
    from transformers import AutoModel, AutoProcessor

    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)

    class TextEncoder(nn.Module):
        def __init__(self, model: nn.Module):
            super().__init__()
            self.model = model

        def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
            pooled = self.model(input_ids=input_ids, return_dict=False)[1]
            return torch.nn.functional.normalize(pooled, p=2, dim=1)

    class ImageEncoder(nn.Module):
        def __init__(self, model: nn.Module):
            super().__init__()
            self.model = model

        def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
            pooled = self.model(pixel_values=pixel_values, return_dict=False)[1]
            return torch.nn.functional.normalize(pooled, p=2, dim=1)

    started = time.monotonic()
    processor = AutoProcessor.from_pretrained(source, local_files_only=True)
    reference_model = AutoModel.from_pretrained(
        source,
        local_files_only=True,
        attn_implementation="eager",
    ).eval()
    if type(reference_model).__name__ != "SiglipModel":
        raise RuntimeError(f"unexpected SigLIP2 compatibility class: {type(reference_model).__name__}")
    text_encoder = TextEncoder(reference_model.text_model).eval()
    image_encoder = ImageEncoder(reference_model.vision_model).eval()
    text_sample = _tokenize(processor, tokenizer_cases()[0])
    image_sample = torch.from_numpy(_procedural_image(0))

    try:
        _export_graph(
            torch,
            text_encoder,
            (text_sample,),
            text_temporary,
            ["input_ids"],
            ["text_embedding"],
            diagnostics / "text",
        )
        _check_graph(onnx, text_temporary, "input_ids", [1, SIGLIP2_TEXT_LENGTH], "text_embedding")
        text_metrics = _verify_text(
            np,
            ort,
            torch,
            processor,
            text_encoder,
            text_temporary,
        )
        _preflight(lab_root)

        _export_graph(
            torch,
            image_encoder,
            (image_sample,),
            image_temporary,
            ["pixel_values"],
            ["image_embedding"],
            diagnostics / "image",
        )
        _check_graph(
            onnx,
            image_temporary,
            "pixel_values",
            [1, 3, SIGLIP2_IMAGE_SIZE, SIGLIP2_IMAGE_SIZE],
            "image_embedding",
        )
        image_metrics = _verify_image(np, ort, torch, image_encoder, image_temporary)
        _preflight(lab_root)

        text_temporary.replace(text_output)
        image_temporary.replace(image_output)
        component = next(
            component
            for component in manifest.components
            if component.component_id == SIGLIP2_COMPONENT_ID
        )
        report = {
            "schemaVersion": 1,
            "componentId": SIGLIP2_COMPONENT_ID,
            "sourceRevision": component.revision,
            "compatibilityClass": type(reference_model).__name__,
            "outputs": {
                "text": _artifact_report(text_output, "input_ids", [1, SIGLIP2_TEXT_LENGTH]),
                "image": _artifact_report(
                    image_output,
                    "pixel_values",
                    [1, 3, SIGLIP2_IMAGE_SIZE, SIGLIP2_IMAGE_SIZE],
                ),
            },
            "parity": {
                "text": text_metrics,
                "image": image_metrics,
                "textCorpusSha256": corpus_sha256(tokenizer_cases()),
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
        (output_directory / "siglip2_fp32.report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return text_output, image_output
    finally:
        text_temporary.unlink(missing_ok=True)
        image_temporary.unlink(missing_ok=True)


def _export_graph(
    torch: object,
    model: object,
    args: tuple[object, ...],
    output: Path,
    input_names: list[str],
    output_names: list[str],
    diagnostics: Path,
) -> None:
    diagnostics.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(
            model,
            args,
            output,
            input_names=input_names,
            output_names=output_names,
            opset_version=18,
            dynamo=True,
            external_data=False,
            optimize=True,
            verify=True,
            report=True,
            artifacts_dir=diagnostics,
        )


def _check_graph(
    onnx: object,
    path: Path,
    input_name: str,
    input_shape: list[int],
    output_name: str,
) -> None:
    model = onnx.load(path, load_external_data=False)
    onnx.checker.check_model(model, full_check=True)
    inputs = {value.name: value for value in model.graph.input}
    outputs = {value.name: value for value in model.graph.output}
    if set(inputs) != {input_name} or set(outputs) != {output_name}:
        raise RuntimeError(f"unexpected SigLIP2 graph interface: {set(inputs)}, {set(outputs)}")
    input_value = inputs[input_name]
    dimensions = [dimension.dim_value for dimension in input_value.type.tensor_type.shape.dim]
    expected_input_type = onnx.TensorProto.INT64 if input_name == "input_ids" else onnx.TensorProto.FLOAT
    if input_value.type.tensor_type.elem_type != expected_input_type:
        raise RuntimeError(f"unexpected SigLIP2 input dtype: {input_value.type.tensor_type.elem_type}")
    if dimensions != input_shape:
        raise RuntimeError(f"unexpected SigLIP2 input shape: {dimensions} != {input_shape}")
    output_value = outputs[output_name]
    dimensions = [dimension.dim_value for dimension in output_value.type.tensor_type.shape.dim]
    if output_value.type.tensor_type.elem_type != onnx.TensorProto.FLOAT:
        raise RuntimeError(f"unexpected SigLIP2 output dtype: {output_value.type.tensor_type.elem_type}")
    if dimensions != [1, SIGLIP2_OUTPUT_DIMENSION]:
        raise RuntimeError(f"unexpected SigLIP2 output shape: {dimensions}")


def _verify_text(
    np: object,
    ort: object,
    torch: object,
    processor: object,
    encoder: object,
    graph: Path,
) -> dict[str, object]:
    session = _session(ort, graph)
    minimum_cosine = 1.0
    maximum_absolute_error = 0.0
    for index, case in enumerate(tokenizer_cases()[:SIGLIP2_TEXT_PARITY_CASES]):
        input_ids = _tokenize(processor, case)
        with torch.inference_mode():
            expected = encoder(input_ids).numpy()
        actual = session.run(["text_embedding"], {"input_ids": input_ids.numpy()})[0]
        cosine, error = _metrics(np, expected, actual)
        minimum_cosine = min(minimum_cosine, cosine)
        maximum_absolute_error = max(maximum_absolute_error, error)
        if not np.isfinite(actual).all() or cosine < 0.999:
            raise RuntimeError(f"SigLIP2 text parity failed at case {index}: cosine={cosine}")
    return {
        "cases": SIGLIP2_TEXT_PARITY_CASES,
        "minimumCosine": minimum_cosine,
        "maximumAbsoluteError": maximum_absolute_error,
    }


def _verify_image(
    np: object,
    ort: object,
    torch: object,
    encoder: object,
    graph: Path,
) -> dict[str, object]:
    session = _session(ort, graph)
    minimum_cosine = 1.0
    maximum_absolute_error = 0.0
    for index in range(SIGLIP2_IMAGE_PARITY_CASES):
        pixel_values = _procedural_image(index)
        tensor = torch.from_numpy(pixel_values)
        with torch.inference_mode():
            expected = encoder(tensor).numpy()
        actual = session.run(["image_embedding"], {"pixel_values": pixel_values})[0]
        cosine, error = _metrics(np, expected, actual)
        minimum_cosine = min(minimum_cosine, cosine)
        maximum_absolute_error = max(maximum_absolute_error, error)
        if not np.isfinite(actual).all() or cosine < 0.999:
            raise RuntimeError(f"SigLIP2 image parity failed at case {index}: cosine={cosine}")
    return {
        "cases": SIGLIP2_IMAGE_PARITY_CASES,
        "minimumCosine": minimum_cosine,
        "maximumAbsoluteError": maximum_absolute_error,
    }


def _session(ort: object, graph: Path) -> object:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    return ort.InferenceSession(graph, sess_options=options, providers=["CPUExecutionProvider"])


def _metrics(np: object, expected: object, actual: object) -> tuple[float, float]:
    cosine = float(np.sum(expected * actual, axis=1)[0])
    absolute_error = float(np.max(np.abs(expected - actual)))
    return cosine, absolute_error


def _tokenize(processor: object, text: str) -> object:
    encoded = processor(
        text=[text],
        padding="max_length",
        truncation=True,
        max_length=SIGLIP2_TEXT_LENGTH,
        return_tensors="pt",
    )
    if set(encoded) != {"input_ids"}:
        raise RuntimeError(f"unexpected SigLIP2 tokenizer outputs: {set(encoded)}")
    return encoded["input_ids"]


def _procedural_image(index: int) -> object:
    import numpy as np

    y, x = np.indices((SIGLIP2_IMAGE_SIZE, SIGLIP2_IMAGE_SIZE), dtype=np.int32)
    channels = (
        (x * (index % 7 + 1) + y * 3 + index * 11) % 256,
        (y * (index % 5 + 2) + x * 5 + index * 17) % 256,
        ((x ^ y) + index * 23 + (x * y) % 31) % 256,
    )
    image = np.stack(channels, axis=0).astype(np.float32)
    return (image / 127.5 - 1.0)[None, ...]


def _artifact_report(path: Path, input_name: str, input_shape: list[int]) -> dict[str, object]:
    return {
        "path": path.name,
        "length": path.stat().st_size,
        "sha256": _sha256_file(path),
        "input": {input_name: input_shape},
        "output": [1, SIGLIP2_OUTPUT_DIMENSION],
        "opset": 18,
    }


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
