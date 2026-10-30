#!/usr/bin/env python3
"""Fetch the pinned runtime bundle and verify every installed build asset."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("runtime-assets.json")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def matches(path: Path, item: dict) -> bool:
    return path.is_file() and path.stat().st_size == item["bytes"] and sha256(path) == item["sha256"]


def target_path(root: Path, name: str) -> Path:
    relative = PurePosixPath(name)
    if relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
        raise ValueError(f"Invalid asset path: {name}")
    target = (root / Path(*relative.parts)).resolve()
    target.relative_to(root.resolve())
    return target


def prepare(manifest: dict, root: Path, archive: Path | None, verify_only: bool) -> None:
    missing = [item for item in manifest["files"] if not matches(target_path(root, item["path"]), item)]
    if not missing:
        print(f"Verified {len(manifest['files'])} runtime assets.")
        return
    if verify_only:
        raise ValueError("Missing or mismatched assets: " + ", ".join(item["path"] for item in missing))

    bundle = manifest["archive"]
    if archive is None:
        cache = root / "android/build/runtime-assets"
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / bundle["name"]
        if not matches(archive, bundle):
            partial = archive.with_suffix(archive.suffix + ".part")
            print(f"Downloading {bundle['name']} ({bundle['bytes'] / 1_000_000:.1f} MB)...", flush=True)
            try:
                request = urllib.request.Request(bundle["url"], headers={"User-Agent": "blackbox-diary-assets/1.3.2"})
                with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                    shutil.copyfileobj(response, output, 1024 * 1024)
                if not matches(partial, bundle):
                    raise ValueError("Downloaded archive checksum does not match runtime-assets.json")
                os.replace(partial, archive)
            finally:
                partial.unlink(missing_ok=True)
    if not matches(archive, bundle):
        raise ValueError("Archive size or SHA-256 does not match runtime-assets.json")

    with zipfile.ZipFile(archive) as zipped:
        names = zipped.namelist()
        if len(names) != len(set(names)) or set(names) != {item["path"] for item in manifest["files"]}:
            raise ValueError("Archive entries do not match the asset manifest")
        for item in missing:
            target = target_path(root, item["path"])
            if zipped.getinfo(item["path"]).file_size != item["bytes"]:
                raise ValueError(f"Unexpected asset size: {item['path']}")
            target.parent.mkdir(parents=True, exist_ok=True)
            partial = target.with_name(target.name + ".part")
            try:
                with zipped.open(item["path"]) as source, partial.open("wb") as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                if not matches(partial, item):
                    raise ValueError(f"Asset checksum mismatch: {item['path']}")
                os.replace(partial, target)
            finally:
                partial.unlink(missing_ok=True)
            print(f"Installed {item['path']}", flush=True)
    print(f"Verified {len(manifest['files'])} runtime assets.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded ZIP instead of downloading")
    parser.add_argument("--verify-only", action="store_true", help="Verify installed assets without downloading or writing")
    args = parser.parse_args()
    try:
        prepare(json.loads(MANIFEST.read_text(encoding="utf-8")), ROOT, args.archive, args.verify_only)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Asset preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
