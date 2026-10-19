#!/usr/bin/env bash
# Automatic button-to-frame test. Needs tools/host/gamepad-photon.html open full screen on the
# host's streamed display, and no physical controller on the tablet (the test uses controller 0).
# Usage: tools/photon.sh [presses=100] [pacing mode]
set -euo pipefail
export MSYS_NO_PATHCONV=1
presses="${1:-100}"; mode="${2:-}"
pkg="${CLUE_PACKAGE:-com.clue.client}"
here="$(cd "$(dirname "$0")" && pwd)"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="/c/Program Files/DroidScreen/adb/adb.exe"

"$ADB" shell am force-stop "$pkg"
"$ADB" shell am start -n "$pkg/com.limelight.PcView" --ei clue_photon_test "$presses" ${mode:+--es clue_pacing "$mode"} >/dev/null
sleep 3
"$ADB" logcat -c
bash "$here/clue-launch-desktop.sh" >/dev/null
# 3 s settle + 1 s arrival + 0.4 s per press
wait_s=$(( 6 + presses * 4 / 10 ))
line=""
for i in $(seq 1 "$wait_s"); do
  sleep 1
  line=$("$ADB" logcat -d -s ClueTrace | grep -o "photon n=.*" | tail -1 || true)
  [ -n "$line" ] && break
done
echo "${line:-no result (is the page open and the stream running?)}"
"$ADB" shell input keyevent 4
