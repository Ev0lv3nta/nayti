from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
from typing import Any

from .pack_format import (
    PACK_ID_PATTERN,
    PATH_PATTERN,
    VERSION_PATTERN,
    PackFormatError,
    PackInspection,
    assemble_pack,
    canonical_json,
    key_id,
)


PROFILE_KEYS = {
    "schemaVersion",
    "packId",
    "packVersion",
    "compatibility",
    "runtime",
    "components",
    "files",
    "provenance",
}
PROFILE_FILE_KEYS = {"path", "role", "sourceRoot", "source"}
SOURCE_ROOTS = {"lab", "repository"}


@dataclass(frozen=True)
class ProfileFile:
    path: str
    role: str
    source_root: str
    source: str


@dataclass(frozen=True)
class PackProfile:
    raw: dict[str, Any]
    canonical_sha256: str
    files: tuple[ProfileFile, ...]


def load_profile(path: Path) -> PackProfile:
    raw_bytes = path.resolve().read_bytes()
    try:
        value = json.loads(raw_bytes.decode("utf-8"), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise PackFormatError("pack profile is not strict UTF-8 JSON") from failure
    if not isinstance(value, dict) or set(value) != PROFILE_KEYS:
        raise PackFormatError("pack profile has missing or unknown fields")
    if value.get("schemaVersion") != 1:
        raise PackFormatError("unsupported pack profile schemaVersion")
    _require_pattern(value.get("packId"), PACK_ID_PATTERN, "profile packId")
    _require_pattern(value.get("packVersion"), VERSION_PATTERN, "profile packVersion")
    for name in ("compatibility", "runtime", "provenance"):
        if not isinstance(value.get(name), dict) or not value[name]:
            raise PackFormatError(f"profile {name} must be a non-empty object")
    if not isinstance(value.get("components"), list) or not value["components"]:
        raise PackFormatError("profile components must be a non-empty array")

    raw_files = value.get("files")
    if not isinstance(raw_files, list) or not raw_files:
        raise PackFormatError("profile files must be a non-empty array")
    files = tuple(_parse_profile_file(item) for item in raw_files)
    paths = [file.path for file in files]
    if paths != sorted(paths) or len(paths) != len(set(paths)):
        raise PackFormatError("profile files must have unique sorted paths")
    folded = [path.casefold() for path in paths]
    if len(folded) != len(set(folded)):
        raise PackFormatError("profile files contain a case collision")

    artifact_paths = {
        component.get("artifactPath")
        for component in value["components"]
        if isinstance(component, dict)
    }
    if None in artifact_paths or not artifact_paths.issubset(set(paths)):
        raise PackFormatError("every component artifactPath must reference profile files")
    canonical = canonical_json(value)
    return PackProfile(
        raw=value,
        canonical_sha256=hashlib.sha256(canonical).hexdigest(),
        files=files,
    )


def assemble_profile(
    profile_path: Path,
    repository_root: Path,
    lab_root: Path,
    private_key: Path,
    public_key: Path,
    output: Path,
) -> PackInspection:
    profile = load_profile(profile_path)
    repository_root = repository_root.resolve()
    lab_root = lab_root.resolve()
    roots = {"repository": repository_root, "lab": lab_root}
    sources: dict[str, Path] = {}
    manifest_files: list[dict[str, Any]] = []
    for profile_file in profile.files:
        source = _safe_source(roots[profile_file.source_root], profile_file.source)
        if source.is_symlink() or not source.is_file():
            raise PackFormatError(f"missing regular profile source: {profile_file.source}")
        length = source.stat().st_size
        digest = _sha256_file(source)
        sources[profile_file.path] = source
        manifest_files.append(
            {
                "path": profile_file.path,
                "role": profile_file.role,
                "length": length,
                "sha256": digest,
            },
        )

    provenance = dict(profile.raw["provenance"])
    provenance["profileSha256"] = profile.canonical_sha256
    manifest = {
        "schemaVersion": 1,
        "packId": profile.raw["packId"],
        "packVersion": profile.raw["packVersion"],
        "keyId": key_id(public_key),
        "compatibility": profile.raw["compatibility"],
        "runtime": profile.raw["runtime"],
        "components": profile.raw["components"],
        "files": manifest_files,
        "provenance": provenance,
    }
    return assemble_pack(manifest, sources, private_key, public_key, output)


def _parse_profile_file(raw: Any) -> ProfileFile:
    if not isinstance(raw, dict) or set(raw) != PROFILE_FILE_KEYS:
        raise PackFormatError("profile file has missing or unknown fields")
    path = raw.get("path")
    role = raw.get("role")
    source_root = raw.get("sourceRoot")
    source = raw.get("source")
    if not isinstance(path, str) or not PATH_PATTERN.fullmatch(path):
        raise PackFormatError(f"invalid profile destination path: {path!r}")
    _validate_relative_path(path, "profile destination")
    if not isinstance(role, str) or not PACK_ID_PATTERN.fullmatch(role):
        raise PackFormatError(f"invalid profile role for {path}")
    if source_root not in SOURCE_ROOTS:
        raise PackFormatError(f"invalid sourceRoot for {path}")
    if not isinstance(source, str):
        raise PackFormatError(f"invalid source for {path}")
    _validate_relative_path(source, "profile source")
    return ProfileFile(path=path, role=role, source_root=source_root, source=source)


def _validate_relative_path(raw: str, description: str) -> None:
    path = PurePosixPath(raw)
    if (
        path.is_absolute()
        or "\\" in raw
        or not path.parts
        or any(part in ("", ".", "..") for part in path.parts)
    ):
        raise PackFormatError(f"unsafe {description} path: {raw!r}")


def _safe_source(root: Path, relative: str) -> Path:
    unresolved = root / relative
    current = root
    for part in PurePosixPath(relative).parts:
        current /= part
        if current.is_symlink():
            raise PackFormatError(f"profile source traverses a symlink: {relative}")
    candidate = unresolved.resolve()
    if not candidate.is_relative_to(root):
        raise PackFormatError(f"profile source escapes its root: {relative}")
    return candidate


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise PackFormatError(f"duplicate profile key: {key}")
        value[key] = item
    return value


def _require_pattern(value: Any, pattern: Any, name: str) -> None:
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise PackFormatError(f"invalid {name}")
