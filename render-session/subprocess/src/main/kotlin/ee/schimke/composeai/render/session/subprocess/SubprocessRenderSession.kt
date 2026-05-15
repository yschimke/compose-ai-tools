package ee.schimke.composeai.render.session.subprocess

import ee.schimke.composeai.daemon.protocol.InitializeResult
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
        DaemonLaunchDescriptor.parse(descriptorFile.readText())
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
    val project =
      RegisteredProject(
        workspaceId = WorkspaceId.derive(config.workspaceName, canonicalRoot),
        rootProjectName = config.workspaceName,
        path = canonicalRoot,
        knownModules = mutableListOf(),
      )

    val spawn =
      try {
        factory.spawn(project, effectiveDescriptor)
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
          moduleId = effectiveDescriptor.modulePath,
          moduleProjectDir = effectiveDescriptor.workingDirectory,
          timeout = config.initializeTimeout,
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
      modulePath = effectiveDescriptor.modulePath,
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
