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
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
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
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
PROBE_DEPENDENCIES = {
    "health-services-client-1.0.0.aar": (
        f"{GOOGLE_MAVEN}/androidx/health/health-services-client/1.0.0/health-services-client-1.0.0.aar",
        "2bb401445b2a621cf85e651e85d38117fe0076cf073e4f2cc97c5e27fe961b53"),
    "core-1.7.0.aar": (
        f"{GOOGLE_MAVEN}/androidx/core/core/1.7.0/core-1.7.0.aar",
        "aaf6734226fff923784f92f65d78a2984dbf17534138855c5ce2038f18656e0b"),
    "core-ktx-1.7.0.aar": (
        f"{GOOGLE_MAVEN}/androidx/core/core-ktx/1.7.0/core-ktx-1.7.0.aar",
        "0d4d007dc81277cd05502a2596044ed2cc55e41501dd1e7f6881200640dc12c0"),
    "annotation-1.1.0.jar": (
        f"{GOOGLE_MAVEN}/androidx/annotation/annotation/1.1.0/annotation-1.1.0.jar",
        "d38d63edb30f1467818d50aaf05f8a692dea8b31392a049bfa991b159ad5b692"),
    "concurrent-futures-1.1.0.jar": (
        f"{GOOGLE_MAVEN}/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar",
        "0ce067c514a0d1049d1bebdf709e344ed3266fe9744275682937cdcb13334e9e"),
    "concurrent-futures-ktx-1.1.0.jar": (
        f"{GOOGLE_MAVEN}/androidx/concurrent/concurrent-futures-ktx/1.1.0/concurrent-futures-ktx-1.1.0.jar",
        "1968bf52039e38636aa6f114cd17d7256919d1e8997417716fef9d1da1f24d85"),
    "guava-31.1-android.jar": (
        f"{MAVEN_CENTRAL}/com/google/guava/guava/31.1-android/guava-31.1-android.jar",
        "32ac2ed709d96d278b5d2e3e5cea178fa4939939c525fb647532f013308db309"),
    "failureaccess-1.0.1.jar": (
        f"{MAVEN_CENTRAL}/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar",
        "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26"),
    "protobuf-javalite-3.21.8.jar": (
        f"{MAVEN_CENTRAL}/com/google/protobuf/protobuf-javalite/3.21.8/protobuf-javalite-3.21.8.jar",
        "975264c135636b41187daa68b6c07592c277933448d012229384e2af4c4272e5"),
    "kotlin-stdlib-1.8.21.jar": (
        f"{MAVEN_CENTRAL}/org/jetbrains/kotlin/kotlin-stdlib/1.8.21/kotlin-stdlib-1.8.21.jar",
        "042a1cd1ac976cdcfe5eb63f1d8e0b0b892c9248e15a69c8cfba495d546ea52a"),
    "kotlin-stdlib-common-1.8.21.jar": (
        f"{MAVEN_CENTRAL}/org/jetbrains/kotlin/kotlin-stdlib-common/1.8.21/kotlin-stdlib-common-1.8.21.jar",
        "6a44c9ecc9d7754d9e943fb1e3588c74d4a3f1785be51074f49d6c5723682a73"),
    "kotlinx-coroutines-core-jvm-1.6.4.jar": (
        f"{MAVEN_CENTRAL}/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/"
        "kotlinx-coroutines-core-jvm-1.6.4.jar",
        "c24c8bb27bb320c4a93871501a7e5e0c61607638907b197aef675513d4c820be"),
    "annotations-13.0.jar": (
        f"{MAVEN_CENTRAL}/org/jetbrains/annotations/13.0/annotations-13.0.jar",
        "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478"),
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(*args, env=None):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT, env=env)


def dependency(cache, name, dependencies=DEPENDENCIES):
    url, expected = dependencies[name]
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
               for name in ["Csv", "StreamStats", "SessionStore", "SensorQuality", "RecordingProfile"]]
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


def build_directory(name):
    work = ROOT / ".build" / name
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    return work


def android_tools(cache):
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
    return platform, tools


def assemble(cache, module, work, libraries, assets, signing_key, filename_prefix):
    platform, tools = android_tools(cache)
    manifest_path = module / "AndroidManifest.xml"
    manifest = ET.parse(manifest_path).getroot()
    version = manifest.attrib[ANDROID + "versionName"]
    min_sdk = manifest.find("uses-sdk").attrib[ANDROID + "minSdkVersion"]
    generated, classes, dex = [work / name for name in ["generated", "classes", "dex"]]
    for directory in [generated, classes, dex]:
        directory.mkdir()
    resources = work / "resources.zip"
    run(tools / "aapt2", "compile", "--dir", ROOT / "app/res", "-o", resources)
    apk_resources = work / "resources.apk"
    run(tools / "aapt2", "link", "-I", platform, "--manifest", manifest_path,
        "--java", generated, "--auto-add-overlay", "-o", apk_resources, resources)
    package = manifest.attrib["package"]
    build_info = generated.joinpath(*package.split("."), "BuildInfo.java")
    build_info.write_text(f"package {package};\n"
                          "public final class BuildInfo { public static final String VERSION_NAME = "
                          + json.dumps(version) + "; }\n")
    java_sources = sorted((module / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run("java", "-Xmx768m", "-jar", compiler(cache), "-warn:none", "-source", "8", "-target", "8",
        "-encoding", "UTF-8", "-bootclasspath", str(platform) + os.pathsep + str(tools / "core-lambda-stubs.jar"),
        "-classpath", os.pathsep.join(map(str, libraries)), "-d", classes, *java_sources)
    program = work / "program.jar"
    with zipfile.ZipFile(program, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(classes.rglob("*.class")):
            output.write(path, path.relative_to(classes).as_posix())
    run("java", "-Xmx768m", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
        "--min-api", min_sdk, "--lib", platform, "--output", dex, program, *libraries)
    unsigned = work / "unsigned.apk"
    shutil.copyfile(apk_resources, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(dex.glob("*.dex")):
            output.write(path, path.name)
        for path, content in assets.items():
            output.writestr(path, content)
    aligned = work / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    signing_key.parent.mkdir(parents=True, exist_ok=True)
    signing_env = dict(os.environ)
    signing_env.setdefault("SLEEP_LAB_KEY_PASSWORD", "android")
    if not signing_key.exists():
        run("keytool", "-genkeypair", "-keystore", signing_key, "-storetype", "PKCS12",
            "-alias", "sleep-lab", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Wear Sleep Lab Development, O=Personal Research",
            "-storepass:env", "SLEEP_LAB_KEY_PASSWORD", env=signing_env)
        signing_key.chmod(0o600)
    target = ROOT / "dist" / f"{filename_prefix}-{version}-watch.apk"
    target.parent.mkdir(exist_ok=True)
    signer = tools / "lib/apksigner.jar"
    run("java", "-jar", signer, "sign", "--ks", signing_key, "--ks-key-alias", "sleep-lab",
        "--ks-pass", "env:SLEEP_LAB_KEY_PASSWORD", "--key-pass", "env:SLEEP_LAB_KEY_PASSWORD",
        "--v4-signing-enabled", "false", "--out", target, aligned, env=signing_env)
    run("java", "-jar", signer, "verify", "--verbose", "--print-certs", target)
    run(tools / "zipalign", "-c", "4", target)
    run(tools / "aapt2", "dump", "badging", target)
    return target, version


def build_probe(cache, signing_key):
    work = build_directory("sleep-state-probe")
    module = ROOT / "sleep-state-probe"
    library_cache = cache / "health-services-probe"
    library_cache.mkdir(exist_ok=True)
    libraries, notices = [], {}
    notice_names = {"META-INF/LICENSE", "META-INF/LICENSE.TXT", "META-INF/NOTICE", "META-INF/NOTICE.TXT"}
    for name in PROBE_DEPENDENCIES:
        archive = dependency(library_cache, name, PROBE_DEPENDENCIES)
        jar = archive
        if archive.suffix == ".aar":
            jar = work / (archive.stem + ".jar")
            with zipfile.ZipFile(archive) as aar:
                jar.write_bytes(aar.read("classes.jar"))
        libraries.append(jar)
        with zipfile.ZipFile(jar) as library:
            for path in library.namelist():
                if path.upper() in notice_names:
                    notices[f"assets/third-party/{jar.stem}/{Path(path).name}"] = library.read(path)
    target, _ = assemble(cache, module, work, libraries, notices, signing_key, "sleep-state-probe")
    print("APK:", target, "\nSHA256:", sha256(target), flush=True)


def build(options):
    cache = options.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    if options.test_only:
        tests(cache)
        return
    if sys.platform != "linux":
        raise RuntimeError("This build helper supports Linux; Java sources are standard Android Java")
    signing_key = options.signing_key.resolve()
    if options.sleep_state_probe:
        build_probe(cache, signing_key)
        return
    if options.samsung_sdk is None or not options.samsung_sdk.is_file():
        raise RuntimeError("Pass --samsung-sdk /path/to/official-sdk.zip (or .aar)")
    work = build_directory("apk")
    sdk, announcement, sdk_sha = vendor_sdk(options.samsung_sdk.resolve(), work)
    assets = {}
    if announcement:
        assets["assets/third-party/Samsung-Open-Source-Announcement.txt"] = announcement
    tests(cache)
    target, version = assemble(cache, ROOT / "app", work, [sdk], assets, signing_key, "sleep-lab")
    report = {"version": version, "target_sdk": 36, "min_sdk": 30,
              "samsung_sdk_sha256": sdk_sha, "apk_sha256": sha256(target),
              "hardware_tested": False, "source_sha256": {str(p.relative_to(ROOT)): sha256(p)
              for p in sorted((ROOT / "app").rglob("*")) if p.is_file()}}
    (target.parent / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("APK:", target, "\nSHA256:", sha256(target), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--samsung-sdk", type=Path)
    mode.add_argument("--sleep-state-probe", action="store_true", help="Build the separate Wear OS sleep state probe")
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
