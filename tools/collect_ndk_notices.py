#!/usr/bin/env python3
"""Collect candidate notices from the exact installed NDK, without guessing licenses."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

ndk = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]) / "ndk-notices"
out.mkdir(parents=True, exist_ok=True)
candidates = []
for path in ndk.rglob("*"):
    if not path.is_file() or path.is_symlink():
        continue
    name = path.name.upper()
    if not name.startswith(("NOTICE", "LICENSE", "COPYING", "COPYRIGHT")):
        continue
    relative = path.relative_to(ndk)
    # Only text candidates, not executables or oversized files.
    if path.stat().st_size > 8 * 1024 * 1024:
        continue
    payload = path.read_bytes()
    if not payload or b"\0" in payload:
        continue
    target = out / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)
    candidates.append({"path": str(relative), "sha256": hashlib.sha256(payload).hexdigest()})
if not candidates:
    raise SystemExit("No installed NDK notice candidates")
(out / "inventory.json").write_text(json.dumps(sorted(candidates, key=lambda x: x["path"]), indent=2) + "\n")
print(f"Collected {len(candidates)} NDK notice candidates; applicability to libc++ requires review")
