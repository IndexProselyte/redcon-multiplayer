#!/usr/bin/env python3
"""Extract and verify the public patch bundle from the distributed installer."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile

from release_common import ReleaseError, validate_manifest, verify_manifest

ROOT = Path(__file__).resolve().parents[2]
RELEASE_APK_SHA256 = "76b7b8346fe98d2fd28e81c85078b9e4f112772a89f7f73fabd4e520cb8c4840"
NAMES = {"manifest.json", "manifest.sig", "manifest-public-key.der", "base.vcdiff",
         "split_config.arm64_v8a.vcdiff", "THIRD-PARTY-NOTICES.txt"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "redcon-pvp-patcher.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "build/patch-bundle")
    parser.add_argument("--apk-sha256", default=RELEASE_APK_SHA256,
                        help="expected installer hash from a trusted release")
    args = parser.parse_args()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != args.apk_sha256.lower():
        raise ReleaseError("installer SHA-256 does not match the expected release")
    if args.output.exists():
        raise ReleaseError("output already exists; use a new directory")
    with zipfile.ZipFile(args.apk) as archive:
        members = [i for i in archive.infolist() if i.filename.startswith("assets/") and not i.is_dir()]
        if len(members) != len(NAMES) or {i.filename for i in members} != {"assets/" + n for n in NAMES}:
            raise ReleaseError("installer asset list differs from the supported bundle")
        if any(i.file_size > 16 * 1024 * 1024 for i in members) or sum(i.file_size for i in members) > 32 * 1024 * 1024:
            raise ReleaseError("bundle exceeds extraction limits")
        data = {n: archive.read("assets/" + n) for n in NAMES}
    verify_manifest(data["manifest.json"], data["manifest.sig"], data["manifest-public-key.der"])
    # The expected APK hash authenticates the bundled public key. A key supplied
    # inside an otherwise untrusted archive is not an independent trust anchor.
    import json
    manifest = json.loads(data["manifest.json"])
    validate_manifest(manifest, ROOT / "release-config/manifest.schema.json")
    for entry in manifest["entries"]:
        for patch in entry["patches"]:
            payload = data.get(patch["name"])
            if payload is None or len(payload) != patch["size"] or hashlib.sha256(payload).hexdigest() != patch["sha256"]:
                raise ReleaseError("bundled patch does not match its signed descriptor")
    args.output.mkdir(parents=True, exist_ok=False)
    for name in sorted(NAMES):
        (args.output / name).write_bytes(data[name])
    print("PASS - extracted six verified public assets; no original game APK or private key")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReleaseError as exc:
        raise SystemExit(f"ERROR: {exc}")
