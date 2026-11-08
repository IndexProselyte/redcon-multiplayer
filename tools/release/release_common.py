"""Shared, fail-closed helpers for REDCON PvP patch-only releases."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any

SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class ReleaseError(RuntimeError):
    pass


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def exact_file(path: Path, size: int, digest: str, label: str) -> None:
    if not path.is_file():
        raise ReleaseError(f"{label} is missing: {path}")
    actual_size = path.stat().st_size
    if actual_size != size:
        raise ReleaseError(f"{label} size mismatch: expected {size}, got {actual_size}")
    actual_hash = sha256_file(path)
    if actual_hash != digest:
        raise ReleaseError(f"{label} SHA-256 mismatch")


def safe_child(root: Path, name: str) -> Path:
    if not SAFE_NAME.fullmatch(name):
        raise ReleaseError(f"unsafe bundle filename: {name!r}")
    root = root.resolve()
    child = (root / name).resolve()
    if child.parent != root:
        raise ReleaseError(f"bundle filename escapes root: {name!r}")
    return child


def canonical_json(data: Any) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReleaseError(f"cannot read JSON {path}: {exc}") from exc


def validate_manifest(manifest: dict[str, Any], schema_path: Path) -> None:
    try:
        import jsonschema
    except ImportError as exc:
        raise ReleaseError("jsonschema is required for release validation") from exc
    try:
        jsonschema.Draft202012Validator(load_json(schema_path)).validate(manifest)
    except jsonschema.ValidationError as exc:
        location = "/".join(str(part) for part in exc.absolute_path) or "<root>"
        raise ReleaseError(f"manifest schema error at {location}: {exc.message}") from exc

    entry_ids: set[str] = set()
    for entry in manifest["entries"]:
        if entry["id"] in entry_ids:
            raise ReleaseError(f"duplicate entry id: {entry['id']}")
        entry_ids.add(entry["id"])
        for side in ("input", "output"):
            names = [item["name"] for item in entry[side]["files"]]
            if len(names) != len(set(names)):
                raise ReleaseError(f"duplicate {side} filename in {entry['id']}")
        inputs = {item["name"] for item in entry["input"]["files"]}
        outputs = {item["name"] for item in entry["output"]["files"]}
        patch_names: set[str] = set()
        covered_outputs: set[str] = set()
        for patch in entry["patches"]:
            if patch["name"] in patch_names:
                raise ReleaseError(f"duplicate patch filename: {patch['name']}")
            if patch["source"] not in inputs or patch["target"] not in outputs:
                raise ReleaseError(f"patch mapping is outside its entry: {patch['name']}")
            if patch["target"] in covered_outputs:
                raise ReleaseError(f"ambiguous target mapping: {patch['target']}")
            patch_names.add(patch["name"])
            covered_outputs.add(patch["target"])
        if covered_outputs != outputs:
            raise ReleaseError(f"entry {entry['id']} does not reconstruct its complete output set")


def run_checked(args: list[str], label: str) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(args, check=False, text=True, encoding="utf-8", errors="replace",
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except OSError as exc:
        raise ReleaseError(f"cannot start {label}: {exc}") from exc
    if result.returncode:
        tail = "\n".join(result.stdout.splitlines()[-12:])
        raise ReleaseError(f"{label} failed ({result.returncode})\n{tail}")
    return result


def sign_manifest(data: bytes, private_key_path: Path) -> tuple[bytes, bytes]:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError as exc:
        raise ReleaseError("cryptography is required for manifest signing") from exc
    raw = private_key_path.read_bytes()
    password = os.environ.get("REDCON_MANIFEST_KEY_PASSWORD")
    try:
        key = serialization.load_pem_private_key(raw, password=password.encode("utf-8") if password else None)
    except (ValueError, TypeError) as exc:
        raise ReleaseError("cannot unlock manifest key; check REDCON_MANIFEST_KEY_PASSWORD") from exc
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ReleaseError("manifest key must be a P-256 EC private key")
    signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    public_der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return signature, public_der


def verify_manifest(data: bytes, signature: bytes, public_der: bytes) -> None:
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        key = serialization.load_der_public_key(public_der)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ReleaseError("manifest public key is not P-256 EC")
        key.verify(signature, data, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as exc:
        raise ReleaseError("manifest signature is invalid") from exc
    except (ValueError, TypeError) as exc:
        raise ReleaseError(f"manifest public key is invalid: {exc}") from exc


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
