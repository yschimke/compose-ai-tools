#!/usr/bin/env bash
# Builds filament-jni for the host from Filament's sources in $1 into $2 for platform $3; called by
# host-natives/build.sh, which checked out the sources at the recipe's commit. Prints the library.
#
# Two parts:
#  1. Filament itself, with CMake, as a headless renderer: the OpenGL backend on EGL
#     (PlatformEGLHeadless), no X11, Wayland or Vulkan, no samples or tests. At run time it needs
#     only libEGL.so.1 and libGL.so.1 (glvnd, with Mesa's drivers or a GPU vendor's).
#  2. The JNI layer from android/filament-android, compiled for the host and linked against (1).
#     Its sources are Filament's, unchanged, except:
#       - nativewindow/Android.cpp is replaced by host/HostSurface.cpp: a Surface gets a headless
#         swap chain whose frames are read back and drawn onto the Surface (see that file);
#       - TextureHelper.cpp, all Android-only, is replaced by host/HostTextureHelper.cpp;
#       - five entry points are renamed in their compiled objects (objcopy --redefine-sym, to
#         filament_upstream_<name>) so HostSurface.cpp can wrap them;
#       - common/CallbackUtils.{h,cpp}: their `#ifdef __ANDROID__` (posting callbacks to an
#         android.os.Handler, plain JNI) is widened to the host, where apps pass Handlers too.
#
# Linux only for now: Filament's macOS headless path is Metal or CGL, not EGL.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
src="$1"
out="$2"
platform="$3"
jobs="${HOST_NATIVES_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

if [[ "$platform" != linux-* ]]; then
  echo "host-natives: filament is built for Linux only" >&2
  exit 1
fi

cxx="${CXX:-clang++}"
cc="${CC:-clang}"
build="$out/../filament-cmake"

# 1. Filament.
# USE_STATIC_LIBCXX=OFF: Filament's Linux default links LLVM's libc++, which manylinux_2_28 does
# not package; libstdc++ is linked statically into filament-jni below instead. Filament annotates
# its mutexes for clang's thread-safety analysis, which only libc++'s std::mutex supports: with
# libstdc++ the annotations are errors under Filament's -Werror. -Wno-error (not -Wno-...) because
# backend/ turns -Wthread-safety back on after these flags; the analysis checks nothing at run time.
cxxflags="${CXXFLAGS:-} -Wno-thread-safety-attributes -Wno-thread-safety-analysis"
cxxflags+=" -Wno-error=thread-safety"
cmake -G Ninja -S "$src" -B "$build" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS="$cxxflags" -DCMAKE_C_FLAGS="${CFLAGS:-}" \
  -DCMAKE_C_COMPILER="$cc" -DCMAKE_CXX_COMPILER="$cxx" \
  -DUSE_STATIC_LIBCXX=OFF \
  -DFILAMENT_SUPPORTS_EGL_ON_LINUX=ON \
  -DFILAMENT_SUPPORTS_XCB=OFF -DFILAMENT_SUPPORTS_XLIB=OFF -DFILAMENT_SUPPORTS_WAYLAND=OFF \
  -DFILAMENT_SUPPORTS_VULKAN=OFF \
  -DFILAMENT_SKIP_SAMPLES=ON -DFILAMENT_SKIP_SDL2=ON -DFILAMENT_BUILD_TESTING=OFF \
  -DFILAMENT_ENABLE_MATDBG=OFF -DFILAMENT_ENABLE_FGVIEWER=OFF >&2
libraries=(
  filament-generatePrefilterMipmap filament backend filaflat filabridge ibl-lite geometry
  utils zstd bluegl meshoptimizer mikktspace
)
ninja -C "$build" -j "$jobs" "${libraries[@]}" >&2
archives=()
for library in "${libraries[@]}"; do
  archive="$(find "$build" -name "lib$library.a" -print -quit)"
  [[ -n "$archive" ]] || { echo "host-natives: no lib$library.a in $build" >&2; exit 1; }
  archives+=("$archive")
done

# 2. The JNI layer.
jni="$src/android/filament-android/src/main/cpp"
common="$src/android/common"
sed -i 's/#ifdef __ANDROID__/#if defined(__ANDROID__) || defined(FILAMENT_HOST_JNI)/' \
  "$common/CallbackUtils.h" "$common/CallbackUtils.cpp"

includes=(
  -I "$src/android" -I "$jni"
  -I "$src/filament/include" -I "$src/filament/backend/include"
  -I "$src/libs/utils/include" -I "$src/libs/math/include" -I "$src/libs/filabridge/include"
  -I "$src/libs/ibl/include" -I "$src/libs/geometry/include" -I "$src/libs/image/include"
  -I "$src/third_party/robin-map" -I "$src/libs/bluegl/include"
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux"
)
# shellcheck disable=SC2206 # CXXFLAGS is a list of flags.
flags=(
  $cxxflags
  -std=c++20 -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden
  -DFILAMENT_HOST_JNI=1 -DFILAMENT_SUPPORTS_VULKAN=0 -DNDEBUG
)
# The entry points HostSurface.cpp wraps, by the object that defines them.
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

objects="$out/../filament-jni-objects"
mkdir -p "$objects"
sources=()
for file in "$jni"/*.cpp; do
  [[ "$(basename "$file")" == TextureHelper.cpp ]] && continue
  sources+=("$file")
done
sources+=("$common/CallbackUtils.cpp" "$common/NioUtils.cpp" "$common/JniUtils.cpp")
sources+=("$here/host/HostSurface.cpp" "$here/host/HostTextureHelper.cpp")

pids=()
objs=()
for file in "${sources[@]}"; do
  obj="$objects/$(basename "$(dirname "$file")")-$(basename "$file" .cpp).o"
  objs+=("$obj")
  "$cxx" "${flags[@]}" "${includes[@]}" -c "$file" -o "$obj" >&2 &
  pids+=($!)
  if (( ${#pids[@]} >= jobs )); then
    wait "${pids[0]}"
    pids=("${pids[@]:1}")
  fi
done
for pid in "${pids[@]}"; do wait "$pid"; done

for file in "${sources[@]}"; do
  obj="$objects/$(basename "$(dirname "$file")")-$(basename "$file" .cpp).o"
  while read -r name; do
    [[ -n "$name" ]] || continue
    short="${name#Java_com_google_android_filament_*_}"
    [[ "$name" == JNI_OnLoad ]] && short=JNI_OnLoad
    objcopy --redefine-sym "$name=filament_upstream_$short" "$obj"
  done < <(renames "$(basename "$file")")
done

lib="$out/libfilament-jni.so"
# shellcheck disable=SC2086 # LDFLAGS is a list of flags.
"$cxx" ${LDFLAGS:-} -shared -o "$lib" "${objs[@]}" \
  -Wl,--start-group "${archives[@]}" -Wl,--end-group \
  -Wl,--version-script="$src/android/filament-android/libfilament-jni.map" \
  -Wl,--exclude-libs,ALL -Wl,-z,defs \
  -static-libstdc++ -static-libgcc \
  -lEGL -ldl -lpthread -lm >&2

# Strong undefined symbols only (`U`): weak ones (`w`), such as __gmon_start__ and the
# _ITM_*TMCloneTable hooks every shared library's startup code names, are optional.
undefined="$(nm -D --undefined-only "$lib" | awk '$1 == "U" { print $2 }' | grep -v -E '@(GLIBC|GCC)_' || true)"
if [[ "$undefined" != "$(printf '%s\n' "$undefined" | grep -E '^egl' || true)" ]]; then
  echo "host-natives: $lib needs symbols other than glibc's and EGL's:" >&2
  echo "$undefined" | grep -v -E '^egl' >&2
  exit 1
fi

# Licences of what is linked in, beside the NOTICE host-natives/build.sh writes.
mkdir -p "$out/licenses"
cp "$src/LICENSE" "$out/licenses/filament.txt"
cp "$src/third_party/zstd/LICENSE" "$out/licenses/zstd.txt"
cp "$src/third_party/robin-map/LICENSE" "$out/licenses/robin-map.txt"
cp "$src/third_party/meshoptimizer/LICENSE" "$out/licenses/meshoptimizer.txt"
sed -n '/Copyright/,/\*\//p' "$src/third_party/mikktspace/src/mikktspace.c" >"$out/licenses/mikktspace.txt"

echo "$lib"
