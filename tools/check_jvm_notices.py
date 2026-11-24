#!/usr/bin/env python3
"""Check flavor inventories and retain verbatim embedded dependency notices."""
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
inventory = json.loads((root / "dependency-notice-inventory.json").read_text())
repo = Path(__file__).resolve().parents[1]
for flavor in ("normal", "embed"):
    asset = (repo / "app" / "src" / flavor / "assets" / "jvm-dependency-notices.txt").read_bytes()
    declared = set(re.findall(rb"(?m)^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$", asset))
    expected = {c.encode() for c, data in inventory.items()
                if any(name.startswith(flavor) for name in data["configurations"])}
    if declared != expected:
        raise SystemExit(f"{flavor}: missing coordinates {expected - declared}; unexpected {declared - expected}")
    count = 0
    for coordinate, data in inventory.items():
        if coordinate.encode() not in expected:
            continue
        for artifact in data["files"].values():
            for notice in artifact["notices"]:
                original = (root / notice["file"]).read_bytes()
                if not original or original not in asset:
                    raise SystemExit(f"{flavor}: original notice absent or altered: {coordinate} / {notice['entry']}")
                count += 1
    print(f"{flavor}: {len(expected)} coordinates and {count} original embedded notices checked")
print("JVM notice coverage checks passed; upstream and corresponding-source review remains required.")
