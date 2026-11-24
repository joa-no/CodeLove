#!/usr/bin/env python3
"""Record exact source revisions used by this checkout; no network or secrets."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]

def git(*args, cwd=root):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()

revision = git("rev-parse", "HEAD")
if not re.fullmatch(r"[0-9a-f]{40}", revision):
    raise SystemExit("Invalid CodeLove revision")
lines = [
    "CodeLove / standalone LOVE runtime source information",
    "CodeLove revision: " + revision,
    "Source: https://github.com/joa-no/CodeLove/tree/" + revision,
    "Rebuild instructions: https://github.com/joa-no/CodeLove/blob/" + revision + "/docs/rebuild-and-relink.md",
    "Native submodules:"
]
for path, upstream in (
    ("app/src/main/cpp/love", "https://github.com/love2d/love"),
    ("app/src/main/cpp/megasource", "https://github.com/love2d/megasource"),
):
    actual = git("rev-parse", "HEAD", cwd=root / path)
    entry = git("ls-tree", "HEAD", "--", path).split()
    if len(entry) < 3 or entry[0] != "160000" or entry[2] != actual:
        raise SystemExit("Uninitialized or mismatched submodule: " + path)
    lines.extend([path + ": " + actual, upstream + "/tree/" + actual])
lines.append("Sora Editor 0.24.7 (editor only): https://github.com/Rosemoe/sora-editor/tree/0.24.7")
lines.append("Corresponding source and redistribution instructions are supplied with the release; this file identifies the revision, not a complete source offer.")
target = root / "app/src/main/resources/META-INF/CODELOVE-SOURCE.txt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Recorded source revision " + revision)
