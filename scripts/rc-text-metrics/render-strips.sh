#!/usr/bin/env bash
# Regenerate the text-metric fixtures, render them on the three server-side lanes, and compose the
# strips committed under `renders/rc-text-metrics/`.
#
# This is the whole recipe, deliberately in one place. The commands underneath have three separate
# ways to look like they worked while producing nothing or something stale, and every one of them
# has already caught someone out:
#
#   * `rc.embedded.input` must be an **absolute** path. The harness resolves it against the *test*
#     working directory, not the repo root, and a path it cannot resolve fails an `assumeTrue` —
#     which Gradle reports as a skipped test inside a green build. A relative path therefore prints
#     `BUILD SUCCESSFUL` and writes no PNGs at all.
#   * `--rerun` (a *task* option, so it follows the task name) is needed because the input arrives
#     as a system property rather than a declared task input. Without it the second run is
#     `UP-TO-DATE` and silently keeps the previous run's PNGs.
#   * `--tests` is needed because `rc.embedded.input` reaches **every** test in that module.
#     Unfiltered, `RcSemanticsExtractionTest` and `RcFigmaSvgExportTest` pick the first staged
#     document up as if it were a catalog capture and fail against it — *after* the PNGs are
#     written, so the regeneration looks broken when it isn't.
#
# Usage: scripts/rc-text-metrics/render-strips.sh [lane-output-dir]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

lanes_dir="${1:-$(mktemp -d -t rc-text-metrics-XXXXXX)}"
fixtures_dir="$repo_root/rc-player/metrics/build/fixtures"

# Fail before the renders rather than after them. Composition is the last step and takes seconds;
# discovering a missing Pillow after ~2 minutes of Gradle is a bad trade.
python3 -c 'import PIL' 2>/dev/null || {
  echo "error: this needs Pillow for the strip composition." >&2
  echo "       pip install --user Pillow   (or apt-get install python3-pil)" >&2
  exit 1
}

echo "==> fixtures"
rm -rf "$fixtures_dir"
./gradlew --quiet :rc-player-metrics:rcTextMetricFixtures

# Reusing a lane directory is supported, so the previous run's PNGs have to go before this one
# starts. A harness that skips — Skiko natives failing to load is the realistic case — leaves the old
# files in place, and a check that only counts PNGs would accept them and recompose the tracked
# strips from the *previous* run while reporting success.
rm -rf "$lanes_dir/java" "$lanes_dir/cmp-android" "$lanes_dir/cmp-jvm"

echo "==> java + cmp-android lanes"
./gradlew --quiet :third-party-rc-embedded-player:testDebugUnitTest --rerun \
  --tests '*RcViewPlayerRenderHarness*' --tests '*RcEmbeddedRenderHarness*' \
  "-Prc.embedded.input=$fixtures_dir" \
  "-Prc.view.output=$lanes_dir/java" \
  "-Prc.embedded.output=$lanes_dir/cmp-android"

echo "==> cmp-jvm lane"
./gradlew --quiet :third-party-rc-embedded-player-jvm:test --rerun \
  --tests '*RcJvmRenderHarness*' \
  "-Prc.jvm.input=$fixtures_dir" \
  "-Prc.jvm.output=$lanes_dir/cmp-jvm"

# A lane that rendered nothing — or rendered only some of the set — is the failure this script
# exists to make loud, because the composed strip would otherwise come out narrower, or built from
# whatever the last run left behind, and still look like a picture of three lanes. Compare against
# the fixture count rather than against zero, so a partial lane fails too.
expected=$(find "$fixtures_dir" -name '*.rc' | wc -l | tr -d ' ')
for lane in java cmp-android cmp-jvm; do
  count=$(find "$lanes_dir/$lane" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
  errors=$(find "$lanes_dir/$lane" -name '*.error' 2>/dev/null | wc -l | tr -d ' ')
  echo "    $lane: $count/$expected png, $errors error"
  if [ "$count" -ne "$expected" ]; then
    echo "    ^ $lane rendered $count of $expected fixtures." >&2
    [ "$count" -eq 0 ] && echo "      Zero usually means the input path was not absolute." >&2
    exit 1
  fi
done

echo "==> strips"
python3 "$repo_root/scripts/rc-text-metrics/compose_strips.py" \
  "$lanes_dir" "$repo_root/renders/rc-text-metrics"

echo "done. lane renders kept in $lanes_dir"
