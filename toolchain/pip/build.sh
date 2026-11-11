#!/usr/bin/env bash
# Rebuilds the native pip entry point. The output is a PIE executable named
# libpip.so so Android's native-library installer extracts it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
OUT="${OUT:-$ROOT/toolchain/src/main/jniLibs/arm64-v8a/libpip.so}"
"$CC" -fPIE -pie -O2 -fvisibility=hidden \
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s \
    -o "$OUT" "$ROOT/toolchain/pip/launcher.c"
file "$OUT"
