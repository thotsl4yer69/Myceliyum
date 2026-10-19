"""Generate release metadata only after checking the actual signed APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "com.aistudio.myceliummapper.vcfqka"
REPOSITORY = "thotsl4yer69/Myceliyum"


def build_version(source):
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', source, re.M)
    if not code or not name or int(code[1]) < 7201:
        raise ValueError("Use a committed versionCode >= 7201 and a versionName")
    return int(code[1]), name[1]


def create_manifest(apk, source, commit):
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise ValueError("A full source commit SHA is required")
    code, name = build_version(source)
    badging = subprocess.check_output(["aapt", "dump", "badging", str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not package or (package[1], int(package[2]), package[3]) != (PACKAGE, code, name):
        raise ValueError("APK package/version does not match committed build configuration")
    subprocess.run(["apksigner", "verify", str(apk)], check=True, stdout=subprocess.DEVNULL)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {
        "versionCode": code, "versionName": name,
        "notes": "Verified signed field build from " + commit[:7],
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/latest/{apk.name}",
        "sha256": digest, "sourceCommit": commit,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    source = Path("app/build.gradle.kts").read_text()
    manifest = create_manifest(args.apk, source, args.commit)
    Path("version.json").write_text(json.dumps(manifest, indent=2) + "\n")
    Path("release-notes.md").write_text(
        f"Android {manifest['versionName']} · build {manifest['versionCode']}\n\n"
        "Automated tests, device smoke tests and APK integrity checks must pass before publication. "
        "Back up your records before changing signing identities. Do not uninstall an existing installation until its records and photos have been recovered and verified.\n\n"
        + "<!-- MYCELIYUMS-RELEASE " + json.dumps(manifest) + " -->\n"
    )
