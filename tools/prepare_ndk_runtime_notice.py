#!/usr/bin/env python3
"""Preserve the exact NDK LLVM notice collected in build 553."""
import hashlib
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
ndk = Path(sys.argv[1])
original = ndk / "toolchains/llvm/prebuilt/linux-x86_64/NOTICE"
payload = original.read_bytes()
expected = "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1"
if hashlib.sha256(payload).hexdigest() != expected:
    raise SystemExit("NDK LLVM notice differs from reviewed NDK 27.3.13750724; review before updating")
target = root / "app/src/main/assets/ndk-runtime-notice.txt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(payload)
print("Preserved original NDK LLVM notice (includes libc++ notices)")
