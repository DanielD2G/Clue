#!/usr/bin/env bash
# Builds the nonRoot variant of Clue and installs it on the connected device.
# Usage: tools/build-install.sh [debug|release]   (default: release)
set -euo pipefail

variant="${1:-release}"
case "$variant" in
  debug)   task=":app:assembleNonRootDebug";   apk_dir="app/build/outputs/apk/nonRoot/debug" ;;
  release) task=":app:assembleNonRootRelease"; apk_dir="app/build/outputs/apk/nonRoot/release" ;;
  *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(ls -d "/c/Program Files/Eclipse Adoptium/jdk-21"* 2>/dev/null | head -1)"
  export JAVA_HOME
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb.exe"
elif [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
  ADB="/c/Program Files/DroidScreen/adb/adb.exe"
fi

./gradlew "$task"

apk="$(ls "$apk_dir"/*.apk | head -1)"
echo "Installing $apk"
"$ADB" install -r "$apk"
"$ADB" shell pm list packages | grep clue
