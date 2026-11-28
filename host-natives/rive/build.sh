#!/usr/bin/env bash
# Builds librive-android for the host from rive-android's sources in $1 into $2 for platform $3;
# called by host-natives/build.sh, which checked out the sources at the recipe's commit. Prints the
# library.
#
# Two parts:
#  1. The Rive C++ runtime and its dependencies (rive, the renderer, HarfBuzz, SheenBidi, Yoga,
#     miniaudio, Luau) as static libraries, with Rive's own premake flow (build_rive.sh) and the
#     options rive-android's CMakeLists.txt passes it, plus position-independent code and no LTO.
#     Off Android the renderer reaches OpenGL and EGL through glad, so nothing links against a GL
#     library.
#  2. The JNI layer, rive-android's kotlin/src/main/cpp, compiled for the host and linked against (1).
#     Its sources are rive-android's, unchanged, except:
#       - host/host.patch: OpenJDK's <jni.h> spells AttachCurrentThread and JavaVMAttachArgs
#         differently from Android's (two call sites), and the GPU (Rive) renderer is switched off,
#         so every renderer falls back to Canvas: Rive's own fallback for devices without it;
#       - every `->FindClass(` is rewritten to rive_host_FindClass (host/HostAndroid.cpp), as Rive
#         looks up framework classes from its own threads, which see only the boot class path;
#       - the NDK's headers it uses (<android/log.h>, <android/bitmap.h>, the native window,
#         <android/api-level.h>, <sys/system_properties.h>) and EGL/GLES are host stand-ins
#         (host/include), implemented over the device's Java API in host/HostAndroid.cpp;
#       - printf, puts, putchar, vprintf and stdout are wrapped (--wrap) to stderr, as stdout is the
#         simulator's control channel.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
src="$1"
out="$2"
platform="$3"
jobs="${HOST_NATIVES_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
prop() { sed -n "s/^$1=//p" "$here/recipe.properties"; }

command -v glslangValidator >/dev/null || {
  echo "host-natives: the runtime's Vulkan shaders need glslangValidator (glslang) on PATH" >&2
  exit 1
}
cxx="${CXX:-clang++}"
cc="${CC:-clang}"
jni="$src/kotlin/src/main/cpp"
runtime="$src/submodules/rive-runtime"

# The runtime, at the commit rive-android's gitlink names, which the recipe pins as well.
gitlink="$(git -C "$src" ls-tree HEAD submodules/rive-runtime | awk '{ print $3 }')"
runtime_commit="$(prop runtime_commit)"
if [[ "$gitlink" != "$runtime_commit" ]]; then
  echo "host-natives: rive-android pins rive-runtime $gitlink, the recipe $runtime_commit" >&2
  exit 1
fi
fetch_at() { # <dir> <repository> <commit>
  rm -rf "$1"
  mkdir -p "$1"
  git -C "$1" init -q
  git -C "$1" fetch -q --depth 1 "https://github.com/$2" "$3"
  git -C "$1" checkout -q FETCH_HEAD
  if [[ "$(git -C "$1" rev-parse HEAD)" != "$3" ]]; then
    echo "host-natives: $2 fetched $(git -C "$1" rev-parse HEAD), not $3" >&2
    exit 1
  fi
}
fetch_at "$runtime" rive-app/rive-runtime "$runtime_commit"

# build_rive.sh clones premake and premake-ninja by tag and branch when they are missing; both are
# put in place here first, at pinned commits, so it uses those.
deps="$runtime/build/dependencies"
premake_tag="$(prop premake_tag)"
fetch_at "$deps/premake-core" premake/premake-core "$(prop premake_commit)"
fetch_at "$deps/premake-ninja" rive-app/premake-ninja "$(prop premake_ninja_commit)"
grep -q "RIVE_PREMAKE_TAG:-$premake_tag}" "$runtime/build/build_rive.sh" || {
  echo "host-natives: rive-runtime's build_rive.sh no longer uses premake $premake_tag" >&2
  exit 1
}
(cd "$deps/premake-core" && make -f Bootstrap.mak linux >&2)
mkdir -p "$deps/premake-core/bin/${premake_tag}_release"
cp "$deps/premake-core/bin/release/premake5" "$deps/premake-core/bin/${premake_tag}_release/"

# 1. The runtime. The options are rive-android's CMakeLists.txt's (audio, scripting, Vulkan, text,
# layout and canvas on; Rive's image decoders off, as Android decodes images itself), plus
# --with-pic --no-lto, as everything goes into one shared library linked here.
grep -q -- '--with_rive_text --with_rive_layout --with_rive_canvas' "$jni/CMakeLists.txt" || {
  echo "host-natives: rive-android's CMakeLists.txt changed its runtime options; re-check build.sh" >&2
  exit 1
}
# Rive's premake runs `clang` and `clang++` by name and ignores CFLAGS and CXXFLAGS, which the
# manylinux build needs (--gcc-toolchain, for gcc-toolset's libstdc++): shims ahead on PATH add them.
shims="$out/../rive-compilers"
rm -rf "$shims"
mkdir -p "$shims"
printf '#!/bin/sh\nexec %s %s "$@"\n' "$(command -v "$cc")" "${CFLAGS:-}" >"$shims/clang"
printf '#!/bin/sh\nexec %s %s "$@"\n' "$(command -v "$cxx")" "${CXXFLAGS:-}" >"$shims/clang++"
chmod +x "$shims/clang" "$shims/clang++"
(
  cd "$jni"
  export PATH="$shims:$PATH"
  export RIVE_RUNTIME_DIR="$runtime" RIVE_SOURCE_ROOT="$src" CC=clang CXX=clang++
  export RIVE_PREMAKE_ARGS="--with_rive_text --with_rive_layout --with_rive_canvas"
  bash "$runtime/build/build_rive.sh" --file=premake5_cpp_runtime.lua ninja release \
    --with_vulkan --no-rive-decoders --with_rive_audio=system --with_rive_scripting \
    --with-pic --no-lto -- rive_cpp_runtime >&2
)
static="$jni/out/release"
libraries=(rive rive_pls_renderer rive_harfbuzz rive_sheenbidi rive_yoga miniaudio luau_vm)
archives=()
for library in "${libraries[@]}"; do
  [[ -f "$static/lib$library.a" ]] || { echo "host-natives: no lib$library.a in $static" >&2; exit 1; }
  archives+=("$static/lib$library.a")
done
# The defines the runtime was built with, which the JNI layer has to share (they change the layout
# of the classes both sides use).
defines=()
for define in $(grep -m1 '^CXXFLAGS' "$static/build_rive_pls_renderer.ninja" | grep -o -- '-D[A-Za-z0-9_=]*'); do
  defines+=("$define")
done

# 2. The JNI layer.
git -C "$src" apply --directory=kotlin/src/main/cpp "$here/host/host.patch"
grep -q 's_openGLDisabled = true;' "$jni/src/helpers/worker_ref.cpp" || {
  echo "host-natives: host/host.patch did not apply" >&2
  exit 1
}
mapfile -t finders < <(grep -rl -- '->FindClass(' "$jni/src" "$jni/include" | grep -v '/helpers/jni_resource.cpp$')
[[ ${#finders[@]} -gt 0 ]] || { echo "host-natives: no ->FindClass( to rewrite" >&2; exit 1; }
sed -i -E 's/(env|GetJNIEnv\(\))->FindClass\(/rive_host_FindClass(\1, /g' "${finders[@]}"
# Left: only the fallback inside rive_android::FindClass (and the ClassLoader bootstrap before it),
# which rive_host_FindClass calls.
left="$(grep -rn -- '->FindClass(' "$jni/src" "$jni/include" | grep -v '/helpers/jni_resource.cpp:' || true)"
[[ -z "$left" ]] || { echo "host-natives: ->FindClass( left unrewritten:" >&2; echo "$left" >&2; exit 1; }

vulkan_headers="$(echo "$jni"/dependencies/KhronosGroup_Vulkan-Headers_*)"
vma="$(echo "$jni"/dependencies/GPUOpen-LibrariesAndSDKs_VulkanMemoryAllocator_*)"
includes=(
  -I "$here/host/include"
  -I "$jni/include" -I "$runtime/include" -I "$runtime/renderer/include"
  -I "$runtime/decoders/include" -I "$runtime/renderer/src"
  -I "$runtime/renderer/rive_vk_bootstrap/include" -I "$runtime/renderer/rive_vk_bootstrap/src"
  -I "$runtime/scripting" -I "$static/include"
  -I "$runtime/renderer/glad" -isystem "$runtime/renderer/glad/include"
  -isystem "$vulkan_headers/include" -isystem "$vma/include"
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux"
)
# shellcheck disable=SC2206 # CXXFLAGS is a list of flags.
flags=(
  ${CXXFLAGS:-}
  -std=c++17 -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden
  -fno-exceptions -fno-rtti -Wno-everything
  -include "$here/host/host_compat.h"
  "${defines[@]}" -DYOGA_EXPORT=
)

objects="$out/../rive-android-objects"
rm -rf "$objects"
mkdir -p "$objects"
sources=()
while read -r file; do sources+=("$file"); done < <(find "$jni/src" -name '*.cpp' | sort)
# rive-android's CMakeLists.txt adds Rive's Vulkan bootstrap to the library itself.
for file in "$runtime"/renderer/rive_vk_bootstrap/src/*.cpp; do sources+=("$file"); done
sources+=("$here/host/HostAndroid.cpp")

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

lib="$out/librive-android.so"
wrapped=(stdout printf vprintf puts putchar)
wraps=()
for symbol in "${wrapped[@]}"; do wraps+=("-Wl,--wrap=$symbol"); done
# shellcheck disable=SC2086 # LDFLAGS is a list of flags.
"$cxx" ${LDFLAGS:-} -shared -o "$lib" "${objs[@]}" \
  -Wl,--start-group "${archives[@]}" -Wl,--end-group \
  -Wl,--version-script="$jni/rive-android.map.txt" \
  -Wl,--exclude-libs,ALL -Wl,-z,defs -Wl,-z,noexecstack \
  "${wraps[@]}" \
  -static-libstdc++ -static-libgcc \
  -ldl -lpthread -lm >&2
strip --strip-unneeded "$lib"

# Only glibc: no GL, EGL, Vulkan or audio library is linked (glad, Vulkan and miniaudio load theirs
# at run time, and the GPU renderer never starts). Weak symbols (`w`) are optional.
undefined="$(nm -D --undefined-only "$lib" | awk '$1 == "U" { print $2 }' | grep -v -E '@(GLIBC|GCC)_' || true)"
if [[ -n "$undefined" ]]; then
  echo "host-natives: $lib needs symbols other than glibc's:" >&2
  echo "$undefined" >&2
  exit 1
fi
for symbol in "${wrapped[@]}"; do
  if nm -D --undefined-only "$lib" | awk '{ print $2 }' | grep -q -E "^$symbol(@|$)"; then
    echo "host-natives: $lib still uses $symbol" >&2
    exit 1
  fi
done
# The JNI surface: the entry points the app's classes bind to, which the version script exports.
exports="$(nm -D --defined-only "$lib" | awk '{ print $3 }' | grep -c -E '^Java_app_rive_' || true)"
[[ "$exports" -gt 0 ]] || { echo "host-natives: $lib exports no Java_app_rive_ entry points" >&2; exit 1; }
echo "host-natives: $lib exports $exports JNI entry points" >&2

# Licences of what is linked in, beside the NOTICE host-natives/build.sh writes.
mkdir -p "$out/licenses"
cp "$src/LICENSE" "$out/licenses/rive-android.txt"
cp "$runtime/LICENSE" "$out/licenses/rive-runtime.txt"
for dependency in "$jni"/dependencies/*/; do
  name="$(basename "$dependency")"
  for licence in LICENSE LICENSE.txt LICENSE.md COPYING; do
    if [[ -f "$dependency/$licence" ]]; then
      cp "$dependency/$licence" "$out/licenses/$name.txt"
      break
    fi
  done
done

echo "$lib"
