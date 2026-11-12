#!/usr/bin/env bash
# Build and install on a watch already connected over adb.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/.local/opt/jdk21" ANDROID_HOME="$HOME/Android/Sdk"
grep -q '^tibber.token=.\+' local.properties || { echo "Add tibber.token=... to local.properties first"; exit 1; }
./gradlew -q :wear:assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r wear/build/outputs/apk/debug/wear-debug.apk
