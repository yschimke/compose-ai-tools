package ee.schimke.composeai.render.session.subprocess

import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DaemonClientFactory
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import ee.schimke.composeai.mcp.RegisteredProject
import ee.schimke.composeai.mcp.SubprocessDaemonClientFactory
import ee.schimke.composeai.mcp.WorkspaceId
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
object SubprocessRenderSessions : RenderSessionFactory {
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  var fileSystem: FileSystem = SystemFileSystem

  override fun open(config: RenderSessionConfig): RenderSession =
    open(config = config, factory = SubprocessDaemonClientFactory())

  /**
   * Open a session, injecting a custom [DaemonClientFactory]. Test scaffolding pairs an in-memory
   * factory with a fake daemon; production callers stick with the default factory in [open].
   */
  fun open(config: RenderSessionConfig, factory: DaemonClientFactory): RenderSession {
    val descriptorFile = config.descriptorPath
    if (!descriptorFile.isFile) {
      throw RenderSessionException(
        "Daemon launch descriptor not found at ${descriptorFile.path}. " +
          "Run `:<modulePath>:composePreviewDaemonStart` to materialise it."
      )
    }
    val descriptor =
      try {
        DaemonLaunchDescriptor.parse(fileSystem.read(descriptorFile.path.toPath()) { readUtf8() })
      } catch (e: Exception) {
        throw RenderSessionException(
          "Daemon launch descriptor at ${descriptorFile.path} is unreadable: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }
    val effectiveDescriptor =
      if (config.forceEnabled && !descriptor.enabled) descriptor.copy(enabled = true)
      else descriptor
    val workspaceRoot = config.workspaceRoot
    val canonicalRoot =
      runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot.absoluteFile)
    return spawnAndInitialize(
      descriptor = effectiveDescriptor,
      workspaceName = config.workspaceName,
      canonicalRoot = canonicalRoot,
      initializeTimeout = config.initializeTimeout,
      maxRenderTime = config.maxRenderTime,
      factory = factory,
    )
  }

  /**
   * Open a session against a self-contained preview bundle — no Gradle project on disk.
   *
   * Where [open] reads a `daemon-launch.json` the gradle plugin wrote into a module's `build/`
   * directory, this overload synthesizes the [DaemonLaunchDescriptor] in-process from the inputs a
   * bundle already carries: the desktop daemon main class, a [daemonClasspath] assembled from the
   * shipped sidecar jars (`lib-daemon-desktop/jars` + `lib-renderer/jars`), and the bundle's
   * extracted user classes ([classesDir]) + discovery manifest ([previewsJson]). The daemon reads
   * those two via the same `composeai.daemon.userClassDirs` / `composeai.daemon.previewsJsonPath`
   * system properties the `compose-preview bundle daemon` CLI command uses, so the spawn +
   * initialize + notification wiring is identical to [open] from there on.
   *
   * Keeping descriptor construction here (rather than in a downstream CLI consumer) means the
   * schema version and field names stay owned by the module that owns [DaemonLaunchDescriptor].
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
  fun openBundleDaemon(
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
    val canonicalRoot =
      runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot.absoluteFile)
    val descriptor =
      DaemonLaunchDescriptor(
        schemaVersion = DAEMON_DESCRIPTOR_SCHEMA_VERSION,
        modulePath = modulePath,
        variant = "",
        enabled = true,
        mainClass = DESKTOP_DAEMON_MAIN_CLASS,
        javaLauncher = null,
        classpath = daemonClasspath,
        jvmArgs = jvmArgs,
        systemProperties =
          mapOf(
            "composeai.daemon.userClassDirs" to userClasspath.joinToString(File.pathSeparator),
            "composeai.daemon.previewsJsonPath" to previewsJson.absolutePath,
            // Set the render-output dir so DaemonMain.dataRoot is non-null and the file-based data
            // products (compose/figma-svg + -long, semantics, wireframe, …) register — otherwise a
            // data/fetch(compose/figma-svg) on a bundle daemon fails "-32020 kind not advertised".
            // `<root>/data` (where the registry + the RenderEngine producer both resolve) then sits
            // inside this session's tree. Mirrors ServeBundleDaemon.materialize.
            "composeai.render.outputDir" to File(canonicalRoot, "renders").absolutePath,
          ) + extraSystemProperties,
        workingDirectory = canonicalRoot.absolutePath,
        manifestPath = previewsJson.absolutePath,
        jailCommand = jailCommand,
        hardTtlSeconds = hardTtlSeconds,
      )
    return spawnAndInitialize(
      descriptor = descriptor,
      workspaceName = canonicalRoot.name.ifBlank { "bundle" },
      canonicalRoot = canonicalRoot,
      initializeTimeout = initializeTimeout,
      maxRenderTime = null,
      factory = factory,
    )
  }

  /** Shared spawn + JSON-RPC initialize + session-wrap path for [open] and [openBundleDaemon]. */
  private fun spawnAndInitialize(
    descriptor: DaemonLaunchDescriptor,
    workspaceName: String,
    canonicalRoot: File,
    initializeTimeout: Duration,
    maxRenderTime: Duration?,
    factory: DaemonClientFactory,
  ): RenderSession {
    val project =
      RegisteredProject(
        workspaceId = WorkspaceId.derive(workspaceName, canonicalRoot),
        rootProjectName = workspaceName,
        path = canonicalRoot,
        knownModules = mutableListOf(),
      )

    val spawn =
      try {
        factory.spawn(project, descriptor)
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
        runCatching { spawn.shutdown() }
        fanout.clear()
      },
    )
  }

  /** `ee.schimke.composeai.daemon.DaemonMain` — the desktop daemon entrypoint a bundle spawns. */
  private const val DESKTOP_DAEMON_MAIN_CLASS = "ee.schimke.composeai.daemon.DaemonMain"

  /**
   * Descriptor schema version the daemon launch path speaks; mirrors the gradle plugin's writer.
   */
  private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

  /**
   * Resolve a module's daemon launch descriptor under [projectDir] / [modulePath] using the
   * conventional `<projectDir>/<modulePath-derived>/build/compose-previews/daemon-launch.json`
   * layout. Convenience wrapper for callers that don't already have the descriptor path.
   */
  fun descriptorFile(projectDir: File, modulePath: String): File {
    val moduleDir =
      if (modulePath.isBlank() || modulePath == ":") projectDir
      else File(projectDir, modulePath.trimStart(':').replace(':', '/'))
    return File(moduleDir, "build/compose-previews/daemon-launch.json")
  }
}
