#!/usr/bin/env bash
# Starts a host app on the device without touching the UI: a fresh PcView applies the stream
# extras, then ShortcutTrampoline opens the app. Confirms the "quit the running app" dialog when
# another app is running on the host.
#
# Usage: tools/clue-launch-app.sh [app-name="Clue Bitrate Test"] [pc-name=DanielsPC]
# Env (all optional, persisted by Clue until changed): CLUE_FPS, CLUE_BITRATE_KBPS,
#   CLUE_VIDEO_FORMAT (auto|forceav1|forceh265|neverh265), CLUE_PACING (clue|balanced|latency|...)
set -euo pipefail
export MSYS_NO_PATHCONV=1

app="${1:-Clue Bitrate Test}"
pc="${2:-DanielsPC}"
pkg="${CLUE_PACKAGE:-com.clue.client}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="/c/Program Files/DroidScreen/adb/adb.exe"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/clue-device.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

focus() { "$ADB" shell dumpsys window | awk '/mCurrentFocus=/ && !d { print; d=1 }' || true; }

"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am force-stop "$pkg"
"$ADB" shell am start -W -n "$pkg/com.limelight.PcView" \
  ${CLUE_FPS:+--es clue_fps "$CLUE_FPS"} \
  ${CLUE_BITRATE_KBPS:+--ei clue_bitrate_kbps "$CLUE_BITRATE_KBPS"} \
  ${CLUE_VIDEO_FORMAT:+--es clue_video_format "$CLUE_VIDEO_FORMAT"} \
  ${CLUE_PACING:+--es clue_pacing "$CLUE_PACING"} >/dev/null
sleep 2
# The app name is quoted again for the device shell, which splits arguments on spaces
"$ADB" shell am start -n "$pkg/com.limelight.ShortcutTrampoline" --es Name "$pc" --es AppName "'$app'" >/dev/null

for attempt in $(seq 1 20); do
  sleep 1.5
  case "$(focus)" in
    *com.limelight.Game*) echo "streaming"; exit 0 ;;
  esac
  # A dialog: quit the running app first (Yes / Sí / Si)
  if clue_ui_dump "$tmp/ui.xml" && grep -qiE 'quit the running app|quitar la aplicaci' "$tmp/ui.xml"; then
    clue_tap_text "$tmp/ui.xml" 'Yes|S[ií]' || true
  fi
done
echo "failed: $(focus)" >&2
exit 1
