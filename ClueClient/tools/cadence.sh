#!/usr/bin/env bash
# Measures the real display cadence of a streaming client's video layer from
# SurfaceFlinger's frame timeline (dumpsys --latency returns nothing on Android 16).
#
# Usage: tools/cadence.sh [seconds=120] [package=com.clue.client] [vsync_ms=8.333]
# Prints a per-dump time series of repeated frames, then the share of present
# intervals that lasted 1, 2, 3 and 4+ vsyncs. Overlapping dumps are de-duplicated
# by present time within each dump; runs should be >= 120 s because the host/panel
# clock beat cycles roughly once a minute.
set -euo pipefail
export MSYS_NO_PATHCONV=1

secs="${1:-120}"
pkg="${2:-com.clue.client}"
vsync="${3:-8.333}"
if [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
  ADB="/c/Program Files/DroidScreen/adb/adb.exe"
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Present times (ms, relative to the dump) of the video layer in one frametimeline dump.
presents() {
  awk -v needle="SurfaceView[$pkg/" '/Layer - TX - / && index($0, needle) > 0 {inl=1; next}
       inl && /^ *Actual/ { n=split($0,a,"|"); p=a[4]; gsub(/[ \t]/,"",p); if (p!="N/A") print p; inl=0 }' "$1" | sort -n -u
}

start=$(date +%s); i=0
while [ $(( $(date +%s) - start )) -lt "$secs" ]; do
  i=$((i+1))
  "$ADB" shell 'dumpsys SurfaceFlinger --frametimeline -all' > "$tmp/ft_$i.txt"
  now=$(( $(date +%s) - start ))
  presents "$tmp/ft_$i.txt" | awk -v t="$now" -v vs="$vsync" '
    { if (NR>1) { d=$1-prev; if (d > 1.5*vs) dbl++; tot++ } prev=$1 }
    END { printf "%3ds:%2d/%-3d ", t, dbl+0, tot+0 }'
  [ $((i % 8)) -eq 0 ] && echo
  sleep 0.3
done
echo

for f in "$tmp"/ft_*.txt; do
  presents "$f" | awk '{ if (NR>1) print $1-prev; prev=$1 }'
done | awk -v vs="$vsync" '
  { n++; s+=$1; ss+=$1*$1; k=int($1/vs+0.5); if (k<1) k=1; if (k>4) k=4; h[k]++ }
  END {
    if (n == 0) { print "no frames found for the video layer"; exit 1 }
    mean=s/n; sd=sqrt(ss/n-mean*mean);
    printf "intervals=%d mean=%.2fms sd=%.2fms\n", n, mean, sd;
    printf "1x=%.1f%% 2x=%.1f%% 3x=%.1f%% 4+x=%.1f%%\n", 100*h[1]/n, 100*h[2]/n, 100*h[3]/n, 100*h[4]/n;
  }'
