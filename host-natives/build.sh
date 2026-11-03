#!/usr/bin/env bash
# Builds the host natives for the machine it runs on, and packs them for a host-natives release.
#
#   host-natives/build.sh            # -> host-natives/build/<platform>/ and build/dist/*.tar.gz
#
# Platforms: `linux-x86_64`, and `macos-universal` (one arm64 + x86_64 dylib). Linux arm64 is not
# built: Robolectric publishes no Linux arm64 native runtime, so the simulator cannot run there.
#
# Needs a C++17 compiler (`CXX`, default g++ on Linux and clang++ on macOS), curl, and a JDK at
# JAVA_HOME for <jni.h>. The AndroidX sources are fetched at the commit `upstream.properties`
# names and checked against `sources.sha256` before anything is compiled.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
version="$(tr -d '[:space:]' <"$here/VERSION")"
build="$here/build"

prop() { sed -n "s/^$1=//p" "$here/graphics-path/upstream.properties"; }
repository="$(prop repository)"
commit="$(prop commit)"
upstream_path="$(prop path)"
library="$(prop library)"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux-x86_64 ;;
  Darwin-*) platform=macos-universal ;;
  *)
    echo "host-natives: no build for $(uname -s) $(uname -m)" >&2
    exit 1
    ;;
esac

: "${JAVA_HOME:?JAVA_HOME must point at a JDK (for jni.h)}"

src="$build/src/graphics-path"
out="$build/$platform"
rm -rf "$src" "$out" "$build/dist"
mkdir -p "$src" "$out"
while read -r _ file; do
  mkdir -p "$src/$(dirname "$file")"
  curl -fsSL --retry 4 -o "$src/$file" \
    "https://raw.githubusercontent.com/$repository/$commit/$upstream_path/$file"
done <"$here/graphics-path/sources.sha256"
if command -v sha256sum >/dev/null; then
  (cd "$src" && sha256sum --quiet -c "$here/graphics-path/sources.sha256")
else
  (cd "$src" && shasum -a 256 --quiet -c "$here/graphics-path/sources.sha256")
fi

common=(
  -std=c++17 -O2 -fPIC -shared -fvisibility=hidden -fvisibility-inlines-hidden
  -include "$here/graphics-path/host/host_compat.h"
  -I "$here/graphics-path/host" -I "$src" -I "$JAVA_HOME/include"
)
sources=("$src/Conic.cpp" "$src/PathIterator.cpp" "$src/pathway.cpp")

if [[ "$platform" == linux-* ]]; then
  lib="$out/lib$library.so"
  "${CXX:-g++}" "${common[@]}" -I "$JAVA_HOME/include/linux" \
    -Wl,--version-script="$src/libandroidx.graphics.path.map" -Wl,-z,defs \
    -static-libstdc++ -static-libgcc \
    -o "$lib" "${sources[@]}"
  exported="$(nm -D --defined-only "$lib" | awk '$2 == "T" { sub(/@.*/, "", $3); print $3 }')"
  glibc="$(objdump -T "$lib" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
  echo "host-natives: $lib needs $glibc"
else
  lib="$out/lib$library.dylib"
  "${CXX:-clang++}" "${common[@]}" -I "$JAVA_HOME/include/darwin" \
    -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 \
    -Wl,-exported_symbol,_JNI_OnLoad -install_name "@rpath/lib$library.dylib" \
    -o "$lib" "${sources[@]}"
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

cat >"$out/host-natives.properties" <<EOF
version=$version
platform=$platform
graphics-path.commit=$commit
graphics-path.library=$(basename "$lib")
EOF
cat >"$out/NOTICE" <<EOF
lib$library is built from AndroidX's graphics-path native sources, unchanged:
https://github.com/$repository/tree/$commit/$upstream_path

Copyright The Android Open Source Project. Licensed under the Apache License, Version 2.0:
https://www.apache.org/licenses/LICENSE-2.0
EOF

mkdir -p "$build/dist"
archive="$build/dist/host-natives-$platform-$version.tar.gz"
tar -czf "$archive" -C "$out" .
echo "host-natives: packed $archive"
