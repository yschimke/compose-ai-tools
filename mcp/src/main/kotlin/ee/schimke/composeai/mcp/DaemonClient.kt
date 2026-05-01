package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.ClientCapabilities
import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataSubscribeParams
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffParams
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadParams
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.protocol.Options
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RenderNowParams
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.SetFocusParams
import ee.schimke.composeai.daemon.protocol.SetVisibleParams
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A JSON-RPC client over `Content-Length`-framed stdio against a single daemon JVM, paired with a
 * callback-driven notification stream. The structure mirrors
 * [`HarnessClient`][ee.schimke.composeai.daemon.harness.HarnessClient] from `:daemon:harness`, with
 * two changes:
 *
 * 1. The transport is taken as `(InputStream, OutputStream)` rather than wrapping a [Process], so
 *    tests can drive the client over an in-memory pipe without a subprocess.
 * 2. Notifications are dispatched via [onNotification] rather than queued for caller polling — the
 *    MCP supervisor consumes every notification (`renderFinished` → push update; `discoveryUpdated`
 *    → list_changed; `classpathDirty` → respawn) so we don't need the predicate-poll API the
 *    harness uses for assertions.
 */
class DaemonClient(
  private val input: InputStream,
  private val output: OutputStream,
  /** Notification sink. Called from the reader thread; handlers must not block. */
  private val onNotification: (method: String, params: JsonObject?) -> Unit,
  /** Optional: invoked when the read thread observes EOF. Supervisor uses this for respawn. */
  private val onClose: () -> Unit = {},
  threadName: String = "mcp-daemon-client-reader",
) : Closeable {

  private val json = Json { ignoreUnknownKeys = true }
  private val nextId = AtomicLong(1)
  private val responseSlots = ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()
  private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
  private val readerThread =
    Thread({ runReader() }, threadName).apply {
      isDaemon = true
      start()
    }

  /** Drives `initialize` + `initialized`. Returns the daemon's [InitializeResult]. */
  fun initialize(
    workspaceRoot: String,
    moduleId: String,
    moduleProjectDir: String,
    capabilities: ClientCapabilities = ClientCapabilities(visibility = true, metrics = true),
    attachDataProducts: List<String>? = null,
    timeout: Duration = 30.seconds,
  ): InitializeResult {
    val id = nextId.getAndIncrement()
    val params =
      InitializeParams(
        protocolVersion = 1,
        clientVersion = "compose-preview-mcp/v0",
        workspaceRoot = workspaceRoot,
        moduleId = moduleId,
        moduleProjectDir = moduleProjectDir,
        capabilities = capabilities,
        // D1 — only attach the option when the caller actually asked for ambient kinds; pre-D2
        // daemons advertise nothing, so the daemon-side filter would silently drop these anyway,
        // but keeping the field absent on `null` matches the `encodeDefaults = false` shape the
        // rest of the protocol fixtures use.
        options =
          if (attachDataProducts.isNullOrEmpty()) null
          else Options(attachDataProducts = attachDataProducts),
      )
    val request =
      JsonRpcRequest(
        id = id,
        method = "initialize",
        params = json.encodeToJsonElement(InitializeParams.serializer(), params),
      )
    val response = sendAndAwait(id, request, timeout)
    val resultElem =
      response["result"]
        ?: error("initialize: no result — error=${response["error"]}, full=$response")
    val result = json.decodeFromJsonElement(InitializeResult.serializer(), resultElem)
    sendNotification("initialized", JsonObject(emptyMap()))
    return result
  }

  fun setVisible(ids: List<String>) =
    sendNotification(
      "setVisible",
      json.encodeToJsonElement(SetVisibleParams.serializer(), SetVisibleParams(ids = ids)),
    )

  fun setFocus(ids: List<String>) =
    sendNotification(
      "setFocus",
      json.encodeToJsonElement(SetFocusParams.serializer(), SetFocusParams(ids = ids)),
    )

  fun fileChanged(
    path: String,
    kind: FileKind = FileKind.SOURCE,
    changeType: ChangeType = ChangeType.MODIFIED,
  ) =
    sendNotification(
      "fileChanged",
      json.encodeToJsonElement(
        FileChangedParams.serializer(),
        FileChangedParams(path = path, kind = kind, changeType = changeType),
      ),
    )

  /** Drives `renderNow` for the given preview ids at the given [tier]. */
  fun renderNow(
    previews: List<String>,
    tier: RenderTier = RenderTier.FULL,
    reason: String? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RenderNowResult {
    val id = nextId.getAndIncrement()
    val params =
      RenderNowParams(previews = previews, tier = tier, reason = reason, overrides = overrides)
    val request =
      JsonRpcRequest(
        id = id,
        method = "renderNow",
        params = json.encodeToJsonElement(RenderNowParams.serializer(), params),
      )
    val response = sendAndAwait(id, request, timeout)
    val resultElem =
      response["result"]
        ?: error("renderNow: no result — error=${response["error"]}, full=$response")
    return json.decodeFromJsonElement(RenderNowResult.serializer(), resultElem)
  }

  // ---------------------------------------------------------------------------
  // History (H2 / H3) — see PROTOCOL.md § 5 (`history/list` / `history/read` /
  // `history/diff`). The MCP server's history mapping (H6) calls these.
  // ---------------------------------------------------------------------------

  /** Drives `history/list`. Default no-filter call returns recent entries across all previews. */
  fun historyList(
    params: HistoryListParams = HistoryListParams(),
    timeout: Duration = 30.seconds,
  ): HistoryListResult {
    val id = nextId.getAndIncrement()
    val request =
      JsonRpcRequest(
        id = id,
        method = "history/list",
        params = json.encodeToJsonElement(HistoryListParams.serializer(), params),
      )
    val response = sendAndAwait(id, request, timeout)
    val resultElem =
      response["result"]
        ?: error("history/list: no result — error=${response["error"]}, full=$response")
    return json.decodeFromJsonElement(HistoryListResult.serializer(), resultElem)
  }

  /** Drives `history/read`. With [inline] = true the daemon returns base64 PNG bytes inline. */
  fun historyRead(
    entryId: String,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): HistoryReadResultDto {
    val id = nextId.getAndIncrement()
    val params = HistoryReadParams(id = entryId, inline = inline)
    val request =
      JsonRpcRequest(
        id = id,
        method = "history/read",
        params = json.encodeToJsonElement(HistoryReadParams.serializer(), params),
      )
    val response = sendAndAwait(id, request, timeout)
    val resultElem =
      response["result"]
        ?: error("history/read: no result — error=${response["error"]}, full=$response")
    return json.decodeFromJsonElement(HistoryReadResultDto.serializer(), resultElem)
  }

  /** Drives `history/diff` (metadata mode by default). */
  fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode = HistoryDiffMode.METADATA,
    timeout: Duration = 30.seconds,
  ): HistoryDiffResult {
    val id = nextId.getAndIncrement()
    val params = HistoryDiffParams(from = fromId, to = toId, mode = mode)
    val request =
      JsonRpcRequest(
        id = id,
        method = "history/diff",
        params = json.encodeToJsonElement(HistoryDiffParams.serializer(), params),
      )
    val response = sendAndAwait(id, request, timeout)
    val resultElem =
      response["result"]
        ?: error("history/diff: no result — error=${response["error"]}, full=$response")
    return json.decodeFromJsonElement(HistoryDiffResult.serializer(), resultElem)
  }

  // ---------------------------------------------------------------------------
  // D1 — data products. See docs/daemon/DATA-PRODUCTS.md § "Wire surface".
  // ---------------------------------------------------------------------------

  /**
   * Drives `data/fetch` for one `(previewId, kind)` pair against the latest render. The daemon
   * resolves to one of:
   *
   * - already-computed payload from the last render (cheap),
   * - a re-projection against the cached state (bounded cost),
   * - a re-render in the right mode then the payload (subject to the daemon's per-request budget).
   *
   * Errors are surfaced as exceptions rather than swallowed: the MCP-side caller wraps them into
   * the tool result so the agent sees the exact wire-error name (`DataProductUnknown`,
   * `DataProductNotAvailable`, `DataProductFetchFailed`, `DataProductBudgetExceeded`).
   */
  fun dataFetch(
    previewId: String,
    kind: String,
    params: kotlinx.serialization.json.JsonElement? = null,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): DataFetchResult {
    val id = nextId.getAndIncrement()
    val request =
      JsonRpcRequest(
        id = id,
        method = "data/fetch",
        params =
          json.encodeToJsonElement(
            DataFetchParams.serializer(),
            DataFetchParams(previewId = previewId, kind = kind, params = params, inline = inline),
          ),
      )
    val response = sendAndAwait(id, request, timeout)
    val errorElem = response["error"]
    if (errorElem != null) error("data/fetch failed: $errorElem")
    val resultElem = response["result"] ?: error("data/fetch: no result — full=$response")
    return json.decodeFromJsonElement(DataFetchResult.serializer(), resultElem)
  }

  /**
   * Drives `data/subscribe`. Idempotent on the daemon side; subscriptions are sticky-while-visible
   * — the daemon drops them automatically when the preview leaves the most recent `setVisible` set
   * (see DATA-PRODUCTS.md). Re-subscribe when the preview returns to view.
   */
  fun dataSubscribe(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult = dataSubOrUnsub("data/subscribe", previewId, kind, timeout)

  /** Drives `data/unsubscribe`. See [dataSubscribe]. */
  fun dataUnsubscribe(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult = dataSubOrUnsub("data/unsubscribe", previewId, kind, timeout)

  private fun dataSubOrUnsub(
    method: String,
    previewId: String,
    kind: String,
    timeout: Duration,
  ): DataSubscribeResult {
    val id = nextId.getAndIncrement()
    val request =
      JsonRpcRequest(
        id = id,
        method = method,
        params =
          json.encodeToJsonElement(
            DataSubscribeParams.serializer(),
            DataSubscribeParams(previewId = previewId, kind = kind),
          ),
      )
    val response = sendAndAwait(id, request, timeout)
    val errorElem = response["error"]
    if (errorElem != null) error("$method failed: $errorElem")
    val resultElem = response["result"] ?: error("$method: no result — full=$response")
    return json.decodeFromJsonElement(DataSubscribeResult.serializer(), resultElem)
  }

  /**
   * Sends `shutdown` (drains in-flight renders) then `exit`. Does not wait for process exit — the
   * [DaemonSpawn] owner does that.
   */
  fun shutdownAndExit(timeout: Duration = 15.seconds) {
    if (closed.get()) return
    runCatching {
      val id = nextId.getAndIncrement()
      val request = JsonRpcRequest(id = id, method = "shutdown", params = JsonNull)
      sendAndAwait(id, request, timeout)
    }
    runCatching { sendNotification("exit", JsonNull) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { input.close() }
    runCatching { output.close() }
    readerThread.join(2_000)
  }

  // -------------------------------------------------------------------------
  // Internals — framing + reader
  // -------------------------------------------------------------------------

  private fun sendAndAwait(id: Long, request: JsonRpcRequest, timeout: Duration): JsonObject {
    val slot = responseSlots.computeIfAbsent(id) { LinkedBlockingQueue() }
    sendFrame(json.encodeToString(JsonRpcRequest.serializer(), request))
    val response = slot.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    responseSlots.remove(id)
    return response
      ?: error(
        "DaemonClient: response for id=$id (method=${request.method}) timed out after $timeout"
      )
  }

  private fun sendNotification(method: String, params: kotlinx.serialization.json.JsonElement) {
    val n = JsonRpcNotification(method = method, params = params)
    sendFrame(json.encodeToString(JsonRpcNotification.serializer(), n))
  }

  private fun sendFrame(jsonText: String) {
    val payload = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(output) {
      output.write(header)
      output.write(payload)
      output.flush()
    }
  }

  private fun runReader() {
    try {
      while (!closed.get()) {
        val frame = readFrame(input) ?: break
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val responseId = obj["id"]?.jsonPrimitive?.long
        if (responseId != null) {
          responseSlots.computeIfAbsent(responseId) { LinkedBlockingQueue() }.put(obj)
        } else {
          val method = obj["method"]?.jsonPrimitive?.contentOrNull
          if (method != null) {
            val params = obj["params"]?.jsonObject
            runCatching { onNotification(method, params) }
          }
        }
      }
    } catch (_: IOException) {
      // EOF — fall through to onClose.
    } catch (e: Throwable) {
      System.err.println("DaemonClient reader: ${e.message}")
      e.printStackTrace(System.err)
    } finally {
      runCatching { onClose() }
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val headerBuf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, headerBuf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed daemon header line: '$line'")
      val name = line.substring(0, colon).trim()
      val value = line.substring(colon + 1).trim()
      if (name.equals("Content-Length", ignoreCase = true)) {
        contentLength = value.toIntOrNull() ?: error("non-integer Content-Length: '$value'")
      }
    }
    if (contentLength < 0) error("missing Content-Length header")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) return null
      off += n
    }
    return payload
  }

  private fun readHeaderLine(input: InputStream, buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      if (b == '\n'.code) {
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }
}
