#!/usr/bin/env python3
"""Compile a verified CodeLove build request in an isolated Android checkout.

Requires JDK, SDK/NDK/Gradle, Pillow and a *game-specific* signing key.
This is a builder-side CLI, NOT an HTTP service and NOT an Android app feature.
Never execute against an untrusted checkout or expose keys in logs.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from game_build_request import prepare_request

SIGNING_ENV = {
    "GAME_SIGNING_STORE_FILE": "CODELOVE_RELEASE_STORE_FILE",
    "GAME_SIGNING_STORE_PASSWORD": "CODELOVE_RELEASE_STORE_PASSWORD",
    "GAME_SIGNING_KEY_ALIAS": "CODELOVE_RELEASE_KEY_ALIAS",
    "GAME_SIGNING_KEY_PASSWORD": "CODELOVE_RELEASE_KEY_PASSWORD",
}


def _tool(sdk: Path, executable: str) -> Path:
    root = sdk / "build-tools"
    candidates = sorted(
        (p / executable for p in root.iterdir() if p.is_dir()
         and (p / executable).is_file()),
        key=lambda p: tuple(
            int(x) for x in re.findall(r"\d+", p.parent.name)),
        reverse=True
    )
    if not candidates:
        raise ValueError(f"Missing Android build tool: {executable}")
    return candidates[0]


def _signing_environment() -> dict:
    signing = {}
    for source, destination in SIGNING_ENV.items():
        value = os.environ.get(source)
        if not value:
            raise ValueError(f"Missing game signing configuration: {source}")
        signing[destination] = value
    keystore = Path(signing["CODELOVE_RELEASE_STORE_FILE"]).resolve(strict=True)
    if not keystore.is_file():
        raise ValueError("Game keystore must be an existing file")
    signing["CODELOVE_RELEASE_STORE_FILE"] = str(keystore)
    # Don't let a host's CodeLove release key silently become the game key.
    result = os.environ.copy()
    for value in SIGNING_ENV.values():
        result.pop(value, None)
    result.update(signing)
    return result


def build(request: Path, repo: Path, destination: Path) -> Path:
    repo = repo.resolve(strict=True)
    request = request.resolve(strict=True)
    destination = destination.resolve()
    if not (repo / "gradlew").is_file() or not (repo / "app/build.gradle").is_file():
        raise ValueError("Builder must run in a CodeLove Android checkout")
    if destination.exists():
        raise ValueError("Output APK already exists; refusing overwrite")
    game_assets = repo / "app/src/embed/assets"
    if game_assets in destination.parents:
        raise ValueError("Output APK cannot be placed inside source assets")
    env = _signing_environment()
    sdk_path = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk_path:
        raise ValueError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    sdk = Path(sdk_path).resolve(strict=True)
    apksigner = _tool(sdk, "apksigner")
    aapt = _tool(sdk, "aapt")

    metadata, count, size = prepare_request(
        request, game_assets, repo / "app/src/embed/res")
    print(f"Validated build request: {count} files, {size} bytes", flush=True)

    command = [
        "bash", str(repo / "gradlew"), "assembleEmbedNoRecordRelease",
        "-Papp.name=" + metadata["name"],
        "-Papp.application_id=" + metadata["package"],
        "-Papp.version_name=" + metadata["version_name"],
        "-Papp.version_code=" + str(metadata["version_code"]),
    ]
    # Never run the user's name/package through a shell or print secrets.
    subprocess.run(command, cwd=repo, env=env, check=True)
    apk = repo / "app/build/outputs/apk/embedNoRecord/release/app-embed-noRecord-release.apk"
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Gradle did not produce the expected signed release APK")
    subprocess.run([str(apksigner), "verify", str(apk)],
                   cwd=repo, check=True)
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)],
                             cwd=repo, check=True, capture_output=True, text=True).stdout
    match = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        badging, re.MULTILINE)
    if not match or (match.group(1) != metadata["package"]
                     or int(match.group(2)) != metadata["version_code"]
                     or match.group(3) != metadata["version_name"]):
        raise ValueError("Resulting APK identity did not match the build request")
    with zipfile.ZipFile(apk) as contents:
        if "assets/main.lua" not in contents.namelist():
            raise ValueError("Resulting APK lacks the embedded game")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                dir=destination.parent, prefix=".codelove-apk-",
                suffix=".tmp", delete=False) as file:
            temporary = Path(file.name)
            with apk.open("rb") as source:
                shutil.copyfileobj(source, file)
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(f"Signed game APK available at {destination}", flush=True)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--repo", type=Path,
                        default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.request, args.repo, args.output)
    except (OSError, ValueError, subprocess.CalledProcessError,
            zipfile.BadZipFile) as exc:
        print(f"CodeLove build failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
