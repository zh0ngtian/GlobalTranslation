#!/usr/bin/env python3
"""Run the opt-in device API acceptance test without putting a key in argv or logs."""
import getpass
import os
import pathlib
import re
import shutil
import subprocess
import sys

sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
adb = shutil.which("adb") or (str(pathlib.Path(sdk) / "platform-tools/adb") if sdk else None)
if not adb:
    sys.exit("Add adb to PATH or set ANDROID_HOME. Install the debug and androidTest APKs first.")
key = getpass.getpass("DeepSeek test API Key (hidden): ").strip()
if not key or len(key) > 512 or any(not 33 <= ord(char) <= 126 for char in key):
    sys.exit("Invalid key format.")
package = "io.github.zh0ngtian.globaltranslation"
remote_file = "no_backup/acceptance-api-key"
subprocess.run([adb, "shell", "run-as", package, "mkdir", "-p", "no_backup"], check=True)
try:
    subprocess.run([adb, "shell", f'run-as {package} sh -c "umask 077; cat > {remote_file}"'],
                   input=key.encode(), check=True)
    key = ""
    result = subprocess.run([adb, "shell", "am", "instrument", "-w", "-e", "class",
                             f"{package}.PhotoDeviceTest#optionalRealApiFromLocalOcrHonorsTargetAndTerminology",
                             f"{package}.test/androidx.test.runner.AndroidJUnitRunner"],
                            capture_output=True, text=True, timeout=240)
    print(result.stdout)
    if result.returncode or not re.search(r"OK \(1 tests?\)", result.stdout):
        sys.exit("API acceptance failed; see the test output above.")
finally:
    subprocess.run([adb, "shell", "run-as", package, "rm", "-f", remote_file], check=True)
