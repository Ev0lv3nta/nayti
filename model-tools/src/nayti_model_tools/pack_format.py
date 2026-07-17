from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tempfile
from typing import Any, BinaryIO, Mapping


MAGIC = b"NAYTIPK1"
HEADER = struct.Struct(">8sIHH")
DOMAIN_SEPARATOR = b"NAYTI_MODEL_PACK_SIGNATURE_V1\0"
SIGNATURE_LENGTH = 64
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_FILES = 128
MAX_FILE_BYTES = 2 * 1024**3
MAX_TOTAL_PAYLOAD_BYTES = 4 * 1024**3
COPY_BUFFER_BYTES = 4 * 1024 * 1024

SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
KEY_ID_PATTERN = re.compile(r"[0-9a-f]{32}")
PACK_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9.-]{2,63}")
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
PATH_PATTERN = re.compile(r"[a-z0-9][a-z0-9._/-]{0,159}")

TOP_LEVEL_KEYS = {
    "schemaVersion",
    "packId",
    "packVersion",
    "keyId",
    "compatibility",
    "runtime",
    "components",
    "files",
    "provenance",
}
FILE_KEYS = {"path", "role", "length", "sha256"}


class PackFormatError(ValueError):
    pass


@dataclass(frozen=True)
class PackFile:
    path: PurePosixPath
    role: str
    length: int
    sha256: str


@dataclass(frozen=True)
class ParsedManifest:
    value: dict[str, Any]
    files: tuple[PackFile, ...]

    @property
    def total_payload_bytes(self) -> int:
        return sum(file.length for file in self.files)


@dataclass(frozen=True)
class PackInspection:
    pack_id: str
    pack_version: str
    key_id: str
    manifest_sha256: str
    file_count: int
    payload_bytes: int


def canonical_json(value: Any) -> bytes:
    _reject_noncanonical_numbers(value)
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def parse_manifest(raw: bytes) -> ParsedManifest:
    if not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise PackFormatError("manifest length is outside the allowed range")
    try:
        text = raw.decode("utf-8", errors="strict")
        value = json.loads(text, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise PackFormatError("manifest is not strict UTF-8 JSON") from failure
    if not isinstance(value, dict):
        raise PackFormatError("manifest root must be an object")
    if raw != canonical_json(value):
        raise PackFormatError("manifest is not canonical JSON")
    if set(value) != TOP_LEVEL_KEYS:
        raise PackFormatError("manifest has missing or unknown top-level fields")
    if value.get("schemaVersion") != 1:
        raise PackFormatError("unsupported manifest schemaVersion")
    _require_pattern(value.get("packId"), PACK_ID_PATTERN, "packId")
    _require_pattern(value.get("packVersion"), VERSION_PATTERN, "packVersion")
    _require_pattern(value.get("keyId"), KEY_ID_PATTERN, "keyId")
    _require_object(value.get("compatibility"), "compatibility")
    _require_object(value.get("runtime"), "runtime")
    _require_object(value.get("provenance"), "provenance")
    if not isinstance(value.get("components"), list) or not value["components"]:
        raise PackFormatError("components must be a non-empty array")

    raw_files = value.get("files")
    if not isinstance(raw_files, list) or not 1 <= len(raw_files) <= MAX_FILES:
        raise PackFormatError("files count is outside the allowed range")
    files = tuple(_parse_file(item) for item in raw_files)
    paths = [file.path.as_posix() for file in files]
    if paths != sorted(paths):
        raise PackFormatError("files must be sorted by canonical path")
    if len(paths) != len(set(paths)):
        raise PackFormatError("duplicate file path")
    folded_paths = [path.casefold() for path in paths]
    if len(folded_paths) != len(set(folded_paths)):
        raise PackFormatError("case-colliding file paths")
    total = sum(file.length for file in files)
    if total > MAX_TOTAL_PAYLOAD_BYTES:
        raise PackFormatError("declared payload exceeds the total size cap")
    return ParsedManifest(value=value, files=files)


def key_id(public_key: Path) -> str:
    der = _openssl(["pkey", "-pubin", "-in", str(public_key), "-outform", "DER"])
    return hashlib.sha256(der).hexdigest()[:32]


def generate_key_pair(private_key: Path, public_key: Path) -> str:
    private_key = private_key.resolve()
    public_key = public_key.resolve()
    if private_key.exists() or public_key.exists():
        raise FileExistsError("refusing to replace an existing signing key")
    private_key.parent.mkdir(parents=True, exist_ok=True)
    public_key.parent.mkdir(parents=True, exist_ok=True)
    try:
        _openssl(["genpkey", "-algorithm", "ED25519", "-out", str(private_key)])
        os.chmod(private_key, 0o600)
        public_bytes = _openssl(["pkey", "-in", str(private_key), "-pubout"])
        _write_new_file(public_key, public_bytes, 0o644)
    except BaseException:
        private_key.unlink(missing_ok=True)
        public_key.unlink(missing_ok=True)
        raise
    return key_id(public_key)


def assemble_pack(
    manifest_value: dict[str, Any],
    sources: Mapping[str, Path],
    private_key: Path,
    public_key: Path,
    output: Path,
) -> PackInspection:
    manifest_bytes = canonical_json(manifest_value)
    manifest = parse_manifest(manifest_bytes)
    expected_paths = {file.path.as_posix() for file in manifest.files}
    if set(sources) != expected_paths:
        raise PackFormatError("payload source set does not match manifest.files")
    expected_key_id = key_id(public_key)
    if manifest.value["keyId"] != expected_key_id:
        raise PackFormatError("manifest keyId does not match the signing public key")
    for descriptor in manifest.files:
        raw_source = sources[descriptor.path.as_posix()]
        if raw_source.is_symlink():
            raise PackFormatError(f"payload source is a symlink: {descriptor.path}")
        source = raw_source.resolve()
        if not source.is_file():
            raise PackFormatError(f"payload source is not a regular file: {descriptor.path}")
        _verify_file(source, descriptor)

    signature = _sign_manifest(private_key, manifest_bytes)
    if len(signature) != SIGNATURE_LENGTH:
        raise PackFormatError("OpenSSL returned an invalid Ed25519 signature length")

    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f"refusing to replace existing pack: {output}")
    temporary = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    if temporary.exists():
        raise FileExistsError(f"temporary pack path already exists: {temporary}")
    try:
        with temporary.open("xb") as stream:
            stream.write(HEADER.pack(MAGIC, len(manifest_bytes), len(signature), 0))
            stream.write(manifest_bytes)
            stream.write(signature)
            for descriptor in manifest.files:
                with sources[descriptor.path.as_posix()].resolve().open("rb") as source:
                    _copy_exact(source, stream, descriptor.length)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return inspect_pack(output, public_key)


def inspect_pack(pack: Path, public_key: Path) -> PackInspection:
    if pack.is_symlink():
        raise PackFormatError("pack must not be a symlink")
    pack = pack.resolve()
    if not pack.is_file():
        raise PackFormatError("pack must be a regular file")
    with pack.open("rb") as stream:
        header = _read_exact(stream, HEADER.size)
        magic, manifest_length, signature_length, reserved = HEADER.unpack(header)
        if magic != MAGIC:
            raise PackFormatError("invalid pack magic")
        if not 1 <= manifest_length <= MAX_MANIFEST_BYTES:
            raise PackFormatError("manifest length is outside the allowed range")
        if signature_length != SIGNATURE_LENGTH or reserved != 0:
            raise PackFormatError("invalid signature length or reserved header bits")
        manifest_bytes = _read_exact(stream, manifest_length)
        signature = _read_exact(stream, signature_length)
        manifest = parse_manifest(manifest_bytes)
        actual_key_id = key_id(public_key)
        if manifest.value["keyId"] != actual_key_id:
            raise PackFormatError("pack keyId is not allowed by this public key")
        _verify_signature(public_key, manifest_bytes, signature)

        for descriptor in manifest.files:
            digest = hashlib.sha256()
            remaining = descriptor.length
            while remaining:
                chunk = stream.read(min(COPY_BUFFER_BYTES, remaining))
                if not chunk:
                    raise PackFormatError(f"truncated payload: {descriptor.path}")
                digest.update(chunk)
                remaining -= len(chunk)
            if digest.hexdigest() != descriptor.sha256:
                raise PackFormatError(f"payload SHA-256 mismatch: {descriptor.path}")
        if stream.read(1):
            raise PackFormatError("pack contains trailing payload")

    return PackInspection(
        pack_id=manifest.value["packId"],
        pack_version=manifest.value["packVersion"],
        key_id=manifest.value["keyId"],
        manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(),
        file_count=len(manifest.files),
        payload_bytes=manifest.total_payload_bytes,
    )


def _parse_file(raw: Any) -> PackFile:
    if not isinstance(raw, dict) or set(raw) != FILE_KEYS:
        raise PackFormatError("file descriptor has missing or unknown fields")
    raw_path = raw.get("path")
    if not isinstance(raw_path, str) or not PATH_PATTERN.fullmatch(raw_path):
        raise PackFormatError(f"invalid canonical payload path: {raw_path!r}")
    if "\\" in raw_path or any(ord(character) < 0x20 for character in raw_path):
        raise PackFormatError(f"unsafe payload path: {raw_path!r}")
    path = PurePosixPath(raw_path)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise PackFormatError(f"unsafe payload path: {raw_path!r}")
    role = raw.get("role")
    length = raw.get("length")
    sha256 = raw.get("sha256")
    if not isinstance(role, str) or not PACK_ID_PATTERN.fullmatch(role):
        raise PackFormatError(f"invalid file role for {raw_path}")
    if not isinstance(length, int) or isinstance(length, bool) or not 0 <= length <= MAX_FILE_BYTES:
        raise PackFormatError(f"invalid file length for {raw_path}")
    if not isinstance(sha256, str) or not SHA256_PATTERN.fullmatch(sha256):
        raise PackFormatError(f"invalid file SHA-256 for {raw_path}")
    return PackFile(path=path, role=role, length=length, sha256=sha256)


def _verify_file(path: Path, descriptor: PackFile) -> None:
    if path.stat().st_size != descriptor.length:
        raise PackFormatError(f"payload length mismatch: {descriptor.path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(COPY_BUFFER_BYTES):
            digest.update(chunk)
    if digest.hexdigest() != descriptor.sha256:
        raise PackFormatError(f"payload SHA-256 mismatch: {descriptor.path}")


def _verify_signature(public_key: Path, manifest: bytes, signature: bytes) -> None:
    with tempfile.TemporaryDirectory(prefix="nayti-pack-signature-") as directory:
        input_path = Path(directory) / "signed-manifest.bin"
        signature_path = Path(directory) / "signature.bin"
        input_path.write_bytes(DOMAIN_SEPARATOR + manifest)
        signature_path.write_bytes(signature)
        command = [
            "pkeyutl",
            "-verify",
            "-pubin",
            "-inkey",
            str(public_key.resolve()),
            "-sigfile",
            str(signature_path),
            "-rawin",
            "-in",
            str(input_path),
        ]
        try:
            _openssl(command)
        except subprocess.CalledProcessError as failure:
            raise PackFormatError("Ed25519 signature verification failed") from failure


def _sign_manifest(private_key: Path, manifest: bytes) -> bytes:
    with tempfile.TemporaryDirectory(prefix="nayti-pack-signing-") as directory:
        input_path = Path(directory) / "signed-manifest.bin"
        input_path.write_bytes(DOMAIN_SEPARATOR + manifest)
        return _openssl(
            [
                "pkeyutl",
                "-sign",
                "-inkey",
                str(private_key.resolve()),
                "-rawin",
                "-in",
                str(input_path),
            ],
        )


def _openssl(arguments: list[str]) -> bytes:
    return subprocess.run(
        ["openssl", *arguments],
        check=True,
        capture_output=True,
    ).stdout


def _write_new_file(path: Path, payload: bytes, mode: int) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def _read_exact(stream: BinaryIO, length: int) -> bytes:
    payload = stream.read(length)
    if len(payload) != length:
        raise PackFormatError("truncated pack header, manifest, or signature")
    return payload


def _copy_exact(source: BinaryIO, destination: BinaryIO, length: int) -> None:
    remaining = length
    while remaining:
        chunk = source.read(min(COPY_BUFFER_BYTES, remaining))
        if not chunk:
            raise PackFormatError("payload source changed while assembling pack")
        destination.write(chunk)
        remaining -= len(chunk)
    if source.read(1):
        raise PackFormatError("payload source grew while assembling pack")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PackFormatError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_noncanonical_numbers(value: Any) -> None:
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return
    if isinstance(value, int):
        return
    if isinstance(value, float):
        raise PackFormatError("floating-point values are forbidden in canonical manifest")
    if isinstance(value, list):
        for item in value:
            _reject_noncanonical_numbers(item)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise PackFormatError("manifest object keys must be strings")
            _reject_noncanonical_numbers(item)
        return
    raise PackFormatError(f"unsupported canonical manifest value: {type(value).__name__}")


def _require_pattern(value: Any, pattern: re.Pattern[str], name: str) -> None:
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise PackFormatError(f"invalid {name}")


def _require_object(value: Any, name: str) -> None:
    if not isinstance(value, dict) or not value:
        raise PackFormatError(f"{name} must be a non-empty object")
