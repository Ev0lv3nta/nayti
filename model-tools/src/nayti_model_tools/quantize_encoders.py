from __future__ import annotations

from dataclasses import dataclass
import gc
import hashlib
import json
import os
from pathlib import Path
import shutil
import time
from typing import Any, Iterable

from .sources import GIB, MINIMUM_FREE_BYTES
from .tokenizer_corpus import corpus_sha256, tokenizer_cases


EVALUATION_CASES = 100
USER2_QUERY_CASES = 40
MIN_MEDIAN_COSINE = 0.995
MIN_P1_COSINE = 0.98
MIN_MEAN_TOP10_OVERLAP = 0.95
QUANTIZED_DIRECTORY = "quantized-encoders"
SIGLIP_RETRIEVAL_PROMPTS = (
    "a red circle on a light background",
    "a blue square on a light background",
    "a green triangle on a light background",
    "yellow horizontal stripes",
    "purple vertical stripes",
    "an orange checkerboard pattern",
    "a black and white document with lines of text",
    "a narrow white shopping receipt",
    "a monthly calendar grid",
    "a landscape with blue sky green ground and sun",
)


@dataclass(frozen=True)
class EncoderSpec:
    name: str
    source: str
    output: str
    dynamic_matmul: bool


SPECS = (
    EncoderSpec(
        name="siglip2_text",
        source="exports/siglip2/siglip2_text_fp32.onnx",
        output="siglip2_text_rowwise_int8.onnx",
        dynamic_matmul=False,
    ),
    EncoderSpec(
        name="user2_encoder",
        source="exports/user2/user2_fp32.onnx",
        output="user2_encoder_dynamic_int8.onnx",
        dynamic_matmul=True,
    ),
)


def quantize_encoders(lab_root: Path, force: bool) -> Path:
    import numpy as np
    import onnx
    import onnxruntime as ort
    import onnxruntime_extensions as ortx
    import psutil
    from onnxruntime.quantization import QuantType, quantize_dynamic

    started = time.monotonic()
    lab_root = lab_root.resolve()
    _preflight(lab_root, psutil)
    if ort.__version__ != "1.27.0":
        raise RuntimeError(f"expected ONNX Runtime 1.27.0, found {ort.__version__}")

    destination = _direct_child(lab_root, QUANTIZED_DIRECTORY)
    staging = _direct_child(lab_root, f".{QUANTIZED_DIRECTORY}.{os.getpid()}.tmp")
    if staging.exists():
        raise FileExistsError(f"quantization staging directory already exists: {staging}")
    if destination.exists() and not force:
        raise FileExistsError(
            f"verified quantized directory exists; pass --force to replace it: {destination}",
        )
    staging.mkdir()

    corpus = tokenizer_cases()

    artifact_reports: dict[str, Any] = {}
    try:
        for spec in SPECS:
            _preflight(lab_root, psutil)
            source = _required_file(lab_root, spec.source)
            output = staging / spec.output
            model_started = time.monotonic()
            quantization_source = source
            embedding_report = None
            prepared = staging / f".{spec.name}_rowwise_int8.onnx"
            embedding_shape = {
                "siglip2_text": (256_000, 768),
                "user2_encoder": (50_368, 384),
            }.get(spec.name)
            if embedding_shape is not None:
                embedding_report = _prepare_rowwise_int8_token_embedding(
                    onnx,
                    np,
                    source,
                    prepared,
                    embedding_shape,
                )
                quantization_source = prepared
                _preflight(lab_root, psutil)
            nodes_to_quantize: list[str] = []
            if spec.dynamic_matmul:
                nodes_to_quantize = _quantizable_nodes(onnx, quantization_source, spec.name)
                if not nodes_to_quantize:
                    raise RuntimeError(f"no constant-weight nodes found in {source.name}")
                quantize_dynamic(
                    model_input=quantization_source,
                    model_output=output,
                    op_types_to_quantize=["Gemm", "MatMul"],
                    nodes_to_quantize=nodes_to_quantize,
                    per_channel=True,
                    reduce_range=False,
                    weight_type=QuantType.QInt8,
                    use_external_data_format=False,
                )
            else:
                if embedding_report is None:
                    raise RuntimeError(f"no deployment transformation selected for {spec.name}")
                prepared.replace(output)
            onnx_model = onnx.load(output, load_external_data=False)
            onnx.checker.check_model(onnx_model, full_check=True)
            del onnx_model
            prepared.unlink(missing_ok=True)
            _assert_contract(ort, source, output, ortx)
            artifact_reports[spec.name] = {
                "source": _identity(source),
                "output": _identity(output),
                "quantizedConstantWeightNodes": len(nodes_to_quantize),
                "nodeSelection": (
                    "all constant-weight MatMul/Gemm"
                    if spec.dynamic_matmul
                    else "token embedding only; transformer remains FP32"
                ),
                "tokenEmbedding": embedding_report,
                "elapsedSeconds": round(time.monotonic() - model_started, 3),
                "processRssBytes": psutil.Process().memory_info().rss,
            }
            gc.collect()

        evaluation = _evaluate(lab_root, staging, ort, ortx, np, corpus)
        report = {
            "schemaVersion": 1,
            "toolchain": {
                "onnx": onnx.__version__,
                "onnxruntime": ort.__version__,
                "onnxruntimeExtensions": ortx.__version__,
            },
            "configuration": {
                "method": "mixed after ARM64 gate",
                "format": "row-wise token embeddings plus selective dynamic integer operators",
                "weightType": "QInt8",
                "perChannel": True,
                "opTypes": ["Gemm", "MatMul"],
                "nodeSelection": {
                    "siglip2Image": "FP32; dynamic MLP candidate failed Android cosine",
                    "siglip2Text": "row-wise token embedding only; dynamic MatMul candidate failed Android cosine",
                    "user2": "all constant-weight MatMul/Gemm; ARM64 cosine passed",
                },
                "tokenEmbeddings": "row-wise symmetric QInt8; Gather before FP32 dequantization",
                "sourceGraphs": "fixed-shape FP32 exports validated before quantization",
            },
            "corpus": {
                "kind": "deterministic synthetic Russian/English/mixed/identifier/boundary",
                "sha256": corpus_sha256(corpus),
                "containsUserData": False,
                "purpose": "held-out numerical and retrieval parity; dynamic quantization has no calibration set",
            },
            "gates": {
                "minimumMedianCosine": MIN_MEDIAN_COSINE,
                "minimumP1Cosine": MIN_P1_COSINE,
                "minimumMeanTop10Overlap": MIN_MEAN_TOP10_OVERLAP,
            },
            "artifacts": artifact_reports,
            "evaluation": evaluation,
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "processRssBytesAfterEvaluation": psutil.Process().memory_info().rss,
        }
        report_path = staging / "quantization.report.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _publish_directory(staging, destination, force)
        return destination / report_path.name
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def procedural_image(index: int, np: Any) -> Any:
    if index < 0:
        raise ValueError("procedural image index must be non-negative")
    y, x = np.indices((256, 256), dtype=np.int32)
    block = (((x // (8 + index % 9)) + (y // (11 + index % 7))) % 2) * 47
    channels = (
        (x * (index % 7 + 1) + y * 3 + index * 11 + block) % 256,
        (y * (index % 5 + 2) + x * 5 + index * 17 + block * 2) % 256,
        ((x ^ y) + index * 23 + (x * y) % 31 + block * 3) % 256,
    )
    image = np.stack(channels, axis=0).astype(np.float32)
    return (image / 127.5 - 1.0)[None, ...]


def semantic_retrieval_image(index: int, np: Any) -> Any:
    if not 0 <= index < 100:
        raise ValueError("semantic retrieval image index must be in [0, 100)")
    category = index // 10
    variant = index % 10
    image = np.full((256, 256, 3), 238, dtype=np.uint8)
    y, x = np.indices((256, 256), dtype=np.int32)
    offset_x = (variant % 5 - 2) * 5
    offset_y = (variant // 5 * 2 - 1) * 7
    if category == 0:
        mask = (x - 128 - offset_x) ** 2 + (y - 128 - offset_y) ** 2 <= (58 + variant) ** 2
        image[mask] = (220, 35, 45)
    elif category == 1:
        image[55 + variant : 201 - variant, 55 + variant : 201 - variant] = (35, 90, 220)
    elif category == 2:
        half_width = np.maximum(0, (y - 45 - variant) // 2)
        mask = (y >= 45 + variant) & (y <= 220 - variant) & (np.abs(x - 128) <= half_width)
        image[mask] = (40, 165, 75)
    elif category == 3:
        image[((y + variant * 3) // 18) % 2 == 0] = (245, 205, 35)
    elif category == 4:
        image[((x + variant * 3) // 18) % 2 == 0] = (135, 55, 185)
    elif category == 5:
        image[(((x + variant * 2) // 24) + ((y + variant * 3) // 24)) % 2 == 0] = (
            235,
            125,
            30,
        )
    elif category == 6:
        image[25:231, 38:218] = (255, 255, 255)
        for line in range(7):
            width = 120 + (line * 17 + variant * 9) % 55
            top = 52 + line * 23
            image[top : top + 5, 58 : 58 + width] = (30, 30, 30)
    elif category == 7:
        image[:] = (205, 205, 205)
        image[15:241, 78:178] = (255, 255, 250)
        for line in range(9):
            width = 45 + (line * 11 + variant * 7) % 42
            top = 38 + line * 19
            image[top : top + 3, 88 : 88 + width] = (35, 35, 35)
    elif category == 8:
        image[25:231, 25:231] = (255, 255, 255)
        image[25:58, 25:231] = (210, 45, 55)
        for position in range(58, 232, 29):
            image[position : position + 2, 25:231] = (45, 45, 45)
            image[58:231, position : position + 2] = (45, 45, 45)
    else:
        image[:145] = (80, 165 + variant * 3, 235)
        image[145:] = (55, 165, 65)
        sun = (x - 190 + offset_x) ** 2 + (y - 55 - offset_y) ** 2 <= 26**2
        image[sun] = (250, 220, 45)
        hill = y >= 160 - ((x - 128) ** 2 // 700)
        image[hill] = (35, 120, 55)
    channel_first = np.transpose(image.astype(np.float32), (2, 0, 1))
    return (channel_first / 127.5 - 1.0)[None, ...]


def cosine_distribution(reference: Any, candidate: Any, np: Any) -> dict[str, float]:
    if reference.shape != candidate.shape or reference.ndim != 2:
        raise ValueError("embedding matrices must have the same rank-2 shape")
    reference_norm = reference / np.clip(
        np.linalg.norm(reference, axis=1, keepdims=True),
        1e-12,
        None,
    )
    candidate_norm = candidate / np.clip(
        np.linalg.norm(candidate, axis=1, keepdims=True),
        1e-12,
        None,
    )
    cosines = np.sum(reference_norm * candidate_norm, axis=1)
    return {
        "minimum": float(np.min(cosines)),
        "p1": float(np.percentile(cosines, 1)),
        "median": float(np.median(cosines)),
        "mean": float(np.mean(cosines)),
    }


def mean_top_k_overlap(
    reference_queries: Any,
    reference_documents: Any,
    candidate_queries: Any,
    candidate_documents: Any,
    np: Any,
    k: int = 10,
) -> dict[str, float]:
    if reference_queries.shape != candidate_queries.shape:
        raise ValueError("reference and candidate query shapes differ")
    if reference_documents.shape != candidate_documents.shape:
        raise ValueError("reference and candidate document shapes differ")
    if not 1 <= k <= reference_documents.shape[0]:
        raise ValueError("top-k is outside the document count")
    expected = _top_k(reference_queries, reference_documents, np, k)
    actual = _top_k(candidate_queries, candidate_documents, np, k)
    overlaps = np.asarray(
        [len(set(left.tolist()) & set(right.tolist())) / k for left, right in zip(expected, actual)],
        dtype=np.float64,
    )
    return {
        "k": k,
        "minimum": float(np.min(overlaps)),
        "mean": float(np.mean(overlaps)),
    }


def rowwise_symmetric_int8(weights: Any, np: Any) -> tuple[Any, Any, float]:
    if weights.ndim != 2 or weights.dtype != np.float32 or weights.shape[0] == 0:
        raise ValueError("row-wise quantization requires a non-empty FP32 matrix")
    scales = np.empty((weights.shape[0],), dtype=np.float32)
    quantized = np.empty(weights.shape, dtype=np.int8)
    maximum_absolute_error = 0.0
    rows_per_chunk = 1024
    for start in range(0, weights.shape[0], rows_per_chunk):
        stop = min(start + rows_per_chunk, weights.shape[0])
        block = weights[start:stop]
        block_scales = np.max(np.abs(block), axis=1).astype(np.float32) / 127.0
        block_scales[block_scales == 0] = 1.0
        block_quantized = np.clip(
            np.rint(block / block_scales[:, None]),
            -127,
            127,
        ).astype(np.int8)
        scales[start:stop] = block_scales
        quantized[start:stop] = block_quantized
        reconstructed = block_quantized.astype(np.float32) * block_scales[:, None]
        maximum_absolute_error = max(
            maximum_absolute_error,
            float(np.max(np.abs(block - reconstructed))),
        )
    return quantized, scales, maximum_absolute_error


def _evaluate(
    lab_root: Path,
    staging: Path,
    ort: Any,
    ortx: Any,
    np: Any,
    corpus: tuple[str, ...],
) -> dict[str, Any]:
    siglip_tokenizer = _session(
        ort,
        lab_root / "exports/siglip2/siglip2_tokenizer.onnx",
        ortx,
    )
    user2_tokenizer = _session(
        ort,
        lab_root / "exports/user2/user2_tokenizer.onnx",
        ortx,
    )
    sessions: dict[str, tuple[Any, Any]] = {}
    siglip2_image_source = lab_root / "exports/siglip2/siglip2_image_fp32.onnx"
    sessions["siglip2_image"] = (
        _session(ort, siglip2_image_source, ortx),
        _session(ort, siglip2_image_source, ortx),
    )
    for spec in SPECS:
        sessions[spec.name] = (
            _session(ort, lab_root / spec.source, ortx),
            _session(ort, staging / spec.output, ortx),
        )

    image_feeds = (
        {"pixel_values": semantic_retrieval_image(index, np)}
        for index in range(EVALUATION_CASES)
    )
    image_reference, image_candidate = _paired_embeddings(
        sessions["siglip2_image"],
        image_feeds,
        "image_embedding",
        np,
    )
    siglip_text_feeds = (
        {
            "input_ids": siglip_tokenizer.run(
                ["input_ids"],
                {"text": np.asarray([corpus[index]])},
            )[0],
        }
        for index in range(128, 128 + EVALUATION_CASES)
    )
    siglip_text_reference, siglip_text_candidate = _paired_embeddings(
        sessions["siglip2_text"],
        siglip_text_feeds,
        "text_embedding",
        np,
    )
    siglip_retrieval_feeds = (
        {
            "input_ids": siglip_tokenizer.run(
                ["input_ids"],
                {"text": np.asarray([prompt])},
            )[0],
        }
        for prompt in SIGLIP_RETRIEVAL_PROMPTS
    )
    siglip_retrieval_reference, siglip_retrieval_candidate = _paired_embeddings(
        sessions["siglip2_text"],
        siglip_retrieval_feeds,
        "text_embedding",
        np,
    )
    user2_document_feeds = (
        _user2_feed(user2_tokenizer, np, "search_document: " + corpus[index])
        for index in range(128, 128 + EVALUATION_CASES)
    )
    user2_document_reference, user2_document_candidate = _paired_embeddings(
        sessions["user2_encoder"],
        user2_document_feeds,
        "sentence_embedding",
        np,
    )
    user2_query_feeds = (
        _user2_feed(user2_tokenizer, np, "search_query: " + corpus[index])
        for index in range(256, 256 + USER2_QUERY_CASES)
    )
    user2_query_reference, user2_query_candidate = _paired_embeddings(
        sessions["user2_encoder"],
        user2_query_feeds,
        "sentence_embedding",
        np,
    )

    metrics = {
        "siglip2Image": cosine_distribution(image_reference, image_candidate, np),
        "siglip2Text": cosine_distribution(siglip_text_reference, siglip_text_candidate, np),
        "user2Documents": cosine_distribution(
            user2_document_reference,
            user2_document_candidate,
            np,
        ),
        "user2Queries": cosine_distribution(user2_query_reference, user2_query_candidate, np),
        "siglip2CrossModalTop10": mean_top_k_overlap(
            siglip_retrieval_reference,
            image_reference,
            siglip_retrieval_candidate,
            image_candidate,
            np,
        ),
        "user2RetrievalTop10": mean_top_k_overlap(
            user2_query_reference,
            user2_document_reference,
            user2_query_candidate,
            user2_document_candidate,
            np,
        ),
    }
    failures: list[str] = []
    for name in ("siglip2Image", "siglip2Text", "user2Documents", "user2Queries"):
        metric = metrics[name]
        if metric["median"] < MIN_MEDIAN_COSINE or metric["p1"] < MIN_P1_COSINE:
            failures.append(f"cosine:{name}")
    for name in ("siglip2CrossModalTop10", "user2RetrievalTop10"):
        metric = metrics[name]
        if metric["mean"] < MIN_MEAN_TOP10_OVERLAP:
            failures.append(f"retrieval:{name}")
    if failures:
        raise RuntimeError(
            "quantization gates failed "
            f"({', '.join(failures)}): {json.dumps(metrics, sort_keys=True)}",
        )
    return metrics


def _paired_embeddings(
    sessions: tuple[Any, Any],
    feeds: Iterable[dict[str, Any]],
    output_name: str,
    np: Any,
) -> tuple[Any, Any]:
    references: list[Any] = []
    candidates: list[Any] = []
    for feed in feeds:
        references.append(sessions[0].run([output_name], feed)[0])
        candidates.append(sessions[1].run([output_name], feed)[0])
    return np.concatenate(references, axis=0), np.concatenate(candidates, axis=0)


def _user2_feed(tokenizer: Any, np: Any, text: str) -> dict[str, Any]:
    input_ids, attention_mask = tokenizer.run(
        ["input_ids", "attention_mask"],
        {"text": np.asarray([text])},
    )
    return {"input_ids": input_ids, "attention_mask": attention_mask}


def _top_k(queries: Any, documents: Any, np: Any, k: int) -> Any:
    query_norms = np.clip(np.linalg.norm(queries, axis=1, keepdims=True), 1e-12, None)
    document_norms = np.clip(np.linalg.norm(documents, axis=1, keepdims=True), 1e-12, None)
    scores = (queries / query_norms) @ (documents / document_norms).T
    return np.argsort(-scores, axis=1, kind="stable")[:, :k]


def _session(ort: Any, path: Path, ortx: Any) -> Any:
    if not path.is_file():
        raise FileNotFoundError(path)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.register_custom_ops_library(ortx.get_library_path())
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def _assert_contract(ort: Any, source: Path, candidate: Path, ortx: Any) -> None:
    reference = _session(ort, source, ortx)
    quantized = _session(ort, candidate, ortx)
    expected_inputs = [(item.name, item.type, item.shape) for item in reference.get_inputs()]
    actual_inputs = [(item.name, item.type, item.shape) for item in quantized.get_inputs()]
    expected_outputs = [(item.name, item.type, item.shape) for item in reference.get_outputs()]
    actual_outputs = [(item.name, item.type, item.shape) for item in quantized.get_outputs()]
    if expected_inputs != actual_inputs or expected_outputs != actual_outputs:
        raise RuntimeError(
            f"quantization changed graph contract for {source.name}: "
            f"{actual_inputs}/{actual_outputs}",
        )


def _quantizable_nodes(onnx: Any, source: Path, model_name: str) -> list[str]:
    model = onnx.load(source, load_external_data=False)
    initializers = {initializer.name: initializer for initializer in model.graph.initializer}
    result = [
        node.name
        for node in model.graph.node
        if node.op_type in {"Gemm", "MatMul"}
        and node.name
        and len(node.input) >= 2
        and node.input[1] in initializers
        and (
            model_name != "siglip2_image"
            or 3072 in initializers[node.input[1]].dims
        )
    ]
    if len(result) != len(set(result)):
        raise RuntimeError(f"duplicate quantizable node names in {source.name}")
    return result


def _prepare_rowwise_int8_token_embedding(
    onnx: Any,
    np: Any,
    source: Path,
    output: Path,
    expected_shape: tuple[int, int],
) -> dict[str, Any]:
    model = onnx.load(source, load_external_data=False)
    initializers = {initializer.name: initializer for initializer in model.graph.initializer}
    matches: list[tuple[int, Any, Any]] = []
    for index, node in enumerate(model.graph.node):
        if node.op_type != "Gather" or not node.input or node.input[0] not in initializers:
            continue
        initializer = initializers[node.input[0]]
        if (
            initializer.data_type == onnx.TensorProto.FLOAT
            and len(initializer.dims) == 2
            and initializer.dims[0] >= 50_000
        ):
            matches.append((index, node, initializer))
    if len(matches) != 1:
        raise RuntimeError(f"expected one large token embedding Gather, found {len(matches)}")
    node_index, gather, initializer = matches[0]
    if tuple(initializer.dims) != expected_shape:
        raise RuntimeError(
            f"unexpected token embedding shape: {list(initializer.dims)} != {list(expected_shape)}",
        )
    axis = next((attribute.i for attribute in gather.attribute if attribute.name == "axis"), 0)
    if axis != 0 or len(gather.output) != 1:
        raise RuntimeError("unsupported token embedding Gather contract")

    weights = onnx.numpy_helper.to_array(initializer)
    if weights.dtype != np.float32:
        raise RuntimeError(f"unexpected token embedding dtype: {weights.dtype}")
    quantized, scales, maximum_absolute_error = rowwise_symmetric_int8(weights, np)

    weight_name = initializer.name
    scale_name = f"{weight_name}.row_scale"
    axes_name = f"{weight_name}.scale_axes"
    original_output = gather.output[0]
    quantized_output = f"{original_output}.qint8"
    cast_output = f"{original_output}.fp32"
    gathered_scale = f"{original_output}.row_scale"
    expanded_scale = f"{original_output}.row_scale_expanded"
    gather.output[0] = quantized_output

    initializer_index = next(
        index
        for index, candidate in enumerate(model.graph.initializer)
        if candidate.name == weight_name
    )
    del model.graph.initializer[initializer_index]
    model.graph.initializer.extend(
        [
            onnx.numpy_helper.from_array(quantized, name=weight_name),
            onnx.numpy_helper.from_array(scales, name=scale_name),
            onnx.numpy_helper.from_array(np.asarray([-1], dtype=np.int64), name=axes_name),
        ],
    )
    weight_value_info = [
        value for value in model.graph.value_info if value.name == weight_name
    ]
    if len(weight_value_info) != 1:
        raise RuntimeError("token embedding value_info contract changed")
    weight_value_info[0].type.tensor_type.elem_type = onnx.TensorProto.INT8
    helper = onnx.helper
    inserted = [
        helper.make_node(
            "Cast",
            [quantized_output],
            [cast_output],
            name="nayti_token_embedding_cast",
            to=onnx.TensorProto.FLOAT,
        ),
        helper.make_node(
            "Gather",
            [scale_name, gather.input[1]],
            [gathered_scale],
            name="nayti_token_embedding_scale_gather",
            axis=0,
        ),
        helper.make_node(
            "Unsqueeze",
            [gathered_scale, axes_name],
            [expanded_scale],
            name="nayti_token_embedding_scale_unsqueeze",
        ),
        helper.make_node(
            "Mul",
            [cast_output, expanded_scale],
            [original_output],
            name="nayti_token_embedding_dequantize",
        ),
    ]
    nodes = list(model.graph.node)
    del model.graph.node[:]
    for index, node in enumerate(nodes):
        model.graph.node.append(node)
        if index == node_index:
            model.graph.node.extend(inserted)
    onnx.checker.check_model(model, full_check=True)
    onnx.save_model(model, output, save_as_external_data=False)
    source_bytes = int(weights.nbytes)
    quantized_bytes = int(quantized.nbytes + scales.nbytes)
    del model, weights, quantized, scales
    gc.collect()
    return {
        "scheme": "row-wise symmetric QInt8 with per-token FP32 reconstruction",
        "shape": list(expected_shape),
        "sourceBytes": source_bytes,
        "quantizedBytes": quantized_bytes,
        "maximumAbsoluteWeightError": maximum_absolute_error,
    }


def _publish_directory(staging: Path, destination: Path, force: bool) -> None:
    backup = destination.with_name(f".{destination.name}.backup")
    if backup.exists():
        raise FileExistsError(f"stale quantization backup requires review: {backup}")
    if destination.exists():
        if not force or destination.is_symlink() or not destination.is_dir():
            raise ValueError(f"refusing to replace quantized destination: {destination}")
        destination.rename(backup)
    try:
        staging.rename(destination)
    except BaseException:
        if backup.exists() and not destination.exists():
            backup.rename(destination)
        raise
    shutil.rmtree(backup, ignore_errors=True)


def _required_file(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or not path.is_file():
        raise FileNotFoundError(relative)
    return path


def _direct_child(root: Path, name: str) -> Path:
    path = (root / name).resolve()
    if path.parent != root:
        raise ValueError(f"unsafe generated path: {path}")
    return path


def _identity(path: Path) -> dict[str, Any]:
    return {
        "path": path.name,
        "length": path.stat().st_size,
        "sha256": _sha256_file(path),
    }


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _preflight(lab_root: Path, psutil: Any) -> None:
    if shutil.disk_usage(lab_root).free < MINIMUM_FREE_BYTES:
        raise RuntimeError(f"less than {MINIMUM_FREE_BYTES / GIB:.0f} GiB free")
    rss = psutil.Process().memory_info().rss
    if rss >= 18 * GIB:
        raise RuntimeError(f"process RSS safety limit reached: {rss / GIB:.1f} GiB")
    swap = psutil.swap_memory()
    if swap.used >= 512 * 1024**2:
        raise RuntimeError(f"swap safety limit reached: {swap.used / GIB:.1f} GiB")
