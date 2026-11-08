#!/usr/bin/env python3
"""Create and sign a patch-only bundle from private original/target APK sets."""

from __future__ import annotations

import argparse
import json
import shutil
import tempfile
from pathlib import Path

from release_common import (ReleaseError, atomic_write, canonical_json, load_json,
                            run_checked, safe_child, sha256_file, sign_manifest,
                            validate_manifest, verify_manifest)

XDELTA_SHA256 = "6e812b38484d0c764291779fadee83ffbe2eceaccb4e3a21f8a043a02654a01e"


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--spec", type=Path, required=True, help="private build specification")
    result.add_argument("--bundle", type=Path, required=True)
    result.add_argument("--xdelta", type=Path, required=True)
    result.add_argument("--manifest-key", type=Path, required=True)
    result.add_argument("--schema", type=Path, default=Path("release-config/manifest.schema.json"))
    return result


def descriptor(path: Path, public_name: str) -> dict[str, object]:
    return {"name": public_name, "size": path.stat().st_size, "sha256": sha256_file(path)}


def main() -> int:
    args = parser().parse_args()
    spec = load_json(args.spec)
    if sha256_file(args.xdelta) != XDELTA_SHA256:
        raise ReleaseError("xdelta executable does not match pinned v3.2.0 Windows x86-64 digest")
    mapping = spec["entry"]["files"]
    if not isinstance(mapping, list) or len(mapping) < 2:
        raise ReleaseError("spec must describe a base and every required split")

    destination = args.bundle.resolve()
    if destination.exists() and any(destination.iterdir()):
        raise ReleaseError(f"bundle directory must be empty: {destination}")
    destination.mkdir(parents=True, exist_ok=True)

    patches: list[dict[str, object]] = []
    inputs: list[dict[str, object]] = []
    outputs: list[dict[str, object]] = []
    # Keep reconstructed full APKs inside the caller-controlled private build
    # tree. On sandboxed Windows hosts the process temp directory can grant a
    # child native executable read access but deny it creation rights.
    with tempfile.TemporaryDirectory(prefix="redcon-patch-", dir=destination.parent) as temp_name:
        temp = Path(temp_name)
        for item in mapping:
            source = Path(item["sourcePath"]).resolve(strict=True)
            target = Path(item["targetPath"]).resolve(strict=True)
            source_name = item["sourceName"]
            target_name = item["targetName"]
            patch_name = item["patchName"]
            patch = safe_child(destination, patch_name)
            inputs.append(descriptor(source, source_name))
            outputs.append(descriptor(target, target_name))
            run_checked([
                str(args.xdelta), "-9", "-a", "-A", "-S", "djw",
                "-B", str(spec["limits"]["sourceWindowBytes"]),
                "-W", str(spec["limits"]["inputWindowBytes"]),
                "-P", str(spec["limits"]["inputWindowBytes"]),
                "-I", str(spec["limits"]["inputWindowBytes"]),
                "-e", "-f", "-s", str(source), str(target), str(patch)
            ], f"xdelta encode {patch_name}")
            reconstructed = temp / target_name
            run_checked([
                str(args.xdelta), "-a",
                "-B", str(spec["limits"]["sourceWindowBytes"]),
                "-W", str(spec["limits"]["inputWindowBytes"]),
                "-I", str(spec["limits"]["inputWindowBytes"]),
                "-d", "-f", "-s", str(source), str(patch), str(reconstructed)
            ], f"xdelta verify {patch_name}")
            if sha256_file(reconstructed) != sha256_file(target):
                raise ReleaseError(f"xdelta did not exactly reconstruct {target_name}")
            patches.append({
                **descriptor(patch, patch_name), "source": source_name, "target": target_name,
                "format": "vcdiff-rfc3284-xdelta3-3.2.0"
            })

    entry_spec = spec["entry"]
    manifest = {
        "formatVersion": 1,
        "build": spec["build"],
        "minimumPatcherVersion": spec["minimumPatcherVersion"],
        "supported": spec["supported"],
        "entries": [{
            "id": entry_spec["id"],
            "input": {**entry_spec["input"], "files": inputs},
            "output": {**entry_spec["output"], "files": outputs},
            "patches": patches,
        }],
        "limits": spec["limits"],
    }
    validate_manifest(manifest, args.schema)
    data = canonical_json(manifest)
    signature, public_der = sign_manifest(data, args.manifest_key)
    verify_manifest(data, signature, public_der)
    atomic_write(destination / "manifest.json", data)
    atomic_write(destination / "manifest.sig", signature)
    atomic_write(destination / "manifest-public-key.der", public_der)
    print(json.dumps({"bundle": str(destination), "manifestSha256": sha256_file(destination / "manifest.json"),
                      "patchBytes": sum(item["size"] for item in patches)}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReleaseError as exc:
        raise SystemExit(f"ERROR: {exc}")
