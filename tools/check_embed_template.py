#!/usr/bin/env python3
"""Verify that the exported-game runtime does not include CodeLove editor code.

This checks the unsigned embed APK before it is bundled with CodeLove.
It must remain independent of the Java editor, SAF workspace and Sora editor.
The native LÖVE/SDL runtime and its own third-party license notices are kept.
"""
from pathlib import Path
import sys
from zipfile import ZipFile

EDITOR_CLASSES = (
    b"Lorg/love2d/android/Workspace;",
    b"Lorg/love2d/android/CodeEditor;",
    b"Lorg/love2d/android/CodeLoveConsolePanel;",
    b"Lorg/love2d/android/CodeLoveUpdater;",
    b"Lorg/love2d/android/CodeLoveLocale;",
    b"Lorg/love2d/android/GameExportDialog;",
    b"Lorg/love2d/android/GameExportArchive;",
    b"Lorg/love2d/android/DirectSafStorage;",
    b"Lorg/love2d/android/EditorActivity;",
    b"Lorg/love2d/android/MainActivity;",
    b"Lio/github/rosemoe/",
)

def check(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"Missing embed template: {path}")
    with ZipFile(path) as apk:
        entries = set(apk.namelist())
        dex_files = sorted(
            name for name in entries
            if name == "classes.dex"
            or (name.startswith("classes") and name.endswith(".dex")
                and name[7:-4].isdigit())
        )
        if not dex_files or "AndroidManifest.xml" not in entries:
            raise SystemExit("Missing runtime DEX or Android manifest")
        dex = b"".join(apk.read(name) for name in dex_files)
        if b"Lorg/love2d/android/GameActivity;" not in dex:
            raise SystemExit("The LÖVE runtime GameActivity is missing")
        for symbol in EDITOR_CLASSES:
            if symbol in dex:
                raise SystemExit(
                    f"Editor dependency leaked into embed APK: {symbol.decode()}"
                )

        if "assets/runtime-licenses.txt" not in entries or not apk.read("assets/runtime-licenses.txt").strip():
            raise SystemExit("Runtime license notices missing")
        if "assets/codelove-licenses.txt" in entries:
            raise SystemExit("CodeLove editor license screen asset leaked")
        if "resources.arsc" not in entries:
            raise SystemExit("Missing Android resources")
        resources = apk.read("resources.arsc")
        for symbol in (b"cl_credits_full", b"cl_open_files", b"codelove_brand_fg"):
            if symbol in resources:
                raise SystemExit(
                    f"CodeLove UI resources leaked into embed APK: {symbol.decode()}"
                )

    print(f"Standalone LÖVE runtime OK: {len(dex_files)} DEX file(s), no editor UI")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_embed_template.py path/to/embed-unsigned.apk")
    check(Path(sys.argv[1]))
