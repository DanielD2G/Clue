#!/usr/bin/env bash
# Codec x bitrate x fps sweep against a host app (default "Clue Bitrate Test", full-screen noise that
# saturates the requested bitrate). Per run: launch through ShortcutTrampoline with the stream extras,
# sample SurfaceFlinger's frame timeline, end the stream (Quick Menu -> Disconnect), pull the trace,
# run TraceAnalyze and read what the host logged during that run only.
#
# Usage: tools/sweep.sh <secs> "<format> <kbps> [fps]" ...
#   format: auto | forceh265 | forceav1 | neverh265; fps: 30..165 (default: whatever Clue has saved)
#   e.g. tools/sweep.sh 120 "forceh265 150000 120" "forceh265 150000 165"
# Env: CLUE_APP (host app), CLUE_TRACES (trace folder), CLUE_SWEEP_OUT (results file),
#      CLUE_VSYNC_MS (panel vsync; read from dumpsys display per run by default),
#      CLUE_HOST_LOGS (Vibepollo log folder).
# Host fields come only from log lines written after the run started (byte offset saved at launch);
# a field with no new line is printed as "?" and the run is flagged "host=DUDOSO".
set -uo pipefail
export MSYS_NO_PATHCONV=1

secs="${1:?seconds}"; shift
[ $# -gt 0 ] || { echo "usage: $0 <secs> \"<format> <kbps> [fps]\" ..." >&2; exit 2; }
pkg="${CLUE_PACKAGE:-com.clue.client}"
app="${CLUE_APP:-Clue Bitrate Test}"
here="$(cd "$(dirname "$0")" && pwd)"
traces="${CLUE_TRACES:-$here/../../traces}"
out="${CLUE_SWEEP_OUT:-$traces/sweep-results.txt}"
hostlogs="${CLUE_HOST_LOGS:-/c/Program Files/Apollo/config/logs}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="/c/Program Files/DroidScreen/adb/adb.exe"
. "$here/clue-device.sh"
J="${JAVA_HOME:-$(ls -d "/c/Program Files/Eclipse Adoptium/jdk-21"* | sort | tail -1)}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$traces"

hostlog() { ls -t "$hostlogs"/*.log 2>/dev/null | awk 'NR == 1'; }
filesize() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo 0; fi; }

rows() {
  awk -v needle="SurfaceView[$pkg/" '/Layer - TX - / && index($0, needle) > 0 {inl=1; next}
       inl && /^ *Actual/ { n=split($0,a,"|"); e=a[3]; p=a[4]; gsub(/[ \t]/,"",e); gsub(/[ \t]/,"",p);
                            if (p!="N/A") print e, p; inl=0 }' "$1"
}

for cfg in "$@"; do
  set -- $cfg; fmt="$1"; kbps="$2"; fps="${3:-}"; label="$fmt-$kbps${fps:+-${fps}fps}"
  echo "=== $label"

  # Everything the host writes from here on belongs to this run
  log0="$(hostlog)"; off0="$(filesize "$log0")"
  "$ADB" logcat -c
  if ! CLUE_FPS="$fps" CLUE_BITRATE_KBPS="$kbps" CLUE_VIDEO_FORMAT="$fmt" bash "$here/clue-launch-app.sh" "$app" >/dev/null; then
    echo "$(date +%H:%M) $label: not streaming" | tee -a "$out"; continue
  fi
  sleep 5   # decoder warm-up and first presenter margin window
  vsync="${CLUE_VSYNC_MS:-$(clue_vsync_ms)}"
  [ -n "$vsync" ] || { echo "$label: could not read the panel refresh rate; set CLUE_VSYNC_MS" | tee -a "$out"; continue; }
  dec=$("$ADB" logcat -d -s com.limelight.LimeLog | grep -oE "Using codec [^ ]+" | tail -1 || true)

  rm -f "$tmp"/ft_*.txt
  start=$(date +%s); i=0
  while [ $(( $(date +%s) - start )) -lt "$secs" ]; do
    i=$((i+1)); "$ADB" shell 'dumpsys SurfaceFlinger --frametimeline -all' > "$tmp/ft_$i.txt"; sleep 0.3
  done
  vsync_end="$(clue_vsync_ms)"
  cadence=$(for f in "$tmp"/ft_*.txt; do rows "$f" | awk '{print $2}' | sort -n -u | awk '{ if (NR>1) print $1-prev; prev=$1 }'; done |
    awk -v vs="$vsync" '{ n++; k=int($1/vs+0.5); if (k<1) k=1; if (k>4) k=4; h[k]++; s+=$1; ss+=$1*$1 }
      END { if (!n) { print "no-frames"; exit } m=s/n; printf "1x=%.1f%% 2x=%.1f%% 3x+=%.1f%% sd=%.2fms", 100*h[1]/n, 100*h[2]/n, 100*(h[3]+h[4])/n, sqrt(ss/n-m*m) }')
  q2p=$(for f in "$tmp"/ft_*.txt; do rows "$f"; done | awk '{ print $2-$1 }' | sort -n |
    awk '{ v[n++]=$1 } END { if (n) printf "p50=%.2f p99=%.2f", v[int(n*.5)], v[int(n*.99)] }')

  # Disconnect through the Quick Menu so the trace is written
  clue_disconnect || echo "WARNING: Disconnect not found in the Quick Menu" >&2
  session=""
  for t in $(seq 1 15); do
    sleep 1
    session=$("$ADB" logcat -d -s ClueTrace | grep -oE 'traces/[0-9-]+' | tail -1 | cut -d/ -f2 || true)
    [ -n "$session" ] && break
  done
  ana="(no trace)"
  if [ -n "$session" ]; then
    mkdir -p "$traces/$session"
    for f in frames.csv input.csv inputwire.csv presenter.csv vsync.csv summary.txt; do
      "$ADB" pull "/storage/emulated/0/Android/data/$pkg/files/traces/$session/$f" "$(cygpath -w "$traces/$session/$f")" >/dev/null 2>&1 || true
    done
    a=$("$J/bin/java" "$(cygpath -w "$here/TraceAnalyze.java")" "$(cygpath -w "$traces/$session")" --vsync-ms "$vsync" 2>&1)
    echo "$a" > "$traces/$session/analyze.txt"
    net=$(echo "$a" | grep "red+ensamblado" | grep -oE "p50= *[0-9.]+ p95= *[0-9.]+ p99= *[0-9.]+" | tr -s ' ')
    decs=$(echo "$a" | grep "^decodificacion" | grep -oE "p50= *[0-9.]+ p95= *[0-9.]+ p99= *[0-9.]+ max= *[0-9.]+" | tr -s ' ')
    rel=$(echo "$a" | grep "salida decoder->release" | grep -oE "p50= *[0-9.]+ p95= *[0-9.]+ p99= *[0-9.]+" | tr -s ' ')
    e2e=$(echo "$a" | grep "completo->pantalla" | grep -oE "p50= *[0-9.]+ p95= *[0-9.]+ p99= *[0-9.]+" | tr -s ' ')
    fr=$(echo "$a" | grep -oE "^frames=[0-9]+ presented=[0-9]+")
    sched=$(echo "$a" | grep "cadencia (programada)" | sed 's/.*: //')
    # Host processing latency (capture to send, includes NVENC) and stream rate on the client clock
    hostlat=$(awk -F, 'NR>1 && $4>0 { print $4/10 }' "$traces/$session/frames.csv" | sort -n |
      awk '{ v[n++]=$1 } END { if (n) printf "p50=%.1f p99=%.1f max=%.1f", v[int(n*.5)], v[int(n*.99)], v[n-1] }')
    rate=$(awk -F, 'NR>1 && $3>0 { if (!f) f=$3; l=$3; c++ } END { if (c>1) printf "%.2f", (c-1)/((l-f)/1e6) }' "$traces/$session/frames.csv")
    ana="net[$net] decode[$decs] out->release[$rel] complete->screen[$e2e] $fr sched[$sched] hostlat[$hostlat] stream=${rate}fps"
  fi

  # Host lines written during this run only: from the saved offset of the same log, or the whole
  # file when the host started a new log meanwhile
  log1="$(hostlog)"
  if [ -n "$log1" ] && [ "$log1" = "$log0" ]; then
    tail -c +$((off0 + 1)) "$log1" > "$tmp/host.log"
  elif [ -n "$log1" ]; then
    cat "$log1" > "$tmp/host.log"
  else
    : > "$tmp/host.log"
  fi
  hostfield() { grep -a -E "$1" "$tmp/host.log" | tail -1 | grep -oE "$2" || true; }
  req=$(hostfield "Client Requested bitrate" "\[[0-9]+kbps\]")
  enc=$(hostfield "created encoder" "created encoder [A-Z0-9]+ P[0-9]")
  hfps=$(hostfield "Requested frame rate" "Requested frame rate \[[^]]*\]" | grep -oE "\[[^]]*\]" || true)
  doubt=""
  [ -n "$req" ] && [ -n "$enc" ] && [ -n "$hfps" ] || doubt=" host=DUDOSO(no new log lines)"
  [ -n "$req" ] && [ "$req" != "[${kbps}kbps]" ] && doubt="$doubt host-bitrate-mismatch"
  vs="vsync=${vsync}ms"
  [ -n "$vsync_end" ] && [ "$vsync_end" != "$vsync" ] && vs="$vs->${vsync_end}ms(CHANGED)"
  line="$(date +%H:%M) $label | host req=${req:-?} fps=${hfps:-?} ${enc:-encoder ?}$doubt | ${dec:-codec ?} | $vs | SF cadence $cadence | queue->present $q2p | $ana | $session"
  echo "$line" | tee -a "$out"
  sleep 4
done
echo DONE
