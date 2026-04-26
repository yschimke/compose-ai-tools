# Preview daemon — configuration

> **Status:** v1, experimental. Defaults are conservative; only the master switch defaults to "off."

The preview daemon's behaviour is configured through a nested DSL block in the consumer module's `build.gradle.kts`:

```kotlin
composePreview {
  experimental {
    daemon {
      enabled = true
      maxHeapMb = 1024
      maxRendersPerSandbox = 1000
      warmSpare = true
    }
  }
}
```

The block lives under `experimental` because the entire daemon path is opt-in for the v1 release cycle — see [DESIGN.md § 1](DESIGN.md#1-goals--non-goals) on the "may eat your laundry" framing. The `experimental` namespace makes the experimental status visible at every call site.

## Fields

### `enabled: Boolean`

| | |
|-|-|
| **Default** | `false` |
| **Range** | `true` / `false` |
| **Effect** | Master switch. When `false`, `composePreviewDaemonStart` still runs and writes a descriptor with `"enabled": false` so VS Code can sniff that the consumer ran the task — but the extension MUST refuse to spawn the daemon JVM. When `true`, the descriptor's `"enabled": true` flag is set and the VS Code extension may launch the daemon per its own `composePreview.experimental.daemon` setting. |

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

## Gradle properties

There is intentionally NO `-PcomposePreview.experimental.daemon.enabled=...` property override in v1. Gradle property reads at config time key the configuration cache, and the daemon flag is one consumers will flip frequently from VS Code — a property override would force a ~5–10s reconfigure on every toggle. Flip via build script and rely on Gradle's incremental task graph, or use VS Code's own setting (which gates the spawn without re-running `composePreviewDaemonStart`).

## Schema

The descriptor written to `<module>/build/compose-previews/daemon-launch.json` carries the resolved values plus the daemon's spawn parameters (classpath, JVM args, system properties, java launcher path). Schema is versioned via the top-level `schemaVersion` field — VS Code's `daemonProcess.ts` gates on it and forces a re-run on mismatch. See `DaemonClasspathDescriptor` in the Gradle plugin.

The daemon JVM reads the same values back at startup via `composeai.daemon.maxHeapMb` / `composeai.daemon.maxRendersPerSandbox` / `composeai.daemon.warmSpare` system properties, so a value change requires re-running `composePreviewDaemonStart` to refresh the descriptor.
