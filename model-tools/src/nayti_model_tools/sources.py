from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
from typing import Any

GIB = 1024**3
MINIMUM_FREE_BYTES = 100 * GIB
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
REVISION_PATTERN = re.compile(r"[0-9a-f]{40}")
COMPONENT_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]*")


@dataclass(frozen=True)
class SourceFile:
    path: PurePosixPath
    role: str
    length: int
    sha256: str


@dataclass(frozen=True)
class SourceComponent:
    component_id: str
    repository: str
    revision: str
    license_spdx: str
    license_evidence_url: str
    files: tuple[SourceFile, ...]


@dataclass(frozen=True)
class SourceManifest:
    schema_version: int
    components: tuple[SourceComponent, ...]

    @property
    def total_bytes(self) -> int:
        return sum(file.length for component in self.components for file in component.files)


def load_manifest(path: Path) -> SourceManifest:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or raw.get("schemaVersion") != 1:
        raise ValueError("sources manifest schemaVersion must be 1")
    raw_components = raw.get("components")
    if not isinstance(raw_components, list) or not raw_components:
        raise ValueError("sources manifest must contain components")

    component_ids: set[str] = set()
    components: list[SourceComponent] = []
    for raw_component in raw_components:
        component = _parse_component(raw_component)
        if component.component_id in component_ids:
            raise ValueError(f"duplicate componentId: {component.component_id}")
        component_ids.add(component.component_id)
        components.append(component)
    return SourceManifest(schema_version=1, components=tuple(components))


def verify_sources(manifest: SourceManifest, sources_root: Path) -> None:
    for component in manifest.components:
        component_root = _safe_child(sources_root, component.component_id)
        for source_file in component.files:
            artifact = _safe_child(component_root, source_file.path.as_posix())
            if artifact.is_symlink() or not artifact.is_file():
                raise ValueError(f"missing regular source file: {artifact}")
            actual_length = artifact.stat().st_size
            if actual_length != source_file.length:
                raise ValueError(
                    f"length mismatch for {artifact}: {actual_length} != {source_file.length}",
                )
            actual_hash = _sha256_file(artifact)
            if actual_hash != source_file.sha256:
                raise ValueError(
                    f"SHA-256 mismatch for {artifact}: {actual_hash} != {source_file.sha256}",
                )


def fetch_sources(
    manifest: SourceManifest,
    lab_root: Path,
    max_workers: int,
) -> Path:
    if max_workers not in (1, 2):
        raise ValueError("max_workers must be 1 or 2")
    lab_root.mkdir(parents=True, exist_ok=True)
    free_bytes = shutil.disk_usage(lab_root).free
    if free_bytes < MINIMUM_FREE_BYTES:
        raise RuntimeError(
            f"model fetch requires at least {MINIMUM_FREE_BYTES / GIB:.0f} GiB free; "
            f"found {free_bytes / GIB:.1f} GiB",
        )

    from huggingface_hub import snapshot_download

    sources_root = lab_root.resolve() / "upstream"
    cache_root = lab_root.resolve() / "hf-cache"
    sources_root.mkdir(parents=True, exist_ok=True)
    cache_root.mkdir(parents=True, exist_ok=True)
    for component in manifest.components:
        destination = _safe_child(sources_root, component.component_id)
        snapshot_download(
            repo_id=component.repository,
            revision=component.revision,
            allow_patterns=[file.path.as_posix() for file in component.files],
            cache_dir=cache_root,
            local_dir=destination,
            max_workers=max_workers,
        )
    verify_sources(manifest, sources_root)
    return sources_root


def _parse_component(raw: Any) -> SourceComponent:
    if not isinstance(raw, dict):
        raise ValueError("component must be an object")
    component_id = raw.get("componentId")
    repository = raw.get("repository")
    revision = raw.get("revision")
    license_info = raw.get("license")
    if not isinstance(component_id, str) or not COMPONENT_PATTERN.fullmatch(component_id):
        raise ValueError(f"invalid componentId: {component_id!r}")
    if not isinstance(repository, str) or repository.count("/") != 1:
        raise ValueError(f"invalid repository: {repository!r}")
    if not isinstance(revision, str) or not REVISION_PATTERN.fullmatch(revision):
        raise ValueError(f"revision must be a 40-character lowercase commit SHA: {revision!r}")
    if not isinstance(license_info, dict):
        raise ValueError(f"missing license for {component_id}")
    spdx = license_info.get("spdx")
    evidence_url = license_info.get("evidenceUrl")
    if spdx != "Apache-2.0":
        raise ValueError(f"unsupported license for {component_id}: {spdx!r}")
    if not isinstance(evidence_url, str) or not evidence_url.startswith("https://"):
        raise ValueError(f"invalid license evidence URL for {component_id}")

    raw_files = raw.get("files")
    if not isinstance(raw_files, list) or not raw_files:
        raise ValueError(f"component {component_id} must contain files")
    paths: set[PurePosixPath] = set()
    files: list[SourceFile] = []
    for raw_file in raw_files:
        source_file = _parse_file(raw_file)
        if source_file.path in paths:
            raise ValueError(f"duplicate path in {component_id}: {source_file.path}")
        paths.add(source_file.path)
        files.append(source_file)
    return SourceComponent(
        component_id=component_id,
        repository=repository,
        revision=revision,
        license_spdx=spdx,
        license_evidence_url=evidence_url,
        files=tuple(files),
    )


def _parse_file(raw: Any) -> SourceFile:
    if not isinstance(raw, dict):
        raise ValueError("source file must be an object")
    raw_path = raw.get("path")
    role = raw.get("role")
    length = raw.get("length")
    sha256 = raw.get("sha256")
    if not isinstance(raw_path, str):
        raise ValueError("source path must be a string")
    path = PurePosixPath(raw_path)
    if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
        raise ValueError(f"unsafe source path: {raw_path!r}")
    if not isinstance(role, str) or not role:
        raise ValueError(f"missing role for {raw_path}")
    if not isinstance(length, int) or isinstance(length, bool) or length <= 0:
        raise ValueError(f"invalid length for {raw_path}: {length!r}")
    if not isinstance(sha256, str) or not SHA256_PATTERN.fullmatch(sha256):
        raise ValueError(f"invalid SHA-256 for {raw_path}")
    return SourceFile(path=path, role=role, length=length, sha256=sha256)


def _safe_child(root: Path, relative: str) -> Path:
    normalized_root = root.resolve()
    candidate = (normalized_root / relative).resolve()
    if not candidate.is_relative_to(normalized_root):
        raise ValueError(f"path escapes root: {relative}")
    return candidate


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
