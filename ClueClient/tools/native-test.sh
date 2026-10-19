#!/usr/bin/env bash
# Builds Clue's native unit tests for arm64 with the NDK's clang and runs them on the connected device.
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
"$ADB" push "$(cygpath -w "$out/clue_native_tests")" /data/local/tmp/clue_native_tests >/dev/null
"$ADB" shell chmod 755 /data/local/tmp/clue_native_tests
"$ADB" shell /data/local/tmp/clue_native_tests
