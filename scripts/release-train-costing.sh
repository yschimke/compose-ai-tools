#!/usr/bin/env bash
# What would splitting the Maven publish into several version lines actually save?
#
# `maven-publish-needed.sh` decides whether a release publishes ALL 94 modules or none. A train
# split replaces that one decision with several: each train publishes when one of its own modules
# changed, so a release that touched only `data/` republishes 58 modules instead of 94, and one
# that touched neither republishes nothing. This script replays candidate groupings over a range
# of real release tags and prints what each would have cost.
#
# It exists because the grouping is a judgement call with a large, non-obvious answer. The five
# trains originally proposed in docs/design/RELEASE_TRAINS.md § 5 turn out to be the wrong shape:
# almost the entire saving comes from separating `data/` alone, and the last two version lines buy
# under three percentage points between them. That is a conclusion nobody should have to take on
# trust, so it is reproducible here.
#
# ## What it models, and where it is approximate
#
# It mirrors `maven-publish-needed.sh`'s rules: the same module enumeration (via `--print-paths`),
# the same shared build inputs, the same test-source exclusion (copied verbatim, so it covers KMP
# source sets like `src/jvmTest/` too), the same `gradle.properties`
# release-please-block exclusion, and the same "a baseline advances only when a publish happens"
# rule — so consecutive skippable releases coalesce into one span instead of each being compared
# against its immediate predecessor.
#
# Two things it does NOT model, both of which make it OPTIMISTIC on historical ranges:
#
#   * Committed lock state did not exist before #5240, so a dependency bump in a historical window
#     moves no `gradle.lockfile` and reads here as "nothing changed". Going forward the guard sees
#     those; historically it cannot. Windows whose only change was `gradle/libs.versions.toml` are
#     therefore counted as skips when some of them would really publish.
#   * Cross-train propagation. A `core` module depending on a `data` module gets a POM naming the
#     data train's version, so a data-only change does not force a core publish. § 6 measures
#     propagation against the real dependency graph and finds it costs about three percentage
#     points — small, but this script charges nothing for it.
#
# Treat the output as an upper bound on the saving and a sound comparison BETWEEN groupings, which
# is the question it exists to answer.
#
# Usage:
#   release-train-costing.sh [<tag-regex>]     # default: the v1.57.0..v1.84.0 window § 2 uses
set -uo pipefail

cd "$(dirname "$0")/.."

TAG_PATTERN="${1:-^v1\.(5[7-9]|6[0-9]|7[0-9]|8[0-4])\.}"

# Shared build inputs, from `shared_paths()` in maven-publish-needed.sh. A change to one of these
# can alter every module's bytes, so it publishes every train.
SHARED=(build-logic gradle/wrapper settings.gradle.kts build.gradle.kts)

# The grouping under test. Paths, not the dependency graph — see § 5's note on why the two differ
# and why closing that gap is not worth a 94-module reshuffle.
train_of() {
  case "$1" in
    gradle-plugin*|renderers/*|render-host|render-matrix|render-session/*|daemon/*) echo core ;;
    data/*) echo data ;;
    runtimes/*) echo runtimes ;;
    api/*) echo api ;;
    *) echo misc ;;
  esac
}

mapfile -t MODULES < <(
  ./.github/scripts/maven-publish-needed.sh --print-paths |
    grep -v -E '^(build-logic|gradle/wrapper|settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties)$'
)
[ "${#MODULES[@]}" -gt 0 ] || { echo "no modules enumerated" >&2; exit 1; }

mapfile -t TAGS < <(git tag | grep -E "${TAG_PATTERN}" | sort -V)
[ "${#TAGS[@]}" -gt 1 ] || { echo "need at least two tags matching ${TAG_PATTERN}" >&2; exit 1; }

# gradle.properties minus the release-please version block, which moves on EVERY release and would
# otherwise report every window as changed.
props_without_version_block() {
  git show "${1}:gradle.properties" 2>/dev/null |
    sed '/# x-release-please-start-version/,/# x-release-please-end/d'
}

# Did anything a train cares about change between two refs? Test sources are excluded: they are
# compiled by `check`, never by `assemble`, so they reach neither the jar nor the sources jar.
train_changed() {
  local from="$1" to="$2"; shift 2
  local diff
  # Verbatim from maven-publish-needed.sh, and it has to be: a narrower pattern (one that misses
  # KMP source sets like `src/jvmTest/`) makes this script report a publish the guard would skip,
  # which shifts every column at once.
  diff="$(git diff --name-only "${from}" "${to}" -- "${SHARED[@]}" "$@" 2>/dev/null |
    grep -Ev '(^|/)src/[a-zA-Z]*[Tt]est[a-zA-Z]*/')"
  [ -n "${diff}" ] && return 0
  # A shared input, so it publishes every train.
  [ "$(props_without_version_block "${from}")" != "$(props_without_version_block "${to}")" ]
}

WINDOWS=$(( ${#TAGS[@]} - 1 ))
TODAY=$(( WINDOWS * ${#MODULES[@]} ))

# One variant: each argument is a comma-separated set of train names forming one version line.
run_variant() {
  local label="$1"; shift
  local -a groups=("$@")
  local -A pubs prev size
  local -A members   # group -> newline-separated module paths, built once
  local g m tr

  for g in "${groups[@]}"; do pubs[$g]=0; prev[$g]="${TAGS[0]}"; size[$g]=0; members[$g]=""; done
  for m in "${MODULES[@]}"; do
    tr="$(train_of "${m}")"
    for g in "${groups[@]}"; do
      if [[ ",${g}," == *",${tr},"* ]]; then
        size[$g]=$(( size[$g] + 1 )); members[$g]+="${m}"$'\n'; break
      fi
    done
  done

  local t total=0
  for t in "${TAGS[@]:1}"; do
    for g in "${groups[@]}"; do
      # `<<<` on a trailing-newline string yields a final EMPTY element, and an empty pathspec
      # makes `git diff --` match nothing here while erroring into /dev/null — every train would
      # read as unchanged and every grouping would score a perfect -100%.
      local -a mods=(); mapfile -t -d $'\n' mods < <(printf '%s' "${members[$g]}")
      if train_changed "${prev[$g]}" "${t}" "${mods[@]}"; then
        pubs[$g]=$(( pubs[$g] + 1 )); prev[$g]="${t}"
      fi
    done
  done
  for g in "${groups[@]}"; do total=$(( total + pubs[$g] * size[$g] )); done

  printf '| %-38s | %6s | %6s%% |' "${label}" "${total}" \
    "$(awk -v a="${total}" -v b="${TODAY}" 'BEGIN{printf "%.1f", (a-b)*100/b}')"
  for g in "${groups[@]}"; do printf ' %s %s/%s (%sm)' "${g}" "${pubs[$g]}" "${WINDOWS}" "${size[$g]}"; done
  echo
}

echo "range      ${TAGS[0]}..${TAGS[-1]}  (${WINDOWS} windows, ${#MODULES[@]} published modules)"
echo "today      ${TODAY} module-publications"
echo
printf '| %-38s | %6s | %7s | %s\n' "grouping" "m-pubs" "vs today" "per train"
printf '|%s|%s|%s|%s\n' "----------------------------------------" "--------" "---------" "-----------"
run_variant "1 train (no split)"          "core,data,runtimes,api,misc"
run_variant "2 trains: data | rest"       "data" "core,runtimes,api,misc"
run_variant "3 trains: core | data | rest" "core" "data" "runtimes,api,misc"
run_variant "5 trains (§ 5's candidate)"  "core" "data" "runtimes" "api" "misc"
