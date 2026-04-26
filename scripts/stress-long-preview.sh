#!/usr/bin/env bash
#
# Stress-runs the `ActivityListLongPreview` Wear render N times and reports
# unique output hashes. Used to reproduce the flaky "ghost peek pill" bug
# investigated around PR #170 — a subset of runs leaks the EdgeButton
# peek-state pill into the stitched output above the fully-revealed
# "Start workout" button.
#
# Any N > 1 distinct SHA-256 groups means the render is non-deterministic,
# and the groups partition the flaky outputs so the bad state can be
# diffed against the good one.
#
# Usage:
#   scripts/stress-long-preview.sh           # default 20 iterations
#   scripts/stress-long-preview.sh 40
#   N=40 scripts/stress-long-preview.sh
#
# Outputs:
#   /tmp/compose-long-stress/run_NN.png      # each iteration's render
#   /tmp/compose-long-stress/hashes.txt      # sha256 per iteration
#   /tmp/compose-long-stress/groups.txt      # unique sha256 groups (count-sorted)
#
# Requires: JDK 17, Android SDK on PATH (for Robolectric), the repo's
# bundled `./gradlew` wrapper.

set -euo pipefail

cd "$(dirname "$0")/.."

ITERATIONS="${1:-${N:-20}}"
OUTDIR="${OUTDIR:-/tmp/compose-long-stress}"
PREVIEW_REL="samples/wear/build/compose-previews/renders/PreviewsKt.ActivityListLongPreview_Devices_-_Large_Round.png"

rm -rf "$OUTDIR"
mkdir -p "$OUTDIR"

echo "[stress] running $ITERATIONS iterations → $OUTDIR"

# First iteration warms up the Robolectric runtime; subsequent iterations
# run with `--rerun-tasks` so the render task is actually re-executed
# rather than pulled from the up-to-date cache.
for i in $(seq 1 "$ITERATIONS"); do
    printf '\r[stress] iteration %d/%d ...' "$i" "$ITERATIONS"
    ./gradlew :samples:wear:renderAllPreviews --rerun-tasks -q \
        --no-configuration-cache >"$OUTDIR/gradle_$i.log" 2>&1 || {
        echo ""
        echo "[stress] gradle failed on iteration $i — log tail:"
        tail -50 "$OUTDIR/gradle_$i.log"
        exit 1
    }
    cp "$PREVIEW_REL" "$OUTDIR/run_$(printf '%02d' "$i").png"
done
echo ""

echo "[stress] hashing outputs"
( cd "$OUTDIR" && sha256sum run_*.png ) > "$OUTDIR/hashes.txt"

echo "[stress] unique groups"
awk '{print $1}' "$OUTDIR/hashes.txt" | sort | uniq -c | sort -rn \
    | tee "$OUTDIR/groups.txt"

GROUPS=$(wc -l < "$OUTDIR/groups.txt")
if [ "$GROUPS" -gt 1 ]; then
    echo ""
    echo "[stress] FLAKY: $GROUPS distinct outputs across $ITERATIONS runs"
    # Show first representative of each group.
    awk '{print $1}' "$OUTDIR/hashes.txt" | sort -u | while read -r h; do
        rep=$(grep "^$h " "$OUTDIR/hashes.txt" | head -1 | awk '{print $2}')
        count=$(grep -c "^$h " "$OUTDIR/hashes.txt")
        echo "  group $h  ×$count  representative: $OUTDIR/$rep"
    done
    exit 2
fi
echo "[stress] DETERMINISTIC: all $ITERATIONS runs produced the same output"
