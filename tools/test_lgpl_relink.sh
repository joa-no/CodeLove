#!/usr/bin/env bash
# Test replacement of LGPL libraries from the extracted candidate, never official release.
set -euo pipefail
root=$(realpath "$1")
out=$(realpath -m "$2")
mkdir -p "$out"
cd "$root/codelove"
test ! -e .git
unset CODELOVE_RELEASE_STORE_FILE CODELOVE_RELEASE_STORE_PASSWORD CODELOVE_RELEASE_KEY_ALIAS CODELOVE_RELEASE_KEY_PASSWORD
sora="$root/third-party/sora-editor"
python3 - "$sora" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
java = root / "editor/src/main/java/io/github/rosemoe/sora/widget/CodeEditor.java"
source = java.read_text()
pos = source.rfind("}")
assert pos >= 0 and not source[pos+1:].strip()
java.write_text(source[:pos] + '''
    // Local LGPL relink test: deliberately modified library.
    public static String codeloveRelinkAuditMarker() {
        return "CODELOVE_SORA_RELINK_AUDIT";
    }
''' + source[pos:])
native = Path("app/src/main/cpp/megasource/libs/openal-soft/al/state.cpp")
text = native.read_text()
original = 'return "OpenAL Soft";'
assert text.count(original) == 1
native.write_text(text.replace(original, 'return "OpenAL Soft CODELOVE_OPENAL_RELINK_AUDIT";'))
PY
# Build the actual modified Sora source, not a repackaged Maven binary.
# CI=false preserves the upstream version and avoids Git-dependent CI naming.
(cd "$sora" && CI=false bash ./gradlew :editor:assembleRelease --console=plain)
aar="$sora/editor/build/outputs/aar/editor-release.aar"
test -s "$aar"
mkdir -p app/src/embed/assets app/src/normal/assets/offline
python3 - <<'PY'
from zipfile import ZipFile
with ZipFile("app/src/embed/assets/game.love", "w") as game:
    game.writestr("main.lua", "error('CodeLove relink template placeholder')")
PY
bash ./gradlew assembleEmbedNoRecordRelease \
  '-Papp.name=CodeLove Offline Template' \
  '-Papp.application_id=com.example.codelove.offlinetemplate' \
  '-Papp.version_name=1.0.0' '-Papp.version_code=1' \
  "-Pcodelove.localSoraAar=$aar"
cp app/build/outputs/apk/embedNoRecord/release/app-embed-noRecord-release-unsigned.apk app/src/normal/assets/offline/embed-unsigned.apk
rm app/src/embed/assets/game.love
python3 tools/check_embed_template.py app/src/normal/assets/offline/embed-unsigned.apk
# Separate application ID and public debug key protect the user's normal installation.
bash ./gradlew assembleNormalRecordDebug \
  '-Papp.application_id=org.codelove.relinktest' \
  '-Papp.name=CodeLove Relink Test' "-Pcodelove.localSoraAar=$aar"
python3 - <<'PY'
from io import BytesIO
from zipfile import ZipFile
from pathlib import Path
p = Path("app/build/outputs/apk/normalRecord/debug/app-normal-record-debug.apk")
def verify_native(archive):
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        libs = [name for name in archive.namelist() if name.startswith(f"lib/{abi}/") and name.lower().endswith("/libopenal.so")]
        assert len(libs) == 1, (abi, libs)
        assert b"CODELOVE_OPENAL_RELINK_AUDIT" in archive.read(libs[0]), abi
def dex_has(archive, marker):
    return any(marker in archive.read(n) for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex"))
with ZipFile(p) as editor:
    assert dex_has(editor, b"CODELOVE_SORA_RELINK_AUDIT"), "Modified Sora not found in editor"
    verify_native(editor)
    with ZipFile(BytesIO(editor.read("assets/offline/embed-unsigned.apk"))) as runtime:
        assert not dex_has(runtime, b"CODELOVE_SORA_RELINK_AUDIT"), "Sora leaked into game runtime"
        verify_native(runtime)
        assert "assets/runtime-licenses.txt" in runtime.namelist()
print("Modified Sora and OpenAL source compiled and verified in the correct APK flavors")
PY
cp app/build/outputs/apk/normalRecord/debug/app-normal-record-debug.apk "$out/CodeLove-relink-test.apk"
# Test binary only. Never attach it to the official release.
