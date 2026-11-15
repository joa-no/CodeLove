#!/usr/bin/env python3
"""Offline APK repackaging feasibility prototype for CodeLove.

Replaces only assets/game.love in a previously built LÖVE Android embed APK.
Requires no Android SDK/NDK or network to do the ZIP rewrite.

IMPORTANT: OUTPUT IS UNSIGNED AND UNALIGNED. It must be aligned and signed
before installation. This script does not edit the Android package ID, app
label, version or icon. It is not a completed user-facing APK exporter.
"""
import argparse
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import zipfile

from prepare_game_export import prepare_love

GAME_ENTRY = "assets/game.love"
MAX_GAME_ARCHIVE = 512 * 1024 * 1024
COPY_BUFFER = 64 * 1024


def _is_signature_entry(name: str) -> bool:
    # Remove stale JAR/v1 signature files from the template. APK v2/v3
    # signatures are also discarded when zipfile reconstructs the archive.
    if not name.startswith("META-INF/"):
        return False
    leaf = name[len("META-INF/"):]
    return bool(leaf) and "/" not in leaf and (
        leaf.upper() == "MANIFEST.MF" or
        leaf.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
    )


def inject_game(template: Path, game: Path, output: Path) -> tuple[int, int]:
    """Create an unsigned APK with exactly one replacement game.love.

    The caller is responsible for zipalign -P 16, apksigner, and verifying
    installation on an Android device. Never modify the trusted template.
    """
    template = template.resolve(strict=True)
    game = game.resolve(strict=True)
    output = output.resolve()
    if not template.is_file() or not game.is_file():
        raise ValueError("Template APK and game.love must be regular files")
    if template == game or output in (template, game):
        raise ValueError("Output must not overwrite the template or game")
    if output.exists():
        raise ValueError("Output already exists; refusing overwrite")
    if game.stat().st_size > MAX_GAME_ARCHIVE:
        raise ValueError("Game LOVE archive is too large")

    # Reuse the same strict .love validation already tested by CodeLove.
    # The extraction happens only in a disposable directory.
    with tempfile.TemporaryDirectory(prefix="codelove-love-check-") as scratch:
        files, size = prepare_love(game, Path(scratch) / "game")

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output.parent, suffix=".apk.tmp",
            prefix=".codelove-offline-", delete=False
        ) as temp:
            temporary = Path(temp.name)

        with zipfile.ZipFile(template, "r") as original:
            entries = original.infolist()
            names = [item.filename for item in entries]
            if names.count(GAME_ENTRY) != 1 or "assets/main.lua" in names:
                raise ValueError(
                    "APK template must have one assets/game.love "
                    "and no loose assets/main.lua"
                )
            if len(names) != len(set(names)):
                raise ValueError("APK template has duplicate ZIP entries")
            with zipfile.ZipFile(temporary, "w", allowZip64=True) as built:
                built.comment = original.comment
                for entry in entries:
                    if entry.filename == GAME_ENTRY or _is_signature_entry(entry.filename):
                        continue
                    mode = stat.S_IFMT(entry.external_attr >> 16)
                    if mode not in (0, stat.S_IFREG, stat.S_IFDIR):
                        raise ValueError("Unsupported APK template entry type")
                    if entry.flag_bits & 1 or entry.compress_type not in (
                        zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED
                    ):
                        raise ValueError("Unsupported APK template encoding")
                    # Rebuild headers without stale extra fields. Android's
                    # 16 KiB native library alignment is restored later by
                    # zipalign, BEFORE the new APK is signed.
                    item = zipfile.ZipInfo(entry.filename, entry.date_time)
                    item.compress_type = entry.compress_type
                    item.create_system = entry.create_system
                    item.external_attr = entry.external_attr
                    item.internal_attr = entry.internal_attr
                    item.comment = entry.comment
                    with original.open(entry) as read, built.open(item, "w") as write:
                        shutil.copyfileobj(read, write, COPY_BUFFER)
                with game.open("rb") as read, built.open(
                    GAME_ENTRY, "w", force_zip64=False
                ) as write:
                    shutil.copyfileobj(read, write, COPY_BUFFER)

        with zipfile.ZipFile(temporary) as result:
            if result.testzip() is not None or result.namelist().count(GAME_ENTRY) != 1:
                raise ValueError("Repacked APK failed ZIP verification")
        temporary.replace(output)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return files, size


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--love", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        count, size = inject_game(args.template, args.love, args.out)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
        parser.error(str(exc))
    print(f"Unsigned APK repacked from {count} game files ({size} uncompressed bytes)")
    print("Not installable yet: zipalign and apksigner are required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
