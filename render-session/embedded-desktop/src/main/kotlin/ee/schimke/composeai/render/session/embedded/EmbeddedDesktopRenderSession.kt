package ee.schimke.composeai.render.session.embedded

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.runDaemon
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.DaemonClientRenderSession
import ee.schimke.composeai.render.session.subprocess.NotificationFanout
import java.io.PipedInputStream
import java.io.PipedOutputStream
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * [RenderSessionFactory] for the in-process Compose Desktop backend: hosts `:daemon:desktop`'s
 * [runDaemon] on a background thread over piped streams, behind the same
 * [DaemonClientRenderSession] the subprocess backend uses.
 *
 * On [RenderSession.close]: send `shutdown` + `exit`; join the daemon thread for up to
 * [SHUTDOWN_JOIN_TIMEOUT_MS] (then interrupt); close both pipe pairs; restore the system properties
 * set at open, LIFO so nested sessions restore correctly.
 */
object EmbeddedDesktopRenderSessions : RenderSessionFactory {
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Embedded

  var fileSystem: FileSystem = SystemFileSystem

  override fun open(config: RenderSessionConfig): RenderSession {
    val descriptorFile = config.descriptorPath
    if (!descriptorFile.isFile) {
      throw RenderSessionException(
        "Daemon launch descriptor not found at ${descriptorFile.path}. " +
          "Run `:<modulePath>:composePreviewDaemonStart` to materialise it."
      )
    }
    var descriptor =
      try {
        DaemonLaunchDescriptor.parse(fileSystem.read(descriptorFile.path.toPath()) { readUtf8() })
      } catch (e: Exception) {
        throw RenderSessionException(
          "Daemon launch descriptor at ${descriptorFile.path} is unreadable: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }
    if (config.systemPropertyOverrides.isNotEmpty()) {
      descriptor =
        descriptor
          .newBuilder()
          .also {
            it.systemProperties = descriptor.systemProperties + config.systemPropertyOverrides
          }
          .build()
    }

    // The daemon reads its configuration from system properties; restored at close() so they don't
    // accumulate across sessions.
    val restores = mutableListOf<() -> Unit>()
    for ((k, v) in descriptor.systemProperties) {
      val previous = System.getProperty(k)
      System.setProperty(k, v)
      val restore: () -> Unit =
        if (previous == null) {
          { System.clearProperty(k) }
        } else {
          { System.setProperty(k, previous) }
        }
      restores += restore
    }

    val workspaceRoot = config.workspaceRoot
    val canonicalRoot = runCatching {
      workspaceRoot.canonicalFile
    }
      .getOrDefault(workspaceRoot.absoluteFile)

    // Two pipe pairs: a single pipe would deadlock with the server blocked reading and the client
    // blocked awaiting responses.
    val clientToServerSink = PipedOutputStream()
    val clientToServerSource = PipedInputStream(clientToServerSink)
    val serverToClientSink = PipedOutputStream()
    val serverToClientSource = PipedInputStream(serverToClientSink)
    val pipesToClose: List<AutoCloseable> =
      listOf(clientToServerSink, clientToServerSource, serverToClientSink, serverToClientSource)

    val daemonThread =
      Thread(
          {
            try {
              runDaemon(
                input = clientToServerSource,
                output = serverToClientSink,
                installSigtermHook = false,
                // CRITICAL: the daemon's default `onExit` calls `System.exit`, which would kill the
                // caller's JVM. The calling thread joins the daemon thread instead.
                onExit = { _ -> },
              )
            } catch (t: Throwable) {
              config.logSink("daemon thread terminated: ${t.javaClass.simpleName}: ${t.message}")
            }
          },
          "compose-preview-embedded-daemon",
        )
        .apply {
          isDaemon = true
          start()
        }

    val fanout = NotificationFanout()
    val client =
      DaemonClient(
        input = serverToClientSource,
        output = clientToServerSink,
        onNotification = { method, params -> fanout.dispatch(method, params) },
        onClose = {},
      )

    val initializeResult: InitializeResult =
      try {
        client.initialize(
          workspaceRoot = canonicalRoot.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          maxRenderMs = config.maxRenderTime?.inWholeMilliseconds,
          timeout = config.initializeTimeout,
        )
      } catch (e: Exception) {
        // Tear down everything we constructed so far before throwing.
        runCatching { client.shutdownAndExit() }
        runCatching { daemonThread.join(5_000) }
        if (daemonThread.isAlive) daemonThread.interrupt()
        pipesToClose.forEach { runCatching { it.close() } }
        runCatching { client.close() }
        restores.asReversed().forEach { runCatching { it.invoke() } }
        throw RenderSessionException(
          "Embedded daemon initialize handshake failed for ${descriptor.modulePath}: " +
            (e.message ?: e.javaClass.simpleName),
          cause = e,
        )
      }

    return DaemonClientRenderSession(
      workspaceRoot = canonicalRoot.absolutePath,
      modulePath = descriptor.modulePath,
      initializeResult = initializeResult,
      backendKind = RenderSessionBackend.Embedded,
      client = client,
      notificationFanout = fanout,
      closeAction = {
        runCatching { client.shutdownAndExit() }
        runCatching { daemonThread.join(SHUTDOWN_JOIN_TIMEOUT_MS) }
        if (daemonThread.isAlive) daemonThread.interrupt()
        pipesToClose.forEach { runCatching { it.close() } }
        runCatching { client.close() }
        fanout.clear()
        restores.asReversed().forEach { runCatching { it.invoke() } }
      },
    )
  }

  /**
   * Whether the desktop daemon entry point is reachable via reflection, so callers can fail clearly
   * before paying the open cost when the embedded-desktop coordinate is missing.
   */
  fun isAvailable(): Boolean = runCatching {
    Class.forName("ee.schimke.composeai.daemon.DaemonMain")
  }
    .isSuccess

  private const val SHUTDOWN_JOIN_TIMEOUT_MS: Long = 30_000L
}
