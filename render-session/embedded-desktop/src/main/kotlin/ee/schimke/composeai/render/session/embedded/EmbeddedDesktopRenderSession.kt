package ee.schimke.composeai.render.session.embedded

import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.runDaemon
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
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
 * [RenderSessionFactory] singleton for the in-process Compose Multiplatform Desktop backend. Builds
 * a [DaemonClientRenderSession] from a [RenderSessionConfig] by hosting `:daemon:desktop`'s
 * [runDaemon] on a background thread with piped streams. The session itself (the protocol delegate)
 * is shared with the subprocess and MCP backends — only the transport (in-process pipes vs.
 * subprocess stdio) and lifecycle (daemon-thread join vs. subprocess shutdown) differ.
 *
 * ## Lifecycle
 *
 * On [RenderSession.close]:
 * 1. Send `shutdown` + `exit` to the daemon via the client.
 * 2. Join the daemon thread, bounded by [SHUTDOWN_JOIN_TIMEOUT_MS]. If the thread doesn't exit
 *    cleanly within that window we interrupt it and continue — the calling thread isn't held
 *    hostage by a misbehaving renderer.
 * 3. Close both pipe pairs so neither side leaks file descriptors.
 * 4. Restore any system properties the session set during open (LIFO so nested sessions in the same
 *    JVM restore in the right order).
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

    // Apply the descriptor's system properties to the calling JVM. These drive PreviewIndex
    // lookup, history paths, classpath fingerprint sources etc. — the daemon code reads them
    // directly via `System.getProperty(...)`. The restores list is executed at close() so the
    // calling JVM doesn't accumulate sysprops over multiple session lifetimes.
    val restores = mutableListOf<() -> Unit>()
    for ((k, v) in descriptor.systemProperties) {
      val previous = System.getProperty(k)
      System.setProperty(k, v)
      val restore: () -> Unit =
        if (previous == null) {
          {
            System.clearProperty(k)
            Unit
          }
        } else {
          {
            System.setProperty(k, previous)
            Unit
          }
        }
      restores += restore
    }

    val workspaceRoot = config.workspaceRoot
    val canonicalRoot =
      runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot.absoluteFile)

    // Two piped pairs: client→server (request channel) and server→client (response channel).
    // We don't share a single pipe because reads + writes from the same thread would deadlock
    // — the JSON-RPC server blocks reading requests while the client blocks waiting for
    // responses.
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
                // CRITICAL: embedded mode shares the JVM with the caller. The daemon's default
                // `onExit` calls `System.exit(...)` when the JSON-RPC `exit` notification
                // arrives — that would terminate the calling JVM (test runners, IDE plugins,
                // etc.) mid-operation. Swallow the exit code; the calling thread joins the
                // daemon thread shortly after sending `exit` and observes a clean termination.
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
   * Quick liveness check — used by tests + callers that want to surface a clearer error before
   * paying the open cost when the daemon classpath isn't on the calling JVM (e.g. someone added the
   * API jar but forgot the embedded-desktop coordinate). Returns `true` only when the desktop
   * daemon entry point is reachable via reflection.
   */
  fun isAvailable(): Boolean =
    runCatching { Class.forName("ee.schimke.composeai.daemon.DaemonMain") }.isSuccess

  private const val SHUTDOWN_JOIN_TIMEOUT_MS: Long = 30_000L
}
