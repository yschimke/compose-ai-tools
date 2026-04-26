package ee.schimke.composeai.plugin.daemon

import kotlinx.serialization.Serializable

/**
 * Wire format of `build/compose-previews/daemon-launch.json`. Authored by [DaemonBootstrapTask],
 * consumed by the VS Code extension's `daemonProcess.ts` (Stream C, task C1.2). Once VS Code reads
 * this, it has everything it needs to spawn the daemon JVM directly — no further Gradle invocation
 * is required for the lifetime of the descriptor.
 *
 * The classpath, JVM args, and system properties are produced from the same helpers
 * ([ee.schimke.composeai.plugin.AndroidPreviewClasspath]) the existing `renderPreviews` task uses,
 * so the daemon JVM is byte-for-byte equivalent (modulo the daemon-specific entries: the
 * renderer-daemon JAR at the head of the classpath, the `composeai.daemon.*` system properties,
 * etc.).
 *
 * **Schema versioning.** Bump [schemaVersion] whenever the field shape changes in a way that could
 * break older readers; VS Code's `daemonProcess.ts` gates on it and forces a
 * `composePreviewDaemonStart` re-run on mismatch.
 *
 * **Stable field ordering.** All collection fields are `List<>` (never `Set<>`) to preserve
 * insertion order: classpath ordering is load-bearing for the Robolectric sandbox (renderer pinned
 * versions must precede consumer transitive versions — see `AndroidPreviewClasspath` KDoc), and JVM
 * arg ordering matters for some `--add-opens` / `-D` precedence cases. The `LinkedHashMap` returned
 * by `buildSystemProperties` is preserved by feeding it into a `LinkedHashMap` here;
 * kotlinx-serialization's default Map encoder iterates in encounter order.
 */
@Serializable
internal data class DaemonClasspathDescriptor(
  /** Bumped on breaking schema changes. See class KDoc. */
  val schemaVersion: Int,
  /**
   * Gradle path of the consumer module the daemon will serve, e.g. `:samples:android`.
   * Per-daemon-per-module — each module gets its own JVM (DESIGN.md § 4).
   */
  val modulePath: String,
  /** AGP variant the daemon was bootstrapped against, e.g. `debug`. */
  val variant: String,
  /**
   * `enabled` mirror of [DaemonExtension.enabled]. When `false`, VS Code reads the descriptor (so
   * it knows the consumer ran the task) but does NOT spawn the daemon JVM. The remaining fields are
   * still populated honestly so a later flip to `true` doesn't require another Gradle round-trip —
   * just a fresh `composePreviewDaemonStart` to refresh stale paths.
   */
  val enabled: Boolean,
  /**
   * Fully-qualified main class, e.g. `ee.schimke.composeai.daemon.DaemonMain`. Stream B
   * (`renderer-android-daemon`) provides this entry point; until that module exists, the descriptor
   * still encodes the conventional name so the descriptor schema is stable.
   */
  val mainClass: String,
  /**
   * Absolute path to the `java` binary AGP wired into the consumer's unit-test task (i.e. their
   * toolchain `JavaLauncher`'s executable). VS Code execs this directly; no JAVA_HOME inference.
   * `null` only when AGP didn't expose a launcher (rare — every AGP variant ships with a default
   * toolchain), in which case VS Code falls back to its own JDK detection.
   */
  val javaLauncher: String?,
  /**
   * Resolved test-runtime classpath, in load order. Daemon module's classes lead, so [mainClass] is
   * loaded ahead of any consumer-graph collisions. Then everything
   * [ee.schimke.composeai.plugin.AndroidPreviewClasspath.buildTestClasspath] produces, then AGP's
   * unit-test task classpath additions (R.jar etc.) at the tail.
   */
  val classpath: List<String>,
  /**
   * Static JVM open flags + heap settings. Composed from
   * [ee.schimke.composeai.plugin.AndroidPreviewClasspath.buildJvmArgs] plus a `-Xmx${maxHeapMb}m`
   * from [DaemonExtension.maxHeapMb].
   */
  val jvmArgs: List<String>,
  /**
   * `-D` system properties. Built from
   * [ee.schimke.composeai.plugin.AndroidPreviewClasspath.buildSystemProperties] with the same
   * path-bearing values the renderPreviews task uses, plus daemon-specific keys for
   * [DaemonExtension] config that the daemon needs to honour at startup.
   */
  val systemProperties: Map<String, String>,
  /**
   * Working directory for the JVM. Set to the consumer module's project directory so relative paths
   * in `composeai.*` system properties (which are absolute today, but defence-in-depth) resolve
   * identically to the Gradle path.
   */
  val workingDirectory: String,
  /**
   * Absolute path to `previews.json`. The daemon reads this on startup to seed its in-memory
   * preview index; subsequent updates arrive via `discoveryUpdated` notifications (see
   * PROTOCOL.md).
   */
  val manifestPath: String,
)

/** Current value of [DaemonClasspathDescriptor.schemaVersion]. Bump on breaking changes. */
internal const val DAEMON_DESCRIPTOR_SCHEMA_VERSION: Int = 1
