#!/usr/bin/env bash
# Rebuilds the two shipped Git binaries from the 2.56.0 recipe. CI only: the
# source tree and NDK are not kept on the device. Outputs are verified for
# PIE, 16 KB LOAD alignment, and a bionic-only DT_NEEDED set.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${WORKDIR:-${RUNNER_TEMP:-/tmp}/rivet-git-build}"
SRC="$WORK/src"
DEPS="$WORK/deps"
OUT="${OUTDIR:-$ROOT/toolchain/git/out}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CC="$LLVM/aarch64-linux-android26-clang"
CXX="$LLVM/aarch64-linux-android26-clang++"
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_NDK_HOME="$NDK"
export PATH="$LLVM:$PATH"

rm -rf "$WORK"
mkdir -p "$SRC" "$DEPS" "$OUT"
cd "$SRC"

fetch() {
  local url="$1" file="$2"
  curl -fsSL --retry 3 -o "$file" "$url"
  sha256sum "$file"
}

fetch https://www.openssl.org/source/openssl-3.3.2.tar.gz openssl-3.3.2.tar.gz
fetch https://curl.se/download/curl-8.23.0.tar.xz curl-8.23.0.tar.xz
fetch https://github.com/libexpat/libexpat/releases/download/R_2_10_0/expat-2.10.0.tar.bz2 expat-2.10.0.tar.bz2
fetch https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.49/pcre2-10.49.tar.bz2 pcre2-10.49.tar.bz2
fetch https://www.kernel.org/pub/software/scm/git/git-2.56.0.tar.xz git-2.56.0.tar.xz

tar -xzf openssl-3.3.2.tar.gz
tar -xJf curl-8.23.0.tar.xz
tar -xjf expat-2.10.0.tar.bz2
tar -xjf pcre2-10.49.tar.bz2
tar -xJf git-2.56.0.tar.xz

# 02 is a libcurl patch; apply it before curl's build.
(cd curl-8.23.0 && patch -p11 < "$ROOT/toolchain/git/patches/02-curl-android-ca-trust.patch")

echo "== OpenSSL 3.3.2 =="
(cd openssl-3.3.2 && \
  ./Configure android-arm64 -D__ANDROID_API__=26 -DNO_SYSLOG \
    no-shared no-tests no-apps no-docs no-legacy no-engine \
    -fPIC -ffunction-sections -fdata-sections -O3 --prefix="$DEPS" && \
  make -j"$(nproc)" build_libs && \
  make install_dev)

COMMON_CFLAGS="-O3 -fPIC -ffunction-sections -fdata-sections"
COMMON_LDFLAGS="-Wl,-z,max-page-size=16384"

echo "== expat 2.10.0 =="
(cd expat-2.10.0 && \
  CC="$CC" CXX="$CXX" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" STRIP="$LLVM/llvm-strip" \
  CFLAGS="$COMMON_CFLAGS" LDFLAGS="$COMMON_LDFLAGS" \
  ./configure --host=aarch64-linux-android --build=x86_64-linux-gnu \
    --prefix="$DEPS" --enable-static --disable-shared \
    --without-xmlwf --without-examples --without-tests --without-docbook && \
  make -j"$(nproc)" && make install)

echo "== pcre2 10.49 =="
(cd pcre2-10.49 && \
  CC="$CC" CXX="$CXX" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" STRIP="$LLVM/llvm-strip" \
  CFLAGS="$COMMON_CFLAGS" LDFLAGS="$COMMON_LDFLAGS" \
  ./configure --host=aarch64-linux-android --build=x86_64-linux-gnu \
    --prefix="$DEPS" --enable-static --disable-shared \
    --enable-jit --disable-pcre2grep-callout && \
  make -j"$(nproc)" && make install)

echo "== libcurl 8.23.0 =="
(cd curl-8.23.0 && \
  CC="$CC" CXX="$CXX" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" STRIP="$LLVM/llvm-strip" \
  CFLAGS="$COMMON_CFLAGS" \
  CPPFLAGS="-I$DEPS/include -I$SYSROOT/usr/include -I$SYSROOT/usr/include/aarch64-linux-android" \
  LDFLAGS="-L$DEPS/lib -L$SYSROOT/usr/lib/aarch64-linux-android/26 $COMMON_LDFLAGS" \
  LIBS="-ldl -pthread" \
  ./configure --host=aarch64-linux-android --build=x86_64-linux-gnu \
    --prefix="$DEPS" --enable-static --disable-shared \
    --with-openssl="$DEPS" --with-zlib \
    --with-ca-path=/system/etc/security/cacerts --with-ca-fallback \
    --without-libpsl --without-libidn2 --without-brotli --without-zstd \
    --without-librtmp --without-nghttp2 --without-nghttp3 --without-ngtcp2 \
    --without-libssh2 --disable-ares --disable-ldap --disable-ldaps \
    --disable-rtsp --disable-dict --disable-telnet --disable-tftp \
    --disable-pop3 --disable-imap --disable-smb --disable-smtp \
    --disable-gopher --disable-mqtt --disable-manual --disable-docs \
    ac_cv_func_getpwuid=yes && \
  make -j"$(nproc)" -C lib && make -C lib install && \
  make -j"$(nproc)" -C include && make -C include install && \
  install -m 755 curl-config "$DEPS/bin/curl-config")

echo "== git 2.56.0 =="
(cd git-2.56.0 && \
  patch -p11 < "$ROOT/toolchain/git/patches/01-git-android-bionic.patch" && \
  patch -p1 < "$ROOT/toolchain/git/patches/03-ident-null-gecos.patch" && \
  CC="$CC" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" STRIP="$LLVM/llvm-strip" \
  CFLAGS="$COMMON_CFLAGS" \
  CPPFLAGS="-I$DEPS/include -I$SYSROOT/usr/include -I$SYSROOT/usr/include/aarch64-linux-android" \
  LDFLAGS="-L$DEPS/lib -L$SYSROOT/usr/lib/aarch64-linux-android/26 $COMMON_LDFLAGS -Wl,--gc-sections -Wl,--exclude-libs,ALL" \
  LIBS="-lssl -lcrypto -lz -ldl" \
  CURL_CONFIG="$DEPS/bin/curl-config" \
  ./configure \
    --host=aarch64-linux-android --build=x86_64-linux-gnu \
    --prefix=/data/data/com.kaiser.rivet/files/toolchain \
    --with-openssl="$DEPS" --with-libpcre2="$DEPS" \
    --with-curl="$DEPS" --with-expat="$DEPS" \
    --without-iconv --with-shell=/system/bin/sh \
    --with-pager=cat --with-editor=vi --with-perl=/usr/bin/perl \
    --without-tcltk --without-python \
    ac_cv_fread_reads_directories=yes \
    ac_cv_header_libintl_h=no \
    ac_cv_iconv_omits_bom=no \
    ac_cv_snprintf_returns_bogus=no && \
  make -j"$(nproc)" \
    SHELL=/bin/bash NO_NSEC=1 NO_GETTEXT=1 NO_INSTALL_HARDLINKS=1 \
    INSTALL_SYMLINKS=1 CSPRNG_METHOD=openssl USE_LIBPCRE2=1 NO_RUST=1 \
    NO_PERL=1 NO_TCLTK=1 NO_PYTHON=1 DEFAULT_PAGER=cat DEFAULT_EDITOR=vi \
    SHELL_PATH=/system/bin/sh SKIP_DASHED_BUILT_INS=YesPlease)

install -m 755 git-2.56.0/git "$OUT/libgit.so"
install -m 755 git-2.56.0/git-remote-https "$OUT/libgit-remote-https.so"
"$LLVM/llvm-strip" --strip-all "$OUT/libgit.so" "$OUT/libgit-remote-https.so"

for f in "$OUT/libgit.so" "$OUT/libgit-remote-https.so"; do
  echo "== verify $(basename "$f") =="
  file "$f"
  readelf -h "$f" | grep -q 'Type:.*DYN' || { echo "not PIE"; exit 1; }
  aligns="$(readelf -lW "$f" | awk '$1=="LOAD" {print $NF}' | sort -u | tr '\n' ' ')"
  test "$aligns" = "0x4000 " || { echo "bad LOAD alignment: $aligns"; exit 1; }
  needed="$(readelf -dW "$f" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort -u | tr '\n' ' ')"
  case "$needed" in
    "libc.so libdl.so libz.so "|"libc.so libz.so "|"libc.so libdl.so "|"libc.so ") ;;
    *) echo "unexpected DT_NEEDED: $needed"; exit 1 ;;
  esac
  echo "LOAD align: $aligns"
  echo "DT_NEEDED: $needed"
done

sha256sum "$OUT/libgit.so" "$OUT/libgit-remote-https.so" | tee "$OUT/SHA256SUMS"
