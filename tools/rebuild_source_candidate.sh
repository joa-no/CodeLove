#!/usr/bin/env bash
# Independently compile the extracted archive, without the original checkout.
set -euo pipefail
candidate=$(realpath "$1")
destination=$(realpath -m "$2")
mkdir -p "$destination"
(cd "$candidate" && sha256sum -c SHA256SUMS)
shopt -s nullglob
archives=("$candidate"/codelove-source-*.tar.gz)
test "${#archives[@]}" -eq 1
tar -xzf "${archives[0]}" -C "$destination"
cd "$destination/codelove"
test ! -e .git
test -s app/src/main/resources/META-INF/CODELOVE-SOURCE.txt
test -s app/src/main/assets/ndk-runtime-notice.txt
test -s app/src/main/assets/jvm-source-notices.txt
# Avoid inheriting release credentials; this is a disposable debug rebuild.
unset CODELOVE_RELEASE_STORE_FILE CODELOVE_RELEASE_STORE_PASSWORD CODELOVE_RELEASE_KEY_ALIAS CODELOVE_RELEASE_KEY_PASSWORD
mkdir -p app/src/embed/assets app/src/normal/assets/offline
python3 - <<'PY'
from zipfile import ZipFile
with ZipFile("app/src/embed/assets/game.love", "w") as game:
    game.writestr("main.lua", "error('CodeLove template placeholder')")
PY
bash ./gradlew assembleEmbedNoRecordRelease \
  '-Papp.name=CodeLove Offline Template' \
  '-Papp.application_id=com.example.codelove.offlinetemplate' \
  '-Papp.version_name=1.0.0' '-Papp.version_code=1'
cp app/build/outputs/apk/embedNoRecord/release/app-embed-noRecord-release-unsigned.apk app/src/normal/assets/offline/embed-unsigned.apk
rm app/src/embed/assets/game.love
python3 tools/check_embed_template.py app/src/normal/assets/offline/embed-unsigned.apk
bash ./gradlew assembleNormalRecordDebug
python3 - <<'PY'
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
with ZipFile("app/build/outputs/apk/normalRecord/debug/app-normal-record-debug.apk") as editor:
    with ZipFile(BytesIO(editor.read("assets/offline/embed-unsigned.apk"))) as runtime:
        for archive in (editor, runtime):
            assert archive.read("META-INF/CODELOVE-SOURCE.txt") == Path("app/src/main/resources/META-INF/CODELOVE-SOURCE.txt").read_bytes()
            assert archive.read("assets/ndk-runtime-notice.txt") == Path("app/src/main/assets/ndk-runtime-notice.txt").read_bytes()
            assert archive.read("assets/jvm-source-notices.txt") == Path("app/src/main/assets/jvm-source-notices.txt").read_bytes()
            assert "lib/arm64-v8a/liblove.so" in archive.namelist()
print("Archive-only unmodified debug editor and unsigned runtime rebuilt successfully")
PY
