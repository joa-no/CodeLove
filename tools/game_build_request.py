#!/usr/bin/env python3
"""Validate and stage a CodeLove Android build-request ZIP.

A build request contains *data only*, never a keystore, token or Gradle script.
This module is a local input adapter for an isolated APK builder; it does not
upload projects to GitHub or invoke a build service.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile

FORMAT = "codelove.android-build.v1"
MAX_GAME = 512 * 1024 * 1024
MAX_ICON = 8 * 1024 * 1024
MAX_MANIFEST = 16 * 1024
PACKAGE_RE = re.compile(
    r"[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)+\Z"
)
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")


def _is_int(value):
    return type(value) is int


def validate_request(metadata: object, *, has_icon: bool) -> dict:
    if not isinstance(metadata, dict):
        raise ValueError("Build request metadata must be an object")
    fields = {"format", "name", "package", "version_name",
              "version_code", "game_love"}
    if has_icon:
        fields.add("icon_png")
    if set(metadata) != fields or metadata["format"] != FORMAT:
        raise ValueError("Unsupported build request format or fields")
    name = metadata["name"]
    package = metadata["package"]
    version_name = metadata["version_name"]
    version_code = metadata["version_code"]
    if (not isinstance(name, str) or not name.strip() or len(name) > 100
            or "\r" in name or "\n" in name or "\x00" in name):
        raise ValueError("Invalid game name in build request")
    if not isinstance(package, str) or not PACKAGE_RE.fullmatch(package):
        raise ValueError("Invalid Android package ID in build request")
    if (not isinstance(version_name, str) or not version_name.strip()
            or len(version_name) > 64 or any(
                c in version_name for c in "\x00\r\n")):
        raise ValueError("Invalid version name in build request")
    if not _is_int(version_code) or not 1 <= version_code <= 2_100_000_000:
        raise ValueError("Invalid numeric version in build request")
    _validate_file(metadata["game_love"], MAX_GAME, "game_love")
    if has_icon:
        _validate_file(metadata["icon_png"], MAX_ICON, "icon_png")
    return metadata


def _validate_file(meta: object, limit: int, label: str) -> None:
    if not isinstance(meta, dict) or set(meta) != {"size", "sha256"}:
        raise ValueError(f"Invalid {label} metadata")
    if not _is_int(meta["size"]) or not 0 < meta["size"] <= limit:
        raise ValueError(f"Invalid {label} size")
    if not isinstance(meta["sha256"], str) or not SHA256_RE.fullmatch(meta["sha256"]):
        raise ValueError(f"Invalid {label} SHA-256")


def _copy_verified(source, destination: Path, entry: zipfile.ZipInfo,
                   expected: dict, maximum: int) -> None:
    digest = hashlib.sha256()
    total = 0
    with source.open(entry) as infile, destination.open("xb") as outfile:
        while True:
            chunk = infile.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > maximum or total > expected["size"]:
                raise ValueError(f"Archive member exceeds declared size: {entry.filename}")
            digest.update(chunk)
            outfile.write(chunk)
    if total != expected["size"] or digest.hexdigest() != expected["sha256"]:
        raise ValueError(f"SHA-256 or size mismatch: {entry.filename}")


def prepare_request(source: Path, assets: Path, icon_resources: Path):
    """Return validated metadata and staged project stats.

    Run in a fresh isolated checkout. Caller is responsible for cleaning that
    checkout if processing fails, and for keeping private build data private.
    """
    from prepare_game_export import prepare_love, prepare_icon

    source = source.resolve(strict=True)
    assets = assets.resolve()
    icon_resources = icon_resources.resolve()
    if not source.is_file():
        raise ValueError("Build request must be a file")
    if assets.exists() and (not assets.is_dir() or any(
            p.name != ".gitkeep" for p in assets.iterdir())):
        raise ValueError("Build assets directory is not empty")
    if icon_resources.exists() and any(icon_resources.rglob("love.png")):
        raise ValueError("Game icon resources already exist")

    with zipfile.ZipFile(source, "r") as bundle:
        info_list = bundle.infolist()
        names = [info.filename for info in info_list]
        if len(info_list) not in (2, 3) or len(names) != len(set(names)):
            raise ValueError("Build request must contain two or three unique files")
        has_icon = "icon.png" in names
        expected = {"request.json", "game.love"}
        if has_icon:
            expected.add("icon.png")
        if set(names) != expected:
            raise ValueError("Build request has missing or unexpected members")
        infos = {i.filename: i for i in info_list}
        for entry in info_list:
            mode = stat.S_IFMT(entry.external_attr >> 16)
            if (entry.is_dir() or mode not in (0, stat.S_IFREG)
                    or entry.flag_bits & 1 or entry.compress_type not in (
                        zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)):
                raise ValueError(f"Unsupported bundle entry: {entry.filename}")
        if infos["request.json"].file_size > MAX_MANIFEST:
            raise ValueError("Build request manifest is too large")
        try:
            metadata = json.loads(bundle.read("request.json"))
        except (UnicodeError, ValueError) as exc:
            raise ValueError("Build request JSON is invalid") from exc
        metadata = validate_request(metadata, has_icon=has_icon)
        if infos["game.love"].file_size != metadata["game_love"]["size"]:
            raise ValueError("Incorrect game.love member size")
        if has_icon and infos["icon.png"].file_size != metadata["icon_png"]["size"]:
            raise ValueError("Incorrect icon.png member size")

        with tempfile.TemporaryDirectory(prefix="codelove-build-") as folder:
            temp = Path(folder)
            love = temp / "game.love"
            _copy_verified(bundle, love, infos["game.love"],
                           metadata["game_love"], MAX_GAME)
            icon = None
            if has_icon:
                icon = temp / "icon.png"
                _copy_verified(bundle, icon, infos["icon.png"],
                               metadata["icon_png"], MAX_ICON)
            count, size = prepare_love(love, assets)
            if icon is not None:
                prepare_icon(icon, icon_resources)
    return metadata, count, size
