#!/usr/bin/env python3
"""Collect original notices for review without changing APK content or dependency versions."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile, is_zipfile

root = Path(sys.argv[1])
rows = json.loads((root / "resolved-artifacts.json").read_text())
if not rows:
    raise SystemExit("No resolved dependency artifacts")
inventory = {}
for row in rows:
    coordinate = row["coordinate"]
    source = Path(row["path"])
    item = inventory.setdefault(coordinate, {"configurations": set(), "files": {}})
    item["configurations"].add(row["configuration"])
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest in item["files"]:
        continue
    found = []
    target = root / "original-notices" / re.sub(r"[^A-Za-z0-9._-]", "_", coordinate) / digest
    def scan(z, prefix="", depth=0):
        for entry in z.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            leaf = name.rsplit("/", 1)[-1].upper()
            if re.match(r"^(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT|DEPENDENCIES|THIRD[-_ ]PARTY)([._-]|$)", leaf):
                payload = z.read(entry)
                label = prefix + name
                target.mkdir(parents=True, exist_ok=True)
                filename = hashlib.sha256(label.encode()).hexdigest()[:16] + "-" + re.sub(r"[^A-Za-z0-9._-]", "_", leaf)
                (target / filename).write_bytes(payload)
                found.append({"entry": label, "file": str((target / filename).relative_to(root))})
            elif depth == 0 and name.endswith(".jar"):
                nested = io.BytesIO(z.read(entry))
                if is_zipfile(nested):
                    with ZipFile(nested) as jar:
                        scan(jar, name + "!", 1)
    if is_zipfile(source):
        with ZipFile(source) as archive:
            scan(archive)
    item["files"][digest] = {"artifact": source.name, "notices": found}
for coordinate, item in inventory.items():
    item["configurations"] = sorted(item["configurations"])
    if any(c.startswith("embed") for c in item["configurations"]):
        if coordinate.startswith(("io.github.rosemoe:", "com.android.tools.build:apksig:", "org.bouncycastle:", "org.jspecify:")):
            raise SystemExit("Unexpected editor-only dependency in runtime: " + coordinate)
(root / "dependency-notice-inventory.json").write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n")
(root / "resolved-artifacts.json").unlink()
print(f"Collected notice inventory for {len(inventory)} coordinates; missing notices require upstream review.")
