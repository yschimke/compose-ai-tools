package ee.schimke.composeai.render.session.subprocess

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
import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DaemonClientFactory
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import ee.schimke.composeai.mcp.DataProductWireException
import ee.schimke.composeai.mcp.RegisteredProject
import ee.schimke.composeai.mcp.SubprocessDaemonClientFactory
import ee.schimke.composeai.mcp.WorkspaceId
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement

/**
 * [RenderSession] backed by a daemon JVM spawned as a subprocess. Each session owns one subprocess;
 * closing the session shuts the subprocess down cleanly (drain → exit → wait, with timeout fallback
 * to `destroyForcibly`).
 *
 * Use [SubprocessRenderSessions] (or the static helpers below) to open sessions — the public
 * constructor is internal so test code can inject a fake [DaemonClientFactory] without inheriting
 * transport implementation details.
 */
class SubprocessRenderSession
internal constructor(
  override val workspaceRoot: String,
  override val modulePath: String,
  override val initializeResult: InitializeResult,
  private val spawnedClient: DaemonClient,
  private val closeSpawn: () -> Unit,
  private val listeners: CopyOnWriteArraySet<NotificationListener>,
) : RenderSession {

  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  private val closed = AtomicBoolean(false)

  override fun setVisible(previewIds: List<String>) {
    checkOpen()
    spawnedClient.setVisible(previewIds)
  }

  override fun setFocus(previewIds: List<String>) {
    checkOpen()
    spawnedClient.setFocus(previewIds)
  }

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) {
    checkOpen()
    spawnedClient.fileChanged(path, kind, changeType)
  }

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RenderNowResult {
    checkOpen()
    return spawnedClient.renderNow(
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
      spawnedClient.dataFetch(
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
    return spawnedClient.dataSubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration,
  ): DataSubscribeResult {
    checkOpen()
    return spawnedClient.dataUnsubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun listExtensions(timeout: Duration): ExtensionsListResult {
    checkOpen()
    return spawnedClient.extensionsList(timeout)
  }

  override fun enableExtensions(ids: List<String>, timeout: Duration): ExtensionsEnableResult {
    checkOpen()
    return spawnedClient.extensionsEnable(ids = ids, timeout = timeout)
  }

  override fun disableExtensions(ids: List<String>, timeout: Duration): ExtensionsDisableResult {
    checkOpen()
    return spawnedClient.extensionsDisable(ids = ids, timeout = timeout)
  }

  override fun historyList(params: HistoryListParams, timeout: Duration): HistoryListResult {
    checkOpen()
    return spawnedClient.historyList(params = params, timeout = timeout)
  }

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: Duration,
  ): HistoryReadResultDto {
    checkOpen()
    return spawnedClient.historyRead(entryId = entryId, inline = inline, timeout = timeout)
  }

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: Duration,
  ): HistoryDiffResult {
    checkOpen()
    return spawnedClient.historyDiff(fromId = fromId, toId = toId, mode = mode, timeout = timeout)
  }

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RecordingStartResult {
    checkOpen()
    return spawnedClient.recordingStart(
      previewId = previewId,
      fps = fps,
      scale = scale,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) {
    checkOpen()
    spawnedClient.recordingScript(recordingId, events)
  }

  override fun recordingStop(recordingId: String, timeout: Duration): RecordingStopResult {
    checkOpen()
    return spawnedClient.recordingStop(recordingId = recordingId, timeout = timeout)
  }

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: Duration,
  ): RecordingEncodeResult {
    checkOpen()
    return spawnedClient.recordingEncode(
      recordingId = recordingId,
      format = format,
      timeout = timeout,
    )
  }

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    checkOpen()
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { closeSpawn() }
  }

  private fun checkOpen() {
    check(!closed.get()) { "RenderSession has been closed" }
  }
}

/**
 * [RenderSessionFactory] singleton for the daemon-subprocess backend. Open a session via
 * `SubprocessRenderSessions.open(config)` or the convenience overloads below.
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

    val listeners = CopyOnWriteArraySet<NotificationListener>()
    val client: DaemonClient =
      spawn.client(
        onNotification = { method, params ->
          for (l in listeners) runCatching { l.onNotification(method, params) }
        },
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

    return SubprocessRenderSession(
      workspaceRoot = canonicalRoot.absolutePath,
      modulePath = effectiveDescriptor.modulePath,
      initializeResult = initializeResult,
      spawnedClient = client,
      closeSpawn = { spawn.shutdown() },
      listeners = listeners,
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
