#!/usr/bin/env bash
# Replays a recorded session through Clue's presenter policy and a SurfaceFlinger model.
# Usage: tools/presenter-sim.sh <session dir> [latchOffsetMs] [-Dvariants="safety,hyst;..."]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jdk="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"
out="$(mktemp -d)"
"$jdk/bin/javac" -d "$out" "$here/../app/src/main/java/com/clue/video/PresentPolicy.java" "$here/PresenterSim.java"
session="$1"; shift
latch="${1:-0}"; [ $# -gt 0 ] && shift
"$jdk/bin/java" -cp "$out" "$@" PresenterSim "$(cygpath -w "$session" 2>/dev/null || echo "$session")" "$latch"
