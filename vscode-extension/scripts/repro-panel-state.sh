#!/usr/bin/env bash
# Gradle-level reproducer for the symptoms reported as
#   - "edits not updating" (Kotlin IC stale, #1493 family)
#   - "weird states with previews" after module switching
#   - "inconsistencies when moving between modules"
#
# Drives the same composePreviewRenderAll pipeline the VS Code extension does,
# but bypasses the VS Code host (network-blocked in this sandbox). Each step
# manipulates source, fires the same task, snapshots previews.json + render
# PNG mtimes, and looks for divergences from the expected post-edit state.

set -euo pipefail

cd /home/user/compose-ai-tools

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH

OUT=/tmp/repro-out
mkdir -p "$OUT"

CMP_FILE=samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt
CMP_RENDER_DIR=samples/cmp/build/compose-previews/renders
CMP_PREVIEWS_JSON=samples/cmp/build/compose-previews/previews.json

snapshot() {
    local label=$1
    {
        echo "===== $label ====="
        echo "-- previews.json ids:"
        if [[ -f $CMP_PREVIEWS_JSON ]]; then
            python3 -c "import json,sys; data=json.load(open('$CMP_PREVIEWS_JSON')); print('\n'.join(sorted(p['id'] for p in data['previews'])))" || true
        else
            echo "  <missing>"
        fi
        echo "-- render PNGs (name + mtime):"
        if [[ -d $CMP_RENDER_DIR ]]; then
            ls -la --time-style=full-iso "$CMP_RENDER_DIR" | awk 'NR>1 {print $9, $6, $7}'
        fi
        echo "-- file hash of $CMP_FILE: $(md5sum "$CMP_FILE" | awk '{print $1}')"
    } > "$OUT/$label.txt"
}

backup() { cp "$CMP_FILE" "$OUT/_backup_$1.kt"; }
restore() { cp "$OUT/_backup_$1.kt" "$CMP_FILE"; }

echo ">>> phase 0: baseline (already primed)"
snapshot "00-baseline"

echo ">>> phase 1: add a brand-new @Preview and re-render"
backup pre_add
TAG="ReproTag$(date +%s)"
cat >> "$CMP_FILE" <<KT

@androidx.compose.desktop.ui.tooling.preview.Preview
@androidx.compose.runtime.Composable
fun ${TAG}() {
    androidx.compose.material.Text(text = "${TAG}")
}
KT

./gradlew :samples:cmp:composePreviewRenderAll 2>&1 | tail -10
snapshot "01-after-add-${TAG}"

echo ">>> phase 2: revert to baseline and re-render"
restore pre_add
./gradlew :samples:cmp:composePreviewRenderAll 2>&1 | tail -10
snapshot "02-after-revert"

echo ">>> phase 3: rename an existing @Preview, re-render, look for stale id"
backup pre_rename
# RedBoxPreview is a known-existing preview function.
sed -i 's/fun RedBoxPreview/fun RedBoxPreviewRenamed/' "$CMP_FILE"
./gradlew :samples:cmp:composePreviewRenderAll 2>&1 | tail -10
snapshot "03-after-rename"

echo ">>> phase 4: revert rename"
restore pre_rename
./gradlew :samples:cmp:composePreviewRenderAll 2>&1 | tail -10
snapshot "04-after-rename-revert"

echo ">>> phase 5: render samples/android, then samples/cmp — module switch"
./gradlew :samples:android:composePreviewRenderAll 2>&1 | tail -8
snapshot "05-after-android-detour"

./gradlew :samples:cmp:composePreviewRenderAll 2>&1 | tail -8
snapshot "06-back-to-cmp"

echo ">>> phase 6: simulate concurrent gradle entry (provokes #1493 family)"
# IMPORTANT: backgrounding inside `( cmd & )` reparents the inner process to
# init and the parent shell's `wait` returns immediately with no children to
# join. The two gradle invocations still race but the script reports "done"
# before either finishes, defeating the snapshot. Use top-level `&` and
# capture the pids so `wait "$pid_a" "$pid_b"` actually blocks until both
# complete.
backup pre_concurrent
echo "// trivial whitespace bump $(date +%N)" >> "$CMP_FILE"
./gradlew :samples:cmp:composePreviewCompile 2>&1 | sed 's/^/[A] /' &
pid_a=$!
sleep 0.5
./gradlew :samples:cmp:composePreviewCompile 2>&1 | sed 's/^/[B] /' &
pid_b=$!
wait "$pid_a" "$pid_b"
restore pre_concurrent
snapshot "07-after-concurrent-compile"

echo ">>> done; outputs under $OUT"
ls -la "$OUT"
