#!/usr/bin/env python3
"""Build an isolated diagnostic APK, never part of the PDM release.
No global DNS changes, privilege escalation, trust-store changes or external requests.
"""
import os
import subprocess
import zipfile
from pathlib import Path

source = Path(__file__).resolve().parent
root = source.parents[1]
out = root / "build/local-dot-probe/apk"
out.mkdir(parents=True, exist_ok=True)
(out / "assets").mkdir(exist_ok=True)
(out / "classes").mkdir(exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
bt = sdk / "build-tools/35.0.0"
android = sdk / "platforms/android-36/android.jar"

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

# Disposable test identity. Never a production server key or trusted CA.
key = out / "test-only.p12"
if not key.exists():
    run("keytool", "-genkeypair", "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth",
        "-validity", "2", "-storetype", "PKCS12", "-keystore", key,
        "-storepass", "testonly", "-keypass", "testonly")
run("keytool", "-exportcert", "-alias", "probe", "-keystore", key,
    "-storepass", "testonly", "-file", out / "assets/probe.cer")
run("javac", "--release", "8", "-cp", android, "-d", out / "classes", source / "Probe.java")
run("jar", "cf", out / "probe.jar", "-C", out / "classes", ".")
run(bt / "d8", "--lib", android, "--min-api", "21", "--output", out, out / "probe.jar")
run(bt / "aapt", "package", "-f", "-M", source / "AndroidManifest.xml", "-A", out / "assets",
    "-I", android, "-F", out / "unsigned.apk")
with zipfile.ZipFile(out / "unsigned.apk", "a") as apk:
    apk.write(out / "classes.dex", "classes.dex")
run(bt / "zipalign", "-f", "4", out / "unsigned.apk", out / "probe.apk")
run(bt / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
    "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
    out / "probe.apk")
print(out / "probe.apk")
