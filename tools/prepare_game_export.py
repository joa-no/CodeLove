#!/usr/bin/env python3
"""Prepare a saved LÖVE project for the existing Android 'embed' flavor.

Run in an isolated CI checkout, not inside the user's active SAF directory.
The script stages assets only; Gradle/NDK and release signing run separately.
"""
import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import sys

PACKAGE_RE = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)+\Z")
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_FILES = 10000

def prepare(source: Path, destination: Path) -> tuple[int, int]:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if not source.is_dir() or not (source / "main.lua").is_file():
        raise ValueError("Project must be a directory containing main.lua at its root")
    if destination == source or source in destination.parents or destination in source.parents:
        raise ValueError("Source and export asset directory must not contain each other")
    if destination.exists() and any(destination.iterdir()):
        # Do not delete existing files automatically. In CI, checkout contains
        # only .gitkeep in the dedicated embed/assets folder.
        entries = list(destination.iterdir())
        if any(x.name != ".gitkeep" for x in entries):
            raise ValueError("Export asset directory is not empty; refusing to overwrite it")
    pending = []
    total = 0
    for root, dirs, files in os.walk(source, followlinks=False):
        base = Path(root)
        for name in dirs:
            path = base / name
            if path.is_symlink():
                raise ValueError(f"Symbolic link is not supported: {path}")
        for name in files:
            path = base / name
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"Unsupported project entry: {path}")
            relative = path.relative_to(source)
            if any(part == ".git" for part in relative.parts):
                continue
            size = path.stat().st_size
            total += size
            pending.append((path, relative))
            if total > MAX_TOTAL_BYTES or len(pending) > MAX_FILES:
                raise ValueError("Project exceeds export limit (512 MiB / 10000 files)")
    destination.mkdir(parents=True, exist_ok=True)
    for origin, relative in pending:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(origin, target)
    return len(pending), total

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--assets", type=Path, default=Path("app/src/embed/assets"))
    parser.add_argument("--name", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version-name", default="1.0.0")
    parser.add_argument("--version-code", type=int, default=1)
    args = parser.parse_args()
    if not args.name.strip() or "\n" in args.name or "\r" in args.name:
        parser.error("Game name must be non-empty and single-line")
    if not PACKAGE_RE.fullmatch(args.package):
        parser.error("Invalid Android application ID")
    if args.version_code < 1 or not args.version_name.strip():
        parser.error("Version code must be positive and version name non-empty")
    try:
        count, size = prepare(args.project, args.assets)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(f"Staged {count} files ({size} bytes) into {args.assets}")
    print("Build in this isolated checkout with:")
    print(f"./gradlew assembleEmbedNoRecordRelease {shlex.quote('-Papp.name=' + args.name)} "
          f"{shlex.quote('-Papp.application_id=' + args.package)} {shlex.quote('-Papp.version_name=' + args.version_name)} "
          f"-Papp.version_code={args.version_code}")
    print("Release signing must be configured separately; this script never signs APKs.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
