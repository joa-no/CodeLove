#!/usr/bin/env python3
"""Create a review candidate from committed source, including native submodules."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
destination = Path(sys.argv[1]).resolve()
sora = Path(sys.argv[2]).resolve()
destination.mkdir(parents=True, exist_ok=True)

def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args])

revision = git(root, "rev-parse", "HEAD").decode().strip()
sora_revision = "ab9f2cb23aeb30ae42f0ad9e32b281c6bdbcbfc9"
if git(sora, "rev-parse", "HEAD").decode().strip() != sora_revision:
    raise SystemExit("Unexpected Sora Editor source revision")
sources = []

def visit(path, prefix):
    sha = git(path, "rev-parse", "HEAD").decode().strip()
    sources.append((path, prefix, sha))
    for row in git(path, "ls-tree", "-rz", "HEAD").split(b"\0"):
        if not row:
            continue
        metadata, name = row.split(b"\t", 1)
        mode, kind, expected = metadata.decode().split()
        if mode != "160000":
            continue
        relative = name.decode("utf-8")
        child = path / relative
        if git(child, "rev-parse", "HEAD").decode().strip() != expected:
            raise SystemExit("Submodule checkout does not match gitlink: " + relative)
        visit(child, prefix + relative + "/")

visit(root, "codelove/")
visit(sora, "third-party/sora-editor/")
archive = destination / f"codelove-source-{revision[:12]}.tar.gz"
seen = set()
with tempfile.TemporaryDirectory() as temp:
    raw = Path(temp) / "archive.tar"
    with tarfile.open(archive, "w:gz", compresslevel=5) as output:
        for path, prefix, sha in sources:
            with raw.open("wb") as stream:
                subprocess.run(["git", "-C", str(path), "archive", "--format=tar",
                                "--prefix=" + prefix, sha], stdout=stream, check=True)
            with tarfile.open(raw, "r:") as part:
                for member in part:
                    if member.isdir():
                        continue
                    name = member.name
                    if name in seen or name.startswith("/") or ".." in Path(name).parts:
                        raise SystemExit("Invalid or duplicate archived path: " + name)
                    if name.endswith((".jks", ".keystore", ".p12", ".pfx", ".key")) and name != "codelove/app/codelove-debug.p12":
                        raise SystemExit("Unexpected tracked signing material: " + name)
                    if not (member.isfile() or member.issym()):
                        raise SystemExit("Unsupported archived entry: " + name)
                    seen.add(name)
                    output.addfile(member, part.extractfile(member) if member.isfile() else None)

        # Include only the explicitly listed generated inputs, not the
        # working tree, signing material, build output, or cached credentials.
        for relative in ("app/src/main/resources/META-INF/CODELOVE-SOURCE.txt",
                         "app/src/main/assets/ndk-runtime-notice.txt",
                         "app/src/main/assets/jvm-source-notices.txt"):
            name = "codelove/" + relative
            source = root / relative
            if name in seen or not source.is_file() or source.is_symlink():
                raise SystemExit("Missing or duplicate generated source input: " + relative)
            seen.add(name)
            output.add(source, arcname=name, recursive=False)

required = {
    "codelove/LICENSE", "codelove/LICENSE-runtime.txt", "codelove/gradlew",
    "codelove/app/build.gradle", "codelove/docs/rebuild-and-relink.md",
    "codelove/app/src/main/cpp/love/license.txt",
    "codelove/app/src/main/cpp/megasource/libs/openal-soft/COPYING",
    "third-party/sora-editor/LICENSE",
    "codelove/app/src/main/resources/META-INF/CODELOVE-SOURCE.txt",
    "codelove/app/src/main/assets/ndk-runtime-notice.txt",
    "codelove/app/src/main/assets/jvm-source-notices.txt",
}
missing = required - seen
if missing:
    raise SystemExit("Missing archived source or licenses: " + str(sorted(missing)))
digest = hashlib.sha256()
with archive.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
(destination / "SHA256SUMS").write_text(f"{digest.hexdigest()}  {archive.name}\n")
(destination / "source-inventory.json").write_text(json.dumps({
    "status": "validated source candidate; official release publication pending",
    "repositories": [{"path": prefix, "revision": sha} for _, prefix, sha in sources],
    "archived_entries": len(seen),
    "sha256": digest.hexdigest(),
    "known_pending": ["validate integrated release candidate", "publish matching source with official binaries"]
}, indent=2) + "\n")
print(f"Source candidate: {archive.name}, {len(seen)} entries, {archive.stat().st_size} bytes")
