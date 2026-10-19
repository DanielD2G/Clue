#!/usr/bin/env bash
# One-pass measurement of a Clue streaming session on the device.
#
# Starts "Desktop" in the given pacing mode, samples SurfaceFlinger's frame timeline once for both
# the real cadence and the real queue->present latency, then stops the stream, pulls only the new
# trace and adds the decode-out->release wait, giving decode-out->present end to end.
#
# Usage: tools/measure.sh <clue|balanced|latency|...> [seconds=40] [label]
# Appends one line per run to ../traces/measure-results.txt.
# 40 s covers several host/panel clock beats when the host runs >= 0.1 fps off the panel; the
# script prints the beat measured from the trace and warns when the run was shorter than 3 beats.
set -euo pipefail
export MSYS_NO_PATHCONV=1

mode="${1:?pacing mode}"; secs="${2:-40}"; label="${3:-$mode}"
pkg="${CLUE_PACKAGE:-com.clue.client}"
here="$(cd "$(dirname "$0")" && pwd)"
traces="$here/../../traces"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="/c/Program Files/DroidScreen/adb/adb.exe"
jdk="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

CLUE_PACING="$mode" bash "$here/clue-launch-desktop.sh" >/dev/null
sleep 5   # decoder warm-up and first margin window

# Rows "end present" (ms, relative to each dump) of the video layer, one dump per file.
rows() {
  awk -v needle="SurfaceView[$pkg/" '/Layer - TX - / && index($0, needle) > 0 {inl=1; next}
       inl && /^ *Actual/ { n=split($0,a,"|"); e=a[3]; p=a[4]; gsub(/[ \t]/,"",e); gsub(/[ \t]/,"",p);
                            if (p!="N/A") print e, p; inl=0 }' "$1"
}

start=$(date +%s); i=0
while [ $(( $(date +%s) - start )) -lt "$secs" ]; do
  i=$((i+1))
  "$ADB" shell 'dumpsys SurfaceFlinger --frametimeline -all' > "$tmp/ft_$i.txt"
  sleep 0.3
done

vsync=8.333
cadence=$(for f in "$tmp"/ft_*.txt; do rows "$f" | awk '{print $2}' | sort -n -u | awk '{ if (NR>1) print $1-prev; prev=$1 }'; done |
  awk -v vs="$vsync" '{ n++; k=int($1/vs+0.5); if (k<1) k=1; if (k>4) k=4; h[k]++; s+=$1; ss+=$1*$1 }
    END { if (!n) { print "no-frames"; exit } m=s/n; printf "1x=%.1f%% 2x=%.1f%% 3x+=%.1f%% sd=%.2fms", 100*h[1]/n, 100*h[2]/n, 100*(h[3]+h[4])/n, sqrt(ss/n-m*m) }')
q2p=$(for f in "$tmp"/ft_*.txt; do rows "$f"; done | awk '$1!="N/A" { print $2-$1 }' | sort -n |
  awk '{ v[n++]=$1 } END { printf "%.2f %.2f %.2f", v[int(n*.5)], v[int(n*.9)], v[int(n*.99)] }')

# Stop the stream so the trace is written, then pull just that session
"$ADB" logcat -c
"$ADB" shell input keyevent 4
session=""
for t in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  session=$("$ADB" logcat -d -s ClueTrace | grep -oE 'traces/[0-9-]+' | tail -1 | cut -d/ -f2 || true)
  [ -n "$session" ] && break
done
wait50="?"; beat="?"
if [ -n "$session" ]; then
  mkdir -p "$traces/$session"
  for f in frames.csv input.csv inputwire.csv presenter.csv vsync.csv summary.txt; do
    "$ADB" pull "/storage/emulated/0/Android/data/$pkg/files/traces/$session/$f" "$(cygpath -w "$traces/$session/$f")" >/dev/null 2>&1 || true
  done
  wait50=$("$jdk/bin/java" "$(cygpath -w "$here/TraceAnalyze.java")" "$(cygpath -w "$traces/$session")" | grep "salida decoder->release" | grep -oE "p50= *[0-9.]+" | grep -oE "[0-9.]+$" || echo "?")
  # Host/panel beat, both rates on the tablet's clock: frames arriving from the decoder per second
  # (over the whole session) against the panel rate from the recorded vsync periods.
  fps=$(awk -F, 'NR>1 && $2>0 { if (!f) f=$2; l=$2; c++ } END { if (c>1) printf "%.4f", (c-1)/((l-f)/1e9) }' "$traces/$session/presenter.csv")
  hz=$(awk -F, 'NR>1 && $4>0 { print $4 }' "$traces/$session/vsync.csv" | sort -n | awk '{ v[n++]=$1 } END { if (n) printf "%.4f", 1e9/v[int(n/2)] }')
  if [ -n "$fps" ] && [ -n "$hz" ]; then
    beat=$(awk -v f="$fps" -v h="$hz" 'BEGIN { d=f-h; if (d<0) d=-d; if (d < 0.005) print ">200"; else printf "%.1f", 1/d }')
    beat="$beat (stream ${fps} fps, panel ${hz} Hz)"
  fi
fi

set -- $q2p
e2e="?"
if [ "$wait50" != "?" ] && [ -n "$wait50" ]; then
  e2e=$(awk -v w="$wait50" -v q="$1" 'BEGIN { printf "%.1f", w+q }')
fi
line="$(date +%H:%M) $label: $cadence | queue->present p50=$1 p90=$2 p99=$3 | out->release p50=$wait50 | out->present p50~$e2e ms | beat ${beat} s | $session"
echo "$line" | tee -a "$traces/measure-results.txt"
if [ "${beat%% *}" != "?" ] && [ "${beat%% *}" != ">200" ] && awk -v b="${beat%% *}" -v s="$secs" 'BEGIN { exit !(s < 3*b) }'; then
  echo "WARNING: run shorter than 3 host/panel beats (${beat}s each); use at least $(awk -v b="${beat%% *}" 'BEGIN { printf "%d", 3*b+1 }') s"
fi
