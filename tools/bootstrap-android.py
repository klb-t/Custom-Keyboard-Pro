#!/usr/bin/env python3
"""Restore the documented local Android toolchain without changing project versions.

Uses official distributions, pinned checksums and the runtime's normal TLS trust.
Downloads are cached and verified before extraction. No project credentials are read.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
from urllib.parse import urlparse
import zipfile


ARTIFACTS = (
    ("jdk.tar.gz", "https://github.com/adoptium/temurin17-binaries/releases/download/"
     "jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz",
     "sha256", "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e"),
    ("gradle.zip", "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
     "sha256", "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"),
    ("cmdline.zip", "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip",
     "sha1", "5fdcc763663eefb86a5b8879697aa6088b041e70"),
)


def digest(path, algorithm):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(root, name, url, algorithm, expected):
    target = root / "downloads" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and digest(target, algorithm) == expected:
        return target
    pending = target.with_suffix(target.suffix + ".part")
    subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location",
                    "--connect-timeout", "30", "--max-time", "600", url,
                    "--output", str(pending)], check=True)
    if digest(pending, algorithm) != expected:
        raise RuntimeError(f"Checksum mismatch: {name}; refusing extraction")
    pending.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2] / "android-toolchain")
    parser.add_argument("--accept-licenses", action="store_true", help="Accept the SDK licenses for this development installation")
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    files = {a[0]: download(root, *a) for a in ARTIFACTS}
    jdk = root / "jdk-17.0.20.1+1"
    if not (jdk / "bin/javac").exists():
        with tarfile.open(files["jdk.tar.gz"]) as archive:
            archive.extractall(root, filter="data")
    gradle = root / "gradle-9.7.1/bin/gradle"
    if not gradle.exists():
        with zipfile.ZipFile(files["gradle.zip"]) as archive:
            archive.extractall(root)
    gradle.chmod(0o755)
    sdk = root / "sdk"
    latest = sdk / "cmdline-tools/latest"
    if not (latest / "bin/sdkmanager").exists():
        stage = root / "cmdline-extraction"
        with zipfile.ZipFile(files["cmdline.zip"]) as archive:
            archive.extractall(stage)
        latest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(stage / "cmdline-tools", latest, dirs_exist_ok=True)
    for path in (latest / "bin").iterdir():
        if path.is_file():
            path.chmod(0o755)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env["PATH"]
    cert = env.get("CODEX_PROXY_CERT")
    keytool = [str(jdk / "bin/keytool")]
    if cert and Path(cert).is_file():
        exists = subprocess.run(keytool + ["-list", "-alias", "codex-network-proxy", "-cacerts",
                                          "-storepass", "changeit"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if exists.returncode:
            subprocess.run(keytool + ["-importcert", "-alias", "codex-network-proxy", "-file", cert,
                                     "-cacerts", "-storepass", "changeit", "-noprompt"], check=True)
    command = [str(latest / "bin/sdkmanager"), "--sdk_root=" + str(sdk)]
    proxy = urlparse(env.get("HTTPS_PROXY", ""))
    if proxy.hostname and proxy.port:
        command += ["--proxy=http", "--proxy_host=" + proxy.hostname, "--proxy_port=" + str(proxy.port)]
    if args.accept_licenses:
        subprocess.run(command + ["--licenses"], input="y\n" * 100, text=True, env=env, check=True)
    subprocess.run(command + ["platforms;android-36.1", "build-tools;36.0.0", "build-tools;36.1.0", "platform-tools"],
                   env=env, check=True)
    platform = sdk / "platforms/android-36.1"
    # The official package can contain a nested platform directory. Only normalize
    # the exact requested platform, preserving package.xml from sdkmanager.
    nested = platform / "android-36.1"
    if not (platform / "android.jar").exists() and (nested / "android.jar").exists():
        properties = (nested / "source.properties").read_text()
        if "AndroidVersion.ApiLevel=36.1" not in properties:
            raise RuntimeError("Unexpected nested SDK platform version")
        for child in nested.iterdir():
            destination = platform / child.name
            if destination.exists():
                raise RuntimeError(f"Refusing to overwrite SDK path: {destination}")
            child.rename(destination)
        nested.rmdir()
    if not (platform / "android.jar").exists():
        raise RuntimeError("SDK installation did not produce the requested android.jar")
    print(f"Toolchain ready at {root}. Run tools/local-android-build.sh; this is not a build result.")


if __name__ == "__main__":
    main()
