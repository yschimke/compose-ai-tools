package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.protocol.JsonRpcResponse
import ee.schimke.composeai.daemon.protocol.LeakDetectionMode
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderFinishedParams
import ee.schimke.composeai.daemon.protocol.RenderNowParams
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderStartedParams
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.SetFocusParams
import ee.schimke.composeai.daemon.protocol.SetVisibleParams
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/**
 * JSON-RPC 2.0 server over stdio for the preview daemon.
 *
 * Wire format and dispatch semantics: docs/daemon/PROTOCOL.md (v1, locked).
 *
 * **Threading model.**
 * - One **read thread** (the thread that calls [run]) drains [input], parses
 *   envelopes, and dispatches them. Inline handlers (initialize, setVisible,
 *   setFocus, fileChanged, shutdown, exit) execute on this thread.
 * - One **write thread** (named `compose-ai-daemon-writer`) consumes a single
 *   outbound queue and writes framed bytes to [output]. This serialises every
 *   reply and notification so framing on the wire is always well-formed even
 *   when notifications race with responses.
 * - The **DaemonHost render thread** (B1.3) is the only place renders execute.
 *   `renderNow` enqueues `RenderRequest.Render` items onto [host]; per-render
 *   notifications (`renderStarted`, `renderFinished`) are emitted from a
 *   dedicated **render-watcher thread** that polls completed results and
 *   forwards them to the writer queue.
 *
 * **No mid-render cancellation invariant** (DESIGN.md § 9, PROTOCOL.md § 3).
 * Shutdown stops accepting new `renderNow` work, then waits for every
 * already-accepted render to complete before responding. We never call
 * `Thread.interrupt()` on the render thread; the host's poison-pill `Shutdown`
 * is enqueued only after the in-flight queue has drained.
 *
 * **Stub render bodies.** B1.4 (RenderEngine) replaces the body of
 * [renderFinishedFromResult] with the real Compose/Robolectric render. For
 * B1.5 the host returns a synthetic [RenderResult] and we materialise it as
 * a placeholder PNG path of `${historyDir}/daemon-stub-${id}.png`. The
 * placeholder file is **not** written to disk — `pngPath` is a string
 * field, not a postcondition that the file must exist. B1.4 will both
 * produce real bytes and make the path point at them.
 *
 * **B1.4 hook point.** When B1.4 introduces `RenderEngine`, the wiring change
 * is: replace the body of [renderFinishedFromResult] with a call into
 * `RenderEngine.renderTookMs(result)` (or similar) that materialises the PNG
 * and returns timing/metrics. The render queue plumbing (submit → poll →
 * notify) does not need to change.
 */
class JsonRpcServer(
  private val input: InputStream,
  private val output: OutputStream,
  private val host: DaemonHost,
  private val daemonVersion: String = DEFAULT_DAEMON_VERSION,
  private val historyDir: String = DEFAULT_HISTORY_DIR,
  private val idleTimeoutMs: Long =
    System.getProperty(IDLE_TIMEOUT_PROP)?.toLongOrNull() ?: DEFAULT_IDLE_TIMEOUT_MS,
  private val onExit: (Int) -> Unit = { code -> System.exit(code) },
) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  private val initialized = AtomicBoolean(false)
  private val shutdownRequested = AtomicBoolean(false)
  private val running = AtomicBoolean(true)
  private val exitInvoked = AtomicBoolean(false)

  /** Outbound frame queue. SHUTDOWN_SENTINEL is the writer's poison pill. */
  private val outbound = LinkedBlockingQueue<ByteArray>()

  /** In-flight render IDs the host is currently working on. */
  private val inFlightRenders = ConcurrentHashMap.newKeySet<Long>()

  /** Per-protocol-id → enqueue wall-clock millis, for `renderStarted.queuedMs`. */
  private val acceptedAtMs = ConcurrentHashMap<Long, Long>()

  /** Mapping host-side internal request id → caller's preview id string. */
  private val hostIdToPreviewId = ConcurrentHashMap<Long, String>()

  private val writerThread =
    Thread({ writerLoop() }, "compose-ai-daemon-writer").apply { isDaemon = false }

  private val renderWatcherThread =
    Thread({ renderWatcherLoop() }, "compose-ai-daemon-render-watcher")
      .apply { isDaemon = false }

  /**
   * Reads from [input] until EOF, dispatches messages inline (or onto the
   * host), and writes replies via the writer thread. Blocks until either:
   *
   *   1. `exit` notification arrives → returns and calls [onExit] (0 if
   *      `shutdown` preceded it, else 1).
   *   2. stdin EOF without `shutdown`+`exit` → waits up to [idleTimeoutMs],
   *      then exits with code 1.
   */
  fun run() {
    host.start()
    writerThread.start()
    renderWatcherThread.start()
    try {
      readLoop()
      // EOF without exit notification — PROTOCOL.md § 3 idle-timeout exit.
      if (!shutdownRequested.get()) {
        try {
          Thread.sleep(idleTimeoutMs)
        } catch (_: InterruptedException) {
          // Restore interrupt status; we're exiting anyway.
          Thread.currentThread().interrupt()
        }
        cleanShutdown()
        invokeExit(1)
      } else {
        // Saw shutdown but stdin closed before `exit`; treat as graceful.
        cleanShutdown()
        invokeExit(0)
      }
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: fatal in JsonRpcServer.run: ${e.message}")
      e.printStackTrace(System.err)
      cleanShutdown()
      invokeExit(1)
    }
  }

  // --------------------------------------------------------------------------
  // Read loop
  // --------------------------------------------------------------------------

  private fun readLoop() {
    val reader = ContentLengthFramer(input)
    while (running.get()) {
      val bytes =
        try {
          reader.readFrame() ?: break // EOF
        } catch (e: FramingException) {
          // Per JSON-RPC § 4.2: a parse error is reported with id=null.
          sendErrorResponse(id = null, code = ERR_PARSE, message = e.message ?: "parse error")
          continue
        } catch (_: EOFException) {
          break
        } catch (_: IOException) {
          break
        }
      try {
        dispatchFrame(bytes)
      } catch (e: Throwable) {
        // Don't let one malformed message kill the read loop — surface to
        // stderr (free-form log per PROTOCOL.md § 1) and continue.
        System.err.println("compose-ai-daemon: dispatch error: ${e.message}")
        e.printStackTrace(System.err)
      }
    }
  }

  private fun dispatchFrame(bytes: ByteArray) {
    val text = bytes.toString(Charsets.UTF_8)
    val element =
      try {
        json.parseToJsonElement(text)
      } catch (e: Throwable) {
        sendErrorResponse(id = null, code = ERR_PARSE, message = "invalid JSON: ${e.message}")
        return
      }
    if (element !is JsonObject) {
      sendErrorResponse(id = null, code = ERR_INVALID_REQUEST, message = "envelope must be object")
      return
    }
    val hasId = element.containsKey("id") && element["id"] !is JsonNull
    if (hasId) {
      val request =
        try {
          json.decodeFromString(JsonRpcRequest.serializer(), text)
        } catch (e: Throwable) {
          val rawId = (element["id"] as? JsonPrimitive)?.long
          sendErrorResponse(
            id = rawId,
            code = ERR_INVALID_REQUEST,
            message = "invalid request: ${e.message}",
          )
          return
        }
      handleRequest(request)
    } else {
      val notification =
        try {
          json.decodeFromString(JsonRpcNotification.serializer(), text)
        } catch (e: Throwable) {
          // Notifications have no response per JSON-RPC; log and drop.
          System.err.println("compose-ai-daemon: invalid notification: ${e.message}")
          return
        }
      handleNotification(notification)
    }
  }

  // --------------------------------------------------------------------------
  // Request handlers
  // --------------------------------------------------------------------------

  private fun handleRequest(req: JsonRpcRequest) {
    if (req.method != "initialize" && !initialized.get()) {
      sendErrorResponse(
        id = req.id,
        code = ERR_NOT_INITIALIZED,
        message = "received '${req.method}' before 'initialized' notification",
      )
      return
    }
    when (req.method) {
      "initialize" -> handleInitialize(req)
      "renderNow" -> handleRenderNow(req)
      "shutdown" -> handleShutdown(req)
      else ->
        sendErrorResponse(
          id = req.id,
          code = ERR_METHOD_NOT_FOUND,
          message = "method not found: ${req.method}",
        )
    }
  }

  private fun handleInitialize(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, InitializeParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid initialize params: ${e.message}",
        )
        return
      }
    if (params.protocolVersion != PROTOCOL_VERSION) {
      sendErrorResponse(
        id = req.id,
        code = ERR_INVALID_REQUEST,
        message =
          "protocolVersion mismatch: client=${params.protocolVersion}, daemon=$PROTOCOL_VERSION",
      )
      // Per PROTOCOL.md § 3: daemon errors out on mismatch.
      shutdownRequested.set(true)
      running.set(false)
      return
    }
    val result =
      InitializeResult(
        protocolVersion = PROTOCOL_VERSION,
        daemonVersion = daemonVersion,
        pid = currentPid(),
        capabilities =
          ServerCapabilities(
            // Tier-2 (B2.2) lands later; until then we honour PROTOCOL.md § 3
            // verbatim and report false.
            incrementalDiscovery = false,
            sandboxRecycle = true,
            // Leak detection (B2.4) not wired yet — empty list = unavailable.
            leakDetection = emptyList<LeakDetectionMode>(),
          ),
        // B2.1 (ClasspathFingerprint) replaces this with a real SHA-256.
        classpathFingerprint = "",
        manifest =
          Manifest(
            // B2.2 (IncrementalDiscovery) replaces this with the real path
            // and count once the daemon owns its own previews.json.
            path = "",
            previewCount = 0,
          ),
      )
    sendResponse(req.id, encode(InitializeResult.serializer(), result))
  }

  private fun handleRenderNow(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, RenderNowParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid renderNow params: ${e.message}",
        )
        return
      }
    if (shutdownRequested.get()) {
      sendErrorResponse(
        id = req.id,
        code = ERR_INTERNAL,
        message = "daemon is shutting down; not accepting new renderNow",
      )
      return
    }
    val queued = mutableListOf<String>()
    val rejected = mutableListOf<RejectedRender>()
    val now = System.currentTimeMillis()
    for (previewId in params.previews) {
      // Stub policy for B1.5: accept any non-blank id. UnknownPreview (-32004)
      // requires a real discovery set, which lands with B2.2.
      if (previewId.isBlank()) {
        rejected.add(RejectedRender(id = previewId, reason = "blank preview id"))
        continue
      }
      val hostId = DaemonHost.nextRequestId()
      hostIdToPreviewId[hostId] = previewId
      acceptedAtMs[hostId] = now
      inFlightRenders.add(hostId)
      // Submit to host on a worker thread so we don't block the read loop.
      // submit() returns when the host returns a result; the watcher thread
      // demuxes the result back into renderFinished.
      submitRenderAsync(hostId)
      queued.add(previewId)
    }
    val result = RenderNowResult(queued = queued, rejected = rejected)
    sendResponse(req.id, encode(RenderNowResult.serializer(), result))
  }

  private fun submitRenderAsync(hostId: Long) {
    // Fire-and-forget: the watcher thread polls the result and emits the
    // notification. We use a fresh thread (cheap; we expect O(visible) renders
    // queued at a time) rather than a pool to keep wiring trivial — B1.4 will
    // revisit when it introduces a real RenderEngine.
    Thread(
        {
          try {
            // 5-minute ceiling: covers cold sandbox bootstrap (~5–15s on
            // first render) plus B1.4's eventual real Compose render
            // (single-digit seconds). DaemonHost still uses its own 60s
            // default for direct callers; we override here because the
            // first render in a daemon's life sits behind the sandbox cold
            // boot.
            val raw = host.submit(RenderRequest.Render(id = hostId), timeoutMs = 5 * 60_000)
            renderResultsQueue.put(raw)
          } catch (e: Throwable) {
            System.err.println("compose-ai-daemon: host.submit($hostId) failed: ${e.message}")
            renderResultsQueue.put(RenderResultOrFailure.Failure(hostId, e))
          }
        },
        "compose-ai-daemon-render-submit-$hostId",
      )
      .apply { isDaemon = true }
      .start()
  }

  private val renderResultsQueue = LinkedBlockingQueue<Any>()

  private fun renderWatcherLoop() {
    while (true) {
      val item =
        try {
          renderResultsQueue.poll(200, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
          // We do not expect the watcher to be interrupted; treat as exit.
          Thread.currentThread().interrupt()
          return
        }
      if (item == null) {
        if (!running.get() && inFlightRenders.isEmpty()) return
        continue
      }
      when (item) {
        is RenderResult -> emitRenderFinished(item)
        is RenderResultOrFailure.Failure -> emitRenderFailed(item)
        else -> System.err.println("compose-ai-daemon: unexpected render result type: $item")
      }
    }
  }

  private fun emitRenderFinished(result: RenderResult) {
    val previewId = hostIdToPreviewId.remove(result.id) ?: result.id.toString()
    val acceptedAt = acceptedAtMs.remove(result.id) ?: System.currentTimeMillis()
    val now = System.currentTimeMillis()
    // Tier "fast" stub timing — actual wall-clock between accept and finish.
    sendNotification(
      "renderStarted",
      encode(
        RenderStartedParams.serializer(),
        RenderStartedParams(id = previewId, queuedMs = (now - acceptedAt).coerceAtLeast(0)),
      ),
    )
    val finished = renderFinishedFromResult(previewId, result, tookMs = 0)
    sendNotification("renderFinished", encode(RenderFinishedParams.serializer(), finished))
    inFlightRenders.remove(result.id)
  }

  private fun emitRenderFailed(failure: RenderResultOrFailure.Failure) {
    val previewId = hostIdToPreviewId.remove(failure.hostId) ?: failure.hostId.toString()
    acceptedAtMs.remove(failure.hostId)
    inFlightRenders.remove(failure.hostId)
    // Minimal renderFailed; B1.4 widens this to the real RenderError shape.
    val payload =
      buildJsonObject {
        put("id", JsonPrimitive(previewId))
        put(
          "error",
          buildJsonObject {
            put("kind", JsonPrimitive("internal"))
            put("message", JsonPrimitive(failure.cause.message ?: failure.cause.javaClass.name))
          },
        )
      }
    sendNotification("renderFailed", payload)
  }

  /**
   * B1.4 hook: replace this body with the real RenderEngine call. For now we
   * emit a deterministic placeholder PNG path; the file is **not** written to
   * disk in B1.5.
   */
  private fun renderFinishedFromResult(
    previewId: String,
    result: RenderResult,
    tookMs: Long,
  ): RenderFinishedParams {
    val placeholderPath = "$historyDir/daemon-stub-${result.id}.png"
    return RenderFinishedParams(
      id = previewId,
      pngPath = placeholderPath,
      tookMs = tookMs,
      metrics = null, // populated by B2.3 when client requests metrics.
    )
  }

  private fun handleShutdown(req: JsonRpcRequest) {
    shutdownRequested.set(true)
    // Drain in-flight renders before responding, per PROTOCOL.md § 3 and
    // DESIGN.md § 9 ("No mid-render cancellation"). We poll rather than
    // wait/notify because renders complete on submit threads we don't own.
    val drainDeadlineMs = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    while (inFlightRenders.isNotEmpty() && System.currentTimeMillis() < drainDeadlineMs) {
      Thread.sleep(50)
    }
    sendResponse(req.id, JsonNull)
  }

  // --------------------------------------------------------------------------
  // Notification handlers
  // --------------------------------------------------------------------------

  private fun handleNotification(n: JsonRpcNotification) {
    when (n.method) {
      "initialized" -> initialized.set(true)
      "exit" -> handleExit()
      "setVisible" -> tryDecode(SetVisibleParams.serializer(), n) { /* no-op for B1.5 */ }
      "setFocus" -> tryDecode(SetFocusParams.serializer(), n) { /* no-op for B1.5 */ }
      "fileChanged" -> tryDecode(FileChangedParams.serializer(), n) { /* no-op for B1.5 */ }
      else -> System.err.println("compose-ai-daemon: unknown notification method: ${n.method}")
    }
  }

  private fun handleExit() {
    val exitCode = if (shutdownRequested.get()) 0 else 1
    running.set(false)
    cleanShutdown()
    invokeExit(exitCode)
  }

  private fun <T> tryDecode(
    serializer: KSerializer<T>,
    n: JsonRpcNotification,
    block: (T) -> Unit,
  ) {
    try {
      block(decodeParams(n.params, serializer))
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: invalid params for ${n.method}: ${e.message}")
    }
  }

  private fun <T> decodeParams(params: JsonElement?, serializer: KSerializer<T>): T {
    val element = params ?: JsonObject(emptyMap())
    return json.decodeFromJsonElement(serializer, element)
  }

  // --------------------------------------------------------------------------
  // Outbound write path
  // --------------------------------------------------------------------------

  private fun sendResponse(id: Long, result: JsonElement) {
    val response = JsonRpcResponse(id = id, result = result, error = null)
    enqueueFrame(json.encodeToString(JsonRpcResponse.serializer(), response))
  }

  private fun sendErrorResponse(id: Long?, code: Int, message: String) {
    val element = buildJsonObject {
      put("jsonrpc", JsonPrimitive("2.0"))
      put("id", if (id == null) JsonNull else JsonPrimitive(id))
      put(
        "error",
        buildJsonObject {
          put("code", JsonPrimitive(code))
          put("message", JsonPrimitive(message))
        },
      )
    }
    enqueueFrame(json.encodeToString(JsonElement.serializer(), element))
  }

  private fun sendNotification(method: String, params: JsonElement) {
    if (!initialized.get() && method != "log") {
      // PROTOCOL.md § 3: daemon must not send notifications before
      // `initialized`. Silently drop pre-initialize notifications.
      return
    }
    val n = JsonRpcNotification(method = method, params = params)
    enqueueFrame(json.encodeToString(JsonRpcNotification.serializer(), n))
  }

  private fun enqueueFrame(jsonText: String) {
    val payload = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val combined = ByteArray(header.size + payload.size)
    System.arraycopy(header, 0, combined, 0, header.size)
    System.arraycopy(payload, 0, combined, header.size, payload.size)
    outbound.put(combined)
  }

  private fun writerLoop() {
    try {
      while (true) {
        val frame = outbound.take()
        if (frame === SHUTDOWN_SENTINEL) return
        output.write(frame)
        output.flush()
      }
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: writer loop error: ${e.message}")
    }
  }

  private fun invokeExit(code: Int) {
    if (exitInvoked.compareAndSet(false, true)) {
      onExit(code)
    }
  }

  private fun cleanShutdown() {
    if (!running.compareAndSet(true, false)) return
    // Wait for any outstanding renders to drain so we honour the
    // no-mid-render-cancellation invariant even on EOF / unexpected exit
    // paths. Bounded by DRAIN_TIMEOUT_MS to avoid hanging the process.
    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    while (inFlightRenders.isNotEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
    }
    try {
      host.shutdown()
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: host.shutdown failed: ${e.message}")
    }
    // Tell the writer to exit, then drain it so any pending bytes flush.
    outbound.put(SHUTDOWN_SENTINEL)
    try {
      writerThread.join(2_000)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    // Watcher exits once running=false and inFlightRenders is empty.
    try {
      renderWatcherThread.join(2_000)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  // --------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------

  private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
    json.encodeToJsonElement(serializer, value)

  private fun currentPid(): Long =
    try {
      ProcessHandle.current().pid()
    } catch (_: Throwable) {
      // Fallback for environments where ProcessHandle is unavailable.
      ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: -1L
    }

  /** Tagged failure carrier for the watcher loop. */
  private sealed interface RenderResultOrFailure {
    class Failure(val hostId: Long, val cause: Throwable) : RenderResultOrFailure
  }

  companion object {
    /** PROTOCOL.md § 7. */
    const val PROTOCOL_VERSION: Int = 1

    const val IDLE_TIMEOUT_PROP: String = "composeai.daemon.idleTimeoutMs"
    const val DEFAULT_IDLE_TIMEOUT_MS: Long = 5_000L

    /** Ceiling on shutdown drain. Renders that take longer than this are still allowed to finish — but the shutdown response will be sent. */
    private const val DRAIN_TIMEOUT_MS: Long = 60_000L

    private const val DEFAULT_DAEMON_VERSION: String = "0.0.0-dev"
    private const val DEFAULT_HISTORY_DIR: String = ".compose-preview-history"

    // JSON-RPC error codes — PROTOCOL.md § 2.
    const val ERR_PARSE: Int = -32700
    const val ERR_INVALID_REQUEST: Int = -32600
    const val ERR_METHOD_NOT_FOUND: Int = -32601
    const val ERR_INVALID_PARAMS: Int = -32602
    const val ERR_INTERNAL: Int = -32603
    const val ERR_NOT_INITIALIZED: Int = -32001

    private val SHUTDOWN_SENTINEL = ByteArray(0)
  }
}

// ---------------------------------------------------------------------------
// LSP-style Content-Length framer (~50 LOC, hand-rolled per PROTOCOL.md § 1).
// ---------------------------------------------------------------------------

internal class FramingException(message: String) : IOException(message)

internal class ContentLengthFramer(private val input: InputStream) {

  /**
   * Reads one framed message, returning its UTF-8 payload bytes. Returns null
   * on clean EOF (i.e. end-of-stream observed at a frame boundary).
   *
   * Header parsing tolerates `\r\n` or `\n` line endings, ignores
   * `Content-Type` and any other headers, and treats a missing
   * `Content-Length` as a [FramingException].
   */
  fun readFrame(): ByteArray? {
    var contentLength = -1
    val headerBuf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(headerBuf) ?: return if (sawAny) {
        throw FramingException("EOF in headers")
      } else {
        null
      }
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) throw FramingException("malformed header line: '$line'")
      val name = line.substring(0, colon).trim()
      val value = line.substring(colon + 1).trim()
      if (name.equals("Content-Length", ignoreCase = true)) {
        contentLength =
          value.toIntOrNull() ?: throw FramingException("non-integer Content-Length: '$value'")
        if (contentLength < 0) throw FramingException("negative Content-Length: $contentLength")
      }
      // Other headers (Content-Type, etc.) are explicitly ignored per
      // PROTOCOL.md § 1.
    }
    if (contentLength < 0) throw FramingException("missing Content-Length header")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) throw FramingException("EOF mid-payload after $off/$contentLength bytes")
      off += n
    }
    return payload
  }

  private fun readHeaderLine(buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) {
        return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      }
      if (b == '\n'.code) {
        // Strip trailing \r if present.
        val bytes = buf.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
                  else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }
}

// ---------------------------------------------------------------------------
// Tiny JSON object builder — avoids a kotlinx-serialization dependency on
// `kotlinx.serialization.json.buildJsonObject` import noise above. Kept local
// so the imports list at the top of the file stays compact.
// ---------------------------------------------------------------------------

private fun buildJsonObject(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
  val map = LinkedHashMap<String, JsonElement>()
  map.block()
  return JsonObject(map)
}
