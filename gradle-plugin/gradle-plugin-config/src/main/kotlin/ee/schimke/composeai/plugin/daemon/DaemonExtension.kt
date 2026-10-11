package ee.schimke.composeai.plugin.daemon

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * `composePreview.daemon { … }`. See `docs/daemon/CONFIG.md` for fields and `docs/daemon/DESIGN.md`
 * § 9 for lifecycle policy. Read by [DaemonBootstrapTask] at configuration time into
 * `daemon-launch.json`; changes need a re-run of `composePreviewDaemonStart` and a daemon restart.
 * On by default for editor integrations.
 */
abstract class DaemonExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Master switch, default `true`. When `false` the descriptor is still written with `enabled:
   * false` so VS Code knows the user opted out, but no daemon is spawned. Build-script only: a
   * Gradle property would key the config cache on a frequently toggled value.
   */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Daemon max heap in MiB (default `1024`), passed as `-Xmx`. The recycle policy treats it as a
   * hard ceiling. Validated by the JVM at start.
   */
  val maxHeapMb: Property<Int> = objects.property(Int::class.java).convention(1024)

  /**
   * Renders per sandbox before forced recycle (default `1000`), as a backstop for slow leaks. See
   * [DESIGN.md § 9].
   */
  val maxRendersPerSandbox: Property<Int> = objects.property(Int::class.java).convention(1000)

  /**
   * Keep a warm spare sandbox (default `true`): doubles idle memory but makes recycles an instant
   * swap instead of a 3–6s pause. Disable on memory-constrained machines.
   */
  val warmSpare: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  /**
   * Answer `initialize` once the first sandbox is ready and boot the rest in the background
   * (default `true`). Eager boot would hold `initialize` for the whole pool (~58s for 5 sandboxes
   * per `docs/daemon/SANDBOX-POOL.md`); with this, ~6.5s on CI. Matches serve-spawned daemons.
   *
   * Set `false` when the whole pool must be hot on `initialize`. This is the descriptor default; an
   * in-process `RobolectricHost` stays eager unless `composeai.daemon.backgroundSandboxBoot` is
   * set. See `docs/daemon/SANDBOX-POOL.md` § Background pool boot.
   */
  val backgroundSandboxBoot: Property<Boolean> =
    objects.property(Boolean::class.java).convention(true)
}
