package ee.schimke.composeai.plugin.daemon

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * `composePreview.daemon { … }` block. See `docs/daemon/CONFIG.md` for field semantics, defaults,
 * and ranges, and `docs/daemon/DESIGN.md` § 9 for the lifecycle policy these knobs feed into.
 *
 * This block is read by [DaemonBootstrapTask] at config time and baked into `daemon-launch.json`.
 * The daemon JVM reads the same values back at startup; a value change requires re-running
 * `composePreviewDaemonStart` (and the existing daemon process exiting via `classpathDirty`-style
 * restart, since heap size and recycle thresholds can't be changed in-flight).
 *
 * The daemon is available by default. Set [disabled] to `true` to turn the build-side path off
 * entirely — the descriptor then carries `enabled: false` and clients (VS Code, MCP) must refuse to
 * spawn the JVM.
 */
abstract class DaemonExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Build-side kill switch. Default: `false` (daemon available).
   *
   * When `false`, `composePreviewDaemonStart` writes a descriptor with `enabled: true` and clients
   * may spawn the daemon JVM. When `true`, the descriptor's `enabled: false` flag is set and
   * clients must NOT spawn — the existing Gradle `renderPreviews` path remains the only way to
   * render previews for this module.
   *
   * Flip via build script (`composePreview { daemon { disabled = true } }`). There is intentionally
   * no Gradle property override — that would key the configuration cache on a value users flip
   * frequently from VS Code. See `docs/daemon/CONFIG.md`.
   */
  val disabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

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
}
