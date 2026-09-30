#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
keystore_file="${GT_RELEASE_STORE_FILE:-$repo_root/keystore/globaltranslation-release.jks}"
key_alias="${GT_RELEASE_KEY_ALIAS:-globaltranslation}"
keychain_service="${GT_RELEASE_KEYCHAIN_SERVICE:-GlobalTranslation Android Release}"
keychain_account="${GT_RELEASE_KEYCHAIN_ACCOUNT:-zh0ngtian}"

if [[ ! -f "$keystore_file" ]]; then
    echo "Release keystore not found: $keystore_file" >&2
    exit 1
fi

if [[ -z "${GT_RELEASE_STORE_PASSWORD:-}" || -z "${GT_RELEASE_KEY_PASSWORD:-}" ]]; then
    if ! command -v security >/dev/null 2>&1; then
        echo "Set GT_RELEASE_STORE_PASSWORD and GT_RELEASE_KEY_PASSWORD." >&2
        exit 1
    fi
    signing_password="$(security find-generic-password -a "$keychain_account" -s "$keychain_service" -w)"
    export GT_RELEASE_STORE_PASSWORD="$signing_password"
    export GT_RELEASE_KEY_PASSWORD="$signing_password"
fi
export GT_RELEASE_STORE_FILE="$keystore_file"
export GT_RELEASE_KEY_ALIAS="$key_alias"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

"$repo_root/gradlew" :app:assembleRelease

sdk_dir="$(sed -n 's/^sdk.dir=//p' "$repo_root/local.properties" | tail -1)"
build_tools="$(find "$sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apksigner="$build_tools/apksigner"
aapt="$build_tools/aapt"
source_apk="$repo_root/app/build/outputs/apk/release/app-release.apk"

if [[ ! -x "$apksigner" || ! -x "$aapt" || ! -f "$source_apk" ]]; then
    echo "Signed release tools or APK output are missing." >&2
    exit 1
fi

version_name="$($aapt dump badging "$source_apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
output_apk="$repo_root/app/build/outputs/apk/release/GlobalTranslation-$version_name-test.apk"
cp "$source_apk" "$output_apk"

verification="$($apksigner verify --verbose --print-certs "$output_apk")"
if ! grep -q '^Verifies$' <<<"$verification"; then
    echo "APK signature verification failed." >&2
    exit 1
fi
if grep -q 'CN=Android Debug' <<<"$verification"; then
    echo "Refusing to deliver an APK signed with the Android debug certificate." >&2
    exit 1
fi

printf '%s\n' "$verification"
shasum -a 256 "$output_apk"
stat -f '%z bytes' "$output_apk"
printf 'APK: %s\n' "$output_apk"

unset signing_password GT_RELEASE_STORE_PASSWORD GT_RELEASE_KEY_PASSWORD
