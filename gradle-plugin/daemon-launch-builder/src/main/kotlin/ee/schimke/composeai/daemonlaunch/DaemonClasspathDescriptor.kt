package ee.schimke.composeai.daemonlaunch

import kotlinx.serialization.Serializable

/**
 * Wire format of `build/compose-previews/daemon-launch.json`, written by the Gradle plugin's
 * `DaemonBootstrapTask` or a non-Gradle equivalent ([DaemonLaunchBuilder] /
 * [DaemonLaunchBuilderCli]), and read by VS Code's `daemonProcess.ts` and
 * `SubprocessRenderSessions.open(...)`. It contains everything needed to spawn the daemon JVM
 * without another build invocation.
 *
 * Bump [schemaVersion] on any change that could break older readers; consumers gate on it.
 * Collections are `List`s because classpath and JVM-arg order are load-bearing; pass a
 * `LinkedHashMap` for [systemProperties] when order matters.
 */
@Serializable
public data class DaemonClasspathDescriptor(
  /** Bumped on breaking schema changes. See class KDoc. */
  public val schemaVersion: Int,
  /** Module the daemon serves (`:samples:android`, `//app`, `app`); one daemon JVM per module. */
  public val modulePath: String,
  /** Build variant the daemon was bootstrapped against, e.g. `debug` / `release` / `desktop`. */
  public val variant: String,
  /**
   * When `false`, consumers read the descriptor but don't spawn the daemon; other fields are still
   * populated so enabling needs no rebuild.
   */
  public val enabled: Boolean,
  /** Fully-qualified daemon entry point class, e.g. `ee.schimke.composeai.daemon.DaemonMain`. */
  public val mainClass: String,
  /** Absolute `java` binary to exec; `null` uses the consumer process's own JDK. */
  public val javaLauncher: String?,
  /**
   * Daemon classpath in load order; the daemon module's jar leads so [mainClass] wins collisions.
   */
  public val classpath: List<String>,
  /**
   * Static JVM flags; Android needs the Robolectric-on-JDK-17 opens (see
   * `AndroidPreviewClasspath.buildJvmArgs`), desktop typically only `-Xmx`.
   */
  public val jvmArgs: List<String>,
  /**
   * `-D` properties read at startup, including the `composeai.daemon.*` keys in
   * `docs/daemon/CONFIG.md`.
   */
  public val systemProperties: Map<String, String>,
  /** Working directory for the JVM. Conventionally the consumer module's project directory. */
  public val workingDirectory: String,
  /** `previews.json`, read at startup; later updates arrive via `discoveryUpdated`. */
  public val manifestPath: String,
  /**
   * Stage-2 in-process compile config; when null `compileSources` returns `result=fallback` and the
   * editor uses Gradle. Adding it bumped the schema to 2, since the v1 VS Code reader rejects
   * unknown fields.
   */
  public val btaCompile: BtaCompileConfig? = null,
)

/**
 * Stage-2 in-process compile inputs, populated when the variant wiring resolved them. BTA's
 * classloader loads lazily on the first `compileSources`, itself gated by the
 * `composePreview.daemon.compileInProcess` VS Code setting, so a non-null block costs nothing
 * otherwise.
 */
@Serializable
public data class BtaCompileConfig(
  /**
   * BTA impl classpath (`kotlin-build-tools-impl`, compiler / daemon / Compose plugin embeddables
   * and runtime deps), loaded into BTA's isolated classloader. Must match the consumer's Kotlin
   * version.
   */
  public val implClasspath: List<String>,
  /** The module's compile classpath, as `compileKotlin` sees it. */
  public val compileClasspath: List<String>,
  /** Compiler plugin JARs (e.g. the Compose plugin); empty without Compose. */
  public val compilerPlugins: List<String>,
  /** Where BTA writes classes: the directory the daemon's child classloader watches. */
  public val outputDir: String,
  /**
   * Kotlin `MODULE_NAME`, matching Gradle's so `kotlin.Metadata.d2[]` agrees for hot-swap diffs.
   */
  public val moduleName: String,
  /** Persistent IC cache dir (conventionally `build/compose-previews/daemon-state/bta-ic/`). */
  public val icWorkingDir: String,
  /**
   * Non-null when the module isn't stage-2 eligible (e.g. KSP / KAPT); returned verbatim as the
   * fallback reason.
   */
  public val ineligibilityReason: String? = null,
)

/**
 * Current [DaemonClasspathDescriptor.schemaVersion]:
 * - **1** — initial schema.
 * - **2** — added optional [DaemonClasspathDescriptor.btaCompile].
 */
public const val DAEMON_DESCRIPTOR_SCHEMA_VERSION: Int = 2
