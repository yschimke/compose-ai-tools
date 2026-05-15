package ee.schimke.composeai.render.session.embedded

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.ExtensionsDisableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.runDaemon
import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import ee.schimke.composeai.mcp.DataProductWireException
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement

/**
 * [RenderSession] backed by an in-process Compose Multiplatform Desktop daemon. The session spawns
 * a single background thread that runs `:daemon:desktop`'s `runDaemon(...)` against piped streams;
 * the calling thread holds the other end of those pipes via a `DaemonClient` and delegates every
 * protocol call straight through. No subprocess fork; no JSON-RPC bytes ever leave the JVM.
 *
 * Use [EmbeddedDesktopRenderSessions] to open instances; the constructor is internal so test fakes
 * can be wired without consumers seeing the transport plumbing.
 *
 * ## Lifecycle
 *
 * On [close] (or via try-with-resources), the session:
 * 1. Sends `shutdown` + `exit` to the daemon via the client (`DaemonClient.shutdownAndExit`).
 * 2. Joins the daemon thread, bounded by [SHUTDOWN_JOIN_TIMEOUT_MS]. If the thread doesn't exit
 *    cleanly within that window we interrupt it and continue — the calling thread isn't held
 *    hostage by a misbehaving renderer.
 * 3. Closes both pipe pairs so neither side leaks file descriptors.
 *
 * After close every other method throws `IllegalStateException`.
 */
class EmbeddedDesktopRenderSession
internal constructor(
  override val workspaceRoot: String,
  override val modulePath: String,
  override val initializeResult: InitializeResult,
  private val client: DaemonClient,
  private val daemonThread: Thread,
  private val pipesToClose: List<AutoCloseable>,
  private val systemPropertyRestores: List<() -> Unit>,
  private val listeners: CopyOnWriteArraySet<NotificationListener>,
) : RenderSession {

  override val backendKind: RenderSessionBackend = RenderSessionBackend.Embedded

  private val closed = AtomicBoolean(false)

  override fun setVisible(previewIds: List<String>) {
    checkOpen()
    client.setVisible(previewIds)
  }

  override fun setFocus(previewIds: List<String>) {
    checkOpen()
    client.setFocus(previewIds)
  }

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) {
    checkOpen()
    client.fileChanged(path, kind, changeType)
  }

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RenderNowResult {
    checkOpen()
    return client.renderNow(
      previews = previewIds,
      tier = tier,
      reason = reason,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean,
    params: JsonElement?,
    timeout: Duration,
  ): DataFetchResult {
    checkOpen()
    return try {
      client.dataFetch(
        previewId = previewId,
        kind = kind,
        params = params,
        inline = inline,
        timeout = timeout,
      )
    } catch (e: DataProductWireException) {
      throw DataProductException(
        code = e.code,
        wireMessage = e.wireMessage,
        data = e.data,
        cause = e,
      )
    }
  }

  override fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement?,
    timeout: Duration,
  ): DataSubscribeResult {
    checkOpen()
    return client.dataSubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration,
  ): DataSubscribeResult {
    checkOpen()
    return client.dataUnsubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun listExtensions(timeout: Duration): ExtensionsListResult {
    checkOpen()
    return client.extensionsList(timeout)
  }

  override fun enableExtensions(ids: List<String>, timeout: Duration): ExtensionsEnableResult {
    checkOpen()
    return client.extensionsEnable(ids = ids, timeout = timeout)
  }

  override fun disableExtensions(ids: List<String>, timeout: Duration): ExtensionsDisableResult {
    checkOpen()
    return client.extensionsDisable(ids = ids, timeout = timeout)
  }

  override fun historyList(params: HistoryListParams, timeout: Duration): HistoryListResult {
    checkOpen()
    return client.historyList(params = params, timeout = timeout)
  }

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: Duration,
  ): HistoryReadResultDto {
    checkOpen()
    return client.historyRead(entryId = entryId, inline = inline, timeout = timeout)
  }

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: Duration,
  ): HistoryDiffResult {
    checkOpen()
    return client.historyDiff(fromId = fromId, toId = toId, mode = mode, timeout = timeout)
  }

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RecordingStartResult {
    checkOpen()
    return client.recordingStart(
      previewId = previewId,
      fps = fps,
      scale = scale,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) {
    checkOpen()
    client.recordingScript(recordingId, events)
  }

  override fun recordingStop(recordingId: String, timeout: Duration): RecordingStopResult {
    checkOpen()
    return client.recordingStop(recordingId = recordingId, timeout = timeout)
  }

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: Duration,
  ): RecordingEncodeResult {
    checkOpen()
    return client.recordingEncode(recordingId = recordingId, format = format, timeout = timeout)
  }

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    checkOpen()
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { client.shutdownAndExit() }
    runCatching { daemonThread.join(SHUTDOWN_JOIN_TIMEOUT_MS) }
    if (daemonThread.isAlive) {
      // Interrupt as a last resort — the join's grace window already passed. The pipes
      // close below will tear the read loop down regardless.
      daemonThread.interrupt()
    }
    pipesToClose.forEach { runCatching { it.close() } }
    runCatching { client.close() }
    // Restore any system properties the session set during open. LIFO so nested sessions
    // (against the same JVM, serially) restore in the right order.
    systemPropertyRestores.asReversed().forEach { runCatching { it.invoke() } }
  }

  private fun checkOpen() {
    check(!closed.get()) { "RenderSession has been closed" }
  }

  companion object {
    private const val SHUTDOWN_JOIN_TIMEOUT_MS: Long = 30_000L
  }
}

/**
 * [RenderSessionFactory] singleton for the in-process Compose Multiplatform Desktop backend. Builds
 * an [EmbeddedDesktopRenderSession] from a [RenderSessionConfig] by hosting `:daemon:desktop`'s
 * [runDaemon] on a background thread with piped streams.
 */
object EmbeddedDesktopRenderSessions : RenderSessionFactory {
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Embedded

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
        DaemonLaunchDescriptor.parse(descriptorFile.readText())
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
                // `onExit` calls `System.exit(...)` when the JSON-RPC `exit` notification arrives
                // — that would terminate the calling JVM (test runners, IDE plugins, etc.) mid-
                // operation. Swallow the exit code; the calling thread joins the daemon thread
                // shortly after sending `exit` and observes a clean termination.
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

    val listeners = CopyOnWriteArraySet<NotificationListener>()
    val client =
      DaemonClient(
        input = serverToClientSource,
        output = clientToServerSink,
        onNotification = { method, params ->
          for (l in listeners) runCatching { l.onNotification(method, params) }
        },
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

    return EmbeddedDesktopRenderSession(
      workspaceRoot = canonicalRoot.absolutePath,
      modulePath = descriptor.modulePath,
      initializeResult = initializeResult,
      client = client,
      daemonThread = daemonThread,
      pipesToClose = pipesToClose,
      systemPropertyRestores = restores,
      listeners = listeners,
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
}
