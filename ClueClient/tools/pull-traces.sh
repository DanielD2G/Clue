#!/usr/bin/env bash
# Copies Clue's session traces from the device to the PC.
# Usage: tools/pull-traces.sh [destination]   (default: ../traces, outside git)
set -euo pipefail

dest="${1:-$(dirname "$0")/../../traces}"
pkg="${CLUE_PACKAGE:-com.clue.client}"

if [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
  ADB="/c/Program Files/DroidScreen/adb/adb.exe"
fi

mkdir -p "$dest"
export MSYS_NO_PATHCONV=1
"$ADB" pull "/sdcard/Android/data/$pkg/files/traces/." "$(cygpath -w "$dest")"
ls -1 "$dest"
