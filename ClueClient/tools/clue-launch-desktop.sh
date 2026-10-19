#!/usr/bin/env bash
# Opens Clue on the device and starts (or resumes) the host's "Desktop" app.
# Works in portrait and landscape: the PC tile is found by its label, and the
# Desktop tile is the second tile of the app grid (same position in both).
# Usage: tools/clue-launch-desktop.sh [pc-name]   (default: DanielsPC)
set -euo pipefail
export MSYS_NO_PATHCONV=1

pc="${1:-DanielsPC}"
pkg="${CLUE_PACKAGE:-com.clue.client}"
if [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
  ADB="/c/Program Files/DroidScreen/adb/adb.exe"
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

focus() { "$ADB" shell dumpsys window | grep -E 'mCurrentFocus=' | head -1 || true; }
dump() { "$ADB" shell uiautomator dump /sdcard/clue-ui.xml >/dev/null 2>&1; "$ADB" pull /sdcard/clue-ui.xml "$(cygpath -w "$tmp/ui.xml")" >/dev/null 2>&1; }
# Prints "x1 y1 x2 y2" of the first node whose text is $1.
bounds_of() { { grep -oE "text=\"$1\"[^>]*bounds=\"[^\"]*\"" "$tmp/ui.xml" || true; } | head -1 | { grep -oE '[0-9]+' || true; } | tr '\n' ' '; }

"$ADB" shell input keyevent KEYCODE_WAKEUP
if [ -n "${CLUE_PACING:-}" ]; then
  # Fresh start so PcView applies the requested pacing mode (clue, balanced, latency, ...)
  "$ADB" shell am force-stop "$pkg"
  "$ADB" shell am start -n "$pkg/com.limelight.PcView" --es clue_pacing "$CLUE_PACING" >/dev/null 2>&1
else
  "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
fi
sleep 4

for attempt in 1 2 3 4 5 6 7 8; do
  f="$(focus)"
  case "$f" in
    *com.limelight.Game*) echo "streaming"; exit 0 ;;
    *com.limelight.PcView*)
      dump; b="$(bounds_of "$pc")"
      if [ -n "$b" ]; then set -- $b; "$ADB" shell input tap $(( ($1+$3)/2 )) $(( $2 - 80 )); fi
      sleep 4 ;;
    *com.limelight.AppView*)
      dump; b="$(bounds_of "Reanudar sesión")"
      if [ -n "$b" ]; then set -- $b; "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 8; continue; fi
      "$ADB" shell input tap 482 354
      sleep 3 ;;
    *) "$ADB" shell input keyevent KEYCODE_BACK; sleep 2 ;;
  esac
done
echo "failed: $(focus)" >&2
exit 1
