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
   * Open a session, injecting a custom [DaemonClientFactory] and, optionally, the [FileSystem] the
   * launch descriptor is read through. Test scaffolding pairs an in-memory factory with a fake
   * daemon; production callers stick with the defaults.
   *
   * A defaulted PARAMETER rather than a property on this object. The `public var` this replaced was
   * a process-wide mutable global any consumer could swap, and it deserved to go — but a private
   * `val` left no seam at all, and `:common-io`'s rule for a stateless `object` that touches files
   * is a defaulted `fileSystem` parameter (see `docs/AGENT_GUIDE.md` → Important constraints).
   * Per-call injection has none of a global's problems: production behaviour is unchanged, and a
   * test can hand in a `FakeFileSystem` without affecting any other caller.
   */
  public fun open(
    config: RenderSessionConfig,
    factory: DaemonClientFactory,
    fileSystem: FileSystem = SystemFileSystem,
  ): RenderSession {
    val descriptorFile = config.descriptorPath
    val descriptorPath = descriptorFile.path.toPath()
    // Through the injected filesystem, not `File.isFile`: an existence check that always reads the
    // real disk would reject every path a `FakeFileSystem` holds, making the parameter above a seam
    // in name only. `isRegularFile` keeps `isFile`'s exact meaning — a directory is still not a
    // descriptor.
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
      if (config.forceEnabled && !descriptor.enabled) descriptor.copy(enabled = true)
      else descriptor
    if (config.systemPropertyOverrides.isNotEmpty()) {
      effectiveDescriptor =
        effectiveDescriptor.copy(
          systemProperties = effectiveDescriptor.systemProperties + config.systemPropertyOverrides
        )
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
      shutdownTimeout = null,
      factory = factory,
    )
  }

  /**
   * Refuse a descriptor whose [DaemonLaunchDescriptor.schemaVersion] is not the one this module
   * speaks, before anything acts on its fields.
   *
   * The descriptor's own contract says consumers gate on the version and force a fresh descriptor
   * on mismatch (`DaemonClasspathDescriptor` KDoc, "Schema versioning"), and `compose-preview
   * doctor` has always reported a mismatch as an error — but the spawn path read the version and
   * never looked at it. That is a silent-wrong-answer shape rather than a cosmetic gap: the JVM
   * reader keeps `ignoreUnknownKeys = true` and gives its reader-only fields Kotlin defaults, so a
   * descriptor from a NEWER writer parses cleanly and the session launches against defaults for
   * everything the new version added. Post-split this module is a published contract an extracted
   * preview server links against (#3824), so "the reader is older than the writer" is an ordinary
   * cross-repo version pairing rather than a same-commit mistake. Filed as #5105, deferred
   * from #4571.
   *
   * Both directions refuse — exact match, the same gate the other two consumers of this descriptor
   * already apply. VS Code's `daemonProcess.ts` requires the version to equal its own and otherwise
   * discards the descriptor and forces a re-run, `compose-preview doctor` reports any mismatch as
   * an error, and `docs/NON_GRADLE_INTEGRATION.md` already told hand-written producers that "the
   * subprocess factory rejects anything else with a clear error". It is the right shape here too:
   * the version is a single integer bumped only when the shape changes in a way that could break
   * older readers, so there is no major/minor split to be lenient about, and unlike a packed bundle
   * (`docs/VERSIONING.md` § 3's "keep readers tolerant of the older value", which is about
   * artifacts that outlive their writer) a `daemon-launch.json` is a build output the producer
   * regenerates on demand. The two messages differ in the remedy, which is the part a caller can
   * act on: an OLDER descriptor is stale and regenerating it fixes it, while a NEWER one needs the
   * consumer upgraded (regenerating would rewrite the same unreadable version).
   *
   * [openBundleDaemon] needs no such gate: it stamps [DAEMON_DESCRIPTOR_SCHEMA_VERSION] into a
   * descriptor it constructs in-process, so writer and reader are the same build by construction.
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
   * Descriptor schema version the daemon launch path speaks; mirrors the gradle plugin's writer.
   *
   * This module cannot import the writer's constant — `:gradle-plugin:daemon-launch-builder` lives
   * in a separate composite build — so the value is duplicated here. `checkDaemonLaunchSchema`
   * fails if the two ever disagree, which matters more than usual: this is one of the published
   * contract modules an extracted preview server links against (#3824), so after the split a stale
   * copy here is cross-repo version skew that no compiler sees.
   *
   * Also the version [open] gates an on-disk descriptor against — see [checkSchemaVersion].
   */
  private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

  /**
   * Resolve a module's daemon launch descriptor under [projectDir] / [modulePath] using the
   * conventional `<projectDir>/<modulePath-derived>/build/compose-previews/daemon-launch.json`
   * layout. Convenience wrapper for callers that don't already have the descriptor path.
   */
  public fun descriptorFile(projectDir: File, modulePath: String): File {
    val moduleDir =
      if (modulePath.isBlank() || modulePath == ":") projectDir
      else File(projectDir, modulePath.trimStart(':').replace(':', '/'))
    return File(moduleDir, "build/compose-previews/daemon-launch.json")
  }
}
