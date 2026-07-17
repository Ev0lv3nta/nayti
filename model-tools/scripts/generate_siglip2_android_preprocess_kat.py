#!/usr/bin/env python3
"""Generate a non-square official-processor fixture for Android SigLIP2 parity."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-lab", type=Path, required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    lab = args.model_lab.resolve()
    source = lab / "upstream" / "siglip2-base-patch16-256"
    model = lab / "ort-models" / "siglip2_image.ort"
    output = lab / "android-preprocess-kat"
    manifest = output / "manifest.json"
    if manifest.exists() and not args.force:
        raise FileExistsError(f"refusing to replace existing fixture without --force: {manifest}")
    if not source.is_dir() or not model.is_file():
        raise FileNotFoundError("pinned SigLIP2 source or reduced image graph is missing")

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    os.environ.setdefault("OMP_NUM_THREADS", "2")
    os.environ.setdefault("VECLIB_MAXIMUM_THREADS", "2")

    import numpy as np
    import onnxruntime as ort
    from PIL import Image
    from transformers import AutoProcessor

    output.mkdir(parents=True, exist_ok=True)
    height, width = 211, 377
    y, x = np.indices((height, width), dtype=np.int32)
    rgb = np.stack(
        (
            (x * 3 + y * 5 + (x * y) % 17) % 256,
            (x * 11 + y * 7 + (x ^ y)) % 256,
            (x * 13 + y * 2 + (x * y) % 29) % 256,
        ),
        axis=2,
    ).astype(np.uint8)
    image_path = output / "siglip2-input.png"
    Image.fromarray(rgb, mode="RGB").save(image_path, format="PNG", optimize=False)

    processor = AutoProcessor.from_pretrained(source, local_files_only=True)
    processed = processor(images=Image.open(image_path).convert("RGB"), return_tensors="np")
    if set(processed) != {"pixel_values"}:
        raise RuntimeError(f"unexpected processor outputs: {set(processed)}")
    pixels = np.asarray(processed["pixel_values"], dtype="<f4")
    if pixels.shape != (1, 3, 256, 256):
        raise RuntimeError(f"unexpected pixel tensor: {pixels.shape}")
    pixels_path = output / "siglip2-pixel-values.raw"
    pixels.tofile(pixels_path)

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(model, sess_options=options, providers=["CPUExecutionProvider"])
    embedding = np.asarray(
        session.run(["image_embedding"], {"pixel_values": pixels.astype(np.float32)})[0],
        dtype="<f4",
    )
    if embedding.shape != (1, 768) or not np.isfinite(embedding).all():
        raise RuntimeError("invalid SigLIP2 reference embedding")
    embedding_path = output / "siglip2-image-embedding.raw"
    embedding.tofile(embedding_path)

    report = {
        "schemaVersion": 1,
        "sourceRevision": "3f9f96cb90da5dbc758b01813f2f6f1aee24c1ab",
        "image": {"path": image_path.name, "width": width, "height": height, "sha256": sha256(image_path)},
        "pixelValues": {
            "path": pixels_path.name,
            "shape": [1, 3, 256, 256],
            "sha256": sha256(pixels_path),
        },
        "embedding": {
            "path": embedding_path.name,
            "shape": [1, 768],
            "sha256": sha256(embedding_path),
        },
        "model": {"path": str(model.relative_to(lab)), "sha256": sha256(model)},
    }
    manifest.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(manifest)


if __name__ == "__main__":
    main()
