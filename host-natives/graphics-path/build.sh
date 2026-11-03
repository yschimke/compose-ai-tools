#!/usr/bin/env bash
# Compiles graphics-path's sources in $1 into $2 for platform $3; called by host-natives/build.sh,
# which fetched and checked the sources. Prints the library's path.
#
# The sources are AndroidX's, unchanged, with two stand-in headers in host/: `android/api-level.h`
# (reports API 34) and a force-included `host_compat.h` for what bionic and the NDK's libc++ supply.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
src="$1"
out="$2"
platform="$3"
library=androidx.graphics.path

common=(
  -std=c++17 -O2 -fPIC -shared -fvisibility=hidden -fvisibility-inlines-hidden
  -include "$here/host/host_compat.h"
  -I "$here/host" -I "$src" -I "$JAVA_HOME/include"
)
sources=("$src/Conic.cpp" "$src/PathIterator.cpp" "$src/pathway.cpp")

if [[ "$platform" == linux-* ]]; then
  lib="$out/lib$library.so"
  "${CXX:-g++}" "${common[@]}" -I "$JAVA_HOME/include/linux" \
    -Wl,--version-script="$src/libandroidx.graphics.path.map" -Wl,-z,defs \
    -static-libstdc++ -static-libgcc \
    -o "$lib" "${sources[@]}" >&2
  exported="$(nm -D --defined-only "$lib" | awk '$2 == "T" { sub(/@.*/, "", $3); print $3 }')"
  glibc="$(objdump -T "$lib" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
  echo "host-natives: $lib needs $glibc" >&2
else
  lib="$out/lib$library.dylib"
  "${CXX:-clang++}" "${common[@]}" -I "$JAVA_HOME/include/darwin" \
    -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 \
    -Wl,-exported_symbol,_JNI_OnLoad -install_name "@rpath/lib$library.dylib" \
    -o "$lib" "${sources[@]}" >&2
  exported="$(nm -gU -arch arm64 "$lib" | awk '{ print $3 }' | sed 's/^_//')"
  [[ "$(lipo -archs "$lib")" == *arm64* && "$(lipo -archs "$lib")" == *x86_64* ]] || {
    echo "host-natives: $lib is not a universal arm64 + x86_64 binary" >&2
    exit 1
  }
fi

if [[ "$exported" != "JNI_OnLoad" ]]; then
  echo "host-natives: $lib exports [$exported], expected only JNI_OnLoad" >&2
  exit 1
fi
echo "$lib"
