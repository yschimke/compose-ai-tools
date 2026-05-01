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
import kotlinx.serialization.json.putJsonArray
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
        "set_visible",
        "set_focus",
        "history_list",
        "history_diff",
        "list_data_products",
        "get_preview_data",
        "subscribe_preview_data",
        "unsubscribe_preview_data",
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
  fun `historyAdded fires list_changed only for sessions interested in the matching live URI`() {
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

    // Without any subscription/watch the targeted fan-out is silent — historyAdded fires for an
    // unrelated session and the test client sees nothing.
    daemon.emitHistoryAdded(entry)
    Thread.sleep(200) // small buffer in case a notification was about to arrive
    // Now subscribe the live preview's URI; the next historyAdded should fire list_changed.
    val liveUri = PreviewUri(workspaceId, ":module", "com.example.X").toUri()
    client.request("resources/subscribe", buildJsonObject { put("uri", liveUri) })
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
  fun `set_visible and set_focus tools forward ids verbatim to the matching daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")

    val visResp =
      client.callTool(
        "set_visible",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("module", ":module")
          putJsonArray("ids") {
            add(JsonPrimitive("com.example.A"))
            add(JsonPrimitive("com.example.B"))
          }
        },
      )
    assertThat(visResp.firstTextContent()).contains("forwarded 2 id(s)")
    val visible = daemon.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(visible).isEqualTo(listOf("com.example.A", "com.example.B"))

    val focusResp =
      client.callTool(
        "set_focus",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("module", ":module")
          putJsonArray("ids") { add(JsonPrimitive("com.example.A")) }
        },
      )
    assertThat(focusResp.firstTextContent()).contains("forwarded 1 id(s)")
    val focus = daemon.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(focus).isEqualTo(listOf("com.example.A"))
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
  // D1 — data product tools
  // -------------------------------------------------------------------------

  @Test
  fun `list_data_products surfaces kinds advertised by each daemon's initialize`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          ),
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/atf",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          ),
        )
    }
    warmDaemonFor(workspaceId, ":module")

    val resp = client.callTool("list_data_products", buildJsonObject {})
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    val daemons = payload["daemons"]!!.jsonArray
    assertThat(daemons).hasSize(1)
    val entry = daemons.single().jsonObject
    assertThat(entry["workspaceId"]?.jsonPrimitive?.contentOrNull).isEqualTo(workspaceId.value)
    assertThat(entry["module"]?.jsonPrimitive?.contentOrNull).isEqualTo(":module")
    val kinds = entry["kinds"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
    assertThat(kinds).containsExactly("a11y/hierarchy", "a11y/atf")
  }

  @Test
  fun `get_preview_data forwards data slash fetch and returns the payload`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      daemon.dataFetchHandler = { previewId, kind, _, _ ->
        FakeDaemon.DataFetchOutcome.Ok(
          kind = kind,
          schemaVersion = 1,
          payload =
            buildJsonObject {
              putJsonArray("nodes") {
                add(
                  buildJsonObject {
                    put("label", "Hello $previewId")
                    put("role", "Button")
                  }
                )
              }
            },
        )
      }
    }
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    assertThat(payload["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("a11y/hierarchy")
    assertThat(payload["schemaVersion"]?.jsonPrimitive?.contentOrNull).isEqualTo("1")
    val nodes = payload["payload"]!!.jsonObject["nodes"]!!.jsonArray
    assertThat(nodes.single().jsonObject["label"]!!.jsonPrimitive.content)
      .isEqualTo("Hello $previewId")
  }

  @Test
  fun `get_preview_data surfaces DataProductUnknown when kind is not advertised`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    // Default daemon advertises nothing — every fetch returns DataProductUnknown.
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
      )
    assertThat(resp.isError()).isTrue()
    assertThat(resp.firstTextContent()).contains("DataProductUnknown")
  }

  @Test
  fun `subscribe_preview_data and unsubscribe_preview_data forward to the daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    }
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val subResp =
      client.callTool(
        "subscribe_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
      )
    assertThat(subResp.firstTextContent()).contains("subscribe_preview_data: ok")
    val sub = daemon.dataSubscribes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(sub).isEqualTo("com.example.Red" to "a11y/hierarchy")

    val unsubResp =
      client.callTool(
        "unsubscribe_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
      )
    assertThat(unsubResp.firstTextContent()).contains("unsubscribe_preview_data: ok")
    val unsub = daemon.dataUnsubscribes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(unsub).isEqualTo("com.example.Red" to "a11y/hierarchy")
  }

  @Test
  fun `get_preview_data auto-renders on DataProductNotAvailable and retries`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    val previewId = "com.example.Red"
    val pngFile = tmp.newFile("auto-render.png")
    Files.write(pngFile.toPath(), byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
    val fetchAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      // First call → NotAvailable (preview hasn't rendered). Second call → Ok. The MCP server's
      // auto-render path between the two issues a renderNow whose renderFinished completes
      // `awaitNextRender`, after which the retry hits the Ok branch.
      daemon.dataFetchHandler = { _, kind, _, _ ->
        if (fetchAttempts.incrementAndGet() == 1) FakeDaemon.DataFetchOutcome.NotAvailable
        else
          FakeDaemon.DataFetchOutcome.Ok(
            kind = kind,
            schemaVersion = 1,
            payload = buildJsonObject { put("nodes", JsonPrimitive("auto-rendered")) },
          )
      }
      // Wire renderNow → renderFinished so awaitNextRender unblocks promptly.
      daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }
    }
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
        timeoutMs = 10_000,
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    assertThat(payload["payload"]?.jsonObject?.get("nodes")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("auto-rendered")
    assertThat(fetchAttempts.get()).isEqualTo(2)
    // The auto-render path issued exactly one renderNow.
    val renderRequest = daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(renderRequest).isEqualTo(listOf(previewId))
  }

  @Test
  fun `get_preview_data does not auto-render on DataProductUnknown`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/hierarchy")
        },
      )
    assertThat(resp.isError()).isTrue()
    assertThat(resp.firstTextContent()).contains("DataProductUnknown")
    // No render was queued — auto-render is reserved for NotAvailable.
    assertThat(daemon.renderRequests.poll(500, TimeUnit.MILLISECONDS)).isNull()
  }

  @Test
  fun `subscribe_preview_data refcounts across two sessions and only forwards on first ref`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    }
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val args = buildJsonObject {
      put("uri", uri)
      put("kind", "a11y/hierarchy")
    }

    // Session A subscribes — first ref, forwards data/subscribe to daemon.
    client.callTool("subscribe_preview_data", args)
    val subA = daemon.dataSubscribes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(subA).isEqualTo("com.example.Red" to "a11y/hierarchy")

    // Session B opens, also subscribes — refcount goes to 2, no new wire call.
    val (cToS2, sFromC2) = pipedPair()
    val (sToC2, cFromS2) = pipedPair()
    val session2 = server.newSession(input = sFromC2, output = sToC2).also { it.start() }
    val client2 = McpTestClient(input = cFromS2, output = cToS2)
    try {
      client2.initialize()
      client2.callTool("subscribe_preview_data", args)
      // The fake's queue must stay quiet — no second daemon-side subscribe.
      val subB = daemon.dataSubscribes.poll(500, TimeUnit.MILLISECONDS)
      assertThat(subB).isNull()

      // Session A unsubscribes — refcount drops to 1, still no wire unsubscribe.
      client.callTool("unsubscribe_preview_data", args)
      val unsubA = daemon.dataUnsubscribes.poll(500, TimeUnit.MILLISECONDS)
      assertThat(unsubA).isNull()

      // Session B unsubscribes — last ref, daemon gets the data/unsubscribe.
      client2.callTool("unsubscribe_preview_data", args)
      val unsubB = daemon.dataUnsubscribes.poll(2_000, TimeUnit.MILLISECONDS)
      assertThat(unsubB).isEqualTo("com.example.Red" to "a11y/hierarchy")
    } finally {
      runCatching { client2.close() }
      runCatching { session2.close() }
    }
  }

  @Test
  fun `session disconnect releases data subscriptions and forwards data slash unsubscribe`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/hierarchy",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    }
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    // Open a second session, subscribe, then disconnect it without unsubscribing. The supervisor's
    // onClose path must drop the daemon-side subscription so the daemon doesn't leak it.
    val (cToS2, sFromC2) = pipedPair()
    val (sToC2, cFromS2) = pipedPair()
    val session2 = server.newSession(input = sFromC2, output = sToC2).also { it.start() }
    val client2 = McpTestClient(input = cFromS2, output = cToS2)
    client2.initialize()

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    client2.callTool(
      "subscribe_preview_data",
      buildJsonObject {
        put("uri", uri)
        put("kind", "a11y/hierarchy")
      },
    )
    val sub = daemon.dataSubscribes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(sub).isEqualTo("com.example.Red" to "a11y/hierarchy")

    // Hard close on the client side simulates the agent process going away. The McpSession's
    // reader hits EOF, fires onClose, and the supervisor's hook calls forgetDataSubscriptions
    // which forwards data/unsubscribe to the daemon. The poll-with-timeout below is what we use
    // to wait for that asynchronous chain to complete.
    client2.close()

    val unsub = daemon.dataUnsubscribes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(unsub).isEqualTo("com.example.Red" to "a11y/hierarchy")
    runCatching { session2.close() }
  }

  @Test
  fun `get_preview_data hits cache from renderFinished attachments without round-tripping`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/atf",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      // Configure dataFetch to fail loudly — the cache hit MUST serve without ever touching the
      // wire. If our cache short-circuit is broken, the test will fall through to this branch
      // and the assertion on payload contents will fail.
      daemon.dataFetchHandler = { _, _, _, _ ->
        FakeDaemon.DataFetchOutcome.FetchFailed("cache-miss-must-not-happen")
      }
    }
    val previewId = "com.example.Red"
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    // Subscribe so the resources/updated notification is our sync point: the supervisor finished
    // processing the renderFinished (including caching attachments) before we move on.
    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.request("resources/subscribe", buildJsonObject { put("uri", uri) })

    // Daemon ships an attached payload on a renderFinished — simulating the post-subscribe
    // attach-on-render path. The supervisor caches it.
    daemon.emitRenderFinishedWithDataProducts(
      previewId,
      "/tmp/red.png",
      listOf(
        buildJsonObject {
          put("kind", "a11y/atf")
          put("schemaVersion", 1)
          putJsonObject("payload") {
            putJsonArray("findings") { add(JsonPrimitive("missing-content-description")) }
          }
        }
      ),
    )
    client.expectNotification("notifications/resources/updated", 2_000)

    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/atf")
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    assertThat(payload["cached"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(payload["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("a11y/atf")
    val findings = payload["payload"]?.jsonObject?.get("findings")?.jsonArray
    assertThat(findings?.single()?.jsonPrimitive?.content).isEqualTo("missing-content-description")
  }

  @Test
  fun `cache evicts stale kinds when next renderFinished omits them`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "a11y/atf",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      // After the cache evicts, dataFetch is the fallback path — return a distinguishable Ok so
      // we can assert that the second call went through the wire (not the cache).
      daemon.dataFetchHandler = { _, kind, _, _ ->
        FakeDaemon.DataFetchOutcome.Ok(
          kind = kind,
          schemaVersion = 1,
          payload = buildJsonObject { put("source", "wire") },
        )
      }
    }
    val previewId = "com.example.Red"
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    // Subscribe to the URI so we get a `resources/updated` notification per renderFinished —
    // those notifications act as our synchronization point that the supervisor finished processing
    // each render (and updating the cache) before we move on.
    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.request("resources/subscribe", buildJsonObject { put("uri", uri) })

    // First render: a11y/atf is attached. Cache is warm.
    daemon.emitRenderFinishedWithDataProducts(
      previewId,
      "/tmp/red-1.png",
      listOf(
        buildJsonObject {
          put("kind", "a11y/atf")
          put("schemaVersion", 1)
          putJsonObject("payload") { put("source", "cache") }
        }
      ),
    )
    client.expectNotification("notifications/resources/updated", 2_000)

    // Second render: NO data products attached (e.g. session unsubscribed in between). Cache for
    // a11y/atf must be evicted so a follow-up get_preview_data falls through to the wire.
    daemon.emitRenderFinishedWithDataProducts(
      previewId,
      "/tmp/red-2.png",
      attachments = emptyList(),
    )
    client.expectNotification("notifications/resources/updated", 2_000)

    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "a11y/atf")
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    // No `cached: true` field on a wire-served payload.
    assertThat(payload["cached"]?.jsonPrimitive?.contentOrNull).isNull()
    assertThat(payload["payload"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("wire")
  }

  @Test
  fun `cache miss when caller passes per-kind params skips the cache`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    factory.daemonConfigurer = { daemon ->
      daemon.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "layout/tree",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      daemon.dataFetchHandler = { _, kind, perKindParams, _ ->
        // The wire call must receive the params verbatim — that's how kinds with sub-views (e.g.
        // layout/tree filtered by nodeId) work.
        val nodeId = perKindParams?.get("nodeId")?.jsonPrimitive?.contentOrNull ?: "?"
        FakeDaemon.DataFetchOutcome.Ok(
          kind = kind,
          schemaVersion = 1,
          payload = buildJsonObject { put("nodeId", nodeId) },
        )
      }
    }
    val previewId = "com.example.Red"
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.request("resources/subscribe", buildJsonObject { put("uri", uri) })

    // Warm the cache with the no-params variant.
    daemon.emitRenderFinishedWithDataProducts(
      previewId,
      "/tmp/red.png",
      listOf(
        buildJsonObject {
          put("kind", "layout/tree")
          put("schemaVersion", 1)
          putJsonObject("payload") { put("nodeId", "root") }
        }
      ),
    )
    client.expectNotification("notifications/resources/updated", 2_000)

    val resp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "layout/tree")
          putJsonObject("params") { put("nodeId", "node-42") }
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    // params present → skip cache → wire call → response carries the param-filtered nodeId, not
    // the cached "root".
    assertThat(payload["cached"]?.jsonPrimitive?.contentOrNull).isNull()
    assertThat(payload["payload"]?.jsonObject?.get("nodeId")?.jsonPrimitive?.contentOrNull)
      .isEqualTo("node-42")
  }

  @Test
  fun `globalAttachDataProducts forwards through initialize to the daemon`() {
    // Build a separate supervisor + server with the global attach list set, so we can assert the
    // exact parameters reach the daemon's initialize handler.
    val factoryLocal = FakeDaemonClientFactory()
    val capturedParams = java.util.concurrent.LinkedBlockingQueue<JsonObject>(/* capacity */ 4)
    factoryLocal.daemonConfigurer = { daemon ->
      daemon.onInitializeReceived = { params -> capturedParams.offer(params) }
    }
    val supervisorLocal =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = factoryLocal,
        globalAttachDataProducts = listOf("a11y/atf", "layout/tree"),
      )
    try {
      val projectDir = tmp.newFolder("workspace-attach")
      tmp.newFolder("workspace-attach", "module")
      val project = supervisorLocal.registerProject(projectDir, "demo-attach")
      // Trigger spawn — this sends initialize with attachDataProducts under options.
      supervisorLocal.daemonFor(project.workspaceId, ":module")

      val params = capturedParams.poll(3_000, TimeUnit.MILLISECONDS)
      assertThat(params).isNotNull()
      val attached = params!!["options"]?.jsonObject?.get("attachDataProducts")?.jsonArray
      assertThat(attached?.map { it.jsonPrimitive.content })
        .containsExactly("a11y/atf", "layout/tree")
        .inOrder()
    } finally {
      runCatching { supervisorLocal.shutdown() }
    }
  }

  @Test
  fun `empty globalAttachDataProducts omits the options field entirely`() {
    val factoryLocal = FakeDaemonClientFactory()
    val capturedParams = java.util.concurrent.LinkedBlockingQueue<JsonObject>(/* capacity */ 4)
    factoryLocal.daemonConfigurer = { daemon ->
      daemon.onInitializeReceived = { params -> capturedParams.offer(params) }
    }
    val supervisorLocal =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = factoryLocal,
        // Empty list (the default) — supervisor must NOT send an options object with an empty
        // attachDataProducts array (encodeDefaults = false on the wire keeps the field absent;
        // an explicit empty array would be an unnecessary protocol diff).
      )
    try {
      val projectDir = tmp.newFolder("workspace-no-attach")
      tmp.newFolder("workspace-no-attach", "module")
      val project = supervisorLocal.registerProject(projectDir, "demo-no-attach")
      supervisorLocal.daemonFor(project.workspaceId, ":module")
      val params = capturedParams.poll(3_000, TimeUnit.MILLISECONDS)
      assertThat(params).isNotNull()
      assertThat(params!!.containsKey("options")).isFalse()
    } finally {
      runCatching { supervisorLocal.shutdown() }
    }
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
