package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolParams
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.Implementation
import ee.schimke.composeai.mcp.protocol.InitializeParams
import ee.schimke.composeai.mcp.protocol.InitializeResult
import ee.schimke.composeai.mcp.protocol.ListResourcesResult
import ee.schimke.composeai.mcp.protocol.ListToolsResult
import ee.schimke.composeai.mcp.protocol.McpError
import ee.schimke.composeai.mcp.protocol.McpErrorCodes
import ee.schimke.composeai.mcp.protocol.McpNotification
import ee.schimke.composeai.mcp.protocol.McpResponse
import ee.schimke.composeai.mcp.protocol.ReadResourceParams
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ResourceUpdatedParams
import ee.schimke.composeai.mcp.protocol.ServerCapabilities
import ee.schimke.composeai.mcp.protocol.SubscribeParams
import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.mcp.protocol.UnsubscribeParams
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single MCP session over `Content-Length`-framed stdio (matching MCP's stdio transport spec).
 *
 * Conceptually this is one connected MCP client. v0 ships one session per server process; HTTP
 * transport will multiplex many. Sessions own:
 *
 * - A read thread that drains [input] and dispatches request frames to [handlers].
 * - A write thread that serialises every reply + outgoing notification to [output].
 * - The set of subscribed URIs and registered watch entries (stored externally in [subscriptions],
 *   keyed by `this` reference so different sessions stay isolated).
 *
 * Method handlers are pluggable — [DaemonMcpServer] wires the resource/tool surface; tests can
 * register their own handlers for protocol conformance assertions.
 */
class McpSession(
  private val input: InputStream,
  private val output: OutputStream,
  private val handlers: McpHandlers,
  private val serverInfo: Implementation,
  private val capabilities: ServerCapabilities,
  threadName: String = "mcp-session",
) : Closeable {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }
  private val closed = AtomicBoolean(false)
  private val initialized = AtomicBoolean(false)

  private val readerThread = Thread({ runReader() }, "$threadName-reader").apply { isDaemon = true }

  fun start() {
    readerThread.start()
  }

  /** Blocks until the reader thread exits (typically on stdin EOF). Used by stdio entry points. */
  fun awaitClose() {
    readerThread.join()
  }

  /** Sends a JSON-RPC notification. Safe to call from any thread; framed atomically on the wire. */
  fun notify(method: String, params: JsonElement? = null) {
    if (closed.get()) return
    val n = McpNotification(method = method, params = params)
    sendFrame(json.encodeToString(McpNotification.serializer(), n))
  }

  /** Sends `notifications/resources/updated` for [uri]. */
  fun notifyResourceUpdated(uri: String) {
    val params =
      json.encodeToJsonElement(ResourceUpdatedParams.serializer(), ResourceUpdatedParams(uri))
    notify("notifications/resources/updated", params)
  }

  /** Sends `notifications/resources/list_changed`. */
  fun notifyResourceListChanged() {
    notify("notifications/resources/list_changed")
  }

  /**
   * Sends a `notifications/progress` per the MCP spec:
   * https://modelcontextprotocol.io/specification/2025-06-18/basic#progress-notifications.
   *
   * Only meaningful when the originating request opted in via `_meta.progressToken` in its params;
   * the [token] argument is the verbatim value the client sent. [progress] is monotonic (caller's
   * responsibility); [total] is optional and may be unknown for streaming work.
   */
  fun notifyProgress(
    token: kotlinx.serialization.json.JsonElement,
    progress: Double,
    total: Double? = null,
    message: String? = null,
  ) {
    val params =
      kotlinx.serialization.json.buildJsonObject {
        put("progressToken", token)
        put("progress", kotlinx.serialization.json.JsonPrimitive(progress))
        if (total != null) put("total", kotlinx.serialization.json.JsonPrimitive(total))
        if (message != null) put("message", kotlinx.serialization.json.JsonPrimitive(message))
      }
    notify("notifications/progress", params)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { input.close() }
    runCatching { output.close() }
    readerThread.join(2_000)
  }

  // -------------------------------------------------------------------------
  // Read loop
  // -------------------------------------------------------------------------

  private fun runReader() {
    try {
      while (!closed.get()) {
        val frame = readFrame(input) ?: break
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        if (obj["id"] != null) dispatchRequest(obj) else dispatchNotification(obj)
      }
    } catch (_: IOException) {
      // EOF — fall through
    } catch (e: Throwable) {
      System.err.println("McpSession reader: ${e.message}")
      e.printStackTrace(System.err)
    } finally {
      runCatching { handlers.onClose(this) }
    }
  }

  private fun dispatchRequest(obj: JsonObject) {
    val id = obj["id"] ?: return
    val method = obj["method"]?.jsonPrimitive?.contentOrNull
    val params = obj["params"]
    if (method == null) {
      sendError(id, McpErrorCodes.INVALID_REQUEST, "missing method")
      return
    }
    if (!initialized.get() && method != "initialize") {
      sendError(id, McpErrorCodes.INVALID_REQUEST, "session not initialized (received $method)")
      return
    }
    try {
      when (method) {
        "initialize" -> handleInitialize(id, params)
        "ping" -> sendResult(id, JsonObject(emptyMap()))
        "tools/list" -> sendResult(id, handlers.listTools(this))
        "tools/call" -> handleCallTool(id, params)
        "resources/list" -> sendResult(id, handlers.listResources(this))
        "resources/read" -> handleReadResource(id, params)
        "resources/subscribe" -> handleSubscribe(id, params)
        "resources/unsubscribe" -> handleUnsubscribe(id, params)
        else -> sendError(id, McpErrorCodes.METHOD_NOT_FOUND, "unknown method: $method")
      }
    } catch (e: Throwable) {
      sendError(id, McpErrorCodes.INTERNAL_ERROR, e.message ?: "internal error")
    }
  }

  private fun dispatchNotification(obj: JsonObject) {
    val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
    when (method) {
      "notifications/initialized" -> initialized.set(true)
      "notifications/cancelled" -> {
        /* v0 ignores cancellation */
      }
      else -> {
        /* unknown notifications ignored per spec */
      }
    }
  }

  private fun handleInitialize(id: JsonElement, params: JsonElement?) {
    val parsed = params?.let {
      runCatching { json.decodeFromJsonElement(InitializeParams.serializer(), it) }.getOrNull()
    }
    val protocolVersion = parsed?.protocolVersion ?: "2025-06-18"
    val result =
      InitializeResult(
        protocolVersion = protocolVersion,
        capabilities = capabilities,
        serverInfo = serverInfo,
      )
    sendResult(id, json.encodeToJsonElement(InitializeResult.serializer(), result))
    // Some clients omit the `notifications/initialized` follow-up; treat the response as a
    // gating signal so subsequent requests don't error out. The notification path still flips
    // the flag if it arrives.
    initialized.set(true)
  }

  private fun handleCallTool(id: JsonElement, params: JsonElement?) {
    val parsed =
      params?.let {
        runCatching { json.decodeFromJsonElement(CallToolParams.serializer(), it) }.getOrNull()
      } ?: return sendError(id, McpErrorCodes.INVALID_PARAMS, "tools/call: missing params")
    val result = handlers.callTool(this, parsed.name, parsed.arguments)
    sendResult(id, json.encodeToJsonElement(CallToolResult.serializer(), result))
  }

  private fun handleReadResource(id: JsonElement, params: JsonElement?) {
    val parsed =
      params?.let {
        runCatching { json.decodeFromJsonElement(ReadResourceParams.serializer(), it) }.getOrNull()
      } ?: return sendError(id, McpErrorCodes.INVALID_PARAMS, "resources/read: missing uri")
    // Per MCP spec, clients opt in to progress notifications by including a
    // `_meta.progressToken` (string | number) on the request's params. Pull it out so the
    // handler can fire periodic `notifications/progress` while a slow render runs.
    val progressToken =
      (params as? JsonObject)?.get("_meta")?.let { it as? JsonObject }?.get("progressToken")
    val result = handlers.readResource(this, parsed.uri, progressToken)
    sendResult(id, json.encodeToJsonElement(ReadResourceResult.serializer(), result))
  }

  private fun handleSubscribe(id: JsonElement, params: JsonElement?) {
    val parsed =
      params?.let {
        runCatching { json.decodeFromJsonElement(SubscribeParams.serializer(), it) }.getOrNull()
      } ?: return sendError(id, McpErrorCodes.INVALID_PARAMS, "resources/subscribe: missing uri")
    handlers.subscribe(this, parsed.uri)
    sendResult(id, JsonObject(emptyMap()))
  }

  private fun handleUnsubscribe(id: JsonElement, params: JsonElement?) {
    val parsed =
      params?.let {
        runCatching { json.decodeFromJsonElement(UnsubscribeParams.serializer(), it) }.getOrNull()
      } ?: return sendError(id, McpErrorCodes.INVALID_PARAMS, "resources/unsubscribe: missing uri")
    handlers.unsubscribe(this, parsed.uri)
    sendResult(id, JsonObject(emptyMap()))
  }

  // -------------------------------------------------------------------------
  // Wire helpers
  // -------------------------------------------------------------------------

  private fun sendResult(id: JsonElement, result: JsonElement) {
    val response = McpResponse(id = id, result = result)
    sendFrame(json.encodeToString(McpResponse.serializer(), response))
  }

  private fun sendError(id: JsonElement, code: Int, message: String) {
    val response = McpResponse(id = id, error = McpError(code = code, message = message))
    sendFrame(json.encodeToString(McpResponse.serializer(), response))
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

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val headerBuf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, headerBuf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header line: '$line'")
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

/**
 * Pluggable handler set the [McpSession] dispatches to. [DaemonMcpServer] supplies the concrete
 * instance; tests can stub it for protocol-level assertions without dragging in a supervisor.
 *
 * Every method is called from the session's reader thread. Implementations must not block on
 * unrelated IO — the daemon's render path is already off-thread, so the main concern is keeping the
 * reader pumping.
 */
interface McpHandlers {
  fun listTools(session: McpSession): JsonElement

  fun callTool(session: McpSession, name: String, arguments: JsonElement?): CallToolResult

  fun listResources(session: McpSession): JsonElement

  /**
   * Reads the resource at [uri]. [progressToken], when non-null, is the client's opt-in handle for
   * `notifications/progress` — implementations that perform slow work should fan out periodic
   * progress notifications via [McpSession.notifyProgress] using this token.
   */
  fun readResource(
    session: McpSession,
    uri: String,
    progressToken: JsonElement? = null,
  ): ReadResourceResult

  fun subscribe(session: McpSession, uri: String)

  fun unsubscribe(session: McpSession, uri: String)

  fun onClose(session: McpSession)

  companion object {
    /**
     * Sentinel: encode a [ListToolsResult] / [ListResourcesResult] for [listTools] /
     * [listResources].
     */
    fun encodeTools(json: Json, tools: List<ToolDef>): JsonElement =
      json.encodeToJsonElement(ListToolsResult.serializer(), ListToolsResult(tools))

    fun encodeResources(json: Json, resources: List<ResourceDescriptor>): JsonElement =
      json.encodeToJsonElement(ListResourcesResult.serializer(), ListResourcesResult(resources))
  }
}

/**
 * Convenience: plain text response — every tool that just confirms an action ("watched", "ok") uses
 * this.
 */
fun textCallToolResult(text: String): CallToolResult =
  CallToolResult(content = listOf(ContentBlock.Text(text)))

/** Convenience: image PNG response, base64-encoded data. */
fun pngCallToolResult(bytesBase64: String): CallToolResult =
  CallToolResult(content = listOf(ContentBlock.Image(data = bytesBase64, mimeType = "image/png")))

/** Convenience: error response — `isError = true` per MCP spec for tool-level errors. */
fun errorCallToolResult(message: String): CallToolResult =
  CallToolResult(content = listOf(ContentBlock.Text(message)), isError = true)

/** Tracks every live [McpSession] so notifications can fan out to multiple connected clients. */
class SessionRegistry {
  private val sessions = ConcurrentHashMap.newKeySet<McpSession>()

  fun register(session: McpSession) {
    sessions.add(session)
  }

  fun unregister(session: McpSession) {
    sessions.remove(session)
  }

  fun forEach(block: (McpSession) -> Unit) {
    sessions.forEach { runCatching { block(it) } }
  }
}
