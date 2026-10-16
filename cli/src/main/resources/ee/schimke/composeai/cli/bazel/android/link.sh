#!/bin/sh
# Link compiled Android resources into a standalone resource APK and final-ID R classes.
#
# Shared by the Android aspect (one link per target) and the compose-preview CLI (one link over the
# union of a selection's targets, so several libraries render from one APK). Both call it with the
# same arguments, which the aspect also records in its metadata, so the two cannot drift.
#
# Usage: link.sh AAPT2 JAVA_HOME OUT_APK OUT_R_JAR MANIFEST ANDROID_JAR EXTRA_PACKAGES ASSETS ARCHIVE...
#   EXTRA_PACKAGES  colon-separated R packages besides the manifest's own (may be empty)
#   ASSETS          a file of "<source>\t<path under assets/>" lines (may be empty); a source that
#                   is a directory merges its contents at that path; later lines win
#   ARCHIVE...      compiled resource archives in overlay order, every dependency first
set -eu
aapt2="$1"; jdk="$2"; apk="$3"; rjar="$4"; manifest="$5"; android_jar="$6"; extra="$7"; assets="$8"
shift 8
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
overlays=""
for zip in "$@"; do overlays="$overlays -R $zip"; done
extra_flag=""
if [ -n "$extra" ]; then extra_flag="--extra-packages $extra"; fi
assets_flag=""
if [ -s "$assets" ]; then
  tab="$(printf '\t')"
  while IFS="$tab" read -r src path; do
    dest="$work/assets/$path"
    if [ -d "$src" ]; then
      # A directory (TreeArtifact) input: merge its contents at its place under assets/.
      mkdir -p "$dest"
      cp -R "$src/." "$dest/"
    else
      mkdir -p "$(dirname "$dest")"
      cp "$src" "$dest"
    fi
  done < "$assets"
  assets_flag="-A $work/assets"
fi
# The SDK's own aapt2 loads the libraries beside it, as rules_android's launcher arranges.
if [ -d "$(dirname "$aapt2")/lib64" ]; then
  LD_LIBRARY_PATH="$(dirname "$aapt2")/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export LD_LIBRARY_PATH
fi
# shellcheck disable=SC2086
"$aapt2" link -o "$apk" --manifest "$manifest" -I "$android_jar" --auto-add-overlay \
  --no-version-vectors --java "$work/gen" $extra_flag $assets_flag $overlays
mkdir "$work/classes"
find "$work/gen" -name '*.java' > "$work/sources"
"$jdk/bin/javac" -nowarn -encoding UTF-8 -d "$work/classes" "@$work/sources"
"$jdk/bin/jar" --create --file "$rjar" --date=1980-01-01T00:00:02Z -C "$work/classes" .
