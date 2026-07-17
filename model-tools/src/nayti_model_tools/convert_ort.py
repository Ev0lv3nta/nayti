from __future__ import annotations

from dataclasses import dataclass
import gc
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any

from .sources import GIB, MINIMUM_FREE_BYTES


@dataclass(frozen=True)
class ModelArtifact:
    name: str
    source: str
    deployment_ir_version: int | None = None


ARTIFACTS = (
    ModelArtifact(
        "eslav_recognizer",
        "upstream/eslav-ppocrv5-mobile-rec-onnx/inference.onnx",
        deployment_ir_version=4,
    ),
    ModelArtifact("ppocrv6_detector", "upstream/ppocrv6-small-det-onnx/inference.onnx"),
    ModelArtifact("siglip2_image", "exports/siglip2/siglip2_image_fp32.onnx"),
    ModelArtifact("siglip2_text", "exports/siglip2/siglip2_text_fp32.onnx"),
    ModelArtifact("siglip2_tokenizer", "exports/siglip2/siglip2_tokenizer.onnx"),
    ModelArtifact("user2_encoder", "exports/user2/user2_fp32.onnx"),
    ModelArtifact("user2_tokenizer", "exports/user2/user2_tokenizer.onnx"),
)

ANDROID_KAT_SCHEMA_VERSION = 1


def convert_models_to_ort(lab_root: Path, force: bool) -> Path:
    import onnx
    import onnxruntime as ort
    import onnxruntime_extensions as ortx

    lab_root = lab_root.resolve()
    _require_free_space(lab_root)
    _require_runtime_versions(ort.__version__, ortx.__version__)
    inputs = _generated_child(lab_root, "ort-inputs")
    outputs = _generated_child(lab_root, "ort-models")
    _prepare_empty_directory(inputs, force)
    _prepare_empty_directory(outputs, force)

    preparations: dict[str, Any] = {}
    for artifact in ARTIFACTS:
        source = _required_source(lab_root, artifact.source)
        destination = inputs / f"{artifact.name}.onnx"
        if artifact.deployment_ir_version is None:
            destination.symlink_to(source)
            preparations[artifact.name] = {
                "kind": "verified-source-symlink",
                "sourceSha256": _sha256_file(source),
            }
            continue

        model = onnx.load(source)
        original_ir_version = model.ir_version
        if original_ir_version >= artifact.deployment_ir_version:
            raise ValueError(
                f"unexpected {artifact.name} IR version {original_ir_version}; "
                f"expected less than {artifact.deployment_ir_version}",
            )
        model.ir_version = artifact.deployment_ir_version
        onnx.checker.check_model(model, full_check=False)
        onnx.save(model, destination)
        preparations[artifact.name] = {
            "kind": "ir-version-normalization",
            "originalIrVersion": original_ir_version,
            "deploymentIrVersion": model.ir_version,
            "sourceSha256": _sha256_file(source),
            "preparedSha256": _sha256_file(destination),
        }

    command = [
        sys.executable,
        "-m",
        "onnxruntime.tools.convert_onnx_models_to_ort",
        str(inputs),
        "--output_dir",
        str(outputs),
        "--optimization_style",
        "Fixed",
        "--enable_type_reduction",
        "--custom_op_library",
        ortx.get_library_path(),
        "--target_platform",
        "arm",
    ]
    subprocess.run(command, check=True)

    expected = {f"{artifact.name}.ort" for artifact in ARTIFACTS}
    actual = {path.name for path in outputs.glob("*.ort")}
    if actual != expected:
        raise ValueError(f"unexpected ORT output set: {sorted(actual)} != {sorted(expected)}")
    config = outputs / "required_operators_and_types.config"
    if not config.is_file():
        raise ValueError("ORT converter did not create the required operator config")
    config_text = config.read_text(encoding="utf-8")
    if "ai.onnx.contrib;1;HfJsonTokenizer" not in config_text:
        raise ValueError("required operator config lost the tokenizer custom op")

    report = {
        "schemaVersion": 1,
        "toolchain": {
            "onnx": onnx.__version__,
            "onnxruntime": ort.__version__,
            "onnxruntimeExtensions": ortx.__version__,
        },
        "conversion": {
            "optimizationStyle": "Fixed",
            "targetPlatform": "arm",
            "typeReduction": True,
            "preparations": preparations,
        },
        "operatorConfig": {
            "path": config.name,
            "length": config.stat().st_size,
            "sha256": _sha256_file(config),
        },
        "artifacts": {
            artifact.name: _file_identity(outputs / f"{artifact.name}.ort")
            for artifact in ARTIFACTS
        },
    }
    report_path = outputs / "conversion.report.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report_path


def verify_ort_models(lab_root: Path) -> Path:
    import numpy as np
    import onnxruntime as ort
    import onnxruntime_extensions as ortx
    import psutil

    lab_root = lab_root.resolve()
    _require_runtime_versions(ort.__version__, ortx.__version__)
    inputs = _generated_child(lab_root, "ort-inputs")
    outputs = _generated_child(lab_root, "ort-models")
    feeds = _verification_feeds()
    results: dict[str, Any] = {}
    for artifact in ARTIFACTS:
        source = inputs / f"{artifact.name}.onnx"
        target = outputs / f"{artifact.name}.ort"
        if not source.exists() or not target.is_file():
            raise ValueError(f"missing conversion artifact for {artifact.name}")
        reference, reference_names = _run_model(source, feeds[artifact.name], ort, ortx)
        actual, actual_names = _run_model(target, feeds[artifact.name], ort, ortx)
        if reference_names != actual_names or len(reference) != len(actual):
            raise AssertionError(f"output contract changed for {artifact.name}")

        maximum_error = 0.0
        exact = True
        output_hashes: list[str] = []
        shapes: list[list[int]] = []
        for expected, observed in zip(reference, actual, strict=True):
            exact = exact and np.array_equal(expected, observed)
            if np.issubdtype(expected.dtype, np.number):
                maximum_error = max(
                    maximum_error,
                    float(
                        np.max(
                            np.abs(expected.astype(np.float64) - observed.astype(np.float64)),
                        ),
                    ),
                )
                np.testing.assert_allclose(expected, observed, rtol=2e-5, atol=2e-5)
            elif not np.array_equal(expected, observed):
                raise AssertionError(f"non-numeric output changed for {artifact.name}")
            contiguous = np.ascontiguousarray(observed)
            output_hashes.append(hashlib.sha256(contiguous.tobytes()).hexdigest())
            shapes.append(list(observed.shape))
        results[artifact.name] = {
            "exact": exact,
            "maximumAbsoluteError": maximum_error,
            "outputNames": actual_names,
            "outputShapes": shapes,
            "ortOutputSha256": output_hashes,
        }
        del reference, actual
        gc.collect()

    report = {
        "schemaVersion": 1,
        "toolchain": {
            "onnxruntime": ort.__version__,
            "onnxruntimeExtensions": ortx.__version__,
        },
        "seed": 20260717,
        "models": results,
        "processRssBytesAfterVerification": psutil.Process().memory_info().rss,
    }
    report_path = outputs / "verification.report.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report_path


def prepare_android_kat(lab_root: Path, force: bool) -> Path:
    import numpy as np
    import onnxruntime as ort
    import onnxruntime_extensions as ortx

    lab_root = lab_root.resolve()
    _require_runtime_versions(ort.__version__, ortx.__version__)
    if sys.byteorder != "little":
        raise RuntimeError("Android known-answer tensors require a little-endian host")

    models = _generated_child(lab_root, "ort-models")
    output = _generated_child(lab_root, "android-kat")
    _prepare_empty_directory(output, force)
    inputs_directory = output / "inputs"
    inputs_directory.mkdir()
    outputs_directory = output / "outputs"
    outputs_directory.mkdir()

    manifest_models: dict[str, Any] = {}
    feeds = _verification_feeds()
    for artifact in ARTIFACTS:
        model = models / f"{artifact.name}.ort"
        if not model.is_file():
            raise ValueError(f"missing ORT artifact for {artifact.name}")

        input_descriptors: dict[str, Any] = {}
        for input_name, value in feeds[artifact.name].items():
            contiguous = np.ascontiguousarray(value)
            if contiguous.dtype == object:
                strings = [str(item) for item in contiguous.reshape(-1).tolist()]
                input_descriptors[input_name] = {
                    "dtype": "string",
                    "shape": list(contiguous.shape),
                    "values": strings,
                }
                continue
            if contiguous.dtype not in (np.dtype("float32"), np.dtype("int64")):
                raise ValueError(
                    f"unsupported Android KAT input dtype for {artifact.name}/{input_name}: "
                    f"{contiguous.dtype}",
                )
            relative_path = Path("inputs") / f"{artifact.name}--{input_name}.raw"
            payload = contiguous.tobytes(order="C")
            (output / relative_path).write_bytes(payload)
            input_descriptors[input_name] = {
                "dtype": str(contiguous.dtype),
                "shape": list(contiguous.shape),
                "path": relative_path.as_posix(),
                "length": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }

        results, output_names = _run_model(model, feeds[artifact.name], ort, ortx)
        output_descriptors = []
        for output_index, (output_name, value) in enumerate(
            zip(output_names, results, strict=True),
        ):
            contiguous = np.ascontiguousarray(value)
            if contiguous.dtype not in (np.dtype("float32"), np.dtype("int64")):
                raise ValueError(
                    f"unsupported Android KAT output dtype for {artifact.name}/{output_name}: "
                    f"{contiguous.dtype}",
                )
            relative_path = (
                Path("outputs") / f"{artifact.name}--{output_index:02d}--{output_name}.raw"
            )
            payload = contiguous.tobytes(order="C")
            (output / relative_path).write_bytes(payload)
            descriptor = {
                "name": output_name,
                "dtype": str(contiguous.dtype),
                "shape": list(contiguous.shape),
                "path": relative_path.as_posix(),
                "length": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
            if contiguous.dtype == np.dtype("float32"):
                descriptor["tolerance"] = {"absolute": 2e-5, "relative": 2e-5}
            output_descriptors.append(descriptor)
        manifest_models[artifact.name] = {
            "model": _file_identity(model),
            "inputs": input_descriptors,
            "outputs": output_descriptors,
        }
        del results
        gc.collect()

    manifest = {
        "schemaVersion": ANDROID_KAT_SCHEMA_VERSION,
        "seed": 20260717,
        "byteOrder": "little",
        "toolchain": {
            "onnxruntime": ort.__version__,
            "onnxruntimeExtensions": ortx.__version__,
        },
        "models": manifest_models,
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest_path


def _verification_feeds() -> dict[str, dict[str, Any]]:
    import numpy as np

    rng = np.random.default_rng(20260717)
    return {
        "eslav_recognizer": {
            "x": rng.uniform(-1, 1, (1, 3, 48, 320)).astype(np.float32),
        },
        "ppocrv6_detector": {
            "x": rng.normal(0, 0.25, (1, 3, 736, 736)).astype(np.float32),
        },
        "siglip2_image": {
            "pixel_values": rng.normal(0, 0.25, (1, 3, 256, 256)).astype(np.float32),
        },
        "siglip2_text": {
            "input_ids": np.concatenate(
                [np.ones((1, 8), np.int64), np.zeros((1, 56), np.int64)],
                axis=1,
            ),
        },
        "siglip2_tokenizer": {
            "text": np.array(["Фото с собакой у моря"], dtype=object),
        },
        "user2_encoder": {
            "input_ids": np.concatenate(
                [np.ones((1, 8), np.int64), np.zeros((1, 120), np.int64)],
                axis=1,
            ),
            "attention_mask": np.concatenate(
                [np.ones((1, 8), np.int64), np.zeros((1, 120), np.int64)],
                axis=1,
            ),
        },
        "user2_tokenizer": {
            "text": np.array(["Фото с собакой у моря"], dtype=object),
        },
    }


def _run_model(
    path: Path,
    feeds: dict[str, Any],
    ort: Any,
    ortx: Any,
) -> tuple[list[Any], list[str]]:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.register_custom_ops_library(ortx.get_library_path())
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    public_inputs = [value.name for value in session.get_inputs()]
    if set(public_inputs) != set(feeds):
        raise AssertionError(f"input contract changed for {path.name}: {public_inputs}")
    output_names = [value.name for value in session.get_outputs()]
    result = session.run(None, feeds)
    del session
    gc.collect()
    return result, output_names


def _require_runtime_versions(ort_version: str, ortx_version: str) -> None:
    if ort_version != "1.27.0":
        raise ValueError(f"expected ONNX Runtime 1.27.0, found {ort_version}")
    if ortx_version != "0.15.0+fe4e13f":
        raise ValueError(
            "expected pinned ONNX Runtime Extensions source build "
            f"0.15.0+fe4e13f, found {ortx_version}",
        )


def _required_source(lab_root: Path, relative: str) -> Path:
    source = (lab_root / relative).resolve()
    if not source.is_relative_to(lab_root) or not source.is_file():
        raise ValueError(f"missing model source: {relative}")
    return source


def _generated_child(lab_root: Path, name: str) -> Path:
    path = (lab_root / name).resolve()
    if path.parent != lab_root:
        raise ValueError(f"unsafe generated path: {path}")
    return path


def _prepare_empty_directory(path: Path, force: bool) -> None:
    if path.exists():
        if not force:
            raise FileExistsError(f"generated directory exists; pass --force to replace it: {path}")
        if path.is_symlink() or not path.is_dir():
            raise ValueError(f"refusing to replace non-directory: {path}")
        shutil.rmtree(path)
    path.mkdir(parents=False)


def _require_free_space(lab_root: Path) -> None:
    free_bytes = shutil.disk_usage(lab_root).free
    if free_bytes < MINIMUM_FREE_BYTES:
        raise RuntimeError(
            f"ORT conversion requires at least {MINIMUM_FREE_BYTES / GIB:.0f} GiB free; "
            f"found {free_bytes / GIB:.1f} GiB",
        )


def _file_identity(path: Path) -> dict[str, Any]:
    return {"path": path.name, "length": path.stat().st_size, "sha256": _sha256_file(path)}


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
