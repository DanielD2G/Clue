#!/usr/bin/env bash
# Builds Clue's native unit tests for arm64 with the NDK's clang and runs them on the connected device.
set -euo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
core="$here/../app/src/main/jni/moonlight-core"
common="$core/moonlight-common-c"
ndk="${ANDROID_NDK:-$LOCALAPPDATA/Android/Sdk/ndk/29.0.14206865}"
cc="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
w() { cygpath -w "$1"; }

# Controller redundancy policy (pure functions)
"$cc" --target=aarch64-linux-android29 -O2 -Wall -Werror \
  -I "$(w "$common/src")" \
  "$(w "$core/test/clue_redundancy_test.c")" \
  "$(w "$common/src/ClueRedundancy.c")" \
  -o "$(w "$out/clue_native_tests")" </dev/null

# Timed queue wait (needs the platform layer; same defines as Android.mk)
"$cc" --target=aarch64-linux-android29 -O2 -Wall -Werror \
  -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1 -DLC_DEBUG \
  -I "$(w "$common/src")" -I "$(w "$common/enet/include")" \
  -I "$(w "$common/nanors")" -I "$(w "$common/nanors/deps/obl")" \
  "$(w "$core/test/clue_lbq_test.c")" \
  "$(w "$common/src/LinkedBlockingQueue.c")" \
  "$(w "$common/src/Platform.c")" \
  -llog \
  -o "$(w "$out/clue_lbq_tests")" </dev/null

for t in clue_native_tests clue_lbq_tests; do
  "$ADB" push "$(w "$out/$t")" "/data/local/tmp/$t" >/dev/null
  "$ADB" shell chmod 755 "/data/local/tmp/$t"
  "$ADB" shell "/data/local/tmp/$t"
done
