# `:mcp` smoke scripts

Runnable end-to-end demos that exercise `DaemonMcpMain` against live daemons.
Not part of the gradle test surface — they mutate source files (and revert
via `git`) and depend on `./gradlew` being available, so they're opt-in.

## `real_e2e_smoke.py`

Drives a live MCP server JVM against the `:samples:cmp` desktop daemon to
demonstrate the full edit → notify → re-render → revert flow:

1. `initialize` + `register_project` + `watch :samples:cmp`.
2. `resources/read` the `RedBoxPreview` URI -> save `before.png`.
3. Edit `Previews.kt` (`Text("Red", …)` -> `Text("Edited", …)`),
   recompile, `notify_file_changed`, `resources/read` -> `after.png`.
4. `git checkout -- Previews.kt` to revert, recompile,
   `notify_file_changed`, `resources/read` -> `revert.png`.

Asserts `before == revert` (byte-equal) and `before != after`.

### Setup

```bash
# 1. Bootstrap descriptors and previews.json for the cmp sample.
./gradlew \
  :samples:cmp:composePreviewDaemonStart \
  :samples:cmp:discoverPreviews \
  -PcomposePreview.experimental.daemon.enabled=true

# 2. Flip the descriptor's `enabled` flag (the gradle property override is
#    intentionally not propagated into the descriptor JSON; see
#    DaemonExtension.kt's KDoc + issue #314 follow-up).
sed -i 's/"enabled": false/"enabled": true/' \
  samples/cmp/build/compose-previews/daemon-launch.json

# 3. Build the mcp + daemon-core jars.
./gradlew :mcp:jar :daemon:core:jar

# 4. Build a runtime classpath. Adjust paths to match your gradle cache layout.
VERSION=$(grep -m1 -oP '"\.":\s*"\K[^"]+' .release-please-manifest.json)
NEXT="${VERSION%.*}.$((${VERSION##*.} + 1))"
CP="$(pwd)/mcp/build/libs/mcp-${NEXT}-SNAPSHOT.jar"
CP="$CP:$(pwd)/daemon/core/build/libs/core-${NEXT}-SNAPSHOT.jar"
for jar in \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.21/"*"/kotlin-stdlib-2.3.21.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/"*"/kotlinx-coroutines-core-jvm-1.10.2.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.11.0/"*"/kotlinx-serialization-json-jvm-1.11.0.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.11.0/"*"/kotlinx-serialization-core-jvm-1.11.0.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/io.github.classgraph/classgraph/4.8.184/"*"/classgraph-4.8.184.jar" \
    "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/"*"/annotations-23.0.0.jar"; do
  CP="$CP:$jar"
done
echo "$CP" > /tmp/mcp-cp.txt

# 5. Run the smoke. Renders are written to /tmp/render-{before,after,revert}.png.
python3 mcp/scripts/real_e2e_smoke.py
```

### Caveats

- Mutates `samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt` and
  reverts via `git checkout`. Run only on a clean tree.
- ~30s end-to-end on a warm machine, dominated by two `compileKotlin` runs.
- The `MCP_WORKDIR` env var lets you point the script at a different repo
  checkout; the workspace id is recomputed from the canonical path the same
  way `WorkspaceId.derive` does.
- Wear (Robolectric) version of the same flow is straightforward in principle
  — the MCP server happily spawns the wear daemon — but Robolectric's cold
  boot is ~5–10s, which slows the loop. The `samples:wear:RedBoxPreview`
  equivalent isn't wired into this script; copy + paste and bump the timeouts.
