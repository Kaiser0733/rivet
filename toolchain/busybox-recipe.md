# BusyBox 1.36.1 for Rivet — build recipe (verified 2026-11-08)

Produced by Gemini 4 Argon (agy session 74028829) doing real test builds against
NDK r27c; its final report was cut by Hermes' session timebox, so the recipe was
reconstructed from its working notes and the surviving build tree, and the binary
was verified by Hermes directly. The agy fallback (3.8 Flash High, same session)
was not needed — the build had already completed.

## The binary (verified by Hermes, 2026-11-08)

- ELF 64-bit LSB PIE, AArch64, dynamically linked, interpreter /system/bin/linker64
- LOAD segments aligned 0x4000 (16 KB pages — safe on 16 KB-page devices)
- 543,392 bytes, stripped
- Config used for the build == `busybox.config` in this directory (only
  formatting normalised by make; zero CONFIG flips)
- Shell: CONFIG_ASH=y, CONFIG_SH_IS_ASH=y (farm's `sh`/`ash` both valid)
- Farm reconciliation: 99 of hb's 101 applets exist in this build; `join` and
  `fmt` do not (absent from Termux's config lineage, no Kconfig symbols in the
  generated config) — trimmed from the farm list in Toolchain.kt 2026-11-08.

## Recipe

1. Fetch busybox-1.36.1.tar.bz2 from busybox.net.
2. Use `busybox.config` (this directory) as `.config`. It is self-contained:
   - Derived from Termux's packages/busybox config (their bionic answer key),
     with bloat removed (console/TTY, root/daemon, networking servers).
   - CONFIG_NSLOOKUP / CONFIG_FEATURE_NSLOOKUP_BIG off — they force -lresolv,
     which bionic does not ship.
   - CONFIG_EXTRA_CFLAGS carries -DBB_GLOBAL_CONST= (clang global-pointer
     safety) plus warning silences for clang.
   - CONFIG_EXTRA_LDFLAGS carries -Wl,-z,max-page-size=16384 (16 KB pages).
3. Build with the NDK r27c wrappers (wrapper scripts, NOT symlinks — the clang
   driver scripts resolve siblings relative to their own path):
   CROSS_COMPILE=aarch64-linux-android- with aarch64-linux-android26-clang.
   `make -j$(nproc) CROSS_COMPILE=aarch64-linux-android-`
4. Verify: `file busybox` (arm64, dynamic, linker64) and
   `llvm-readelf -l busybox` (LOAD align 0x4000). Rename to libbusybox.so and
   place at toolchain/src/main/jniLibs/arm64-v8a/ in the repo.

## CI

`.github/workflows/toolchain.yml` reproduces this build from the committed
config (checked into the repo under toolchain/busybox.config) so the shipped
binary is never a one-off artifact.
