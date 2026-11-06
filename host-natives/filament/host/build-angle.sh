#!/usr/bin/env bash
# Builds ANGLE (OpenGL ES over Metal) for macOS, arm64 + x86_64, into $1: universal libEGL.dylib and
# libGLESv2.dylib, their headers (include/) and LICENSE. filament-jni links against them on macOS,
# which has no OpenGL ES of its own, and ships them beside itself.
#
#   build-angle.sh <out> <angle commit>
#
# From Google's ANGLE sources at <angle commit> (recipe.properties), the way kivy/angle-builder
# builds them: depot_tools, `gclient sync`, then `gn` + `autoninja` per architecture, `lipo` to
# combine. Nothing prebuilt. Skips the build when <out> already holds this commit's libraries, so a
# CI cache of <out> keyed on the commit saves the hour it takes.
set -euo pipefail

out="$1"
commit="$2"
if [[ -f "$out/.angle-commit" && "$(cat "$out/.angle-commit")" == "$commit" ]]; then
  echo "host-natives: ANGLE $commit already built in $out" >&2
  exit 0
fi

work="$(cd "$(dirname "$out")" && pwd)/angle-src"
mkdir -p "$work"
cd "$work"

# depot_tools (gclient, gn, autoninja), without letting it update itself mid-build.
if [[ ! -d depot_tools ]]; then
  git clone -q --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
"$work/depot_tools/ensure_bootstrap" >&2
export PATH="$work/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0

if [[ ! -d angle/.git ]]; then
  git init -q angle
fi
git -C angle fetch -q --depth 1 https://github.com/google/angle "$commit"
git -C angle checkout -q FETCH_HEAD
if [[ "$(git -C angle rev-parse HEAD)" != "$commit" ]]; then
  echo "host-natives: fetched ANGLE $(git -C angle rev-parse HEAD), not $commit" >&2
  exit 1
fi
(cd angle && python3 scripts/bootstrap.py >&2 && gclient sync --no-history --shallow -j8 >&2)
if ! xcrun metal -v >/dev/null 2>&1; then
  xcodebuild -downloadComponent MetalToolchain >&2
fi

for cpu in arm64 x64; do
  (
    cd angle
    gn gen "out/$cpu" --args="is_component_build=false is_debug=false use_siso=false angle_enable_wgpu=false \
angle_enable_vulkan=false angle_enable_gl=false target_os=\"mac\" target_cpu=\"$cpu\" \
mac_deployment_target=\"11.0\"" >&2
    autoninja -C "out/$cpu" libEGL libGLESv2 >&2
  )
done

rm -rf "$out"
mkdir -p "$out"
for lib in libEGL libGLESv2; do
  lipo -create "angle/out/arm64/$lib.dylib" "angle/out/x64/$lib.dylib" -output "$out/$lib.dylib"
  # Found beside filament-jni (its @rpath is @loader_path), and libEGL finds libGLESv2 the same way.
  install_name_tool -id "@rpath/$lib.dylib" "$out/$lib.dylib"
done
otool -L "$out/libEGL.dylib" | awk 'NR > 1 && /libGLESv2/ { print $1 }' | while read -r old; do
  install_name_tool -change "$old" "@loader_path/libGLESv2.dylib" "$out/libEGL.dylib"
done
# install_name_tool invalidates the ad-hoc signature arm64 requires; sign again.
codesign --force --sign - "$out/libEGL.dylib" "$out/libGLESv2.dylib"
cp -R angle/include "$out/include"
cp angle/LICENSE "$out/LICENSE"
echo "$commit" >"$out/.angle-commit"
echo "host-natives: ANGLE $commit built into $out" >&2
