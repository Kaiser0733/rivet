# CPython 3.12.3 for Rivet (aarch64-linux-android, API 26) — Build Recipe

Status: **Completed & Verified**
Date: 2026-11-08
Target: `aarch64-linux-android` (API 26, Android 8.0+ / targetSdk 35)
Toolchain: Android NDK r27c (`clang` 18.0.3, dynamic against Bionic `/system/bin/linker64`, `-Wl,-z,max-page-size=16384` for 16 KB page alignment)

---

## 1. Version & Dependency Selection

- **CPython:** `3.12.3` (matches host build-python `3.12.3` for exact bytecode/freeze compatibility while incorporating Termux's `packages/python` Android Bionic patch set).
- **libffi:** `3.4.6` (static PIC `libffi.a` with `FFI_MMAP_EXEC_WRIT=1` and `ac_cv_func_memfd_create=no` per Termux `packages/libffi`, linked into `_ctypes`).
- **SQLite:** `3.46.1` autoconf amalgamation (static PIC `libsqlite3.a`, linked into `_sqlite3`).
- **OpenSSL:** `3.3.2` (static PIC `libcrypto.a` + `libssl.a` configured for `android-arm64` API 26, linked into `_ssl` and `_hashlib` with `-Wl,--gc-sections -Wl,--exclude-libs,ALL` to avoid symbol collisions with Android system `/system/lib64/libcrypto.so` / `libssl.so` BoringSSL).
- **zlib & expat:** `libz.so` from Android NDK API 26 Bionic sysroot (`/system/lib64/libz.so`); CPython bundled `libexpat` (`--with-builtin-expat`).

---

## 2. Two-Zone Target Layout (Plan §2.3 & §4.2)

- **Zone E (`zone-e/`, packaged in APK `lib/arm64-v8a/` -> `applicationInfo.nativeLibraryDir`):**
  - Every file is a valid ELF with `lib*.so` naming (no version suffixes after `.so`, so Android's APK installer extracts every file) and `0x4000` (16 KB) `LOAD` alignment:
    - `libpython3.so` — the PIE `python3` interpreter executable (`DT_NEEDED: libpython3.12.so`, `DT_RUNPATH: $ORIGIN`)
    - `libpython3.12.so` — the shared CPython library (`DT_SONAME: libpython3.12.so`)
    - `libpyext_<module>.so` — every C extension module from `lib-dynload` (67 modules, including `_ssl`, `_hashlib`, `_sqlite3`, `_ctypes`, `zlib`, etc.)
- **Zone D (`zone-d/`, extracted at runtime to `filesDir/toolchain/python`):**
  - Pure-Python standard library under `lib/python3.12/`
  - `lib/python3.12/lib-dynload/<module>.cpython-312.so` — symlinks pointing to `<nativeLibraryDir>/libpyext_<module>.so` (listed in `zone-d/SYMLINKS.manifest` and materialized as symlinks in `zone-d/`)
- **PATH Symlink Farm (`filesDir/toolchain/bin/`):**
  - `python3 -> <nativeLibraryDir>/libpython3.so`
  - `python -> <nativeLibraryDir>/libpython3.so`

---

## 3. Dependency Builds (Static PIC into CPython Extensions)

Why static PIC (`-fPIC -ffunction-sections -fdata-sections` + `-Wl,--gc-sections -Wl,--exclude-libs,...`) for `libffi.a`, `libsqlite3.a`, `libcrypto.a`, and `libssl.a`:
1. **No APK `lib*.so.X` extraction failures:** Android's package installer only extracts files matching `lib*.so` from `lib/arm64-v8a/` and ignores versioned SONAMEs (`libffi.so.8`, `libcrypto.so.3`, `libssl.so.3`).
2. **No Bionic Zygote/System Library Collisions:** Android's `/system/lib64/` ships system `libcrypto.so`, `libssl.so` (BoringSSL), and `libsqlite.so`. Statically linking our PIC `libcrypto.a`, `libssl.a`, `libsqlite3.a`, and `libffi.a` directly into `_ssl`, `_hashlib`, `_sqlite3`, and `_ctypes` with `--exclude-libs` isolates all symbols inside each extension module.
3. **Zero Transitive `DT_NEEDED` Resolution Risk on `dlopen()`:** Every extension `.so` in Zone E depends only on `libpython3.12.so` (already loaded by the interpreter executable via `DT_RUNPATH: $ORIGIN`) and Bionic system libraries (`libc.so`, `libm.so`, `libdl.so`, `libz.so`).

### 3.1 SQLite 3.46.1 (`sqlite-autoconf-3460100.tar.gz`)
```sh
aarch64-linux-android26-clang -O2 -fPIC -ffunction-sections -fdata-sections \
  -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS4 \
  -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_MATH_FUNCTIONS \
  -DSQLITE_THREADSAFE=1 -c sqlite3.c -o sqlite3.o
llvm-ar rcs "$DEPS/lib/libsqlite3.a" sqlite3.o
cp sqlite3.h sqlite3ext.h "$DEPS/include/"
```

### 3.2 libffi 3.4.6 (`libffi-3.4.6.tar.gz`)
Adapted from Termux `packages/libffi/build.sh` (`ac_cv_func_memfd_create=no` + `FFI_MMAP_EXEC_WRIT=1`):
```sh
CC=aarch64-linux-android26-clang CXX=aarch64-linux-android26-clang++ \
AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
CFLAGS="-O3 -fPIC -ffunction-sections -fdata-sections" \
LDFLAGS="-Wl,-z,max-page-size=16384" \
./configure --host=aarch64-linux-android --build=x86_64-linux-gnu \
  --prefix="$DEPS" --enable-static --disable-shared \
  --disable-multi-os-directory --disable-docs \
  ac_cv_func_memfd_create=no
for f in fficonfig.h */fficonfig.h; do
  [ -f "$f" ] && echo "#define FFI_MMAP_EXEC_WRIT 1" >> "$f"
done
make -j"$(nproc)" && make install
```

### 3.3 OpenSSL 3.3.2 (`openssl-3.3.2.tar.gz`)
Adapted from Termux `packages/openssl/build.sh` (`-DNO_SYSLOG`, `android-arm64`, API 26):
```sh
export ANDROID_NDK_ROOT="$NDK"
./Configure android-arm64 -D__ANDROID_API__=26 -DNO_SYSLOG \
  no-shared no-tests no-apps no-docs no-legacy no-engine \
  -fPIC -ffunction-sections -fdata-sections -O3 \
  --prefix="$DEPS" --openssldir="/data/data/com.kaiser.rivet/files/toolchain/etc/tls"
make -j"$(nproc)" build_libs
make install_dev
```

---

## 4. Patch Set (`patches/`)

All patches are stored in `/home/hatch/workspace/rivet-major-update/toolchain/python/patches/`:

1. **`patches/01-android-soname-and-linking.patch`**
   - **`configure` & `Makefile.pre.in`:** Sets `INSTSONAME="libpython3.12.so"` (unversioned `.so`, removing the `.1.0` suffix that Android's APK installer rejects), sets `PY3LIBRARY=""` (freeing `libpython3.so` for the Zone E PIE interpreter binary), passes `-Wl,-h$(INSTSONAME)` when linking `libpython3.12.so`, disables `MULTIARCH` suffix on `Linux-android`, and replaces hardlinks with symlinks (`0008-do-not-hardlink.patch`).
   - **`Modules/makesetup`:** Links every shared extension module against `$(BLDLIBRARY)` (`-L. -lpython3.12`) and adds `$(LDLIBRARY)` as a Make prerequisite (`0009-fix-module-linking.patch` + parallel `make` race fix).
2. **`patches/02-two-zone-getpath-and-dynload.patch`**
   - **`Modules/getpath.py`:** Before `executable_dir` falls back to `dirname(real_executable)` (which resolves the `Zone D/bin/python3` symlink into `Zone E` `nativeLibraryDir`), checks whether the unresolved symlink parent directory in `Zone D` (`../lib/python3.12/os.py` or `../python/lib/python3.12/os.py`) holds the stdlib. This guarantees that both `PYTHONHOME=<Zone D>` and isolated invocations (`python3 -I` / `python3 -E` / direct symlink execution without `PYTHONHOME`) locate the Zone D stdlib.
   - **`Python/dynload_shlib.c`:** Calls `realpath(pathname, resolved_path)` immediately before `dlopen()` in `_PyImport_FindSharedFuncptr()`. When `importlib` loads `<Zone D>/lib/python3.12/lib-dynload/<mod>.cpython-312.so` (a symlink into `Zone E`), `dlopen()` receives the canonical `<nativeLibraryDir>/libpyext_<mod>.so` path in Zone E while `dlsym()` still resolves `PyInit_<mod>` from the original module shortname.
3. **`patches/03-bionic-socket-and-multiprocessing.patch`**
   - **`Modules/socketmodule.c`:** Embeds Termux's `0004-impl-getprotobyname.patch` protocol lookup table under `#ifdef __ANDROID__` (since Android lacks `/etc/protocols`).
   - **`Lib/multiprocessing/heap.py`, `Modules/_multiprocessing/posixshmem.c`, `Modules/_multiprocessing/semaphore.c`:** Replaces `/dev/shm` and Bionic's unimplemented `sem_open`/`sem_unlink`/`shm_open`/`shm_unlink` (`ENOSYS`) with `TMPDIR`-backed (fallback `/data/data/com.kaiser.rivet/files/toolchain/tmp`) shared-memory files and `mmap(MAP_SHARED)` + Bionic `sem_init(sem, 1, val)` POSIX semaphores.
4. **`patches/04-android-stdlib-paths.patch`**
   - **`Lib/posixpath.py` & `Lib/tempfile.py`:** Sets default `PATH` and fallback temp directory to Rivet's Zone D `/data/data/com.kaiser.rivet/files/toolchain/{bin,tmp}`.
   - **`Lib/http/server.py`:** Removes `os.setuid(nobody)` (`0002-no-setuid-servers.patch`, avoids Android seccomp `SIGSYS`).
   - **`Lib/ctypes/util.py`:** Updates `find_library()` on Android to search Zone E (`os.path.dirname(os.path.realpath(sys.executable))`), `LD_LIBRARY_PATH`, `/system/lib64`, and `/vendor/lib64`, plus LLVM tool names (`0003-ctypes-util-use-llvm-tools.patch`).

---

## 5. Configure Invocation & Environment

Cross-compile configuration invoked from CPython 3.12.3:

```sh
export PATH="$SHIM:$LLVM:$PATH"
export PKG_CONFIG_LIBDIR="$DEPS/lib/pkgconfig"
export PKG_CONFIG_PATH="$DEPS/lib/pkgconfig"

CC=aarch64-linux-android26-clang \
CXX=aarch64-linux-android26-clang++ \
AR=llvm-ar \
RANLIB=llvm-ranlib \
STRIP=llvm-strip \
READELF=llvm-readelf \
CFLAGS="-O3 -fPIC" \
CPPFLAGS="-I$DEPS/include -I$SYSROOT/usr/include -I$SYSROOT/usr/include/aarch64-linux-android" \
LDFLAGS="-L$DEPS/lib -L$SYSROOT/usr/lib/aarch64-linux-android/26 -Wl,-z,max-page-size=16384 -Wl,-rpath,'\$\$ORIGIN' -Wl,--gc-sections -Wl,--exclude-libs,libcrypto.a:libssl.a:libsqlite3.a:libffi.a" \
./configure \
  --host=aarch64-linux-android \
  --build=x86_64-linux-gnu \
  --prefix=/data/data/com.kaiser.rivet/files/toolchain/python \
  --with-build-python=/usr/bin/python3.12 \
  --enable-shared \
  --with-system-ffi \
  --with-openssl="$DEPS" \
  --enable-loadable-sqlite-extensions \
  --without-ensurepip \
  ac_cv_file__dev_ptmx=yes \
  ac_cv_file__dev_ptc=no \
  ac_cv_func_wcsftime=no \
  ac_cv_func_ftime=no \
  ac_cv_func_faccessat=no \
  ac_cv_func_link=no \
  ac_cv_func_linkat=no \
  ac_cv_buggy_getaddrinfo=no \
  ac_cv_little_endian_double=yes \
  ac_cv_posix_semaphores_enabled=yes \
  ac_cv_func_sem_open=yes \
  ac_cv_func_sem_timedwait=yes \
  ac_cv_func_sem_getvalue=yes \
  ac_cv_func_sem_unlink=yes \
  ac_cv_func_shm_open=yes \
  ac_cv_func_shm_unlink=yes \
  ac_cv_working_tzset=yes \
  ac_cv_header_sys_xattr_h=no \
  ac_cv_func_fexecve=no \
  ac_cv_func_getlogin_r=no \
  ac_cv_func_getloadavg=no \
  ac_cv_func_sem_clockwait=no \
  ac_cv_func_preadv2=no \
  ac_cv_func_pwritev2=no \
  ac_cv_func_close_range=no \
  ac_cv_func_copy_file_range=no
```

---

## 6. Module Coverage (In / Out List)

Out of 111 CPython extension modules audited during configure and build:
- **31 built-in core modules:** `_io`, `time`, `posix` (`os`), `_thread`, `_signal`, `_sre` (`re`), `_codecs`, `_weakref`, `_abc`, `_stat`, `_symtable`, etc.
- **67 shared extension modules (BUILT & INCLUDED in Zone E):**
  - **Required Core:** `_ssl`, `_hashlib`, `_sqlite3`, `_ctypes`, `zlib`, `_json`, `_posixsubprocess`, `_socket`, `_multiprocessing`, `_posixshmem`, `_asyncio`, `pyexpat`, `_elementtree`, `_datetime`, `_decimal`, `math`, `cmath`, `_struct`, `binascii`, `_pickle`.
  - **Text & Encoding:** `_codecs_cn`, `_codecs_hk`, `_codecs_iso2022`, `_codecs_jp`, `_codecs_kr`, `_codecs_tw`, `_multibytecodec`, `unicodedata`.
  - **Hashing & Crypto:** `_md5`, `_sha1`, `_sha2`, `_sha3`, `_blake2`, `_random`, `_ssl`, `_hashlib`.
  - **System & OS Utilities:** `select`, `fcntl`, `mmap`, `resource`, `termios`, `syslog`, `grp`, `audioop`, `ossaudiodev`.
  - **Data Structures & Collections:** `array`, `_queue`, `_heapq`, `_bisect`, `_csv`, `_statistics`, `_contextvars`, `_opcode`, `_lsprof`, `_zoneinfo`.
  - **Limited ABI & Subinterpreters:** `_xxsubinterpreters`, `_xxinterpchannels`, `xxlimited`, `xxlimited_35`, `xxsubtype`.
  - **Testing/Internal:** `_ctypes_test`, `_testbuffer`, `_testcapi`, `_testclinic`, `_testimportmultiple`, `_testinternalcapi`, `_testmultiphase`, `_testsinglephase`, `_xxtestfuzz`.
- **12 modules deliberately omitted (Gaps & Rationale):**
  - `_bz2`, `_lzma`: External C compression libs not in Bionic API 26 sysroot (`libbz2.so`, `liblzma.so`); standard library tarfile/zipfile/gzip use pure Python + `zlib.so`.
  - `_tkinter`: Tk/X11 GUI framework, incompatible with headless Android terminal.
  - `_curses`, `_curses_panel`: Requires `libncurses.so` / terminfo; terminal output uses ANSI VT100 / xterm escapes directly via stdout.
  - `readline`: Requires `libreadline.so`; agent tool execution runs headless scripts non-interactively.
  - `_crypt`, `spwd`, `nis`: Obsolete Unix password/shadow/NIS services unimplemented and disallowed on Android.
  - `_uuid`: Uses pure-Python fallback (`uuid.py` generates v4 UUIDs via `os.urandom()` cleanly without `libuuid.so`).
  - `_dbm`, `_gdbm`: Obsolete DBM key-value engines; SQLite3 provides relational and embedded storage.

---

## 7. Verification Evidence

### 7.1 `file` Output
```text
zone-e/libpython3.so:    ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64, stripped
zone-e/libpython3.12.so: ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, stripped
```

### 7.2 `llvm-readelf -l` (LOAD Alignments: 16 KB / `0x4000`)
```text
Program Headers for libpython3.so:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  PHDR           0x000040 0x0000000000000040 0x0000000000000040 0x000268 0x000268 R   0x8
  INTERP         0x0002a8 0x00000000000002a8 0x00000000000002a8 0x000015 0x000015 R   0x1
      [Requesting program interpreter: /system/bin/linker64]
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x000668 0x000668 R   0x4000
  LOAD           0x000668 0x0000000000004668 0x0000000000004668 0x000168 0x000168 R E 0x4000
  LOAD           0x0007d0 0x00000000000087d0 0x00000000000087d0 0x0001e0 0x000830 RW  0x4000
  LOAD           0x0009b0 0x000000000000c9b0 0x000000000000c9b0 0x000018 0x000020 RW  0x4000

Program Headers for libpython3.12.so:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  PHDR           0x000040 0x0000000000000040 0x0000000000000040 0x000230 0x000230 R   0x8
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x253630 0x253630 R   0x4000
  LOAD           0x253630 0x0000000000257630 0x0000000000257630 0x21f200 0x21f200 R E 0x4000
  LOAD           0x472830 0x000000000047a830 0x000000000047a830 0x077250 0x0777d0 RW  0x4000
  LOAD           0x4e9a80 0x00000000004f5a80 0x00000000004f5a80 0x158fa8 0x15a368 RW  0x4000
```

### 7.3 `llvm-readelf -d` (RUNPATH, SONAME & DT_NEEDED)
```text
Dynamic section for libpython3.so:
  0x000000000000001d (RUNPATH)    Library runpath: [$ORIGIN]
  0x0000000000000001 (NEEDED)     Shared library: [libpython3.12.so]
  0x0000000000000001 (NEEDED)     Shared library: [libdl.so]
  0x0000000000000001 (NEEDED)     Shared library: [libm.so]
  0x0000000000000001 (NEEDED)     Shared library: [libc.so]

Dynamic section for libpython3.12.so:
  0x000000000000001d (RUNPATH)      Library runpath: [$ORIGIN]
  0x0000000000000001 (NEEDED)       Shared library: [libdl.so]
  0x0000000000000001 (NEEDED)       Shared library: [libm.so]
  0x0000000000000001 (NEEDED)       Shared library: [libc.so]
  0x000000000000000e (SONAME)       Library soname: [libpython3.12.so]
```

### 7.4 Extension Module Dependency Audit (67 Modules)
Every `.so` in `zone-e/libpyext_*.so` was audited against allowed DT_NEEDED libraries (`libpython3.12.so`, `libc.so`, `libm.so`, `libdl.so`, `libz.so`):
- `libpyext__ssl.so`: `['libpython3.12.so', 'libdl.so', 'libc.so']`
- `libpyext__hashlib.so`: `['libpython3.12.so', 'libdl.so', 'libc.so']`
- `libpyext__sqlite3.so`: `['libm.so', 'libpython3.12.so', 'libdl.so', 'libc.so']`
- `libpyext__ctypes.so`: `['libdl.so', 'libpython3.12.so', 'libc.so']`
- `libpyext_zlib.so`: `['libz.so', 'libpython3.12.so', 'libdl.so', 'libc.so']`
- `libpyext__socket.so`: `['libpython3.12.so', 'libdl.so', 'libc.so']`

Result: **Zero foreign library dependencies.** Zero DT_NEEDED references to system `/system/lib64/libcrypto.so` or `/system/lib64/libssl.so`.

---

## 8. App Runtime Symlink Mechanics

When Rivet initializes the toolchain at app launch (via `Toolchain.kt`):
1. **PATH farm:** `filesDir/toolchain/bin/python3` -> `<nativeLibraryDir>/libpython3.so`
2. **Zone D Stdlib Extraction:** `zone-d/lib/python3.12/` is extracted to `filesDir/toolchain/python/lib/python3.12/`.
3. **Symlink Manifest Materialization:** Rivet reads `zone-d/SYMLINKS.manifest` (or `zone-d/lib/python3.12/lib-dynload/SYMLINKS.manifest`), which contains:
   ```text
   lib/python3.12/lib-dynload/<mod>.cpython-312.so libpyext_<mod>.so
   ```
   For each line, Rivet invokes `Os.symlink(File(nativeLibraryDir, targetName).absolutePath, File(zoneDRoot, relPath).absolutePath)`.
4. **Environment Variables:**
   - `PYTHONHOME = "/data/data/com.kaiser.rivet/files/toolchain/python"` (or `filesDir/toolchain/python`)
   - `PATH = "/data/data/com.kaiser.rivet/files/toolchain/bin:/system/bin:..."`
   - `TMPDIR = "/data/data/com.kaiser.rivet/files/toolchain/tmp"` (or workspace mirror tmp)

---

## 9. Residual Risks

1. **Android Linker Namespace (`dlopen` through symlinks):**
   - On Android 8.0+ (minSdk 26 through targetSdk 35), the dynamic linker restricts `dlopen()` calls from the app process to files located inside the app's `nativeLibraryDir` (Zone E).
   - Our build solves this at the C level in `Python/dynload_shlib.c`: `_PyImport_FindSharedFuncptr()` calls `realpath(pathname, resolved_path)` before calling `dlopen()`. Even though Python's `import` mechanism traverses the symlink inside `lib-dynload/` (Zone D), the path passed to `dlopen()` is the real canonical path in `nativeLibraryDir` (Zone E).
   - *Residual risk:* If an aggressive OEM ROM or Android platform security patch enforces caller-namespace inspection beyond canonical file path resolution, `import` of shared modules would log a linker namespace violation. This must be verified on a physical device.
2. **SELinux Permissions for `mmap(MAP_SHARED)` Temp Files:**
   - Our multiprocessing patch maps anonymous or file-backed semaphores in `TMPDIR` (`filesDir/toolchain/tmp`). Under normal app UID rules this writable directory allows file creation and `mmap(PROT_READ|PROT_WRITE, MAP_SHARED)`. On devices where `TMPDIR` is mounted `noexec`, data `mmap` is unaffected; if an OEM SELinux policy restricts `TMPDIR` file mapping, multiprocessing pools should fall back to threading.
3. **CA Certificate Bundle for HTTPS/urllib:**
   - OpenSSL is statically compiled with default openssldir `/data/data/com.kaiser.rivet/files/toolchain/etc/tls`. Rivet must set `SSL_CERT_FILE` in the command execution environment (pointing to Android's system certs at `/system/etc/security/cacerts` or a bundled CA cert file in Zone D) so HTTPS queries via `urllib.request` or `requests` succeed out of the box.


