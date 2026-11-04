#!/usr/bin/env bash
# Builds host-natives recipes for the machine it runs on, and packs each for its own release.
#
#   host-natives/build.sh [recipe...]   # default: every host-natives/*/recipe.properties
#     -> host-natives/build/<recipe>/<platform>/ and
#        host-natives/build/dist/host-natives-<recipe>-<version>-<platform>.tar.gz
#
# A recipe is a directory with `recipe.properties` (the library, the library `version` it is built
# from, and where its sources are), `sources.sha256` (every source file, checked before anything is
# compiled), `NOTICE` (its licence, copied into the tarball) and `build.sh <src> <out> <platform>`,
# which compiles and prints the library's path.
#
# A recipe whose sources are a whole repository (Filament's) sets `fetch=git` instead of listing
# them: the repository is cloned at `commit`, and the clone's HEAD has to be that commit, which is
# what pins its contents. `platforms=` limits a recipe to some platforms (default: all); on the
# others it is skipped.
# Each recipe releases on its own, as `host-natives-<recipe>-<version>` (`-r<revision>` past 1):
# `release-tag` prints that tag, and host-natives.yml publishes only the recipes whose tag is not
# out yet, so nothing is republished while a recipe is unchanged.
#
# Platforms: `linux-x86_64`, and `macos-universal` (arm64 + x86_64 in one library). Linux arm64 is
# not built: Robolectric publishes no Linux arm64 native runtime, so the simulator cannot run there.
#
# Needs a C++17 compiler (`CXX`, default g++ on Linux and clang++ on macOS), curl, and a JDK at
# JAVA_HOME for <jni.h>.
#
#   host-natives/build.sh release-tag <recipe>   # print the recipe's release tag
#   host-natives/build.sh recipes                # list the recipes

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
build="$here/build"

recipes() {
  for f in "$here"/*/recipe.properties; do basename "$(dirname "$f")"; done
}
prop() { sed -n "s/^$2=//p" "$here/$1/recipe.properties"; }
release_tag() {
  local version revision
  version="$(prop "$1" version)"
  revision="$(prop "$1" revision)"
  if [[ -n "$revision" && "$revision" != 1 ]]; then
    echo "host-natives-$1-$version-r$revision"
  else
    echo "host-natives-$1-$version"
  fi
}

case "${1:-}" in
  recipes)
    recipes
    exit 0
    ;;
  release-tag)
    release_tag "$2"
    exit 0
    ;;
esac

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux-x86_64 ;;
  Darwin-*) platform=macos-universal ;;
  *)
    echo "host-natives: no build for $(uname -s) $(uname -m)" >&2
    exit 1
    ;;
esac

: "${JAVA_HOME:?JAVA_HOME must point at a JDK (for jni.h)}"

# No mapfile: macOS ships bash 3.2.
if [[ $# -gt 0 ]]; then
  selected=("$@")
else
  selected=()
  while read -r recipe; do selected+=("$recipe"); done < <(recipes)
fi
rm -rf "$build/dist"
mkdir -p "$build/dist"

for recipe in "${selected[@]}"; do
  platforms="$(prop "$recipe" platforms)"
  if [[ -n "$platforms" && ",$platforms," != *",$platform,"* ]]; then
    echo "host-natives: $recipe is not built for $platform (platforms=$platforms)" >&2
    continue
  fi
  repository="$(prop "$recipe" repository)"
  commit="$(prop "$recipe" commit)"
  upstream_path="$(prop "$recipe" path)"
  version="$(prop "$recipe" version)"
  tag="$(release_tag "$recipe")"
  src="$build/src/$recipe"
  out="$build/$recipe/$platform"
  rm -rf "$src" "$out"
  mkdir -p "$src" "$out"

  if [[ "$(prop "$recipe" fetch)" == git ]]; then
    git -C "$src" init -q
    git -C "$src" fetch -q --depth 1 "https://github.com/$repository" "$commit"
    git -C "$src" checkout -q FETCH_HEAD
    if [[ "$(git -C "$src" rev-parse HEAD)" != "$commit" ]]; then
      echo "host-natives: $repository fetched $(git -C "$src" rev-parse HEAD), not $commit" >&2
      exit 1
    fi
  else
    while read -r _ file; do
      mkdir -p "$src/$(dirname "$file")"
      curl -fsSL --retry 4 -o "$src/$file" \
        "https://raw.githubusercontent.com/$repository/$commit/$upstream_path/$file"
    done <"$here/$recipe/sources.sha256"
    if command -v sha256sum >/dev/null; then
      (cd "$src" && sha256sum --quiet -c "$here/$recipe/sources.sha256")
    else
      (cd "$src" && shasum -a 256 --quiet -c "$here/$recipe/sources.sha256")
    fi
  fi

  lib="$("$here/$recipe/build.sh" "$src" "$out" "$platform")"

  # A Linux library loads only on a glibc at least as new as every symbol version it needs, and a
  # compiler versions symbols as new as the glibc it builds against: built on Ubuntu 26.04 it
  # needed sqrtf@GLIBC_2.43 and loaded nowhere older. host-natives.yml builds Linux in PyPA's
  # manylinux_2_28 image.
  if [[ "$platform" == linux-* ]]; then
    floor="${HOST_NATIVES_GLIBC_FLOOR:-2.28}"
    needs="$(objdump -T "$lib" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -uV | tail -1)"
    if [[ "$(printf '%s\n%s\n' "$needs" "$floor" | sort -V | tail -1)" != "$floor" ]]; then
      echo "host-natives: $lib needs glibc $needs, newer than the $floor floor; build it on an older glibc" >&2
      exit 1
    fi
    echo "host-natives: $lib needs glibc $needs (floor $floor)" >&2
  fi

  cat >"$out/host-natives.properties" <<PROPS
recipe=$recipe
tag=$tag
platform=$platform
library=$(prop "$recipe" library)
artifact=$(prop "$recipe" artifact)
version=$version
file=$(basename "$lib")
commit=$commit
PROPS
  {
    echo "$(basename "$lib") is built from $repository's sources at $commit, unchanged:"
    echo "https://github.com/$repository/tree/$commit/$upstream_path"
    echo
    cat "$here/$recipe/NOTICE"
  } >"$out/NOTICE"

  archive="$build/dist/host-natives-$recipe-$version-$platform.tar.gz"
  tar -czf "$archive" -C "$out" .
  echo "host-natives: packed $archive ($tag)"
done
