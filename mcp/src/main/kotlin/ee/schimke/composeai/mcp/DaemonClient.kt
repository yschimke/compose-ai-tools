package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.ClientCapabilities
import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
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
    timeout: Duration = 30.seconds,
  ): RenderNowResult {
    val id = nextId.getAndIncrement()
    val params = RenderNowParams(previews = previews, tier = tier, reason = reason)
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
