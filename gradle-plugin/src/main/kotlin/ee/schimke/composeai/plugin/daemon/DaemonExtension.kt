package ee.schimke.composeai.plugin.daemon

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * `composePreview.daemon { … }` block. See `docs/daemon/CONFIG.md` for field semantics, defaults,
 * and ranges, and `docs/daemon/DESIGN.md` § 9 for the lifecycle policy these knobs feed into.
 *
 * This block is read by [DaemonBootstrapTask] (Phase 1, Stream A) at config time and baked into
 * `daemon-launch.json`. The daemon JVM reads the same values back at startup; a value change
 * requires re-running `composePreviewDaemonStart` (and the existing daemon process exiting via
 * `classpathDirty`-style restart, since heap size and recycle thresholds can't be changed
 * in-flight).
 *
 * Defaults are [DESIGN.md § 9]. The daemon is on by default for editor integrations; consumers may
 * disable it temporarily with [enabled].
 */
abstract class DaemonExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Master switch. Default: `true`.
   *
   * When `false`, `composePreviewDaemonStart` still runs and writes a descriptor with `enabled:
   * false` so the VS Code extension can sniff the file and learn the user explicitly opted out —
   * but the extension must NOT spawn the daemon JVM in that case.
   *
   * When `true`, the descriptor's `enabled: true` flag is set and the VS Code extension may launch
   * the daemon per its `composePreview.daemon.enabled` setting.
   *
   * Flip via build script (`composePreview { daemon { enabled = false } }`). The Gradle property
   * override is intentionally NOT wired here — it would key the config cache on a property that VS
   * Code flips frequently. See `CONFIG.md`.
   */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Maximum heap (post-GC) the daemon JVM may use, in MiB. Default: `1024`.
   *
   * Translates to a `-Xmx${maxHeapMb}m` JVM flag in [DaemonBootstrapTask]. The daemon's recycle
   * policy ([DESIGN.md § 9]) treats this as a hard ceiling — a sandbox is recycled when
   * heap-after-GC crosses this value.
   *
   * Range: 256 — system memory. Validation is delegated to the JVM (an unreasonable value fails at
   * JVM start, not at Gradle config).
   */
  val maxHeapMb: Property<Int> = objects.property(Int::class.java).convention(1024)

  /**
   * Hard cap on render count per sandbox before it is recycled, regardless of heap / time drift
   * signals. Default: `1000`.
   *
   * Belt-and-braces against slow leaks the lifecycle measurement misses. Higher values amortise the
   * spare-rebuild cost over more renders; lower values catch leaks earlier at a recycle-frequency
   * cost. See [DESIGN.md § 9].
   */
  val maxRendersPerSandbox: Property<Int> = objects.property(Int::class.java).convention(1000)

  /**
   * Whether the daemon keeps a "warm spare" sandbox in addition to the active one. Default: `true`.
   *
   * Doubles the daemon's idle memory footprint (two Robolectric sandboxes loaded at once) but
   * eliminates the user-visible 3–6s pause on recycle — recycle becomes an atomic swap of the spare
   * into the active slot. See [DESIGN.md § 9 — Warm spare].
   *
   * Set to `false` on memory-constrained machines; the daemon will then pay the recycle pause
   * inline and emit a `daemonWarming` notification while the new sandbox builds.
   */
  val warmSpare: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * D2 — whether the daemon advertises and produces the `a11y/atf` + `a11y/hierarchy` data
   * products. Default: `true`.
   *
   * When `true`, every render runs in a11y mode (`LocalInspectionMode = false`) so ATF can walk the
   * semantics tree and the `AccessibilityDataProductRegistry` can surface findings + the hierarchy
   * via `data/fetch` / `data/subscribe`. Cost is a few ms per render plus the usual a11y-mode side
   * effect (animations don't park under the paused clock — see
   * `RobolectricRenderTest.renderWithA11y`'s docstring).
   *
   * When `false`, the daemon advertises no a11y kinds at handshake time. Clients see an empty
   * `capabilities.dataProducts` and the focus-mode "Show accessibility overlay" toggle in the VS
   * Code panel becomes a no-op (its `data/subscribe` rejects with `DataProductUnknown`). The
   * daemon's internal renderer skips the a11y branch entirely, which is the cheapest mode for users
   * who don't care about a11y in the daemon path. Diagnostic squigglies still flow via the Gradle
   * sidecar reader as today — toggling this off only affects the daemon's data-product surface.
   *
   * Flip via build script (`composePreview { daemon { attachA11y = false } }`) to opt out at the
   * producer side. Pairs with the VS Code-side `composePreview.a11y.alwaysSubscribe` setting (which
   * controls whether the *consumer* side subscribes ambient or only on the focus-mode toggle).
   */
  val attachA11y: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
