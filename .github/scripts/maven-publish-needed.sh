#!/usr/bin/env bash
# Does this release actually need a Maven Central publish?
#
# `release.yml`'s `publish-gradle-plugin` runs a root-level `publishAndReleaseToMavenCentral`,
# which fans out to every subproject applying `composeai.maven-publishing` — 94 modules — on
# every release, whether or not any of them changed. Measured over v1.57.0..v1.84.0 (38
# releases): 117 module-changes against 3,572 module publications, so 96.7% of what we upload
# is a version-bumped rebuild of identical code. Six of those 38 releases changed no published
# module at all. Maven Central meters file count, release size and release count per
# organisation, so that ratio is the thing to fix. Numbers, method and the wider plan:
# docs/design/RELEASE_TRAINS.md.
#
# This script answers the narrow question — "could any published artifact's bytes differ from
# the last Maven-published release?" — and nothing else. It does not decide policy, it does not
# skip anything by itself, and it is deliberately ALL-OR-NOTHING: either every module publishes
# at this version or none does. Publishing a subset would leave `renderer-desktop:X` naming
# `data-focus-core:X` in its POM with no such artifact on Central, which is a worse failure than
# the one we are fixing.
#
# ## It fails OPEN, always
#
# Every uncertainty — an unresolvable baseline, a shallow clone that cannot reach it, an empty
# module enumeration, a missing worktree — answers `needed=true`. Publishing when we did not
# need to costs upload quota. NOT publishing when we needed to strips a version out from under
# `resolvePluginVersion`, and Central will not let us go back and add it (it refuses to
# overwrite a published version). The asymmetry is total, so the default is total.
#
# ## What counts as a change
#
# Any file under a module that applies `composeai.maven-publishing` (enumerated from the working
# tree, so a newly added module is picked up as its own change), plus the shared inputs that
# change the bytes or the POM of every one of them: `build-logic/`, the version catalog, the
# wrapper, `settings.gradle.kts` and the root `build.gradle.kts`.
#
# `gradle.properties` is watched with ONE exclusion: the `x-release-please-start-version` block.
# Release-please rewrites `composeaiReleasedRuntimeVersion` there on every single release (see
# release-please-config.json's extra-files), so a naive watch on that file would report "changed"
# for every release forever and the guard would never once say no. The rest of the file — JVM
# args, compiler flags, the duplicate-classpath gate — is watched normally.
#
# Usage:
#   maven-publish-needed.sh --baseline <ref> [--head <ref>] [--github-output] [--explain]
#   maven-publish-needed.sh --print-paths      # the watch list, one path per line
#
# Writes `needed=<true|false>` and `reason=<text>` to stdout as `key=value` lines, and to
# $GITHUB_OUTPUT as well when --github-output is passed. Exits 0 whenever it reached an answer,
# including `needed=false`; a non-zero exit means the script itself broke, and a caller that
# cannot tell those apart should treat any failure as `needed=true`.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

BASELINE=""
HEAD_REF="HEAD"
GITHUB_OUTPUT_MODE=0
EXPLAIN=0
PRINT_PATHS=0
# The repository to inspect. Defaults to this checkout; the self-test points it at a fixture so
# the logic can be exercised against histories this repository does not have.
REPO="${SCRIPT_DIR}/../.."

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE="${2:?--baseline needs a ref}"; shift 2 ;;
    --head) HEAD_REF="${2:?--head needs a ref}"; shift 2 ;;
    --github-output) GITHUB_OUTPUT_MODE=1; shift ;;
    --explain) EXPLAIN=1; shift ;;
    --print-paths) PRINT_PATHS=1; shift ;;
    --repo) REPO="${2:?--repo needs a directory}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cd "${REPO}"

# The shared inputs. A change to any of these can change every published artifact, so they are
# watched wholesale rather than reasoned about file by file.
shared_paths() {
  printf '%s\n' \
    'build-logic' \
    'gradle/libs.versions.toml' \
    'gradle/wrapper' \
    'settings.gradle.kts' \
    'build.gradle.kts'
}

# Every module that publishes, derived from the build files themselves rather than a hand-kept
# list — a list would go stale the first time someone adds a module, and go stale silently, in
# the direction that skips a publish.
#
# `build-logic` is dropped: it DEFINES the convention plugin (`id = "composeai.maven-publishing"`
# in its build file) rather than applying it, so it matches the grep without being a published
# module. It is watched anyway as a shared input, so this only keeps the enumeration — and the
# module count it reports — honest.
publishing_module_paths() {
  grep -rl --include=build.gradle.kts 'composeai\.maven-publishing' . 2>/dev/null |
    sed 's|^\./||; s|/build\.gradle\.kts$||' |
    grep -v '^build-logic$' |
    sort -u
}

watch_paths() {
  { shared_paths; publishing_module_paths; } | sort -u
}

if [ "${PRINT_PATHS}" = 1 ]; then
  watch_paths
  exit 0
fi

emit() {
  local needed="$1" reason="$2"
  printf 'needed=%s\n' "${needed}"
  printf 'reason=%s\n' "${reason}"
  if [ "${GITHUB_OUTPUT_MODE}" = 1 ] && [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'needed=%s\n' "${needed}" >> "${GITHUB_OUTPUT}"
    printf 'reason=%s\n' "${reason}" >> "${GITHUB_OUTPUT}"
  fi
  exit 0
}

[ -n "${BASELINE}" ] || emit true "no baseline given"

git rev-parse --verify --quiet "${BASELINE}^{commit}" >/dev/null ||
  emit true "baseline ${BASELINE} is not a commit in this clone (shallow checkout?)"
git rev-parse --verify --quiet "${HEAD_REF}^{commit}" >/dev/null ||
  emit true "head ${HEAD_REF} is not a commit in this clone"

# A shallow clone can hold both refs and still have no common history to diff across.
git merge-base "${BASELINE}" "${HEAD_REF}" >/dev/null 2>&1 ||
  emit true "no common history between ${BASELINE} and ${HEAD_REF} (shallow checkout?)"

# Check the MODULE enumeration, not the combined watch set: the shared paths are literals and
# are always present, so a combined check can never fail. If the enumeration itself breaks — the
# convention plugin gets renamed, the grep stops matching — the watch set silently narrows to the
# shared inputs and the guard starts answering `false` for real module changes. That is the one
# failure mode this script must not have, so it is checked on its own.
mapfile -t MODULES < <(publishing_module_paths)
[ "${#MODULES[@]}" -gt 0 ] || emit true "found no modules applying composeai.maven-publishing"

mapfile -t WATCHED < <(watch_paths)

# `--` and the explicit pathspec list keep this to the watched set; everything else in the diff
# (docs, CI, the CLI, the screen generator) is by definition not a published artifact.
#
# Test sources are then dropped: `src/test`, `src/functionalTest`, `src/androidTest` and their
# neighbours are compiled by `check`, never by `assemble`, so they are in neither the jar nor the
# sources jar that vanniktech builds from `src/main`. A published artifact cannot differ because
# a test changed. This is the one narrowing in the script, and it is safe in the fail-open
# direction: it can only remove files that provably do not ship.
mapfile -t CHANGED < <(
  git diff --name-only "${BASELINE}" "${HEAD_REF}" -- "${WATCHED[@]}" 2>/dev/null |
    grep -Ev '(^|/)src/[a-zA-Z]*[Tt]est[a-zA-Z]*/' || true
)

# `gradle.properties` is not in WATCHED above; it is judged separately so the release-please
# version block can be excluded. Strip the block from both sides and compare what is left.
without_release_block() {
  local ref="$1"
  git show "${ref}:gradle.properties" 2>/dev/null |
    sed '/# x-release-please-start-version/,/# x-release-please-end/d'
}

gradle_properties_changed=0
if ! diff -q <(without_release_block "${BASELINE}") <(without_release_block "${HEAD_REF}") \
  >/dev/null 2>&1; then
  gradle_properties_changed=1
fi

if [ "${EXPLAIN}" = 1 ]; then
  {
    echo "baseline: ${BASELINE} ($(git rev-parse --short "${BASELINE}"))"
    echo "head:     ${HEAD_REF} ($(git rev-parse --short "${HEAD_REF}"))"
    echo "watching: ${#WATCHED[@]} paths"
    if [ "${#CHANGED[@]}" -gt 0 ]; then
      echo "changed files under watched paths:"
      printf '  %s\n' "${CHANGED[@]}"
    else
      echo "changed files under watched paths: none"
    fi
    if [ "${gradle_properties_changed}" = 1 ]; then
      echo "gradle.properties: changed outside the release-please version block"
    else
      echo "gradle.properties: no change outside the release-please version block"
    fi
  } >&2
fi

if [ "${#CHANGED[@]}" -gt 0 ]; then
  emit true "${#CHANGED[@]} file(s) changed under a published module or a shared build input"
fi
if [ "${gradle_properties_changed}" = 1 ]; then
  emit true "gradle.properties changed outside the release-please version block"
fi

emit false "no published module or shared build input changed since ${BASELINE}"
