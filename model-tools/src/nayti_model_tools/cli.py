from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

from .sources import GIB, fetch_sources, load_manifest, verify_sources


def main() -> None:
    parser = argparse.ArgumentParser(prog="nayti-model")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).parents[2] / "manifests" / "sources.v1.json",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("audit", help="validate manifest and report exact source size")
    fetch = subparsers.add_parser("fetch", help="download and verify pinned sources")
    fetch.add_argument("--max-workers", type=int, default=2)
    subparsers.add_parser("verify", help="verify existing source bytes")
    user2_export = subparsers.add_parser("export-user2", help="export and verify USER2 FP32 graph")
    user2_export.add_argument("--force", action="store_true")
    user2_tokenizer = subparsers.add_parser(
        "export-user2-tokenizer",
        help="export and verify the fixed-shape USER2 tokenizer graph",
    )
    user2_tokenizer.add_argument("--force", action="store_true")
    siglip2_export = subparsers.add_parser(
        "export-siglip2",
        help="export and verify split SigLIP2 FP32 graphs",
    )
    siglip2_export.add_argument("--force", action="store_true")
    siglip2_tokenizer = subparsers.add_parser(
        "export-siglip2-tokenizer",
        help="export and verify the fixed-shape SigLIP2 tokenizer graph",
    )
    siglip2_tokenizer.add_argument("--force", action="store_true")
    subparsers.add_parser("verify-ocr", help="verify official PaddleOCR graphs and contracts")
    convert_ort = subparsers.add_parser(
        "convert-ort",
        help="convert the seven verified deployment graphs to fixed ARM ORT format",
    )
    convert_ort.add_argument("--force", action="store_true")
    subparsers.add_parser(
        "verify-ort",
        help="execute every ONNX and ORT graph and compare their outputs",
    )
    android_kat = subparsers.add_parser(
        "prepare-android-kat",
        help="create deterministic Android inputs and known-answer ORT outputs",
    )
    android_kat.add_argument("--force", action="store_true")
    keygen = subparsers.add_parser(
        "pack-keygen",
        help="create a workspace-local Ed25519 model-pack signing key",
    )
    keygen.add_argument("--private-key", type=Path)
    keygen.add_argument("--public-key", type=Path)
    assemble = subparsers.add_parser(
        "pack-assemble",
        help="assemble and sign a deterministic Nayti model pack",
    )
    assemble.add_argument(
        "--profile",
        type=Path,
        default=Path(__file__).parents[2] / "manifests" / "pack-profile.alpha1.json",
    )
    assemble.add_argument("--private-key", type=Path)
    assemble.add_argument("--public-key", type=Path)
    assemble.add_argument("--output", type=Path)
    inspect = subparsers.add_parser(
        "pack-inspect",
        help="verify a signed pack without extracting it",
    )
    inspect.add_argument("--pack", type=Path, required=True)
    inspect.add_argument("--public-key", type=Path)
    args = parser.parse_args()

    manifest = load_manifest(args.manifest.resolve())
    if args.command == "audit":
        for component in manifest.components:
            size = sum(file.length for file in component.files)
            print(f"{component.component_id}: {size / GIB:.3f} GiB")
        print(f"total: {manifest.total_bytes} bytes ({manifest.total_bytes / GIB:.3f} GiB)")
        return

    lab_root = _lab_root()
    if args.command == "fetch":
        sources_root = fetch_sources(manifest, lab_root, args.max_workers)
        print(f"verified sources: {sources_root}")
        return
    if args.command == "verify":
        sources_root = lab_root.resolve() / "upstream"
        verify_sources(manifest, sources_root)
        print(f"verified sources: {sources_root}")
        return
    if args.command == "export-user2":
        _ensure_deterministic_hash_seed()
        from .export_user2 import export_user2

        output = export_user2(manifest, lab_root, args.force)
        print(f"verified USER2 export: {output}")
        return
    if args.command == "export-user2-tokenizer":
        from .export_user2_tokenizer import export_user2_tokenizer

        output = export_user2_tokenizer(manifest, lab_root, args.force)
        print(f"verified USER2 tokenizer export: {output}")
        return
    if args.command == "export-siglip2":
        _ensure_deterministic_hash_seed()
        from .export_siglip2 import export_siglip2

        text_output, image_output = export_siglip2(manifest, lab_root, args.force)
        print(f"verified SigLIP2 exports: {text_output}, {image_output}")
        return
    if args.command == "export-siglip2-tokenizer":
        from .export_siglip2_tokenizer import export_siglip2_tokenizer

        output = export_siglip2_tokenizer(manifest, lab_root, args.force)
        print(f"verified SigLIP2 tokenizer export: {output}")
        return
    if args.command == "verify-ocr":
        from .verify_ocr import verify_ocr

        report = verify_ocr(manifest, lab_root)
        print(f"verified PaddleOCR contracts: {report}")
        return
    if args.command == "convert-ort":
        from .convert_ort import convert_models_to_ort

        report = convert_models_to_ort(lab_root, args.force)
        print(f"converted seven ARM ORT graphs: {report}")
        return
    if args.command == "verify-ort":
        from .convert_ort import verify_ort_models

        report = verify_ort_models(lab_root)
        print(f"verified seven ARM ORT graphs: {report}")
        return
    if args.command == "prepare-android-kat":
        from .convert_ort import prepare_android_kat

        manifest_path = prepare_android_kat(lab_root, args.force)
        print(f"prepared Android known-answer bundle: {manifest_path}")
        return
    if args.command in {"pack-keygen", "pack-assemble", "pack-inspect"}:
        from .pack_format import generate_key_pair, inspect_pack

        keys_root = lab_root / "keys"
        private_key = (
            getattr(args, "private_key", None) or keys_root / "alpha-pack-ed25519.pem"
        ).resolve()
        public_key = (args.public_key or keys_root / "alpha-pack-ed25519.pub.pem").resolve()
        if args.command == "pack-keygen":
            identifier = generate_key_pair(private_key, public_key)
            print(f"created Ed25519 public key {identifier}: {public_key}")
            return
        if args.command == "pack-inspect":
            inspection = inspect_pack(args.pack.resolve(), public_key)
            print(
                f"verified {inspection.pack_id}@{inspection.pack_version}: "
                f"{inspection.file_count} files, {inspection.payload_bytes} bytes, "
                f"manifest {inspection.manifest_sha256}",
            )
            return

        from .pack_profile import assemble_profile

        output = (args.output or lab_root / "model-packs" / "nayti-alpha.naytipack").resolve()
        inspection = assemble_profile(
            args.profile.resolve(),
            Path(__file__).parents[3].resolve(),
            lab_root,
            private_key,
            public_key,
            output,
        )
        print(
            f"assembled {inspection.pack_id}@{inspection.pack_version}: "
            f"{inspection.file_count} files, {inspection.payload_bytes} bytes, {output}",
        )
        return
    raise AssertionError(f"unhandled command: {args.command}")


def _lab_root() -> Path:
    raw = os.environ.get("NAYTI_MODEL_LAB")
    if not raw:
        raise SystemExit("NAYTI_MODEL_LAB must point to the workspace-local model lab")
    return Path(raw).expanduser().resolve()


def _ensure_deterministic_hash_seed() -> None:
    if os.environ.get("PYTHONHASHSEED") == "0":
        return
    environment = os.environ.copy()
    environment["PYTHONHASHSEED"] = "0"
    os.execve(
        sys.executable,
        [sys.executable, "-m", "nayti_model_tools.cli", *sys.argv[1:]],
        environment,
    )


if __name__ == "__main__":
    main()
