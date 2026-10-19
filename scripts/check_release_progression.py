"""Prevent replacing a released build with different code at the same/lower version."""
import json
from pathlib import Path
import re
import sys


def check(previous, current):
    match = re.search(r"<!-- MYCELIYUMS-RELEASE ([\s\S]*?) -->", previous.get("body", ""))
    if not match:
        return  # Legacy releases have no verified manifest.
    old = json.loads(match[1])
    if current["versionCode"] <= old["versionCode"]:
        raise ValueError("Increase committed versionCode before publishing another release")


if __name__ == "__main__":
    check(json.loads(Path(sys.argv[1]).read_text()), json.loads(Path(sys.argv[2]).read_text()))
