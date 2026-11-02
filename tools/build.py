#!/usr/bin/env python3
"""Build the native Java Wear OS app using official Android tools and a pinned Eclipse compiler."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = {
    "platform-36_r02.zip": (
        "https://dl.google.com/android/repository/platform-36_r02.zip",
        "37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e"),
    "build-tools_r36_linux.zip": (
        "https://dl.google.com/android/repository/build-tools_r36_linux.zip",
        "5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b"),
    "ecj-3.40.0.jar": (
        "https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.40.0/ecj-3.40.0.jar",
        "05cc22a24e7982970f63a405fc6c820bc80b806f27f3c5a6236fc475f8f7152b"),
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(*args, env=None):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT, env=env)


def dependency(cache, name):
    url, expected = DEPENDENCIES[name]
    path = cache / name
    if not path.exists():
        print("Downloading", name, flush=True)
        temporary = path.with_suffix(path.suffix + ".partial")
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        temporary.replace(path)
    if sha256(path) != expected:
        raise RuntimeError("SHA-256 mismatch: " + str(path))
    return path


def compiler(cache):
    return dependency(cache, "ecj-3.40.0.jar")


def tests(cache):
    ecj = compiler(cache)
    dest = ROOT / ".build/tests"
    dest.mkdir(parents=True, exist_ok=True)
    sources = [ROOT / "app/src/ru/wearsleep/lab" / (name + ".java")
               for name in ["Csv", "StreamStats", "SessionStore"]]
    run("java", "-jar", ecj, "-warn:none", "-source", "8", "-target", "8", "-encoding", "UTF-8",
        "-d", dest, *sources, ROOT / "tests/TestMain.java")
    run("java", "-cp", dest, "ru.wearsleep.lab.TestMain")
    run(sys.executable, "-m", "unittest", "discover", "-s", "tests", "-p", "test_*.py")


def vendor_sdk(source, work):
    """Accept the official Samsung ZIP or AAR; never download/rehost the proprietary SDK."""
    announcement = None
    with zipfile.ZipFile(source) as package:
        if "classes.jar" in package.namelist():
            aar = source
        else:
            matches = [name for name in package.namelist()
                       if name.endswith("/samsung-health-sensor-api-1.4.1.aar")]
            if len(matches) != 1:
                raise RuntimeError("Expected the official Samsung Health Sensor SDK 1.4.1 archive")
            aar = work / "samsung-health-sensor-api-1.4.1.aar"
            aar.write_bytes(package.read(matches[0]))
            notices = [name for name in package.namelist() if name.endswith("/Announcement.txt")]
            if notices:
                announcement = package.read(notices[0])
    classes = work / "samsung-classes.jar"
    with zipfile.ZipFile(aar) as package:
        classes.write_bytes(package.read("classes.jar"))
        manifest = package.read("AndroidManifest.xml")
        if b"com.samsung.android.service.health.tracking.libraries" not in manifest:
            raise RuntimeError("Unexpected vendor AAR")
    with zipfile.ZipFile(classes) as library:
        for item in ["HealthTrackingService", "data/PpgType", "data/ValueKey$PpgSet"]:
            if "com/samsung/android/service/health/tracking/" + item + ".class" not in library.namelist():
                raise RuntimeError("Required Samsung SDK API is missing: " + item)
    return classes, announcement, sha256(aar)


def build(options):
    cache = options.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    if options.test_only:
        tests(cache)
        return
    if options.samsung_sdk is None or not options.samsung_sdk.is_file():
        raise RuntimeError("Pass --samsung-sdk /path/to/official-sdk.zip (or .aar)")
    if sys.platform != "linux":
        raise RuntimeError("This build helper supports Linux; Java sources are standard Android Java")
    work = ROOT / ".build/apk"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    sdk, announcement, sdk_sha = vendor_sdk(options.samsung_sdk.resolve(), work)
    for archive, directory in [("platform-36_r02.zip", "platform"), ("build-tools_r36_linux.zip", "tools")]:
        path = dependency(cache, archive)
        destination = cache / directory
        if not destination.exists():
            with zipfile.ZipFile(path) as package:
                package.extractall(destination)
    platform = cache / "platform/android-36/android.jar"
    tools = cache / "tools/android-16"
    for name in ["aapt2", "zipalign", "dexdump"]:
        (tools / name).chmod(0o755)
    tests(cache)
    generated, classes, dex = [work / name for name in ["generated", "classes", "dex"]]
    for directory in [generated, classes, dex]:
        directory.mkdir()
    resources = work / "resources.zip"
    run(tools / "aapt2", "compile", "--dir", ROOT / "app/res", "-o", resources)
    apk_resources = work / "resources.apk"
    run(tools / "aapt2", "link", "-I", platform, "--manifest", ROOT / "app/AndroidManifest.xml",
        "--java", generated, "--auto-add-overlay", "-o", apk_resources, resources)
    java_sources = sorted((ROOT / "app/src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run("java", "-Xmx768m", "-jar", compiler(cache), "-warn:none", "-source", "8", "-target", "8",
        "-encoding", "UTF-8", "-bootclasspath", str(platform) + os.pathsep + str(tools / "core-lambda-stubs.jar"),
        "-classpath", sdk, "-d", classes, *java_sources)
    program = work / "program.jar"
    with zipfile.ZipFile(program, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(classes.rglob("*.class")):
            output.write(path, path.relative_to(classes).as_posix())
        with zipfile.ZipFile(sdk) as library:
            for name in library.namelist():
                if name.endswith(".class"):
                    output.writestr(name, library.read(name))
    run("java", "-Xmx768m", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
        "--min-api", "30", "--lib", platform, "--output", dex, program)
    unsigned = work / "unsigned.apk"
    shutil.copyfile(apk_resources, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(dex.glob("*.dex")):
            output.write(path, path.name)
        if announcement:
            output.writestr("assets/third-party/Samsung-Open-Source-Announcement.txt", announcement)
    aligned = work / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    signing_key = options.signing_key.resolve()
    signing_key.parent.mkdir(parents=True, exist_ok=True)
    signing_env = dict(os.environ)
    signing_env.setdefault("SLEEP_LAB_KEY_PASSWORD", "android")
    if not signing_key.exists():
        run("keytool", "-genkeypair", "-keystore", signing_key, "-storetype", "PKCS12",
            "-alias", "sleep-lab", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Wear Sleep Lab Development, O=Personal Research",
            "-storepass:env", "SLEEP_LAB_KEY_PASSWORD", env=signing_env)
        signing_key.chmod(0o600)
    target = ROOT / "dist/sleep-lab-0.1.0-watch.apk"
    target.parent.mkdir(exist_ok=True)
    signer = tools / "lib/apksigner.jar"
    run("java", "-jar", signer, "sign", "--ks", signing_key, "--ks-key-alias", "sleep-lab",
        "--ks-pass", "env:SLEEP_LAB_KEY_PASSWORD", "--key-pass", "env:SLEEP_LAB_KEY_PASSWORD",
        "--v4-signing-enabled", "false", "--out", target, aligned, env=signing_env)
    run("java", "-jar", signer, "verify", "--verbose", "--print-certs", target)
    run(tools / "zipalign", "-c", "4", target)
    run(tools / "aapt2", "dump", "badging", target)
    report = {"version": "0.1.0", "target_sdk": 36, "min_sdk": 30,
              "samsung_sdk_sha256": sdk_sha, "apk_sha256": sha256(target),
              "hardware_tested": False, "source_sha256": {str(p.relative_to(ROOT)): sha256(p)
              for p in sorted((ROOT / "app").rglob("*")) if p.is_file()}}
    (target.parent / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("APK:", target, "\nSHA256:", sha256(target), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samsung-sdk", type=Path)
    parser.add_argument("--cache", type=Path, default=ROOT / ".local/build-cache")
    parser.add_argument("--signing-key", type=Path, default=ROOT / ".local/signing.p12")
    parser.add_argument("--test-only", action="store_true")
    options = parser.parse_args()
    try:
        build(options)
    except (OSError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("Build failed:", error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
