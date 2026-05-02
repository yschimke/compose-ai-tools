# Preview daemon — configuration

> **Status:** stable editor path. Defaults are conservative; the master switch defaults to "on."

The preview daemon's behaviour is configured through a nested DSL block in the consumer module's `build.gradle.kts`:

```kotlin
composePreview {
  daemon {
    enabled = true
    maxHeapMb = 1024
    maxRendersPerSandbox = 1000
    warmSpare = true
  }
}
```

The block used to live under `experimental`; new builds should use the top-level
`composePreview.daemon { ... }` block.

## Fields

### `enabled: Boolean`

| | |
|-|-|
| **Default** | `true` |
| **Range** | `true` / `false` |
| **Effect** | Master switch. When `false`, `composePreviewDaemonStart` still runs and writes a descriptor with `"enabled": false` so VS Code can warn and use the Gradle render path temporarily. When `true`, the descriptor's `"enabled": true` flag is set and the VS Code extension launches the daemon per its own `composePreview.daemon.enabled` setting. |

The flag does NOT control task registration: the task is always registered so the file-presence check on the VS Code side has a stable signal.

### `maxHeapMb: Int`

| | |
|-|-|
| **Default** | `1024` |
| **Range** | `≥ 256`. Validation is delegated to the JVM — an unreasonable value fails at JVM start, not at Gradle config. |
| **Effect** | Maximum heap (post-GC) the daemon JVM may use, in MiB. Translates to a `-Xmx${maxHeapMb}m` JVM flag in `DaemonBootstrapTask`. The daemon's recycle policy (DESIGN.md § 9) treats this as a hard ceiling: a sandbox is recycled when post-GC heap crosses this value. |

Tune this against your project's preview complexity — a module with mostly-static previews can run comfortably at `512`, while one rendering complex `@AnimatedPreview` GIFs may want `1536` or higher to defer recycle pressure.

### `maxRendersPerSandbox: Int`

| | |
|-|-|
| **Default** | `1000` |
| **Range** | `≥ 1` |
| **Effect** | Hard cap on the number of renders a sandbox handles before it is recycled, regardless of the heap / time / class-histogram drift signals. Belt-and-braces against slow leaks the lifecycle measurement misses. See DESIGN.md § 9 — Recycle policy. |

Higher values amortise the spare-rebuild cost over more renders; lower values catch leaks earlier at a higher recycle frequency. The default trades roughly 30 minutes of warm sandbox for one recycle cycle on a heavy-edit day.

### `warmSpare: Boolean`

| | |
|-|-|
| **Default** | `true` |
| **Range** | `true` / `false` |
| **Effect** | Whether the daemon keeps a "warm spare" sandbox in addition to the active one. With a spare, recycle becomes an atomic swap — no user-visible pause. Without a spare, the daemon pays the 3–6s recycle cost inline and emits a `daemonWarming` notification while the new sandbox builds. See DESIGN.md § 9 — Warm spare. |

`true` doubles the daemon's idle memory footprint. Set to `false` on memory-constrained dev machines (< 16GB system RAM, or where multiple modules' daemons run side by side). The recycle pause is still bounded by `maxHeapMb` + `maxRendersPerSandbox` so the worst case stays predictable.

### MCP-only — `replicasPerDaemon`

Configured at the **MCP server**, not the per-module Gradle DSL. Pass `--replicas-per-daemon N`
on the `compose-preview-mcp` CLI (or `-Dcomposeai.mcp.replicasPerDaemon=N`). The supervisor
translates this to `composeai.daemon.sandboxCount = 1 + N` on the daemon launch descriptor — see
[SANDBOX-POOL.md](SANDBOX-POOL.md) for the full picture.

| | |
|-|-|
| **Default** | `3` (4 in-JVM sandboxes per daemon) |
| **Range** | `≥ 0` |
| **Effect** | Number of additional in-JVM Robolectric sandboxes the daemon hosts beyond the primary. Concurrent `renderNow` requests with different ids dispatch across them via `Math.floorMod(id, 1 + N)`. `0` opts out and runs a single sandbox per daemon — bit-identical with the pre-pool path on disk. |

Cheap to raise thanks to the in-JVM pool: extra sandboxes share the JVM baseline + native heap
(loaded once-per-JVM), so the marginal cost per slot is the sandbox classloader's instrumented
bytecode (~75 MB). Pre-Layer-3 this knob spawned N+1 separate JVM subprocesses; ~750 MB per
replica was wasted on duplicated baselines.

Compatible with the gradle plugin's hot-reload path. Each sandbox slot allocates its own child
`URLClassLoader` parented to its own sandbox classloader, so `fileChanged({ kind: "source" })`
broadcasts a `swap()` to every slot's holder — see
[SANDBOX-POOL-FOLLOWUPS.md](SANDBOX-POOL-FOLLOWUPS.md) "#1 Per-slot user-class child loaders" for
the design and the per-slot factory the host accepts in place of the legacy single-instance
holder.

## Gradle properties

There is intentionally NO `-PcomposePreview.daemon.enabled=...` property override. Gradle property reads at config time key the configuration cache, and the daemon flag is one consumers may flip from VS Code — a property override would force a ~5–10s reconfigure on every toggle. Flip via build script and rely on Gradle's incremental task graph, or use VS Code's own setting (which gates the spawn without re-running `composePreviewDaemonStart`).

## Schema

The descriptor written to `<module>/build/compose-previews/daemon-launch.json` carries the resolved values plus the daemon's spawn parameters (classpath, JVM args, system properties, java launcher path). Schema is versioned via the top-level `schemaVersion` field — VS Code's `daemonProcess.ts` gates on it and forces a re-run on mismatch. See `DaemonClasspathDescriptor` in the Gradle plugin.

The daemon JVM reads the same values back at startup via `composeai.daemon.maxHeapMb` / `composeai.daemon.maxRendersPerSandbox` / `composeai.daemon.warmSpare` system properties, so a value change requires re-running `composePreviewDaemonStart` to refresh the descriptor.
