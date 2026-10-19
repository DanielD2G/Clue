#!/usr/bin/env bash
# Builds Clue's native unit tests for arm64 with the NDK's clang and runs them on the connected device.
# --build-only compiles them without a device.
set -euo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
core="$here/../app/src/main/jni/moonlight-core"
ndk="${ANDROID_NDK:-$LOCALAPPDATA/Android/Sdk/ndk/29.0.14206865}"
cc="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

"$cc" --target=aarch64-linux-android29 -O2 -Wall -Werror \
  -I "$(cygpath -w "$core/moonlight-common-c/src")" \
  "$(cygpath -w "$core/test/clue_redundancy_test.c")" \
  "$(cygpath -w "$core/moonlight-common-c/src/ClueRedundancy.c")" \
  -o "$(cygpath -w "$out/clue_native_tests")" </dev/null
"$cc" --target=aarch64-linux-android29 -O2 -Wall -Wextra -Werror -Wno-unused-parameter \
  -I "$(cygpath -w "$core")" \
  "$(cygpath -w "$core/test/clue_audio_test.c")" \
  "$(cygpath -w "$core/clue_audio_ring.c")" \
  -o "$(cygpath -w "$out/clue_audio_tests")" </dev/null

if [ "${1:-}" = "--build-only" ]; then
  echo "native tests built"
  exit 0
fi

for t in clue_native_tests clue_audio_tests; do
  "$ADB" push "$(cygpath -w "$out/$t")" "/data/local/tmp/$t" >/dev/null
  "$ADB" shell chmod 755 "/data/local/tmp/$t"
  "$ADB" shell "/data/local/tmp/$t"
done
