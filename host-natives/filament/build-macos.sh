#!/usr/bin/env bash
# filament-jni for macOS, arm64 + x86_64 in one library; build.sh runs it for macos-universal.
#
#   build-macos.sh <src> <out>      # prints the library's path
#
# Linux runs Filament's OpenGL backend on headless EGL over Mesa. macOS has no OpenGL ES, and an
# Android app's materials are OpenGL ES only (Filament fixes the shader model when it is built), so
# here Filament runs on ANGLE, OpenGL ES over Metal, built from source by host/build-angle.sh and
# shipped beside the library (libEGL.dylib, libGLESv2.dylib; host-natives.properties `extra`).
#
# Same Filament, JNI layer and host stand-ins as Linux (see build.sh), plus host/macos-egl.patch:
# Filament's headless EGL platform, otherwise Linux-only, on macOS, and OpenGL rather than Metal as
# the default backend. The rest is macOS's tools for the same steps: llvm-objcopy for the renamed
# entry points, an exported-symbols list for the version script, lipo for the two architectures.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
src="$1"
out="$2"
jobs="${HOST_NATIVES_JOBS:-$(sysctl -n hw.ncpu)}"
# At least two, so the compile loop's pid list is never emptied: bash 3.2 (macOS's) calls an empty
# "${a[@]}" unbound under set -u.
((jobs >= 2)) || jobs=2
work="$(cd "$out/.." && pwd)"
min_macos=11.0

angle_commit="$(sed -n 's/^angle_commit=//p' "$here/recipe.properties")"
angle="$work/angle"
"$here/host/build-angle.sh" "$angle" "$angle_commit"

objcopy="$(command -v llvm-objcopy || true)"
if [[ -z "$objcopy" ]]; then
  for prefix in "$(brew --prefix llvm 2>/dev/null || true)" "$(brew --prefix llvm@18 2>/dev/null || true)"; do
    [[ -x "$prefix/bin/llvm-objcopy" ]] && objcopy="$prefix/bin/llvm-objcopy" && break
  done
fi
[[ -n "$objcopy" ]] || { echo "host-natives: llvm-objcopy not found (brew install llvm)" >&2; exit 1; }

# 1. Filament, universal, against ANGLE's EGL and GLES headers.
perl -pi -e 's/set\(MATC_TARGET desktop\)/set(MATC_TARGET all)/' "$src/CMakeLists.txt"
grep -q 'set(MATC_TARGET all)' "$src/CMakeLists.txt" || {
  echo "host-natives: Filament's CMakeLists.txt no longer sets MATC_TARGET desktop" >&2
  exit 1
}
git -C "$src" apply "$here/host/utils-log.patch"
git -C "$src" apply "$here/host/macos-egl.patch"

build="$work/filament-cmake"
cmake -G Ninja -S "$src" -B "$build" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$min_macos" \
  -DCMAKE_C_FLAGS="-I$angle/include" -DCMAKE_CXX_FLAGS="-I$angle/include" \
  -DFILAMENT_SUPPORTS_EGL_ON_LINUX=ON \
  -DFILAMENT_SUPPORTS_METAL=OFF -DFILAMENT_SUPPORTS_VULKAN=OFF \
  -DFILAMENT_SKIP_SAMPLES=ON -DFILAMENT_SKIP_SDL2=ON -DFILAMENT_BUILD_TESTING=OFF \
  -DFILAMENT_ENABLE_MATDBG=OFF -DFILAMENT_ENABLE_FGVIEWER=OFF >&2
libraries=(
  filament-generatePrefilterMipmap filament backend filaflat filabridge ibl-lite geometry
  utils zstd bluegl meshoptimizer mikktspace
)
ninja -C "$build" -j "$jobs" "${libraries[@]}" >&2
archives=()
for library in "${libraries[@]}"; do
  archive="$(find "$build/" -name "lib$library.a" -print -quit)"
  [[ -n "$archive" ]] || { echo "host-natives: no lib$library.a in $build" >&2; exit 1; }
  archives+=("$archive")
done

# 2. The JNI layer, once per architecture.
jni="$src/android/filament-android/src/main/cpp"
common="$src/android/common"
perl -pi -e 's/#ifdef __ANDROID__/#if defined(__ANDROID__) || defined(FILAMENT_HOST_JNI)/' \
  "$common/CallbackUtils.h" "$common/CallbackUtils.cpp"
includes=(
  -I "$src/android" -I "$jni"
  -I "$src/filament/include" -I "$src/filament/backend/include"
  -I "$src/libs/utils/include" -I "$src/libs/math/include" -I "$src/libs/filabridge/include"
  -I "$src/libs/ibl/include" -I "$src/libs/geometry/include" -I "$src/libs/image/include"
  -I "$src/third_party/robin-map" -I "$src/libs/bluegl/include" -I "$angle/include"
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin"
)
sources=()
for file in "$jni"/*.cpp; do
  [[ "$(basename "$file")" == TextureHelper.cpp ]] && continue
  sources+=("$file")
done
sources+=("$common/CallbackUtils.cpp" "$common/NioUtils.cpp" "$common/JniUtils.cpp")
sources+=("$src/filament/backend/src/VirtualMachineEnv.cpp")
sources+=("$here/host/HostSurface.cpp" "$here/host/HostTextureHelper.cpp")

renames() {
  case "$1" in
    Filament.cpp) echo JNI_OnLoad ;;
    Engine.cpp)
      echo Java_com_google_android_filament_Engine_nCreateSwapChain
      echo Java_com_google_android_filament_Engine_nDestroySwapChain
      ;;
    Renderer.cpp)
      echo Java_com_google_android_filament_Renderer_nBeginFrame
      echo Java_com_google_android_filament_Renderer_nEndFrame
      ;;
  esac
}

exports="$work/filament-jni.exports"
printf '%s\n' _JNI_OnLoad '_Java_com_google_android_filament_*' >"$exports"

slices=()
for arch in arm64 x86_64; do
  objects="$work/filament-jni-objects-$arch"
  rm -rf "$objects"
  mkdir -p "$objects"
  objs=()
  pids=()
  for file in "${sources[@]}"; do
    obj="$objects/$(basename "$(dirname "$file")")-$(basename "$file" .cpp).o"
    objs+=("$obj")
    clang++ -arch "$arch" -mmacosx-version-min="$min_macos" \
      -std=c++20 -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden \
      -DFILAMENT_HOST_JNI=1 -DFILAMENT_SUPPORTS_VULKAN=0 -DNDEBUG \
      "${includes[@]}" -c "$file" -o "$obj" >&2 &
    pids+=($!)
    if ((${#pids[@]} >= jobs)); then
      wait "${pids[0]}"
      pids=("${pids[@]:1}") # never empty: jobs >= 2
    fi
  done
  for pid in ${pids[@]+"${pids[@]}"}; do wait "$pid"; done
  for file in "${sources[@]}"; do
    obj="$objects/$(basename "$(dirname "$file")")-$(basename "$file" .cpp).o"
    while read -r name; do
      [[ -n "$name" ]] || continue
      short="${name#Java_com_google_android_filament_*_}"
      [[ "$name" == JNI_OnLoad ]] && short=JNI_OnLoad
      # Mach-O symbols carry a leading underscore.
      "$objcopy" --redefine-sym "_$name=_filament_upstream_$short" "$obj"
    done < <(renames "$(basename "$file")")
  done
  slice="$objects/libfilament-jni.dylib"
  clang++ -arch "$arch" -mmacosx-version-min="$min_macos" -dynamiclib -o "$slice" "${objs[@]}" \
    "${archives[@]}" \
    -L"$angle" -lEGL -lGLESv2 \
    -install_name @rpath/libfilament-jni.dylib -Wl,-rpath,@loader_path \
    -Wl,-exported_symbols_list,"$exports" -Wl,-dead_strip \
    -framework Foundation -framework CoreFoundation >&2
  slices+=("$slice")
done

lib="$out/libfilament-jni.dylib"
lipo -create "${slices[@]}" -output "$lib"
for arch in arm64 x86_64; do
  lipo "$lib" -verify_arch "$arch" || { echo "host-natives: $lib has no $arch" >&2; exit 1; }
done
cp "$angle/libEGL.dylib" "$angle/libGLESv2.dylib" "$out/"
# Tells host-natives/build.sh to record them as the archive's `extra` files.
echo "libEGL.dylib libGLESv2.dylib" >"$out/.host-natives-extra"

mkdir -p "$out/licenses"
cp "$src/LICENSE" "$out/licenses/filament.txt"
cp "$angle/LICENSE" "$out/licenses/angle.txt"
cp "$src/third_party/zstd/LICENSE" "$out/licenses/zstd.txt"
cp "$src/third_party/robin-map/LICENSE" "$out/licenses/robin-map.txt"
cp "$src/third_party/meshoptimizer/LICENSE" "$out/licenses/meshoptimizer.txt"
sed -n '/Copyright/,/\*\//p' "$src/third_party/mikktspace/src/mikktspace.c" >"$out/licenses/mikktspace.txt"

echo "$lib"
