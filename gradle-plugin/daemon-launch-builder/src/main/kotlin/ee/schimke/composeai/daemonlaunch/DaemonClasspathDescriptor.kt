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
 * **Schema versioning.** Bump [schemaVersion] whenever the field shape changes in a way that could
 * break older readers; consumers gate on it and force a fresh build of the descriptor on mismatch.
 *
 * **Stable field ordering.** All collection fields are `List<>` (never `Set<>`) to preserve
 * insertion order: classpath ordering is load-bearing for the Robolectric sandbox (renderer pinned
 * versions must precede consumer transitive versions), and JVM arg ordering matters for some
 * `--add-opens` / `-D` precedence cases. Producers should hand the builder a `LinkedHashMap` for
 * [systemProperties] when stable iteration matters; kotlinx-serialization's default Map encoder
 * iterates in encounter order.
 */
@Serializable
public data class DaemonClasspathDescriptor(
  /** Bumped on breaking schema changes. See class KDoc. */
  public val schemaVersion: Int,
  /**
   * Module path of the consumer module the daemon will serve, e.g. `:samples:android` for a Gradle
   * project, `//app` for Bazel, `app` for Amper. Per-daemon-per-module — each module gets its own
   * JVM.
   */
  public val modulePath: String,
  /** Build variant the daemon was bootstrapped against, e.g. `debug` / `release` / `desktop`. */
  public val variant: String,
  /**
   * When `false`, consumers read the descriptor (so they know the producer ran) but do NOT spawn
   * the daemon JVM. The remaining fields are still populated honestly so a later flip to `true`
   * doesn't require another build round-trip.
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
   * [mainClass] is loaded ahead of any consumer-graph collisions; everything else (data extensions,
   * Compose runtime, user classes) follows.
   */
  public val classpath: List<String>,
  /**
   * Static JVM flags — `-Xmx`, `--add-opens`, `--add-exports`, etc. Renderer-android needs the
   * Robolectric-on-JDK-17 set (see the Gradle plugin's `AndroidPreviewClasspath.buildJvmArgs` for
   * the canonical list); renderer-desktop typically needs only `-Xmx`.
   */
  public val jvmArgs: List<String>,
  /**
   * `-D` system properties read by the daemon at startup. Includes the `composeai.daemon.*` keys
   * documented in `docs/daemon/CONFIG.md` (e.g. `protocolVersion`, `modulePath`,
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
  /**
   * Stage-2 in-process compile config (see `docs/daemon/COMPILE-IN-PROCESS.md`). When non-null the
   * daemon constructs a `DefaultBtaCompileService` from these fields at startup and
   * `JsonRpcServer.compileSources` dispatches through it. `null` (the default) means the consumer
   * hasn't opted in via `composePreview { daemon { compileInProcess = true } }` and the daemon's
   * `compileSources` handler returns `result=fallback` for every call — the editor falls back to
   * stage 1 (`gradle --continuous`) or stage 0 (one-shot Gradle).
   *
   * Schema-version bumped to 2 when this field landed (the v1 reader in the VS Code extension fails
   * the descriptor on any unknown field, even null-defaulted ones, so adding the field IS a
   * breaking schema change for existing readers).
   */
  public val btaCompile: BtaCompileConfig? = null,
)

/**
 * Stage-2 in-process compile config — see `docs/daemon/COMPILE-IN-PROCESS.md`. Populated by the
 * gradle plugin's `DaemonBootstrapTask` when both the workspace and the build opt in
 * (`composePreview.daemon.compileInProcess` and `composePreview { daemon { compileInProcess = true
 * } }` respectively). The daemon reads these fields into a `DefaultBtaCompileService` once at
 * startup; they don't change across compile calls (Tier-1 dirty recycles the daemon and produces a
 * fresh descriptor).
 */
@Serializable
public data class BtaCompileConfig(
  /**
   * BTA-impl classpath: `kotlin-build-tools-impl` + the matching `kotlin-compiler-embeddable`
   * + `kotlin-daemon-embeddable` + `kotlin-compose-compiler-plugin-embeddable` + transitive
   *   `kotlinx-coroutines-core` / `kotlin-stdlib` / `kotlin-reflect` runtime JARs. These get loaded
   *   into BTA's isolated classloader; the daemon's main classloader never sees them. Version must
   *   match the consumer's `kotlin` version (read from `libs.versions.toml`).
   */
  public val implClasspath: List<String>,
  /**
   * The consumer's compile classpath for this module — same JAR list `compileKotlin` would see,
   * resolved by KGP/AGP at config time. Includes Compose runtime, kotlin-stdlib, AGP- generated R /
   * BuildConfig jars (when present), all transitive dependencies.
   */
  public val compileClasspath: List<String>,
  /**
   * Compiler plugin JARs (e.g. `kotlin-compose-compiler-plugin-embeddable`). Empty list when the
   * consumer doesn't apply Compose. Each entry is loaded into BTA's classloader and its
   * `META-INF/services/...CompilerPluginRegistrar` activated.
   */
  public val compilerPlugins: List<String>,
  /**
   * Where BTA writes `.class` files. Same directory the daemon's child classloader watches —
   * `build/intermediates/built_in_kotlinc/<variant>/classes/` (Android) or
   * `build/classes/kotlin/<variant>/main/` (JVM/CMP).
   */
  public val outputDir: String,
  /**
   * Kotlin `MODULE_NAME` arg. Matches the consumer's Gradle module name so BTA-emitted
   * `kotlin.Metadata.d2[]` agrees with Gradle's output. Stage-2 spike § 4 covers why this is
   * load-bearing for the daemon's child classloader hot-swap.
   */
  public val moduleName: String,
  /**
   * Per-module persistent IC cache directory. Conventionally
   * `<module>/build/compose-previews/daemon-state/bta-ic/`. Survives across daemon spawns; recycled
   * with the daemon on classpath-dirty (Tier 1) which invalidates the IC inputs anyway.
   */
  public val icWorkingDir: String,
  /**
   * Daemon-warm-time decision: non-null means this module is NOT a stage-2 candidate (typically
   * because KSP / KAPT / annotationProcessor is on the classpath, see COMPILE-IN-PROCESS.md §
   * "Eligibility"). `JsonRpcServer.compileSources` returns `result=fallback` with this reason
   * verbatim. `null` means eligible — BTA actually runs.
   */
  public val ineligibilityReason: String? = null,
)

/**
 * Current value of [DaemonClasspathDescriptor.schemaVersion]. Bump on breaking changes.
 *
 * Version history:
 * - **1** — initial schema (B1.2).
 * - **2** — added optional [DaemonClasspathDescriptor.btaCompile] for stage-2 in-process compile
 *   (see `docs/daemon/COMPILE-IN-PROCESS.md`).
 */
public const val DAEMON_DESCRIPTOR_SCHEMA_VERSION: Int = 2
