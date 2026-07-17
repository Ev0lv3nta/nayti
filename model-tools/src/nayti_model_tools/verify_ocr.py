from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import time

from .sources import GIB, MINIMUM_FREE_BYTES, SourceManifest, verify_sources

DETECTOR_ID = "ppocrv6-small-det-onnx"
RECOGNIZER_ID = "eslav-ppocrv5-mobile-rec-onnx"
DETECTOR_MEAN = [0.485, 0.456, 0.406]
DETECTOR_STD = [0.229, 0.224, 0.225]
RECOGNIZER_SHAPE = [3, 48, 320]
RECOGNIZER_CLASSES = 519


def verify_ocr(
    manifest: SourceManifest,
    lab_root: Path,
) -> Path:
    _preflight(lab_root)
    sources_root = lab_root / "upstream"
    verify_sources(manifest, sources_root)
    detector_root = sources_root / DETECTOR_ID
    recognizer_root = sources_root / RECOGNIZER_ID

    import numpy as np
    import onnx
    import onnxruntime as ort
    import psutil
    import yaml

    started = time.monotonic()
    detector_config = yaml.safe_load((detector_root / "inference.yml").read_text(encoding="utf-8"))
    recognizer_config = yaml.safe_load((recognizer_root / "inference.yml").read_text(encoding="utf-8"))
    detector_contract = _detector_contract(detector_config)
    recognizer_contract = _recognizer_contract(recognizer_config)

    detector_graph = detector_root / "inference.onnx"
    recognizer_graph = recognizer_root / "inference.onnx"
    detector_validation = _check_onnx(
        onnx,
        detector_graph,
        ["", 3, "", ""],
        ["", 1, "", ""],
        allow_legacy_shape_inference=False,
    )
    recognizer_validation = _check_onnx(
        onnx,
        recognizer_graph,
        ["", 3, 48, ""],
        ["", "", RECOGNIZER_CLASSES],
        allow_legacy_shape_inference=True,
    )

    detector_session = _session(ort, detector_graph)
    detector_known_answer = hashlib.sha256()
    detector_shapes: list[list[int]] = []
    detector_ranges: list[list[float]] = []
    for index, (height, width) in enumerate(((736, 736), (736, 960), (960, 736))):
        input_tensor = _procedural_tensor(np, index, 3, height, width, normalized=False)
        output = detector_session.run(["fetch_name_0"], {"x": input_tensor})[0]
        if output.shape != (1, 1, height, width) or output.dtype != np.float32:
            raise RuntimeError(f"unexpected detector output: {output.shape}, {output.dtype}")
        minimum, maximum = float(output.min()), float(output.max())
        if not np.isfinite(output).all() or minimum < 0.0 or maximum > 1.0:
            raise RuntimeError(f"invalid detector probability range: {minimum}, {maximum}")
        _digest_tensor(detector_known_answer, output)
        detector_shapes.append(list(output.shape))
        detector_ranges.append([minimum, maximum])
    del detector_session
    _preflight(lab_root)

    recognizer_session = _session(ort, recognizer_graph)
    recognizer_known_answer = hashlib.sha256()
    recognizer_shapes: list[list[int]] = []
    maximum_probability_sum_error = 0.0
    for index in range(10):
        input_tensor = _procedural_tensor(np, index, *RECOGNIZER_SHAPE, normalized=True)
        output = recognizer_session.run(["fetch_name_0"], {"x": input_tensor})[0]
        if output.shape != (1, 40, RECOGNIZER_CLASSES) or output.dtype != np.float32:
            raise RuntimeError(f"unexpected recognizer output: {output.shape}, {output.dtype}")
        if not np.isfinite(output).all() or float(output.min()) < 0.0 or float(output.max()) > 1.0:
            raise RuntimeError("invalid recognizer probability tensor")
        sum_error = float(np.max(np.abs(output.sum(axis=2) - 1.0)))
        maximum_probability_sum_error = max(maximum_probability_sum_error, sum_error)
        if sum_error > 1e-4:
            raise RuntimeError(f"recognizer probabilities do not sum to one: {sum_error}")
        _digest_tensor(recognizer_known_answer, output)
        recognizer_shapes.append(list(output.shape))
    _preflight(lab_root)

    components = {component.component_id: component for component in manifest.components}
    report = {
        "schemaVersion": 1,
        "detector": {
            "componentId": DETECTOR_ID,
            "sourceRevision": components[DETECTOR_ID].revision,
            "graph": _graph_artifact(detector_graph),
            "graphValidation": detector_validation,
            "contract": detector_contract,
            "knownAnswerSha256": detector_known_answer.hexdigest(),
            "outputShapes": detector_shapes,
            "probabilityRanges": detector_ranges,
        },
        "recognizer": {
            "componentId": RECOGNIZER_ID,
            "sourceRevision": components[RECOGNIZER_ID].revision,
            "graph": _graph_artifact(recognizer_graph),
            "graphValidation": recognizer_validation,
            "contract": recognizer_contract,
            "knownAnswerSha256": recognizer_known_answer.hexdigest(),
            "outputShapes": recognizer_shapes,
            "maximumProbabilitySumError": maximum_probability_sum_error,
        },
        "toolchain": {"onnx": onnx.__version__, "onnxruntime": ort.__version__, "pyyaml": yaml.__version__},
        "elapsedSeconds": round(time.monotonic() - started, 3),
        "processRssBytesAfterVerification": psutil.Process().memory_info().rss,
    }
    output_directory = lab_root / "exports" / "ocr"
    output_directory.mkdir(parents=True, exist_ok=True)
    report_path = output_directory / "ocr_contract.report.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report_path


def _detector_contract(config: object) -> dict[str, object]:
    if not isinstance(config, dict) or config.get("Global") != {"model_name": "PP-OCRv6_small_det"}:
        raise RuntimeError("unexpected detector identity")
    preprocess = config.get("PreProcess", {}).get("transform_ops")
    postprocess = config.get("PostProcess")
    if not isinstance(preprocess, list) or not isinstance(postprocess, dict):
        raise RuntimeError("missing detector preprocessing contract")
    expected_preprocess = [
        {"DecodeImage": {"channel_first": False, "img_mode": "BGR"}},
        {"DetLabelEncode": None},
        {"DetResizeForTest": None},
        {
            "NormalizeImage": {
                "mean": DETECTOR_MEAN,
                "order": "hwc",
                "scale": "1./255.",
                "std": DETECTOR_STD,
            },
        },
        {"ToCHWImage": None},
        {"KeepKeys": {"keep_keys": ["image", "shape", "polys", "ignore_tags"]}},
    ]
    expected_postprocess = {
        "box_thresh": 0.45,
        "max_candidates": 3000,
        "name": "DBPostProcess",
        "thresh": 0.2,
        "unclip_ratio": 1.4,
    }
    if preprocess != expected_preprocess or postprocess != expected_postprocess:
        raise RuntimeError("detector preprocessing/postprocessing contract drifted")
    return {
        "input": "BGR uint8",
        "resize": {"limitType": "min", "limitSideLength": 736, "maxSideLimit": 4000, "multiple": 32},
        "normalize": {"scale": 1.0 / 255.0, "mean": DETECTOR_MEAN, "std": DETECTOR_STD},
        "layout": "NCHW FP32",
        "postprocess": expected_postprocess,
    }


def _recognizer_contract(config: object) -> dict[str, object]:
    if not isinstance(config, dict) or config.get("Global") != {"model_name": "eslav_PP-OCRv5_mobile_rec"}:
        raise RuntimeError("unexpected recognizer identity")
    preprocess = config.get("PreProcess", {}).get("transform_ops")
    postprocess = config.get("PostProcess")
    if not isinstance(preprocess, list) or not isinstance(postprocess, dict):
        raise RuntimeError("missing recognizer preprocessing contract")
    resize = next(
        (operation["RecResizeImg"] for operation in preprocess if "RecResizeImg" in operation),
        None,
    )
    if resize != {"image_shape": RECOGNIZER_SHAPE} or postprocess.get("name") != "CTCLabelDecode":
        raise RuntimeError("recognizer preprocessing/postprocessing contract drifted")
    characters = postprocess.get("character_dict")
    if not isinstance(characters, list) or len(characters) != 517 or len(set(characters)) != 517:
        raise RuntimeError("unexpected East Slavic character dictionary")
    decoder = ["blank", *characters, " "]
    if len(decoder) != RECOGNIZER_CLASSES:
        raise RuntimeError("recognizer class count does not match decoder")
    decoder_sha256 = hashlib.sha256(
        json.dumps(decoder, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
    ).hexdigest()
    return {
        "input": "BGR uint8 text crop",
        "resize": {"height": 48, "width": 320, "preserveAspectRatio": True, "rightPad": 0.0},
        "normalize": {"scale": 1.0 / 255.0, "subtract": 0.5, "divide": 0.5},
        "layout": "NCHW FP32",
        "ctc": {
            "blankIndex": 0,
            "removeConsecutiveDuplicates": True,
            "characterCount": len(characters),
            "spaceIndex": RECOGNIZER_CLASSES - 1,
            "decoderSha256": decoder_sha256,
        },
    }


def _check_onnx(
    onnx: object,
    path: Path,
    input_shape: list[object],
    output_shape: list[object],
    allow_legacy_shape_inference: bool,
) -> dict[str, object]:
    model = onnx.load(path, load_external_data=False)
    onnx.checker.check_model(model, full_check=False)
    full_check = True
    legacy_reason: str | None = None
    try:
        onnx.checker.check_model(model, full_check=True)
    except Exception as error:
        message = str(error)
        if not allow_legacy_shape_inference or "Constant" not in message or "tensor(int64)" not in message:
            raise
        full_check = False
        legacy_reason = "ONNX 1.22 shape inference rejects Constant<int64> in the official IR3/opset7 graph"
    if len(model.graph.input) != 1 or len(model.graph.output) != 1:
        raise RuntimeError("OCR graph must have one input and one output")
    input_value = model.graph.input[0]
    output_value = model.graph.output[0]
    if input_value.name != "x" or output_value.name != "fetch_name_0":
        raise RuntimeError("unexpected OCR tensor names")
    if input_value.type.tensor_type.elem_type != onnx.TensorProto.FLOAT:
        raise RuntimeError("OCR input must be FP32")
    if output_value.type.tensor_type.elem_type != onnx.TensorProto.FLOAT:
        raise RuntimeError("OCR output must be FP32")
    if not _shape_matches(input_value, input_shape) or not _shape_matches(output_value, output_shape):
        raise RuntimeError("unexpected OCR graph shape contract")
    return {
        "structuralCheck": True,
        "fullShapeInference": full_check,
        "legacyShapeInferenceReason": legacy_reason,
        "irVersion": model.ir_version,
        "opsets": {opset.domain or "ai.onnx": opset.version for opset in model.opset_import},
    }


def _shape_matches(value: object, expected: list[object]) -> bool:
    dimensions = value.type.tensor_type.shape.dim
    if len(dimensions) != len(expected):
        return False
    for dimension, target in zip(dimensions, expected, strict=True):
        if target == "":
            if not dimension.dim_param:
                return False
        elif dimension.dim_value != target:
            return False
    return True


def _session(ort: object, graph: Path) -> object:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.log_severity_level = 3
    return ort.InferenceSession(graph, sess_options=options, providers=["CPUExecutionProvider"])


def _procedural_tensor(
    np: object,
    index: int,
    channels: int,
    height: int,
    width: int,
    normalized: bool,
) -> object:
    channel, y, x = np.indices((channels, height, width), dtype=np.int32)
    values = (x * (index + 3) + y * 5 + channel * 47 + index * 19) % 256
    tensor = values.astype(np.float32) / 255.0
    if normalized:
        tensor = (tensor - 0.5) / 0.5
    else:
        mean = np.asarray(DETECTOR_MEAN, dtype=np.float32)[:, None, None]
        std = np.asarray(DETECTOR_STD, dtype=np.float32)[:, None, None]
        tensor = (tensor - mean) / std
    return tensor[None, ...]


def _digest_tensor(digest: object, tensor: object) -> None:
    contiguous = tensor.astype("<f4", copy=False)
    digest.update(json.dumps(list(contiguous.shape), separators=(",", ":")).encode("ascii"))
    digest.update(contiguous.tobytes(order="C"))


def _graph_artifact(path: Path) -> dict[str, object]:
    return {"path": path.name, "length": path.stat().st_size, "sha256": _sha256_file(path)}


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
