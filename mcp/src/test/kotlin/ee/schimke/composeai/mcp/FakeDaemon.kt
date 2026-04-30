package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Test-only fake daemon. Speaks the daemon protocol (PROTOCOL.md) over piped streams so the MCP
 * server can talk to it without a real subprocess. Just enough behavior to exercise:
 *
 * - `initialize` → returns a stub [InitializeResult] with empty capabilities.
 * - `renderNow(previews)` → returns `queued = previews` and emits a [`renderFinished`][..]
 *   notification for every queued id with a synthetic png path.
 * - `setVisible` / `setFocus` → recorded so tests can assert watch propagation.
 * - `shutdown` → returns null result; subsequent `exit` causes the reader to drain.
 *
 * The fake also lets the test push a [`discoveryUpdated`][..] notification on demand to trigger the
 * MCP server's catalog update + resources/list_changed signal.
 */
class FakeDaemon : DaemonSpawn {

  private val mcpToDaemon = PipedOutputStream()
  private val daemonReadIn = PipedInputStream(mcpToDaemon, BUFFER)
  private val daemonToMcp = PipedOutputStream()
  private val mcpReadIn = PipedInputStream(daemonToMcp, BUFFER)

  private val running = AtomicBoolean(true)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  /** Sent by the fake's reader when the MCP shim issues `setVisible`. Tests assert ordering. */
  val visibleSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** Same for `setFocus`. */
  val focusSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** `renderNow` invocations the fake observed. */
  val renderRequests = java.util.concurrent.LinkedBlockingQueue<List<String>>()

  /**
   * When set, the fake auto-emits a `renderFinished` notification for every preview id in an
   * incoming `renderNow` whose path the lambda returns non-null. Removes the
   * spawn-a-thread-and-poll-renderRequests race in tests that just want "render → bytes back".
   */
  @Volatile var autoRenderPngPath: ((previewId: String) -> String?)? = null

  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  init {
    Thread({ runDaemonReader() }, "fake-daemon-reader").apply { isDaemon = true }.start()
  }

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = mcpReadIn,
        output = mcpToDaemon,
        onNotification = onNotification,
        onClose = onClose,
        threadName = "mcp-fake-daemon-client",
      )
    return _client
  }

  override fun shutdown() {
    if (!running.compareAndSet(true, false)) return
    runCatching { daemonToMcp.close() }
    runCatching { daemonReadIn.close() }
  }

  /** Pushes a `discoveryUpdated` notification with one preview added. Test helper. */
  fun emitDiscovery(previewId: String, displayName: String = previewId) {
    val params = buildJsonObject {
      putJsonArray("added") {
        add(
          buildJsonObject {
            put("id", previewId)
            put("className", previewId.substringBeforeLast('.'))
            put("methodName", previewId.substringAfterLast('.'))
            put("displayName", displayName)
          }
        )
      }
      putJsonArray("removed") {}
      putJsonArray("changed") {}
      put("totalPreviews", 1)
    }
    sendNotification("discoveryUpdated", params)
  }

  /** Pushes a `renderFinished` notification. Returns the synthetic pngPath emitted. */
  fun emitRenderFinished(previewId: String, pngPath: String): String {
    val params = buildJsonObject {
      put("id", previewId)
      put("pngPath", pngPath)
      put("tookMs", 50L)
    }
    sendNotification("renderFinished", params)
    return pngPath
  }

  // -------------------------------------------------------------------------
  // Daemon-side reader: read framed JSON-RPC from the MCP shim, respond.
  // -------------------------------------------------------------------------

  private fun runDaemonReader() {
    try {
      while (running.get()) {
        val frame = readFrame(daemonReadIn) ?: return
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val responseId = obj["id"]?.jsonPrimitive?.long
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val params = obj["params"] as? JsonObject
        if (responseId != null && method != null) {
          handleRequest(responseId, method, params)
        } else if (method != null) {
          handleNotification(method, params)
        }
      }
    } catch (_: IOException) {
      // EOF
    }
  }

  private fun handleRequest(id: Long, method: String, params: JsonObject?) {
    when (method) {
      "initialize" -> {
        val result =
          InitializeResult(
            protocolVersion = 1,
            daemonVersion = "fake",
            pid = 0,
            capabilities =
              ServerCapabilities(
                incrementalDiscovery = true,
                sandboxRecycle = false,
                leakDetection = emptyList(),
              ),
            classpathFingerprint = "fake-fingerprint",
            manifest = Manifest(path = "", previewCount = 0),
          )
        sendResponse(id, json.encodeToJsonElement(InitializeResult.serializer(), result))
      }
      "renderNow" -> {
        val previews =
          (params?.get("previews") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        renderRequests.offer(previews)
        val result = RenderNowResult(queued = previews, rejected = emptyList())
        sendResponse(id, json.encodeToJsonElement(RenderNowResult.serializer(), result))
        // Auto-emit renderFinished for any preview whose path the test pre-registered. The
        // emission happens AFTER the response so the daemon-protocol ordering matches what a
        // real backend produces (queued → started → finished).
        autoRenderPngPath?.let { provider ->
          previews.forEach { pid ->
            val path = provider(pid)
            if (path != null) emitRenderFinished(pid, path)
          }
        }
      }
      "shutdown" -> {
        sendResponse(id, kotlinx.serialization.json.JsonNull)
      }
      else -> {
        // Unknown methods: error response so the client doesn't hang.
        sendError(id, -32601, "method not found: $method")
      }
    }
  }

  private fun handleNotification(method: String, params: JsonObject?) {
    when (method) {
      "initialized" -> {}
      "setVisible" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        visibleSets.offer(ids)
      }
      "setFocus" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        focusSets.offer(ids)
      }
      "exit" -> running.set(false)
      else -> {}
    }
  }

  private fun sendResponse(id: Long, result: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    sendFrame(payload.toString())
  }

  private fun sendError(id: Long, code: Int, message: String) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendNotification(method: String, params: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    }
    sendFrame(payload.toString())
  }

  private fun sendFrame(jsonText: String) {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(daemonToMcp) {
      daemonToMcp.write(header)
      daemonToMcp.write(bytes)
      daemonToMcp.flush()
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, buf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header line: '$line'")
      if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
        contentLength = line.substring(colon + 1).trim().toInt()
      }
    }
    if (contentLength < 0) error("missing Content-Length")
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

  companion object {
    private const val BUFFER = 64 * 1024
  }
}

/** Test [DaemonClientFactory] that always returns a [FakeDaemon]. */
class FakeDaemonClientFactory : DaemonClientFactory {
  /** Map of (workspaceId, modulePath) → fake. Tests assert/manipulate via this. */
  val daemons: MutableMap<Pair<WorkspaceId, String>, FakeDaemon> = mutableMapOf()

  override fun spawn(project: RegisteredProject, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    val daemon = FakeDaemon()
    daemons[project.workspaceId to descriptor.modulePath] = daemon
    return daemon
  }
}

/** Test [DescriptorProvider] returning a stub descriptor for any module. */
class FakeDescriptorProvider : DescriptorProvider {
  override fun descriptorFor(
    project: RegisteredProject,
    modulePath: String,
  ): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor(
      schemaVersion = 1,
      modulePath = modulePath,
      variant = "debug",
      enabled = true,
      mainClass = "fake.Main",
      classpath = emptyList(),
      jvmArgs = emptyList(),
      systemProperties = emptyMap(),
      workingDirectory = project.path.absolutePath,
      manifestPath = "",
    )
}
