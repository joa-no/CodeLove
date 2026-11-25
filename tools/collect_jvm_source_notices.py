#!/usr/bin/env python3
"""Collect verbatim copyright/license headers from the exact Maven source JARs."""
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
coordinates = (
    ("com.google.guava", "listenablefuture", "1.0"),
    ("org.jetbrains", "annotations", "13.0"),
)
base = "https://repo.maven.apache.org/maven2/"
out = [b"Supplemental source copyright and license notices\n"
       b"Original headers and notice files from the exact Maven Central source artifacts.\n"
       b"The Apache 2.0 license text is in jvm-dependency-notices.txt.\n"]
with tempfile.TemporaryDirectory() as tmp:
    for group, artifact, version in coordinates:
        filename = f"{artifact}-{version}-sources.jar"
        url = base + group.replace(".", "/") + f"/{artifact}/{version}/{filename}"
        path = Path(tmp) / filename
        for address, target in ((url, path), (url + ".sha1", path.with_suffix(".sha1"))):
            subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location",
                            "--retry", "5", "--retry-max-time", "180",
                            "--connect-timeout", "20", "--max-time", "60",
                            "--output", str(target), address], check=True)
        payload = path.read_bytes()
        expected = path.with_suffix(".sha1").read_text().split()[0].lower()
        if hashlib.sha1(payload).hexdigest() != expected:
            raise SystemExit("Maven Central checksum mismatch: " + filename)
        out.append(("\n" + "=" * 64 + "\n" + ":".join((group, artifact, version))
                    + "\nSource: " + url + "\nSHA-256: "
                    + hashlib.sha256(payload).hexdigest() + "\n").encode())
        count = 0
        with ZipFile(path) as jar:
            if jar.testzip() is not None:
                raise SystemExit("Corrupt source JAR: " + filename)
            for name in sorted(jar.namelist()):
                if name.endswith("/"):
                    continue
                data = jar.read(name)
                leaf = name.rsplit("/", 1)[-1].upper()
                if re.fullmatch(r"(LICENSE|NOTICE|COPYING|COPYRIGHT)(\.[A-Z0-9]+)?", leaf):
                    notice = data
                elif name.endswith(".java"):
                    header = re.match(rb"\s*/\*[\s\S]*?\*/", data)
                    if not header:
                        continue
                    notice = header.group()
                    if not re.search(rb"copyright|licensed under|license", notice, re.I):
                        continue
                else:
                    continue
                out.extend([b"\nFile: " + name.encode() + b"\n", notice, b"\n"])
                count += 1
        if not count:
            raise SystemExit("No source notice found: " + filename)
        print(f"{group}:{artifact}:{version}: {count} original source notices")
target = root / "app/src/main/assets/jvm-source-notices.txt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(b"".join(out))
