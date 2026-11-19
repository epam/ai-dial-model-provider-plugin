#!/usr/bin/env python3
"""Pin the DIAL Core plugin API to a version, then build the plugin jar against it (make build-plugin-jar).

usage: bump_plugin_api.py <version>, e.g. 0.51.0 or 0.51.0-SNAPSHOT. A version the plugin does not build against is
rolled back: gradle.properties keeps the previous one.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = ROOT / "gradle.properties"
LINE = re.compile(r"^plugin_api_version=.*$", re.MULTILINE)
VERSION = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$")


def pin(version: str) -> str:
    """Writes the version, and returns the previous one."""
    text = PROPERTIES.read_bytes().decode()  # bytes: the file's line endings stay as they are
    match = LINE.search(text)
    if match is None:
        sys.exit("plugin_api_version is not set in gradle.properties")
    PROPERTIES.write_bytes(LINE.sub(f"plugin_api_version={version}", text, count=1).encode())
    return match.group(0).split("=", 1)[1].strip()


def main() -> None:
    version = sys.argv[1] if len(sys.argv) == 2 else ""
    if not version:
        sys.exit("usage: make bump-ai-dial-plugin-api PACKAGE_VERSION=<version>, e.g. 0.51.0 or 0.51.0-SNAPSHOT")
    if not VERSION.match(version):
        sys.exit(f"not a version: {version}")

    previous = pin(version)
    print(f"aidial-core-plugin-api: {previous} -> {version}")
    if subprocess.run(["make", "build-plugin-jar"], cwd=ROOT).returncode != 0:
        pin(previous)
        sys.exit(f"the plugin does not build against {version}: kept {previous}")


if __name__ == "__main__":
    main()
