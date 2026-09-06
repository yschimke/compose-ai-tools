#!/usr/bin/env bash
# Does this release actually need a Maven Central publish?
#
# `release.yml`'s `publish-gradle-plugin` runs a root-level `publishAndReleaseToMavenCentral`,
# which fans out to every subproject applying `composeai.maven-publishing` — 94 modules. It used
# to do that on every release, whether or not any of them changed; this script is what that job's
# `if:` now consults. Measured over v1.57.0..v1.84.0 (38
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
# change the bytes or the POM of every one of them: `build-logic/`, the wrapper,
# `settings.gradle.kts` and the root `build.gradle.kts`.
#
# A module's committed `gradle.lockfile` lives inside that module, so DEPENDENCY movement is
# caught by the same rule that catches source changes. The version catalog is therefore no longer
# a blanket shared input — only its build-toolchain half is checked separately. See
# `toolchain_fingerprint`.
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
# Which train to answer for. `all` is every published module — the single-train question this
# script has always answered, and still the default. `data` and `core` split the same set in two,
# for the two version lines docs/design/RELEASE_TRAINS.md § 5 costs at -50.7%.
#
# The split is `data/*` against everything else, and it is that lopsided on purpose: 58 of the 94
# modules live under `data/` and they move on a third of the releases, so separating them alone
# buys 29.6 of the 40 percentage points any split has to offer. Costing for the three- and
# five-way alternatives is in § 5; `scripts/release-train-costing.sh` reproduces it.
TRAIN=all
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
    --train)
      TRAIN="${2:?--train needs core|data|all}"
      case "${TRAIN}" in
        core|data|all) ;;
        *) echo "unknown train: ${TRAIN} (expected core, data or all)" >&2; exit 2 ;;
      esac
      shift 2 ;;
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
    'gradle/wrapper' \
    'settings.gradle.kts' \
    'build.gradle.kts'
}

# `gradle/libs.versions.toml` is deliberately NOT in that list any more.
#
# It used to be, and it was the single most expensive rule here: any catalog edit dirtied all 94
# modules, because a path diff cannot tell which of them actually resolve the bumped coordinate.
# Over v1.57.0..v1.84.0 that rule was ~97% of the publishing this guard could not eliminate.
#
# Committed lock state answers that exactly. Each module's `gradle.lockfile` lives INSIDE the
# module directory, which is already watched, so a catalog bump that moves a module's resolved
# classpath shows up as a change to that module and needs no special handling. A bump that moves
# nothing any published module resolves — a test-only dependency, something only the CLI uses —
# leaves every lockfile untouched, and now correctly publishes nothing.
#
# That leaves one hole, which `toolchain_fingerprint` below closes: the BUILD TOOLCHAIN. AGP, the
# Compose compiler and friends are `[plugins]` entries, they change the bytecode we publish, and
# they do not necessarily appear on any module's compile or runtime classpath — so no lockfile
# moves when they do. Measured over the same window, 13 release windows touched the catalog and 1
# of them was a toolchain move: rare, but a wrongly skipped publish is unrepairable, so it is
# checked rather than hoped about.

# The `[plugins]` block plus the `[versions]` entries those plugins reference, for one git ref.
# Anything else in the catalog is a library and is covered by lock state.
#
# Two awk passes rather than one because `[versions]` is declared above `[plugins]`, so the refs
# are not known on first read. Empty output for a ref that has no catalog at all is fine — the
# caller compares two of these and only cares whether they differ.
toolchain_fingerprint() {
  local ref="$1" catalog
  catalog="$(git show "${ref}:gradle/libs.versions.toml" 2>/dev/null)" || return 1
  local refs
  refs="$(printf '%s\n' "${catalog}" | awk '
    /^\[/        { section = $0; next }
    section == "[plugins]" && match($0, /version\.ref[ \t]*=[ \t]*"[^"]+"/) {
      # Strip the key and the quotes off the matched fragment with anchored subs. NOT
      # `gsub(/.*"/, ...)`: `.*` is greedy, so it eats through the closing quote and yields an
      # empty ref — which silently reduced this fingerprint to the [plugins] text alone, so an
      # `agp` or `kotlin` version bump moved nothing and the guard would have skipped a publish
      # it needed. That is the one direction this script must never fail in.
      s = substr($0, RSTART, RLENGTH)
      sub(/^version\.ref[ \t]*=[ \t]*"/, "", s)
      sub(/"$/, "", s)
      print s
    }')"
  {
    printf '%s\n' "${catalog}" | awk '
      /^\[/ { section = $0; next }
      # Comment lines are skipped: a `#` line in TOML is inert, and this repository comments
      # heavily enough that including them would force a 94-module publish for a prose edit.
      section == "[plugins]" && NF && $0 !~ /^[ \t]*#/ { print "plugin " $0 }'
    printf '%s\n' "${catalog}" | awk -v refs="${refs}" '
      BEGIN { n = split(refs, a, "\n"); for (i = 1; i <= n; i++) if (a[i] != "") want[a[i]] = 1 }
      /^\[/ { section = $0; next }
      section == "[versions]" && match($0, /^[ \t]*[A-Za-z0-9_.-]+[ \t]*=/) {
        key = $0; sub(/[ \t]*=.*/, "", key); gsub(/^[ \t]+/, "", key)
        if (key in want) print "version " key " " $0
      }'
  } | sort
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
    sort -u |
    train_filter
}

# Restrict the enumeration to one train. Note what this does NOT touch: `shared_paths` is applied
# to every train, because a change to `build-logic/` or the wrapper can move the bytes of every
# module regardless of which line it versions on. Splitting the trains does not split that rule.
train_filter() {
  case "${TRAIN}" in
    all) cat ;;
    data) grep '^data/' || true ;;
    core) grep -v '^data/' || true ;;
  esac
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

# Dependency movement is now read from committed lock state, so a published module WITHOUT a
# lockfile is a module whose dependencies we cannot see. Its source changes are still caught, but a
# catalog bump that moved only its classpath would look like nothing at all — the one direction
# this script must never fail in. So: any module missing lock state, and the whole thing fails open.
mapfile -t UNLOCKED < <(
  for m in "${MODULES[@]}"; do [ -f "${m}/gradle.lockfile" ] || printf '%s\n' "${m}"; done
)
[ "${#UNLOCKED[@]}" -eq 0 ] ||
  emit true "${#UNLOCKED[@]} published module(s) have no gradle.lockfile (e.g. ${UNLOCKED[0]})"

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

# The build toolchain, which lock state cannot see. See the comment on `toolchain_fingerprint`.
# A failure to read either catalog fails open rather than silently comparing nothing.
tc_baseline="$(toolchain_fingerprint "${BASELINE}")" ||
  emit true "could not read the version catalog at ${BASELINE}"
tc_head="$(toolchain_fingerprint "${HEAD_REF}")" ||
  emit true "could not read the version catalog at ${HEAD_REF}"
if [ "${tc_baseline}" != "${tc_head}" ]; then
  emit true "the build toolchain moved ([plugins], or a version they reference)"
fi

emit false "no published module, shared build input or build-toolchain pin changed since ${BASELINE}"
