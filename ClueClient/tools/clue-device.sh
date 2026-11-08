#!/usr/bin/env bash
# Shared helpers for the measurement scripts; source it after setting ADB.
#   clue_vsync_ms        current panel vsync period in ms, from dumpsys display (empty if unknown)
#   clue_ui_dump <file>  uiautomator dump of the screen into <file>
#   clue_tap_text <file> <regex>  taps the centre of the first node whose text matches <regex>
#                        (case-insensitive, whole text); returns 1 when there is none
#   clue_disconnect      ends the stream: back opens the Quick Menu, then taps Disconnect
# The pipelines read their whole input (awk, no grep -m/head): an early exit would SIGPIPE adb and,
# under the callers' "set -euo pipefail", end the script without a message.

clue_vsync_ms() {
  "$ADB" shell dumpsys display | tr -d '\r' |
    awk '!done && match($0, /mActiveRenderFrameRate=[0-9.]+/) {
           hz = substr($0, RSTART + 23, RLENGTH - 23) + 0; if (hz > 0) printf "%.4f", 1000 / hz; done = 1 }'
}

clue_ui_dump() {
  "$ADB" shell uiautomator dump /sdcard/clue-ui.xml >/dev/null 2>&1 || return 1
  "$ADB" pull /sdcard/clue-ui.xml "$(cygpath -w "$1")" >/dev/null 2>&1
}

clue_tap_text() {
  local b
  b=$(tr '>' '\n' < "$1" | awk -v re="^($2)\$" '
        !done && match($0, /text="[^"]*"/) {
          t = substr($0, RSTART + 6, RLENGTH - 7)
          if (tolower(t) ~ tolower(re) && match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)) {
            s = substr($0, RSTART, RLENGTH); gsub(/[^0-9]+/, " ", s); print s; done = 1 } }')
  [ -n "${b// /}" ] || return 1
  set -- $b
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

clue_disconnect() {
  local xml i
  xml="$(mktemp)"
  for i in 1 2 3; do
    "$ADB" shell input keyevent KEYCODE_BACK
    sleep 1.5
    if clue_ui_dump "$xml" && clue_tap_text "$xml" 'Disconnect|Desconectar'; then
      rm -f "$xml"; return 0
    fi
  done
  rm -f "$xml"
  return 1
}
