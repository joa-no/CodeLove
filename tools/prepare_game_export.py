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
import stat
import sys
import zipfile

PACKAGE_RE = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)+\Z")
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_FILES = 10000
MAX_ENTRIES = 10000

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


def prepare_love(source: Path, destination: Path) -> tuple[int, int]:
    """Stage a .love ZIP without trusting embedded paths or metadata."""
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if not source.is_file():
        raise ValueError("LOVE input must be a file")
    if destination.exists():
        if not destination.is_dir() or any(
            child.name != ".gitkeep" for child in destination.iterdir()
        ):
            raise ValueError("Export asset directory is not empty; refusing to overwrite it")

    files = set()
    directories = set()
    seen = set()
    pending = []
    expected_bytes = 0
    with zipfile.ZipFile(source, "r") as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError("LOVE archive exceeds 10000 entries")
        # Preflight the *entire* archive before creating any staged files.
        for info in infos:
            name = info.filename
            is_dir = info.is_dir()
            logical = name[:-1] if is_dir else name
            parts = logical.split("/")
            if (not logical or name.startswith("/") or "\\" in name
                    or "\x00" in name or ":" in parts[0]
                    or any(part in ("", ".", "..") for part in parts)):
                raise ValueError(f"Unsafe LOVE path: {name!r}")
            if logical in seen:
                raise ValueError(f"Duplicate LOVE entry: {logical}")
            seen.add(logical)
            mode = stat.S_IFMT(info.external_attr >> 16)
            if mode not in (0, stat.S_IFDIR if is_dir else stat.S_IFREG):
                raise ValueError(f"Unsupported LOVE entry type: {logical}")
            if info.flag_bits & 1 or info.compress_type not in (
                    zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError(f"Unsupported LOVE entry encoding: {logical}")
            for depth in range(1, len(parts)):
                parent = "/".join(parts[:depth])
                if parent in files:
                    raise ValueError(f"File/directory conflict: {parent}")
                directories.add(parent)
            if is_dir:
                if logical in files:
                    raise ValueError(f"File/directory conflict: {logical}")
                directories.add(logical)
                continue
            if logical in directories:
                raise ValueError(f"File/directory conflict: {logical}")
            files.add(logical)
            expected_bytes += info.file_size
            if len(files) > MAX_FILES or expected_bytes > MAX_TOTAL_BYTES:
                raise ValueError("LOVE archive exceeds export size/file limits")
            pending.append((info, Path(*parts)))
        if "main.lua" not in files:
            raise ValueError("LOVE archive must contain main.lua at its root")

        destination.mkdir(parents=True, exist_ok=True)
        for directory in sorted(directories, key=lambda p: (p.count("/"), p)):
            (destination / Path(*directory.split("/"))).mkdir(parents=True, exist_ok=True)
        actual_bytes = 0
        for info, relative in pending:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            # Never use extractall: stream only validated entries.
            with archive.open(info) as reader, target.open("xb") as writer:
                while True:
                    chunk = reader.read(65536)
                    if not chunk:
                        break
                    actual_bytes += len(chunk)
                    if actual_bytes > MAX_TOTAL_BYTES:
                        raise ValueError("LOVE archive exceeds 512 MiB uncompressed")
                    writer.write(chunk)
    return len(files), actual_bytes


def prepare_icon(source: Path, resources: Path) -> dict[str, Path]:
    """Generate launcher icons for the embed flavor only.

    Requires Pillow when --icon is provided. This step runs on the isolated
    builder, never on the phone or against the CodeLove launcher resources.
    """
    source = source.resolve(strict=True)
    if not source.is_file() or source.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Icon must be an image no larger than 8 MiB")
    try:
        from PIL import Image, ImageOps
    except ImportError as exc:
        raise ValueError("Pillow is required to stage a custom game icon") from exc

    with Image.open(source) as picture:
        if picture.format not in ("PNG", "JPEG", "WEBP"):
            raise ValueError("Icon must be PNG, JPEG or WebP")
        width, height = picture.size
        if min(width, height) < 192 or max(width, height) > 4096:
            raise ValueError("Icon dimensions must be between 192 and 4096 pixels")
        picture.load()
        picture = picture.convert("RGBA")
        result = {}
        for density, size in (
            ("mdpi", 48),
            ("hdpi", 72),
            ("xhdpi", 96),
            ("xxhdpi", 144),
            ("xxxhdpi", 192),
        ):
            # Same qualifiers as main/res, but in the higher-priority embed
            # source set. This deliberately leaves the normal CodeLove icon.
            output = resources / f"drawable-{density}" / "love.png"
            if output.exists():
                raise ValueError(f"Refusing to overwrite existing icon: {output}")
            output.parent.mkdir(parents=True, exist_ok=True)
            ImageOps.fit(picture, (size, size), method=Image.Resampling.LANCZOS
                         ).save(output, format="PNG", optimize=True)
            result[density] = output
        return result

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--project", type=Path)
    source.add_argument("--love", type=Path)
    parser.add_argument("--assets", type=Path, default=Path("app/src/embed/assets"))
    parser.add_argument("--icon", type=Path,
                        help="Custom PNG, JPEG or WebP launcher image")
    parser.add_argument("--icon-res", type=Path, default=Path("app/src/embed/res"),
                        help="Embed-only Android resource output directory")
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
        count, size = (prepare_love(args.love, args.assets)
                       if args.love is not None else prepare(args.project, args.assets))
        if args.icon is not None:
            staged = prepare_icon(args.icon, args.icon_res)
            print(f"Staged custom icon for {len(staged)} Android densities")
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
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
