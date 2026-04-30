package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.ListToolsResult
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end test of the MCP server: drives an [McpSession] via in-memory pipes with a synthetic
 * MCP client and a [FakeDaemon]. Asserts the load-bearing behaviors:
 *
 * 1. `initialize` succeeds and `tools/list` returns the expected tool surface.
 * 2. `register_project` returns a workspace id.
 * 3. `discoveryUpdated` from a fake daemon → `notifications/resources/list_changed` to the client.
 * 4. `resources/list` returns the catalog.
 * 5. `resources/subscribe` + fake daemon `renderFinished` → `notifications/resources/updated`.
 * 6. The `watch` tool propagates `setVisible`/`setFocus` to the matched fake daemon.
 * 7. `resources/read` round-trips through `renderNow` → `renderFinished` and returns base64 PNG.
 */
class DaemonMcpServerTest {

  @get:Rule val tmp = TemporaryFolder()

  private lateinit var supervisor: DaemonSupervisor
  private lateinit var factory: FakeDaemonClientFactory
  private lateinit var server: DaemonMcpServer
  private lateinit var client: McpTestClient
  private lateinit var session: McpSession

  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    factory = FakeDaemonClientFactory()
    supervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
    server = DaemonMcpServer(supervisor)

    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
  }

  @After
  fun tearDown() {
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { supervisor.shutdown() }
  }

  @Test
  fun `initialize and tools list returns expected tool surface`() {
    val initResult = client.initialize()
    val caps = initResult["capabilities"]?.jsonObject
    assertThat(caps?.get("resources")?.jsonObject?.get("subscribe")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("true")

    val toolsResult = client.request("tools/list")
    val tools = json.decodeFromJsonElement(ListToolsResult.serializer(), toolsResult)
    val names = tools.tools.map { it.name }.toSet()
    assertThat(names)
      .containsExactly(
        "register_project",
        "unregister_project",
        "list_projects",
        "render_preview",
        "watch",
        "unwatch",
        "list_watches",
        "notify_file_changed",
        "history_list",
        "history_diff",
      )
  }

  @Test
  fun `register_project returns workspaceId and notifies list_changed`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val callResult =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", projectDir.absolutePath)
          put("rootProjectName", "test-project")
        },
      )
    val text = callResult.firstTextContent()
    val payload = json.parseToJsonElement(text).jsonObject
    assertThat(payload["workspaceId"]?.jsonPrimitive?.contentOrNull).startsWith("test-project-")
    val n = client.expectNotification("notifications/resources/list_changed", 2_000)
    assertThat(n.method).isEqualTo("notifications/resources/list_changed")
  }

  @Test
  fun `subscribe receives resources updated push when daemon emits renderFinished`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    val previewId = "com.example.Red"
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val expectedUri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.notifyOnly("resources/subscribe", buildJsonObject { put("uri", expectedUri) })
    // The subscribe is a request; use callRequest to await the response.
    val subResp = client.request("resources/subscribe", buildJsonObject { put("uri", expectedUri) })
    assertThat(subResp).isInstanceOf(JsonObject::class.java)

    daemon.emitRenderFinished(previewId, "/tmp/fake.png")
    val update = client.expectNotification("notifications/resources/updated", 2_000)
    val updatedUri = update.params?.get("uri")?.jsonPrimitive?.contentOrNull
    assertThat(updatedUri).isEqualTo(expectedUri)
  }

  @Test
  fun `watch propagates setVisible and setFocus to matching daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    daemon.emitDiscovery("com.example.Blue")
    // Drain the two list_changed notifications produced.
    repeat(2) { client.expectNotification("notifications/resources/list_changed", 2_000) }

    // Watch only the Red preview.
    val watchResp =
      client.callTool(
        "watch",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("module", ":module")
          put("fqnGlob", "com.example.Red")
        },
      )
    assertThat(watchResp.firstTextContent()).contains("watching")

    val visible = daemon.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    val focus = daemon.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(visible).isEqualTo(listOf("com.example.Red"))
    assertThat(focus).isEqualTo(listOf("com.example.Red"))
  }

  @Test
  fun `classpathDirty respawn re-issues setVisible to the replacement daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    // First daemon: spawn via watch, register interest, observe initial setVisible.
    val firstDaemon = warmDaemonFor(workspaceId, ":module")
    firstDaemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)
    client.callTool(
      "watch",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("module", ":module")
        put("fqnGlob", "com.example.Red")
      },
    )
    val firstVisible = firstDaemon.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(firstVisible).isEqualTo(listOf("com.example.Red"))

    // Trigger the classpathDirty respawn flow on the first daemon.
    firstDaemon.emitClasspathDirty()

    // Wait for the supervisor's async respawn to construct a second FakeDaemon. The supervisor
    // hands ownership of the new daemon to factory.spawnHistory[1].
    val deadline = System.currentTimeMillis() + 5_000
    while (factory.spawnHistory.size < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    assertThat(factory.spawnHistory.size).isAtLeast(2)
    val secondDaemon = factory.spawnHistory[1]

    // Replacement daemon emits its (newly-rebuilt) discovery — supervisor's
    // `synthesiseInitialDiscovery` would normally do this from the manifest path, but the
    // FakeDescriptorProvider's blank manifestPath skips that, so we drive it manually.
    secondDaemon.emitDiscovery("com.example.Red")

    // Existing watch should re-fire setVisible on the replacement daemon (the propagator's
    // memo for this DaemonKey was forgotten by `onClasspathDirty`, so the same watched set
    // counts as a delta against `previous=null` and is re-sent).
    val secondVisible = secondDaemon.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    val secondFocus = secondDaemon.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(secondVisible).isEqualTo(listOf("com.example.Red"))
    assertThat(secondFocus).isEqualTo(listOf("com.example.Red"))
  }

  @Test
  fun `history_list returns entries decorated with compose-preview-history URIs`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")

    val entry1 = buildJsonObject {
      put("id", "entry-1")
      put("previewId", "com.example.RedSquare")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:00:00Z")
      put("pngHash", "deadbeef")
      put("pngSize", 4218L)
      put("pngPath", "/tmp/r1.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 12L)
    }
    val entry2 = buildJsonObject {
      put("id", "entry-2")
      put("previewId", "com.example.RedSquare")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:01:00Z")
      put("pngHash", "cafef00d")
      put("pngSize", 4218L)
      put("pngPath", "/tmp/r2.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 13L)
    }
    daemon.setHistory(listOf(entry1, entry2))

    val resp =
      client.callTool(
        "history_list",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("module", ":module")
        },
      )
    val text = resp.firstTextContent()
    val parsed = json.parseToJsonElement(text).jsonObject
    assertThat(parsed["totalCount"]?.jsonPrimitive?.content?.toInt()).isEqualTo(2)
    val entries = parsed["entries"]!!.jsonArray
    assertThat(entries).hasSize(2)
    val firstUri = entries[0].jsonObject["resourceUri"]?.jsonPrimitive?.contentOrNull
    assertThat(firstUri)
      .isEqualTo(
        "compose-preview-history://${workspaceId.value}/_module/com.example.RedSquare/entry-1"
      )
  }

  @Test
  fun `history_diff forwards from to ids and decodes pngHashChanged`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")

    val entryA = buildJsonObject {
      put("id", "a")
      put("previewId", "com.example.X")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:00:00Z")
      put("pngHash", "AAAA")
      put("pngSize", 100L)
      put("pngPath", "/tmp/a.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 1L)
    }
    val entryB = buildJsonObject {
      put("id", "b")
      put("previewId", "com.example.X")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:01:00Z")
      put("pngHash", "BBBB")
      put("pngSize", 100L)
      put("pngPath", "/tmp/b.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 1L)
    }
    daemon.setHistory(listOf(entryA, entryB))

    val resp =
      client.callTool(
        "history_diff",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("module", ":module")
          put("from", "a")
          put("to", "b")
        },
      )
    val parsed = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    assertThat(parsed["pngHashChanged"]?.jsonPrimitive?.content?.toBoolean()).isTrue()
    assertThat(parsed["fromMetadata"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("a")
    assertThat(parsed["toMetadata"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("b")
  }

  @Test
  fun `resources read on a compose-preview-history URI returns inline PNG bytes`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")

    val entry = buildJsonObject {
      put("id", "h-1")
      put("previewId", "com.example.X")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:00:00Z")
      put("pngHash", "AAAA")
      put("pngSize", 11L)
      put("pngPath", "/tmp/h-1.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 1L)
    }
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    daemon.setHistory(listOf(entry))
    daemon.setHistoryInlineBytes("h-1", pngBytes)

    val historyUri = HistoryUri(workspaceId, ":module", "com.example.X", "h-1").toUri()
    val resp = client.request("resources/read", buildJsonObject { put("uri", historyUri) })
    val parsed = json.decodeFromJsonElement(ReadResourceResult.serializer(), resp)
    val blob = (parsed.contents.single() as ResourceContents.Blob).blob
    assertThat(java.util.Base64.getDecoder().decode(blob)).isEqualTo(pngBytes)
  }

  @Test
  fun `historyAdded notification triggers resources list_changed push`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")

    val entry = buildJsonObject {
      put("id", "added-1")
      put("previewId", "com.example.X")
      put("module", ":module")
      put("timestamp", "2026-04-30T10:00:00Z")
      put("pngHash", "AAAA")
      put("pngSize", 11L)
      put("pngPath", "/tmp/added.png")
      put("producer", "fake")
      put("trigger", "manual")
      putJsonObject("source") {
        put("kind", "fs")
        put("id", "fs:/tmp/h")
      }
      put("renderTookMs", 1L)
    }
    daemon.emitHistoryAdded(entry)
    val n = client.expectNotification("notifications/resources/list_changed", 2_000)
    assertThat(n.method).isEqualTo("notifications/resources/list_changed")
  }

  @Test
  fun `resources read emits progress notifications when the client opts in`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Slow"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("slow.png")
    Files.write(pngFile.toPath(), pngBytes)

    // Stage the auto-emit but DELAY it for ~700ms so the 500ms progress-beat cadence has time
    // to fire at least once before the renderFinished arrives.
    daemon.autoRenderPngPath = { id ->
      if (id == previewId) {
        Thread.sleep(700)
        pngFile.absolutePath
      } else null
    }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val params = buildJsonObject {
      put("uri", uri)
      // Per MCP spec, `_meta.progressToken` is the client's opt-in handle.
      putJsonObject("_meta") { put("progressToken", JsonPrimitive("read-1")) }
    }
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    val readFuture =
      pool.submit<JsonObject> { client.request("resources/read", params, timeoutMs = 10_000) }

    val progress = client.expectNotification("notifications/progress", 5_000)
    val progressParams = progress.params
    assertThat(progressParams?.get("progressToken")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("read-1")
    // progress is a numeric millisecond elapsed counter
    val progressVal = progressParams?.get("progress")?.jsonPrimitive?.content?.toDouble() ?: 0.0
    assertThat(progressVal).isAtLeast(0.0)

    val readResp = readFuture.get(15, TimeUnit.SECONDS)
    pool.shutdown()
    val parsed = json.decodeFromJsonElement(ReadResourceResult.serializer(), readResp)
    val blob = (parsed.contents.single() as ResourceContents.Blob).blob
    assertThat(java.util.Base64.getDecoder().decode(blob)).isEqualTo(pngBytes)
  }

  @Test
  fun `concurrent resources read calls for the same URI both receive bytes`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    val pngFile = tmp.newFile("fake-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    // Two concurrent reads of the same URI. Pre-dedup-fix this would race on a single queue
    // slot: one waiter would get the bytes, the other would time out. With per-call futures,
    // both complete on the same `renderFinished` (the daemon may emit one or two events
    // depending on internal dedup; either way both waiters wake).
    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    val futA =
      pool.submit<JsonObject> {
        client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
      }
    val futB =
      pool.submit<JsonObject> {
        client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
      }
    val readA = futA.get(15, TimeUnit.SECONDS)
    val readB = futB.get(15, TimeUnit.SECONDS)
    pool.shutdown()

    val parsedA = json.decodeFromJsonElement(ReadResourceResult.serializer(), readA)
    val parsedB = json.decodeFromJsonElement(ReadResourceResult.serializer(), readB)
    val blobA = (parsedA.contents.single() as ResourceContents.Blob).blob
    val blobB = (parsedB.contents.single() as ResourceContents.Blob).blob
    assertThat(java.util.Base64.getDecoder().decode(blobA)).isEqualTo(pngBytes)
    assertThat(java.util.Base64.getDecoder().decode(blobB)).isEqualTo(pngBytes)
  }

  @Test
  fun `resources read round-trips through renderNow and returns base64 PNG`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    // Pre-stage a fake PNG file the renderFinished notification will reference.
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    val pngFile = tmp.newFile("fake-render.png")
    Files.write(pngFile.toPath(), pngBytes)

    // Configure the fake to auto-emit renderFinished whenever it processes a renderNow for this
    // preview id, pointing at the staged PNG. Avoids the spawn-a-thread-and-poll race the earlier
    // test had.
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val response =
      client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
    val parsed = json.decodeFromJsonElement(ReadResourceResult.serializer(), response)
    val blob = parsed.contents.single() as ResourceContents.Blob
    val decoded = Base64.getDecoder().decode(blob.blob)
    assertThat(decoded).isEqualTo(pngBytes)
    assertThat(blob.mimeType).isEqualTo("image/png")
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private fun registerWorkspace(projectDir: java.io.File, rootName: String): WorkspaceId {
    val resp =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", projectDir.absolutePath)
          put("rootProjectName", rootName)
        },
      )
    val text = resp.firstTextContent()
    val payload = json.parseToJsonElement(text).jsonObject
    val ws = payload["workspaceId"]!!.jsonPrimitive.content
    // Drain the list_changed notification from registration.
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(ws)
  }

  private fun warmDaemonFor(workspaceId: WorkspaceId, modulePath: String): FakeDaemon {
    // Trigger lazy spawn by accessing the daemon. Render request to /dev/null preview id is fine —
    // the fake responds and the spawn is recorded in factory.daemons.
    supervisor.daemonFor(workspaceId, modulePath)
    return factory.daemons.getValue(workspaceId to modulePath)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val out = PipedOutputStream()
    val input = PipedInputStream(out, 64 * 1024)
    return out to input
  }
}

/**
 * Minimal MCP client used by [DaemonMcpServerTest]. Speaks Content-Length-framed JSON-RPC over the
 * pipes the McpSession exposes.
 */
class McpTestClient(private val input: InputStream, private val output: OutputStream) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }
  private val nextId = AtomicLong(1)
  private val responses =
    java.util.concurrent.ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()
  private val notifications = LinkedBlockingQueue<NotificationFrame>()
  @Volatile private var closed = false

  private val readerThread =
    Thread({ runReader() }, "mcp-test-client-reader").apply { isDaemon = true }

  init {
    readerThread.start()
  }

  fun initialize(timeoutMs: Long = 5_000): JsonObject {
    val params = buildJsonObject {
      put("protocolVersion", "2025-06-18")
      putJsonObject("capabilities") {}
      putJsonObject("clientInfo") {
        put("name", "mcp-test-client")
        put("version", "0.0")
      }
    }
    val resp = request("initialize", params, timeoutMs)
    notifyOnly("notifications/initialized", null)
    return resp
  }

  fun request(method: String, params: JsonElement? = null, timeoutMs: Long = 5_000): JsonObject {
    val id = nextId.getAndIncrement()
    val slot = responses.computeIfAbsent(id) { LinkedBlockingQueue() }
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      if (params != null) put("params", params)
    }
    sendFrame(payload.toString())
    val resp =
      slot.poll(timeoutMs, TimeUnit.MILLISECONDS)
        ?: error("request($method) timed out after ${timeoutMs}ms")
    responses.remove(id)
    if (resp["error"] != null) {
      error("request($method) error: ${resp["error"]}")
    }
    return resp["result"]?.jsonObject ?: error("request($method): no result in $resp")
  }

  fun callTool(
    name: String,
    arguments: JsonObject? = null,
    timeoutMs: Long = 5_000,
  ): McpToolResult {
    val params = buildJsonObject {
      put("name", name)
      if (arguments != null) put("arguments", arguments)
    }
    val result = request("tools/call", params, timeoutMs)
    return McpToolResult(result)
  }

  fun notifyOnly(method: String, params: JsonElement?) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      if (params != null) put("params", params)
    }
    sendFrame(payload.toString())
  }

  fun expectNotification(method: String, timeoutMs: Long): NotificationFrame {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val n =
        notifications.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: continue
      if (n.method == method) return n
      // Drop unrelated notifications.
    }
    error("expectNotification($method) timed out after ${timeoutMs}ms")
  }

  fun close() {
    closed = true
    runCatching { input.close() }
    runCatching { output.close() }
    readerThread.join(2_000)
  }

  private fun runReader() {
    try {
      while (!closed) {
        val frame = readFrame(input) ?: break
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val id = obj["id"]?.jsonPrimitive?.intOrNull()
        if (id != null) {
          responses.computeIfAbsent(id.toLong()) { LinkedBlockingQueue() }.put(obj)
        } else {
          val method = obj["method"]?.jsonPrimitive?.contentOrNull
          if (method != null) {
            notifications.put(NotificationFrame(method, obj["params"] as? JsonObject))
          }
        }
      }
    } catch (_: IOException) {
      // EOF
    }
  }

  private fun JsonPrimitive.intOrNull(): Int? = runCatching { content.toInt() }.getOrNull()

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
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, buf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header: '$line'")
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
}

data class NotificationFrame(val method: String, val params: JsonObject?)

/** Wrapper around `tools/call` result for ergonomic content access. */
class McpToolResult(val raw: JsonObject) {
  fun firstTextContent(): String {
    val content = raw["content"]?.jsonArray ?: error("tool result has no content array")
    val first = content.first().jsonObject
    return first["text"]?.jsonPrimitive?.content ?: error("first content block is not text")
  }

  fun isError(): Boolean = raw["isError"]?.jsonPrimitive?.contentOrNull == "true"
}
