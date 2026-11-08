#!/usr/bin/env python3
"""Reference offline patcher; Android must enforce the same manifest contract."""

from __future__ import annotations

import argparse
import os
import shutil
from pathlib import Path

from release_common import (ReleaseError, exact_file, load_json, run_checked,
                            safe_child, sha256_file, validate_manifest,
                            verify_manifest)

PINNED_XDELTA_SHA256 = "6e812b38484d0c764291779fadee83ffbe2eceaccb4e3a21f8a043a02654a01e"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--xdelta", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, required=True, help="trusted public key obtained separately")
    parser.add_argument("--schema", type=Path, default=Path("release-config/manifest.schema.json"))
    args = parser.parse_args()

    bundle = args.bundle.resolve(strict=True)
    manifest_path = safe_child(bundle, "manifest.json")
    signature_path = safe_child(bundle, "manifest.sig")
    public_key_path = args.public_key
    data = manifest_path.read_bytes()
    verify_manifest(data, signature_path.read_bytes(), public_key_path.read_bytes())
    manifest = load_json(manifest_path)
    validate_manifest(manifest, args.schema)
    if sha256_file(args.xdelta) != PINNED_XDELTA_SHA256:
        raise ReleaseError("xdelta executable is not the pinned v3.2.0 build")

    matches = []
    for entry in manifest["entries"]:
        try:
            for item in entry["input"]["files"]:
                exact_file(safe_child(args.input, item["name"]), item["size"], item["sha256"], "input")
            matches.append(entry)
        except ReleaseError:
            pass
    if len(matches) != 1:
        raise ReleaseError("input set is unsupported or ambiguous")
    entry = matches[0]
    required = sum(item["size"] for item in entry["output"]["files"])
    required += manifest["limits"]["minFreeSpaceBytes"]
    args.output.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(args.output).free < required:
        raise ReleaseError("insufficient free space for safe reconstruction")

    temporary = args.output / ".redcon-patch-tmp"
    temporary.mkdir(exist_ok=False)
    try:
        outputs = {item["name"]: item for item in entry["output"]["files"]}
        for patch in entry["patches"]:
            patch_path = safe_child(bundle, patch["name"])
            exact_file(patch_path, patch["size"], patch["sha256"], "patch")
            target = safe_child(temporary, patch["target"])
            run_checked([
                str(args.xdelta), "-a", "-B", str(manifest["limits"]["sourceWindowBytes"]),
                "-W", str(manifest["limits"]["inputWindowBytes"]),
                "-I", str(manifest["limits"]["inputWindowBytes"]), "-d", "-f", "-s",
                str(safe_child(args.input, patch["source"])), str(patch_path), str(target)
            ], f"xdelta decode {patch['name']}")
            expected = outputs[patch["target"]]
            exact_file(target, expected["size"], expected["sha256"], "reconstructed output")
        for item in entry["output"]["files"]:
            final = safe_child(args.output, item["name"])
            if final.exists():
                raise ReleaseError(f"refusing to overwrite output: {final}")
            os.replace(safe_child(temporary, item["name"]), final)
    finally:
        shutil.rmtree(temporary, ignore_errors=True)
    print(f"PASS - reconstructed {len(entry['output']['files'])} exact signed APKs")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReleaseError as exc:
        raise SystemExit(f"ERROR: {exc}")
