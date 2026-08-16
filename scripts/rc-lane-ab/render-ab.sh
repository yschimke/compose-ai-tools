#!/usr/bin/env bash
# Render a catalog's `.rc` documents on the view and embedded lanes, score the two against each
# other, and compose the comparison images committed under `renders/rc-embedded-lane-ab/`.
#
# This is the whole recipe in one place, for the same reason
# `scripts/rc-text-metrics/render-strips.sh` is: the Gradle invocation underneath has three ways to
# look like it worked while producing nothing or something stale, and the scoring and composition
# steps are not part of it at all.
#
#   * `rc.embedded.input` must be an **absolute** path. The harness resolves it against the *test*
#     working directory, not the repo root, and an unresolvable path fails an `assumeTrue` — which
#     Gradle reports as a skipped test inside a green build. A relative path prints
#     `BUILD SUCCESSFUL` and writes no PNGs.
#   * `--rerun` (a *task* option, so it follows the task name) is needed because the input arrives
#     as a system property rather than a declared task input. Without it a second run is
#     `UP-TO-DATE` and keeps the previous run's PNGs.
#   * `--tests` is needed because `rc.embedded.input` reaches **every** test in the module.
#     Unfiltered, `RcSemanticsExtractionTest` and `RcFigmaSvgExportTest` treat the first staged
#     document as a catalog capture and fail against it.
#
# Scoring uses pixelmatch with its default anti-aliasing detection, via
# `scripts/design-artifacts/rc-lane-ab-score.mjs` — the same library `rc-compare.mjs` uses.
# Counting raw pixel inequality instead is actively misleading here: a fill that goes from absent
# to present-but-one-LSB-off keeps the same differing-pixel count.
#
# Usage: scripts/rc-lane-ab/render-ab.sh <dir with <id>.rc + manifest.json> [lane-output-dir]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [ $# -lt 1 ]; then
  sed -n '2,24p' "$0" >&2
  exit 2
fi

# Absolute, always — see the note above. `mkdir -p` first so `cd` can resolve it.
input_dir="$(cd "$1" && pwd)"
lanes_dir="${2:-$(mktemp -d -t rc-lane-ab-XXXXXX)}"
mkdir -p "$lanes_dir"
lanes_dir="$(cd "$lanes_dir" && pwd)"

if [ ! -f "$input_dir/manifest.json" ]; then
  echo "error: no manifest.json in $input_dir" >&2
  echo "       The harnesses read <id>.rc plus a manifest of" >&2
  echo "       {id, width, height, density} — what 'rc-compare --stage-embedded' writes." >&2
  exit 1
fi

node "$repo_root/scripts/rc-lane-ab/validate-stage.mjs" inputs "$input_dir"

# Fail before the renders rather than after them. Composition is the last step and takes seconds;
# discovering a missing dependency after minutes of Gradle is a bad trade.
python3 -c 'import PIL' 2>/dev/null || {
  echo "error: this needs Pillow for the comparison images." >&2
  echo "       pip install --user Pillow   (or apt-get install python3-pil)" >&2
  exit 1
}
# pixelmatch and pngjs are declared by the design-artifacts driver, which is where the repo's other
# rc-compare tooling already gets them.
if [ ! -d "$repo_root/scripts/design-artifacts/node_modules/pixelmatch" ]; then
  echo "==> npm install (scripts/design-artifacts, for pixelmatch)"
  (cd "$repo_root/scripts/design-artifacts" && npm install --no-audit --no-fund --silent)
fi

# Reusing a lane directory is supported, so the previous run's output has to go before this one
# starts — a harness that skips would otherwise leave stale PNGs that score as a clean result.
rm -rf "$lanes_dir/view" "$lanes_dir/embedded"

echo "==> rendering both lanes"
./gradlew --quiet :third-party-rc-embedded-player:testDebugUnitTest --rerun \
  --tests '*RcViewPlayerRenderHarness*' --tests '*RcEmbeddedRenderHarness*' \
  "-Prc.embedded.input=$input_dir" \
  "-Prc.view.output=$lanes_dir/view" \
  "-Prc.embedded.output=$lanes_dir/embedded"

# The manifest is the source of truth, not whatever `.rc` files happen to remain in a reused
# directory. Every manifest id must have exactly one input and one result in each lane. Embedded
# failures are explicit `.error` results; a view error is terminal because the scorer enumerates
# view PNGs and would otherwise silently drop that document from the A/B.
node "$repo_root/scripts/rc-lane-ab/validate-stage.mjs" results "$input_dir" "$lanes_dir"

echo "==> scoring"
node "$repo_root/scripts/design-artifacts/rc-lane-ab-score.mjs" \
  "$lanes_dir/view" "$lanes_dir/embedded"

echo "==> comparison images"
python3 "$repo_root/scripts/rc-lane-ab/compose_ab.py" \
  "$lanes_dir" "$repo_root/renders/rc-embedded-lane-ab"

echo "done. lane renders kept in $lanes_dir"
