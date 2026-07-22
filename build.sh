#!/usr/bin/env bash
# Local APK build for USB Camera Viewer.
# Usage: ./build.sh [debug|release] [--install] [--gates]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

usage() {
    echo "Usage: $0 [debug|release] [--install] [--gates]" >&2
    echo "  debug|release  variant to build (default: debug)" >&2
    echo "  --install      adb install -r the resulting APK" >&2
    echo "  --gates        run test/detekt/ktlint/lint first" >&2
    exit 1
}

VARIANT="debug"
INSTALL=0
GATES=0
for arg in "$@"; do
    case "$arg" in
        debug|release) VARIANT="$arg" ;;
        --install) INSTALL=1 ;;
        --gates) GATES=1 ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $arg" >&2; usage ;;
    esac
done

# Gradle 8.14 supports JDK 17-23; system default may be newer.
jdk_ok() { [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | head -1 | grep -qE 'version "(1[7-9]|2[0-3])[."]'; }
if ! jdk_ok "${JAVA_HOME:-/nonexistent}"; then
    for candidate in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
        if jdk_ok "$candidate"; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
jdk_ok "${JAVA_HOME:-/nonexistent}" || { echo "ERROR: no JDK 17-23 found. Set JAVA_HOME." >&2; exit 1; }
echo "JAVA_HOME=$JAVA_HOME"

if [[ "$GATES" == 1 ]]; then
    ./gradlew test detekt ktlintCheck lint
fi

if [[ "$VARIANT" == "release" ]]; then
    ./gradlew :app:assembleRelease
    APK="app/build/outputs/apk/release/app-release.apk"
    [[ -f "$APK" ]] || APK="app/build/outputs/apk/release/app-release-unsigned.apk"
    if [[ -f keystore.properties || -n "${UCV_KEYSTORE_FILE:-}" ]]; then
        echo "Signing: release keystore"
    else
        echo "Signing: UNSIGNED (create keystore.properties or set UCV_KEYSTORE_FILE)"
    fi
else
    ./gradlew :app:assembleDebug
    APK="app/build/outputs/apk/debug/app-debug.apk"
fi

[[ -f "$APK" ]] || { echo "ERROR: expected APK not found: $APK" >&2; exit 1; }
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

if [[ "$INSTALL" == 1 ]]; then
    adb install -r "$APK"
fi
