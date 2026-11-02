#!/usr/bin/env python3
"""Publish an explicitly prepared APK. No SDK or signing key is needed in Actions."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def prepared_apk(config):
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", config["tag"]):
        raise ValueError("Invalid version tag")
    if not re.fullmatch(r"[0-9a-f]{40}", config["source_commit"]):
        raise ValueError("An exact source commit is required")
    if not re.fullmatch(r"[0-9a-f]{64}", config["sha256"]):
        raise ValueError("Invalid APK digest")
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", config["filename"]):
        raise ValueError("Invalid APK filename")
    path = (ROOT / config["payload"]).resolve()
    if ROOT / "release-assets" not in path.parents:
        raise ValueError("Payload must be under release-assets")
    binary = base64.b64decode(path.read_bytes(), validate=True)
    if hashlib.sha256(binary).hexdigest() != config["sha256"]:
        raise ValueError("Prepared APK checksum mismatch")
    return binary


def publish(config, repository):
    binary = prepared_apk(config)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    # An existing release is never overwritten. A completed identical upload is idempotent.
    existing = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{config['tag']}"],
                              text=True, capture_output=True)
    if existing.returncode == 0:
        release = json.loads(existing.stdout)
        for asset in release.get("assets", []):
            if (asset["name"] == config["filename"] and asset.get("state") == "uploaded"
                    and asset.get("digest") == "sha256:" + config["sha256"] and not release["draft"]):
                print("Release already published:", release["html_url"])
                return
        raise RuntimeError("Release already exists; refusing to overwrite it")
    with tempfile.TemporaryDirectory(prefix="sleep-release-") as temporary:
        directory = Path(temporary)
        apk = directory / config["filename"]
        apk.write_bytes(binary)
        with zipfile.ZipFile(apk) as archive:
            if not {"AndroidManifest.xml", "classes.dex"}.issubset(archive.namelist()):
                raise ValueError("Prepared file is not an APK")
        notes = directory / "notes.md"
        notes.write_text(config["notes"], encoding="utf-8")
        command = ["gh", "release", "create", config["tag"], str(apk), "--repo", repository,
                   "--target", config["source_commit"], "--title", config["title"],
                   "--notes-file", str(notes), "--prerelease"]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: publish_release.py release-assets/current.json")
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    publish(config, os.environ["GITHUB_REPOSITORY"])
