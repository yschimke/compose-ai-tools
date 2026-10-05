package ee.schimke.composeai.render.session.subprocess

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DaemonClientFactory
import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * [RenderSessionFactory] singleton for the daemon-subprocess backend. Open a session via
 * `SubprocessRenderSessions.open(config)` or the convenience overloads below.
 *
 * Constructs a [DaemonClientRenderSession] from the daemon launch descriptor at
 * [RenderSessionConfig.descriptorPath] — the heavy lifting (subprocess fork, JSON-RPC handshake)
 * happens before the factory returns, so consumers never see an un-initialized session.
 */
public object SubprocessRenderSessions : RenderSessionFactory {
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  override fun open(config: RenderSessionConfig): RenderSession =
    open(config = config, factory = SubprocessDaemonClientFactory())

  /**
   * Open a session with a custom [DaemonClientFactory] and, optionally, the [FileSystem] the launch
   * descriptor is read through (per-call injection rather than a mutable global, per `:common-io`'s
   * convention).
   */
  public fun open(
    config: RenderSessionConfig,
    factory: DaemonClientFactory,
    fileSystem: FileSystem = SystemFileSystem,
  ): RenderSession {
    val descriptorFile = config.descriptorPath
    val descriptorPath = descriptorFile.path.toPath()
    // Through the injected filesystem so a `FakeFileSystem` path is honoured.
    if (fileSystem.metadataOrNull(descriptorPath)?.isRegularFile != true) {
      throw RenderSessionException(
        "Daemon launch descriptor not found at ${descriptorFile.path}. " +
          "Run `:<modulePath>:composePreviewDaemonStart` to materialise it."
      )
    }
    val descriptor =
      try {
        DaemonLaunchDescriptor.parse(fileSystem.read(descriptorPath) { readUtf8() })
      } catch (e: Exception) {
        throw RenderSessionException(
          "Daemon launch descriptor at ${descriptorFile.path} is unreadable: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }
    checkSchemaVersion(descriptor, descriptorFile.path)
    var effectiveDescriptor =
      if (config.forceEnabled && !descriptor.enabled)
        descriptor.newBuilder().also { it.enabled = true }.build()
      else descriptor
    if (config.systemPropertyOverrides.isNotEmpty()) {
      effectiveDescriptor =
        effectiveDescriptor
          .newBuilder()
          .also {
            it.systemProperties =
              effectiveDescriptor.systemProperties + config.systemPropertyOverrides
          }
          .build()
    }
    val workspaceRoot = config.workspaceRoot
    val canonicalRoot = runCatching {
      workspaceRoot.canonicalFile
    }
      .getOrDefault(workspaceRoot.absoluteFile)
    return spawnAndInitialize(
      descriptor = effectiveDescriptor,
      workspaceName = config.workspaceName,
      canonicalRoot = canonicalRoot,
      initializeTimeout = config.initializeTimeout,
      maxRenderTime = config.maxRenderTime,
      shutdownTimeout = config.shutdownTimeout,
      factory = factory,
    )
  }

  /**
   * Open a session against a self-contained preview bundle, with no Gradle project on disk. The
   * [DaemonLaunchDescriptor] is synthesized here (so its schema stays owned by this module) from
   * the shipped sidecar jars and the bundle's extracted classes and `previews.json`, passed via the
   * same system properties `compose-preview bundle daemon` uses.
   *
   * @param daemonClasspath absolute paths of every jar on the daemon subprocess classpath.
   * @param classesDir directory holding the bundle's extracted `classes/app.jar` contents; also the
   *   default single entry of [userClasspath] when the caller doesn't pass a fuller one.
   * @param previewsJson the bundle's extracted `previews.json` discovery manifest.
   * @param workspaceRoot a scratch directory used as the daemon's working directory + workspace id.
   * @param modulePath informational module label surfaced in diagnostics (defaults to the bundle).
   * @param jvmArgs JVM args for the daemon subprocess. Defaults to the desktop set
   *   (`--enable-native-access=ALL-UNNAMED`); an Android/Robolectric caller passes
   *   `AndroidBundleLaunch().jvmArgs()` (which adds the `--add-opens` set JDK 17+ Robolectric
   *   needs).
   * @param extraSystemProperties merged over the base daemon sysprops (last-wins). An
   *   Android/Robolectric caller passes `AndroidBundleLaunch().robolectricSystemProperties()`.
   * @param userClasspath the child-loaded user classpath (`composeai.daemon.userClassDirs`, a
   *   `File.pathSeparator`-joined list of dirs **and** jars — the same shape the Gradle plugin's
   *   launch emits). Defaults to just [classesDir] (a bundle already carries its deps on
   *   [daemonClasspath]); a playground snippet passes its full compile classpath so the catalog's
   *   library jars reach the render.
   * @param jailCommand optional argv prefix the daemon JVM launches behind — the playground's
   *   per-session sandbox (`bwrap`/`unshare`/`systemd-run`); empty launches the JVM directly.
   * @param hardTtlSeconds optional wall-clock deadline after which the spawner force-kills the JVM.
   *   Set for a sandboxed playground snippet; null for an ordinary bundle daemon.
   */
  public fun openBundleDaemon(
    daemonClasspath: List<String>,
    classesDir: File,
    previewsJson: File,
    workspaceRoot: File,
    modulePath: String = ":bundle",
    initializeTimeout: Duration = 60.seconds,
    jvmArgs: List<String> = listOf("--enable-native-access=ALL-UNNAMED"),
    extraSystemProperties: Map<String, String> = emptyMap(),
    userClasspath: List<String> = listOf(classesDir.absolutePath),
    jailCommand: List<String> = emptyList(),
    hardTtlSeconds: Long? = null,
    factory: DaemonClientFactory = SubprocessDaemonClientFactory(),
  ): RenderSession {
    require(daemonClasspath.isNotEmpty()) { "daemonClasspath must not be empty" }
    val canonicalRoot = runCatching {
      workspaceRoot.canonicalFile
    }
      .getOrDefault(workspaceRoot.absoluteFile)
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = DAEMON_DESCRIPTOR_SCHEMA_VERSION,
          modulePath = modulePath,
          variant = "",
          enabled = true,
          mainClass = DESKTOP_DAEMON_MAIN_CLASS,
          classpath = daemonClasspath,
          jvmArgs = jvmArgs,
          systemProperties =
            mapOf(
              "composeai.daemon.userClassDirs" to userClasspath.joinToString(File.pathSeparator),
              "composeai.daemon.previewsJsonPath" to previewsJson.absolutePath,
              // Gives DaemonMain a dataRoot so file-based data products (figma-svg, semantics, …)
              // register; otherwise data/fetch fails "-32020 kind not advertised". Mirrors
              // ServeBundleDaemon.materialize.
              "composeai.render.outputDir" to File(canonicalRoot, "renders").absolutePath,
            ) + extraSystemProperties,
          workingDirectory = canonicalRoot.absolutePath,
          manifestPath = previewsJson.absolutePath,
        )
        .also {
          it.javaLauncher = null
          it.jailCommand = jailCommand
          it.hardTtlSeconds = hardTtlSeconds
        }
        .build()
    return spawnAndInitialize(
      descriptor = descriptor,
      workspaceName = canonicalRoot.name.ifBlank { "bundle" },
      canonicalRoot = canonicalRoot,
      initializeTimeout = initializeTimeout,
      maxRenderTime = null,
      shutdownTimeout = null,
      factory = factory,
    )
  }

  /**
   * Refuse a descriptor whose schema version is not exactly this module's (#5105). The reader
   * ignores unknown keys, so a newer descriptor would otherwise launch silently against defaults.
   * Same exact-match gate as VS Code's `daemonProcess.ts` and `doctor`; the message names the
   * remedy, regenerate (older) or upgrade the consumer (newer). [openBundleDaemon] builds its own
   * descriptor and needs no gate.
   */
  private fun checkSchemaVersion(descriptor: DaemonLaunchDescriptor, path: String) {
    val found = descriptor.schemaVersion
    if (found == DAEMON_DESCRIPTOR_SCHEMA_VERSION) return
    val remedy =
      if (found < DAEMON_DESCRIPTOR_SCHEMA_VERSION)
        "The descriptor was written by an older compose-preview plugin. Re-run " +
          "`:<modulePath>:composePreviewDaemonStart` with the current plugin to regenerate it."
      else
        "The descriptor was written by a newer compose-preview plugin than this library " +
          "understands. Upgrade the consumer (or pin the plugin to the version that matches " +
          "it) — reading it as v$DAEMON_DESCRIPTOR_SCHEMA_VERSION would launch the daemon " +
          "against defaults for whatever the newer schema added."
    throw RenderSessionException(
      "Daemon launch descriptor at $path declares schemaVersion=$found, but this build of " +
        "render-session-subprocess speaks version $DAEMON_DESCRIPTOR_SCHEMA_VERSION. $remedy"
    )
  }

  /** Shared spawn + JSON-RPC initialize + session-wrap path for [open] and [openBundleDaemon]. */
  private fun spawnAndInitialize(
    descriptor: DaemonLaunchDescriptor,
    workspaceName: String,
    canonicalRoot: File,
    initializeTimeout: Duration,
    maxRenderTime: Duration?,
    shutdownTimeout: Duration?,
    factory: DaemonClientFactory,
  ): RenderSession {
    val spawn =
      try {
        factory.spawn(WorkspaceId.derive(workspaceName, canonicalRoot), descriptor)
      } catch (e: Exception) {
        throw RenderSessionException(
          "Failed to spawn daemon subprocess for ${descriptor.modulePath}: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }

    val fanout = NotificationFanout()
    val client: DaemonClient =
      spawn.client(
        onNotification = { method, params -> fanout.dispatch(method, params) },
        onClose = {},
      )

    val initializeResult: InitializeResult =
      try {
        client.initialize(
          workspaceRoot = canonicalRoot.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          maxRenderMs = maxRenderTime?.inWholeMilliseconds,
          timeout = initializeTimeout,
        )
      } catch (e: Exception) {
        runCatching { spawn.shutdown() }
        throw RenderSessionException(
          "Daemon initialize handshake failed for ${descriptor.modulePath}: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }

    return DaemonClientRenderSession(
      workspaceRoot = canonicalRoot.absolutePath,
      modulePath = descriptor.modulePath,
      initializeResult = initializeResult,
      backendKind = RenderSessionBackend.Subprocess,
      client = client,
      notificationFanout = fanout,
      closeAction = {
        runCatching {
          if (shutdownTimeout == null) spawn.shutdown() else spawn.shutdown(shutdownTimeout)
        }
        fanout.clear()
      },
    )
  }

  /** `ee.schimke.composeai.daemon.DaemonMain` — the desktop daemon entrypoint a bundle spawns. */
  private const val DESKTOP_DAEMON_MAIN_CLASS = "ee.schimke.composeai.daemon.DaemonMain"

  /**
   * Descriptor schema version; duplicated from the plugin's writer (a separate composite build).
   * `checkDaemonLaunchSchema` fails if the two disagree.
   */
  private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

  /** The conventional `<module dir>/build/compose-previews/daemon-launch.json` for [modulePath]. */
  public fun descriptorFile(projectDir: File, modulePath: String): File {
    val moduleDir =
      if (modulePath.isBlank() || modulePath == ":") projectDir
      else File(projectDir, modulePath.trimStart(':').replace(':', '/'))
    return File(moduleDir, "build/compose-previews/daemon-launch.json")
  }
}
