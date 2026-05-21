package ee.schimke.composeai.daemonlaunch

import kotlinx.serialization.Serializable

/**
 * Wire format of `build/compose-previews/daemon-launch.json`. Authored by the Gradle plugin's
 * `DaemonBootstrapTask` or by a non-Gradle equivalent (Bazel rule, Amper task — see
 * [DaemonLaunchBuilder] / [DaemonLaunchBuilderCli]). Consumed by the VS Code extension's
 * `daemonProcess.ts` and by `render-session-subprocess`'s `SubprocessRenderSessions.open(...)`.
 * Once a consumer reads this descriptor, it has everything it needs to spawn the daemon JVM
 * directly — no further build-system invocation is required for the lifetime of the descriptor.
 *
 * **Schema versioning.** Bump [schemaVersion] whenever the field shape changes in a way that
 * could break older readers; consumers gate on it and force a fresh build of the descriptor on
 * mismatch.
 *
 * **Stable field ordering.** All collection fields are `List<>` (never `Set<>`) to preserve
 * insertion order: classpath ordering is load-bearing for the Robolectric sandbox (renderer
 * pinned versions must precede consumer transitive versions), and JVM arg ordering matters for
 * some `--add-opens` / `-D` precedence cases. Producers should hand the builder a
 * `LinkedHashMap` for [systemProperties] when stable iteration matters;
 * kotlinx-serialization's default Map encoder iterates in encounter order.
 */
@Serializable
public data class DaemonClasspathDescriptor(
  /** Bumped on breaking schema changes. See class KDoc. */
  public val schemaVersion: Int,
  /**
   * Module path of the consumer module the daemon will serve, e.g. `:samples:android` for a
   * Gradle project, `//app` for Bazel, `app` for Amper. Per-daemon-per-module — each module
   * gets its own JVM.
   */
  public val modulePath: String,
  /** Build variant the daemon was bootstrapped against, e.g. `debug` / `release` / `desktop`. */
  public val variant: String,
  /**
   * When `false`, consumers read the descriptor (so they know the producer ran) but do NOT
   * spawn the daemon JVM. The remaining fields are still populated honestly so a later flip to
   * `true` doesn't require another build round-trip.
   */
  public val enabled: Boolean,
  /** Fully-qualified daemon entry point class, e.g. `ee.schimke.composeai.daemon.DaemonMain`. */
  public val mainClass: String,
  /**
   * Absolute path to the `java` binary the producer resolved (e.g. AGP's unit-test toolchain).
   * Consumers exec this directly; no `JAVA_HOME` inference. `null` falls back to the JDK the
   * consumer's own process is using.
   */
  public val javaLauncher: String?,
  /**
   * Resolved daemon classpath, in load order. The renderer / daemon module's jar should lead so
   * [mainClass] is loaded ahead of any consumer-graph collisions; everything else (data
   * extensions, Compose runtime, user classes) follows.
   */
  public val classpath: List<String>,
  /**
   * Static JVM flags — `-Xmx`, `--add-opens`, `--add-exports`, etc. Renderer-android needs the
   * Robolectric-on-JDK-17 set (see the Gradle plugin's `AndroidPreviewClasspath.buildJvmArgs`
   * for the canonical list); renderer-desktop typically needs only `-Xmx`.
   */
  public val jvmArgs: List<String>,
  /**
   * `-D` system properties read by the daemon at startup. Includes the `composeai.daemon.*`
   * keys documented in `docs/daemon/CONFIG.md` (e.g. `protocolVersion`, `modulePath`,
   * `previewsJsonPath`, `outputDir`, `idleTimeoutMs`).
   */
  public val systemProperties: Map<String, String>,
  /** Working directory for the JVM. Conventionally the consumer module's project directory. */
  public val workingDirectory: String,
  /**
   * Absolute path to `previews.json`. The daemon reads this on startup to seed its in-memory
   * preview index; subsequent updates arrive via `discoveryUpdated` notifications.
   */
  public val manifestPath: String,
)

/** Current value of [DaemonClasspathDescriptor.schemaVersion]. Bump on breaking changes. */
public const val DAEMON_DESCRIPTOR_SCHEMA_VERSION: Int = 1
