package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.mcp.protocol.ListToolsResult
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
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

    val tools = client.awaitToolsContaining("record_preview")
    val names = tools.tools.map { it.name }.toSet()
    assertThat(names)
      .containsExactly(
        "status",
        "register_project",
        "unregister_project",
        "list_projects",
        "list_devices",
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
        "list_extension_commands",
        "run_extension_command",
        "get_preview_data",
        "subscribe_preview_data",
        "unsubscribe_preview_data",
        "render_preview_overlay",
        "get_preview_extras",
        "record_preview",
      )
  }

  @Test
  fun `status is available from bootstrap tool surface`() {
    client.initialize()

    val bootstrapTools =
      json.decodeFromJsonElement(ListToolsResult.serializer(), client.request("tools/list"))
    assertThat(bootstrapTools.tools.map { it.name }).contains("status")

    val status = client.callTool("status").firstTextContent()
    val payload = json.parseToJsonElement(status).jsonObject
    assertThat(payload["schema"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("compose-preview-mcp-status/v1")
    assertThat(payload["ready"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(payload["toolCatalog"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull)
      .isAnyOf("loading", "ready")
  }

  @Test
  fun `bootstrap tools list_changed fires when full catalog completes inside grace period`() {
    // Race covered by issue #670: a client receives bootstrapToolDefs from tools/list, the full
    // catalog finishes loading well under TOOL_CATALOG_NOTIFY_DELAY_MS, and without this fix no
    // notifications/tools/list_changed is sent — so listChanged-only clients permanently miss
    // tools like `record_preview`.
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { supervisor.shutdown() }

    val gate = java.util.concurrent.CountDownLatch(1)
    val loaderEntered = java.util.concurrent.CountDownLatch(1)
    factory = FakeDaemonClientFactory()
    supervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
    server =
      DaemonMcpServer(
        supervisor = supervisor,
        fullToolDefsLoader = {
          loaderEntered.countDown()
          gate.await()
          listOf(
            ee.schimke.composeai.mcp.protocol.ToolDef(
              name = "record_preview",
              description = "stub for the test",
              inputSchema =
                Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
            )
          )
        },
      )

    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session =
      server.newSession(input = serverFromClient, output = serverToClient).also { it.start() }
    client = McpTestClient(input = clientFromServer, output = clientToServer)

    client.initialize()

    // Wait until the loader is actually blocked, otherwise we might race past the bootstrap window.
    assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue()

    val bootstrap =
      json.decodeFromJsonElement(ListToolsResult.serializer(), client.request("tools/list"))
    assertThat(bootstrap.tools.map { it.name }).doesNotContain("record_preview")

    // Release the loader; the full catalog now resolves quickly — long before the 3s grace window.
    gate.countDown()

    // The fix's contract: this notification arrives even though the load was not "delayed".
    client.expectNotification("notifications/tools/list_changed", 5_000)

    val full =
      json.decodeFromJsonElement(ListToolsResult.serializer(), client.request("tools/list"))
    assertThat(full.tools.map { it.name }).contains("record_preview")
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
  fun `list_devices returns the daemon catalog projected to id widthDp heightDp density`() {
    // No daemon needs to be spawned — list_devices reads directly from the shared
    // :daemon:core DeviceDimensions catalog.
    client.initialize()
    val result = client.callTool("list_devices")
    val payload = json.parseToJsonElement(result.firstTextContent()).jsonObject
    val devices =
      payload["devices"]?.jsonArray ?: error("list_devices payload must include 'devices' array")
    // Spot-check a few well-known catalog entries — full enumeration would couple this test to
    // the catalog membership, which is exactly the kind of hardcoded duplication the wire
    // surface aims to remove. Pick one phone, one Wear, one TV; verify shape + a couple of
    // dimensions to prove the mapping reaches the JSON.
    val byId = devices.associateBy { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    val pixel5 =
      byId["id:pixel_5"]?.jsonObject ?: error("expected id:pixel_5 in list_devices output")
    assertThat(pixel5["widthDp"]?.jsonPrimitive?.contentOrNull?.toInt()).isEqualTo(393)
    assertThat(pixel5["heightDp"]?.jsonPrimitive?.contentOrNull?.toInt()).isEqualTo(851)
    assertThat(pixel5["density"]?.jsonPrimitive?.contentOrNull?.toDouble()).isEqualTo(2.75)
    assertThat(byId).containsKey("id:wearos_small_round")
    assertThat(byId).containsKey("id:tv_1080p")
    // Sorted alphabetically — same contract as InitializeResult.capabilities.knownDevices.
    val ids = devices.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull!! }
    assertThat(ids).isInOrder()
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
    val watchPayload = json.parseToJsonElement(watchResp.firstTextContent()).jsonObject
    assertThat(watchPayload["ready"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    val moduleState = watchPayload["modules"]!!.jsonArray.single().jsonObject
    assertThat(moduleState["discoveryReady"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(moduleState["previewCount"]?.jsonPrimitive?.contentOrNull?.toInt()).isEqualTo(2)

    val visible = daemon.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    val focus = daemon.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(visible).isEqualTo(listOf("com.example.Red"))
    assertThat(focus).isEqualTo(listOf("com.example.Red"))
  }

  @Test
  fun `watch awaitDiscovery waits for daemon startup and reports readiness`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val register =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", projectDir.absolutePath)
          put("rootProjectName", "demo")
          putJsonArray("modules") { add(JsonPrimitive(":module")) }
        },
      )
    val workspaceId =
      WorkspaceId(
        json
          .parseToJsonElement(register.firstTextContent())
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      )
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val watchResp =
      client.callTool(
        "watch",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("awaitDiscovery", true)
        },
      )
    val payload = json.parseToJsonElement(watchResp.firstTextContent()).jsonObject
    assertThat(payload["awaitDiscovery"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(payload["ready"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(payload["spawning"]?.jsonPrimitive?.contentOrNull?.toInt()).isEqualTo(1)
    val moduleState = payload["modules"]!!.jsonArray.single().jsonObject
    assertThat(moduleState["module"]?.jsonPrimitive?.contentOrNull).isEqualTo(":module")
    assertThat(moduleState["spawned"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(moduleState["discoveryReady"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(moduleState["previewCount"]?.jsonPrimitive?.contentOrNull?.toInt()).isEqualTo(0)
    assertThat(factory.spawnHistory).hasSize(1)
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
  fun `render_preview with overrides forwards them to the daemon's renderNow`() {
    // Regression for the broken main / mismatched-bytes pair:
    //   1. PR #413 split renderAndReadBytes into renderAndReadBytes + awaitNextRender, but
    //      didn't thread the new `overrides` parameter through — the latter just reused a
    //      symbol-not-in-scope `overrides` reference, leaving main with an unresolved-reference
    //      error and the MCP tool's overrides silently dropped on every call. This test asserts
    //      the daemon actually sees them.
    //   2. Two concurrent render_preview calls for the same URI but different overrides used to
    //      share a single CompletableFuture in pendingRenders, returning whichever rendered
    //      first's bytes to BOTH callers. RenderKey now includes overrides so that fanout splits
    //      cleanly. The serial-call assertion below is the minimum — the dedup fix is documented
    //      via the kdoc on awaitNextRender.
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    val pngFile = tmp.newFile("override-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.callTool(
      "render_preview",
      buildJsonObject {
        put("uri", uri)
        put(
          "overrides",
          buildJsonObject {
            put("widthPx", 600)
            put("heightPx", 800)
            put("uiMode", "dark")
            put("device", "id:pixel_5")
            put("captureAdvanceMs", 250)
            put("inspectionMode", false)
            putJsonObject("material3Theme") {
              putJsonObject("colorScheme") {
                put("primary", "#FF336699")
                put("onPrimary", "#FFFFFFFF")
              }
              putJsonObject("typography") {
                putJsonObject("bodyLarge") {
                  put("fontSizeSp", 18)
                  put("lineHeightSp", 24)
                  put("fontWeight", 700)
                }
              }
              putJsonObject("shapes") { put("medium", 16) }
            }
          },
        )
      },
      timeoutMs = 10_000,
    )

    // The daemon recorded one renderNow whose overrides match what we sent. Without the
    // compile fix, `renderOverrides[0]` would be `null` because the param was dropped on the
    // floor on its way through awaitNextRender.
    assertThat(daemon.renderOverrides).hasSize(1)
    val firstOverrides = daemon.renderOverrides[0]
    assertThat(firstOverrides).isNotNull()
    assertThat(firstOverrides!!.widthPx).isEqualTo(600)
    assertThat(firstOverrides.heightPx).isEqualTo(800)
    assertThat(firstOverrides.uiMode).isEqualTo(ee.schimke.composeai.daemon.protocol.UiMode.DARK)
    assertThat(firstOverrides.device).isEqualTo("id:pixel_5")
    assertThat(firstOverrides.captureAdvanceMs).isEqualTo(250L)
    assertThat(firstOverrides.inspectionMode).isFalse()
    val material3Theme = firstOverrides.material3Theme!!
    assertThat(material3Theme.colorScheme["primary"]).isEqualTo("#FF336699")
    assertThat(material3Theme.typography["bodyLarge"]!!.fontWeight).isEqualTo(700)
    assertThat(material3Theme.shapes["medium"]).isEqualTo(16.0f)

    // A second render_preview call WITHOUT overrides now uses a different RenderKey and triggers
    // a fresh renderNow rather than dedup'ing onto the first. Pre-fix, the now-stale shared key
    // path would have skipped the renderNow and the request would have hung.
    client.callTool("render_preview", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
    assertThat(daemon.renderOverrides).hasSize(2)
    assertThat(daemon.renderOverrides[1]).isNull()
  }

  @Test
  fun `render_preview rejects overrides the daemon does not support`() {
    // Daemon advertises supportedOverrides = ["widthPx", "uiMode"]; an agent passes localeTag
    // (which the backend would silently ignore today). MCP rejects with a diagnostic instead
    // of letting the wire round-trip succeed and the locale silently drop.
    factory.daemonConfigurer = { d -> d.advertisedSupportedOverrides = listOf("widthPx", "uiMode") }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("overrides") {
            put("widthPx", 600) // supported
            put("localeTag", "fr-FR") // NOT supported on this fake's advertisement
          }
        },
      )
    assertThat(resp.isError()).isTrue()
    val msg = resp.firstTextContent()
    assertThat(msg).contains("does not apply 'localeTag'")
    // No renderNow should have been issued — validation runs before the daemon dispatch.
    assertThat(daemon.renderRequests).isEmpty()
  }

  @Test
  fun `render_preview falls open when daemon advertises empty supportedOverrides`() {
    // Pre-feature daemon: empty supportedOverrides means we can't tell which fields work.
    // Validation must fall open — same behaviour as before #441 landed. The renderNow goes
    // through unchanged.
    factory.daemonConfigurer = { d ->
      d.advertisedSupportedOverrides = emptyList()
      d.autoRenderPngPath = { _ ->
        java.io.File.createTempFile("preview", ".png").also { it.deleteOnExit() }.absolutePath
      }
    }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.callTool(
      "render_preview",
      buildJsonObject {
        put("uri", uri)
        putJsonObject("overrides") { put("localeTag", "fr-FR") }
      },
      timeoutMs = 10_000,
    )
    // The daemon DID receive the renderNow with the overrides — validation fell open.
    assertThat(daemon.renderOverrides).hasSize(1)
    assertThat(daemon.renderOverrides[0]?.localeTag).isEqualTo("fr-FR")
  }

  @Test
  fun `render_preview rejects unknown device id and accepts spec grammar`() {
    // Daemon advertises a small known-devices catalog. Agent passes id:typo_phone — rejected.
    // Then the same agent passes a spec: grammar — accepted (spec: is not enumerable, so
    // validation passes through and the daemon parses it at resolve-time).
    factory.daemonConfigurer = { d ->
      d.advertisedSupportedOverrides = listOf("device", "widthPx", "heightPx", "density")
      d.advertisedKnownDevices =
        listOf(
          ee.schimke.composeai.daemon.protocol.KnownDevice(
            id = "id:pixel_5",
            widthDp = 393,
            heightDp = 851,
            density = 2.75f,
          )
        )
      d.autoRenderPngPath = { _ ->
        java.io.File.createTempFile("preview", ".png").also { it.deleteOnExit() }.absolutePath
      }
    }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

    // Unknown id rejected with a helpful pointer to list_devices + spec: escape hatch.
    val rejectResp =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("overrides") { put("device", "id:typo_phone") }
        },
      )
    assertThat(rejectResp.isError()).isTrue()
    val rejectMsg = rejectResp.firstTextContent()
    assertThat(rejectMsg).contains("device='id:typo_phone' is not in the daemon's catalog")
    assertThat(rejectMsg).contains("list_devices")
    assertThat(daemon.renderRequests).isEmpty()

    // spec: grammar accepted — passes validation, reaches the daemon as-is.
    client.callTool(
      "render_preview",
      buildJsonObject {
        put("uri", uri)
        putJsonObject("overrides") { put("device", "spec:width=400dp,height=800dp,dpi=320") }
      },
      timeoutMs = 10_000,
    )
    assertThat(daemon.renderOverrides).hasSize(1)
    assertThat(daemon.renderOverrides[0]?.device).isEqualTo("spec:width=400dp,height=800dp,dpi=320")
  }

  @Test
  fun `record_preview drives start-script-stop-encode and returns base64 APNG inline`() {
    // P2: end-to-end MCP exercise of the recording surface. The MCP `record_preview` tool drives
    // the four daemon RPCs in sequence and packages the encoded bytes into an image content
    // block. Asserts:
    //   1. Each of the four daemon-side handlers observed exactly one call with the expected
    //      arguments — `recording/start` with previewId/fps/scale, `recording/script` with the
    //      script timeline, `recording/stop` with the recordingId, `recording/encode` with format.
    //   2. The tool result carries an image content block (mimeType = image/apng) whose data
    //      decodes to the canned APNG bytes the fake daemon wrote.
    //   3. A sibling text block carries the metadata (recordingId, frameCount, durationMs,
    //      framesDir, sizeBytes) so an agent that prefers the path / metrics doesn't need to
    //      base64-decode the bytes to find them.
    //
    // The fake daemon doesn't actually render anything — its `recording/encode` handler writes
    // the pre-loaded bytes to a temp file and the MCP server reads them back. That's enough to
    // verify the wire flow without standing up a real Compose backend.
    val recordingsDir = tmp.newFolder("recordings-out")
    val framesDir = tmp.newFolder("recording-frames")
    writeSolidPng(framesDir.resolve("frame-00000.png"), 0xFF000000.toInt())
    writeSolidPng(framesDir.resolve("frame-00001.png"), 0xFFFFFFFF.toInt())
    writeSolidPng(framesDir.resolve("frame-00002.png"), 0xFFFFFFFF.toInt())
    val cannedApngBytes =
      // PNG signature + an arbitrary tail. The MCP server reads these verbatim; we don't decode.
      byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0x01, 0x02, 0x03, 0x04, 0x05)
    factory.daemonConfigurer = { d ->
      d.recordingEncodedBytes = cannedApngBytes
      d.recordingEncodeDir = recordingsDir
      d.advertisedDataExtensions =
        ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions.descriptors +
          ee.schimke.composeai.daemon.InputTouchRecordingScriptEvents.descriptor
      d.recordingStopResult =
        ee.schimke.composeai.daemon.protocol.RecordingStopResult(
          frameCount = 16,
          durationMs = 500L,
          framesDir = framesDir.absolutePath,
          frameWidthPx = 240,
          frameHeightPx = 80,
          scriptEvents =
            listOf(
              ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence(
                tMs = 250L,
                kind = "recording.probe",
                status = ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus.APPLIED,
                label = "before-rotate",
                checkpointId = "checkpoint-1",
                message = "probe marker reached",
              )
            ),
        )
    }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.MyButton"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("fps", 30)
          put("scale", 4.0)
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "input.click")
                put("pixelX", 120)
                put("pixelY", 40)
              }
            )
            add(
              buildJsonObject {
                put("tMs", 500)
                put("kind", "input.click")
                put("pixelX", 120)
                put("pixelY", 40)
              }
            )
            add(
              buildJsonObject {
                put("tMs", 250)
                put("kind", "recording.probe")
                put("label", "before-rotate")
                put("checkpointId", "checkpoint-1")
                putJsonArray("tags") { add(JsonPrimitive("state-restoration")) }
              }
            )
          }
          putJsonObject("overrides") {
            put("widthPx", 240)
            put("heightPx", 80)
          }
        },
        timeoutMs = 10_000,
      )

    assertThat(resp.isError()).isFalse()

    // 1. Wire-level: each handler saw exactly one call with the expected arguments.
    val startCall = daemon.recordingStarts.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(startCall).isNotNull()
    assertThat(startCall!!.previewId).isEqualTo(previewId)
    assertThat(startCall.fps).isEqualTo(30)
    assertThat(startCall.scale).isEqualTo(4.0f)
    assertThat(startCall.overrides?.widthPx).isEqualTo(240)
    assertThat(startCall.overrides?.heightPx).isEqualTo(80)

    val scriptCall = daemon.recordingScripts.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(scriptCall).isNotNull()
    assertThat(scriptCall!!.events).hasSize(3)
    assertThat(scriptCall.events[0].tMs).isEqualTo(0L)
    assertThat(scriptCall.events[0].kind).isEqualTo("input.click")
    assertThat(scriptCall.events[0].pixelX).isEqualTo(120)
    assertThat(scriptCall.events[0].pixelY).isEqualTo(40)
    assertThat(scriptCall.events[1].tMs).isEqualTo(500L)
    assertThat(scriptCall.events[2].kind).isEqualTo("recording.probe")
    assertThat(scriptCall.events[2].label).isEqualTo("before-rotate")
    assertThat(scriptCall.events[2].checkpointId).isEqualTo("checkpoint-1")
    assertThat(scriptCall.events[2].tags).containsExactly("state-restoration")

    val stopCall = daemon.recordingStops.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(stopCall).isNotNull()
    val encodeCall = daemon.recordingEncodes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(encodeCall).isNotNull()
    assertThat(encodeCall!!.format)
      .isEqualTo(ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG)
    // start, script, stop, encode all reference the same recordingId.
    assertThat(stopCall).isEqualTo(encodeCall.recordingId)

    // 2. Image content block: mime + decoded bytes match the canned bytes.
    val (base64Data, mime) = resp.firstImageContent()
    assertThat(mime).isEqualTo("image/apng")
    val decoded = Base64.getDecoder().decode(base64Data)
    assertThat(decoded).isEqualTo(cannedApngBytes)

    // 3. Sibling text block carries the metadata payload.
    val textBlocks = resp.textContents()
    assertThat(textBlocks).isNotEmpty()
    val metadata = json.parseToJsonElement(textBlocks.last()).jsonObject
    assertThat(metadata["recordingId"]?.jsonPrimitive?.contentOrNull).isEqualTo(stopCall)
    assertThat(metadata["mimeType"]?.jsonPrimitive?.contentOrNull).isEqualTo("image/apng")
    assertThat(metadata["frameCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()).isEqualTo(16)
    assertThat(metadata["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()).isEqualTo(500L)
    assertThat(metadata["frameWidthPx"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()).isEqualTo(240)
    assertThat(metadata["frameHeightPx"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()).isEqualTo(80)
    assertThat(metadata["framesDir"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo(framesDir.absolutePath)
    assertThat(metadata["changedFrameCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
      .isEqualTo(1)
    assertThat(metadata["firstFramePath"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo(framesDir.resolve("frame-00000.png").absolutePath)
    assertThat(metadata["lastFramePath"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo(framesDir.resolve("frame-00002.png").absolutePath)
    assertThat(metadata["firstChangedFramePath"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo(framesDir.resolve("frame-00001.png").absolutePath)
    assertThat(metadata["lastChangedFrameIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
      .isEqualTo(1)
    val frames = metadata["frames"]!!.jsonArray
    assertThat(frames).hasSize(3)
    assertThat(frames[0].jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
      .isEqualTo(0)
    assertThat(frames[0].jsonObject["sha256"]?.jsonPrimitive?.contentOrNull).hasLength(64)
    assertThat(frames[0].jsonObject["changedFromPrevious"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("false")
    assertThat(frames[1].jsonObject["changedFromPrevious"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("true")
    assertThat(
        frames[1]
          .jsonObject["changedPixelsFromPrevious"]
          ?.jsonPrimitive
          ?.contentOrNull
          ?.toIntOrNull()
      )
      .isEqualTo(4)
    assertThat(frames[2].jsonObject["changedFromPrevious"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("false")
    val scriptEvents = metadata["scriptEvents"]!!.jsonArray
    assertThat(scriptEvents).hasSize(1)
    assertThat(scriptEvents[0].jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("recording.probe")
    assertThat(scriptEvents[0].jsonObject["status"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("applied")
    assertThat(scriptEvents[0].jsonObject["checkpointId"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("checkpoint-1")
    assertThat(
        frames[2]
          .jsonObject["changedPixelsFromPrevious"]
          ?.jsonPrimitive
          ?.contentOrNull
          ?.toIntOrNull()
      )
      .isEqualTo(0)
    assertThat(metadata["sizeBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull())
      .isEqualTo(cannedApngBytes.size.toLong())
  }

  @Test
  fun `MCP exposes data slash navigation snapshot and dispatches navigation deepLink + back`() {
    // End-to-end exercise of the navigation surface from an MCP-tool perspective:
    //
    // 1. The MCP `get_preview_data` tool surfaces the daemon's `data/navigation` data product —
    //    an agent can read the held activity's launch Intent (action / data URI / simple-typed
    //    extras) and the registered `OnBackPressedCallback` state for the rendered preview.
    // 2. The MCP `record_preview` tool forwards `navigation.deepLink` (with a `deepLinkUri`
    //    payload) and `navigation.back` script events to the daemon's `recording/script` channel,
    //    proving the wire shape carries the new fields end-to-end (including the
    //    `decodeRecordingEvents` extension we added in `DaemonMcpServer.kt`).
    //
    // The fake daemon advertises the navigation descriptor + a `data/navigation` capability so
    // the MCP-side validators (script-kind allow-list in `validateRecordingScriptKinds`,
    // `data/fetch` advertised-kind check) accept the request. We don't render anything — the
    // test verifies that the MCP server forwards the agent's intent verbatim, not that the
    // daemon dispatches it. The dispatch end is pinned by `AndroidRecordingSessionTest`.
    val recordingsDir = tmp.newFolder("nav-recordings-out")
    val framesDir = tmp.newFolder("nav-recording-frames")
    writeSolidPng(framesDir.resolve("frame-00000.png"), 0xFF000000.toInt())
    val cannedApngBytes =
      byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0x01, 0x02, 0x03, 0x04, 0x05)

    // Reconstruct the navigation descriptor inline rather than depending on
    // `:daemon:android` from `:mcp` — `NavigationRecordingScriptEvents` lives there because the
    // dispatch arms are Android-only, but the descriptor is plain metadata so we can construct
    // the equivalent advertisement with `:data-render-core` types alone.
    val navDescriptor =
      ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor(
        id = ee.schimke.composeai.data.render.extensions.DataExtensionId("navigation"),
        displayName = "Navigation script controls",
        recordingScriptEvents =
          listOf(
              "navigation.deepLink",
              "navigation.back",
              "navigation.predictiveBackStarted",
              "navigation.predictiveBackProgressed",
              "navigation.predictiveBackCommitted",
              "navigation.predictiveBackCancelled",
            )
            .map { id ->
              ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor(
                id = id,
                displayName = id,
                summary = "navigation script event $id",
                supported = true,
              )
            },
      )

    factory.daemonConfigurer = { d ->
      d.advertisedDataProducts =
        listOf(
          ee.schimke.composeai.daemon.protocol.DataProductCapability(
            kind = "data/navigation",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      d.advertisedDataExtensions =
        ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions.descriptors +
          ee.schimke.composeai.daemon.InputTouchRecordingScriptEvents.descriptor +
          navDescriptor
      d.dataFetchHandler = { _, kind, _, _ ->
        // Mirror the wire shape that `NavigationDataProducer` writes on a real Android render —
        // a deep-link Intent (post `navigation.deepLink` dispatch) with `hasEnabledCallbacks=true`
        // because the held screen registered a `BackHandler` (matches the NavHostHomePreview
        // sample's profile destination).
        if (kind != "data/navigation") FakeDaemon.DataFetchOutcome.Unknown
        else
          FakeDaemon.DataFetchOutcome.Ok(
            kind = kind,
            schemaVersion = 1,
            payload =
              buildJsonObject {
                putJsonObject("intent") {
                  put("action", "android.intent.action.VIEW")
                  put("dataUri", "app://route/profile/42")
                  put("packageName", "com.example.sampleandroid")
                  putJsonArray("categories") {
                    add(JsonPrimitive("android.intent.category.DEFAULT"))
                  }
                  putJsonObject("extras") {
                    put("user_id", 42)
                    put("show_fab", true)
                    put("source", "deeplink")
                  }
                }
                putJsonObject("onBackPressed") { put("hasEnabledCallbacks", true) }
              },
          )
      }
      d.recordingEncodedBytes = cannedApngBytes
      d.recordingEncodeDir = recordingsDir
      d.recordingStopResult =
        ee.schimke.composeai.daemon.protocol.RecordingStopResult(
          frameCount = 1,
          durationMs = 0L,
          framesDir = framesDir.absolutePath,
          frameWidthPx = 8,
          frameHeightPx = 8,
        )
    }
    client.initialize()
    val projectDir = tmp.newFolder("nav-workspace")
    tmp.newFolder("nav-workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo-nav")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.sampleandroid.NavHostPreviewKt.NavHostHomePreview"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

    // 1. `get_preview_data` round-trips the navigation snapshot.
    val fetchResp =
      client.callTool(
        "get_preview_data",
        buildJsonObject {
          put("uri", uri)
          put("kind", "data/navigation")
          put("inline", true)
        },
        timeoutMs = 10_000,
      )
    assertWithMessage("get_preview_data should succeed for an advertised navigation kind")
      .that(fetchResp.isError())
      .isFalse()
    val fetched = json.parseToJsonElement(fetchResp.firstTextContent()).jsonObject
    val payload = fetched["payload"]!!.jsonObject
    val intentJson = payload["intent"]!!.jsonObject
    assertThat(intentJson["action"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("android.intent.action.VIEW")
    assertThat(intentJson["dataUri"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("app://route/profile/42")
    val extras = intentJson["extras"]!!.jsonObject
    assertThat(extras["user_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()).isEqualTo(42)
    assertThat(extras["show_fab"]?.jsonPrimitive?.contentOrNull).isEqualTo("true")
    assertThat(extras["source"]?.jsonPrimitive?.contentOrNull).isEqualTo("deeplink")
    assertThat(
        payload["onBackPressed"]!!.jsonObject["hasEnabledCallbacks"]?.jsonPrimitive?.contentOrNull
      )
      .isEqualTo("true")

    // 2. `record_preview` forwards a navigation.deepLink + predictive-back gesture sequence.
    val recordResp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("fps", 30)
          put("scale", 1.0)
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "navigation.deepLink")
                put("deepLinkUri", "app://route/profile/42")
              }
            )
            add(
              buildJsonObject {
                put("tMs", 50)
                put("kind", "navigation.predictiveBackStarted")
                put("backProgress", 0.0)
                put("backEdge", "left")
              }
            )
            add(
              buildJsonObject {
                put("tMs", 100)
                put("kind", "navigation.predictiveBackProgressed")
                put("backProgress", 0.5)
                put("backEdge", "left")
              }
            )
            add(
              buildJsonObject {
                put("tMs", 150)
                put("kind", "navigation.back")
              }
            )
          }
        },
        timeoutMs = 10_000,
      )
    assertWithMessage("record_preview should accept navigation.* events advertised by the daemon")
      .that(recordResp.isError())
      .isFalse()

    // The daemon's `recording/script` handler captures the events it received — assert the
    // extension fields rode through the MCP encoder unchanged.
    val scriptCall = daemon.recordingScripts.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(scriptCall).isNotNull()
    assertThat(scriptCall!!.events).hasSize(4)

    val deepLink = scriptCall.events[0]
    assertThat(deepLink.kind).isEqualTo("navigation.deepLink")
    assertThat(deepLink.deepLinkUri).isEqualTo("app://route/profile/42")

    val started = scriptCall.events[1]
    assertThat(started.kind).isEqualTo("navigation.predictiveBackStarted")
    assertThat(started.backProgress).isEqualTo(0.0f)
    assertThat(started.backEdge).isEqualTo("left")

    val progressed = scriptCall.events[2]
    assertThat(progressed.kind).isEqualTo("navigation.predictiveBackProgressed")
    assertThat(progressed.backProgress).isEqualTo(0.5f)
    assertThat(progressed.backEdge).isEqualTo("left")

    val back = scriptCall.events[3]
    assertThat(back.kind).isEqualTo("navigation.back")
    assertThat(back.deepLinkUri).isNull()
    assertThat(back.backProgress).isNull()
  }

  @Test
  fun `record_preview rejects unknown event kind without spawning a session`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "scroll") // not a wire-name id any extension advertises
                put("pixelX", 10)
                put("pixelY", 10)
              }
            )
          }
        },
      )
    assertThat(resp.isError()).isTrue()
    val msg = resp.firstTextContent()
    assertThat(msg).contains("kind 'scroll' is not advertised by this daemon")
    assertThat(msg).contains("list_data_products")
    // No daemon-side recording session should have been allocated when validation fails.
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `record_preview rejects unadvertised extension event without starting recording`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "unknown.event")
              }
            )
          }
        },
      )

    assertThat(resp.isError()).isTrue()
    assertThat(resp.firstTextContent()).contains("not advertised by this daemon")
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `record_preview rejects extension events advertised but not yet implemented`() {
    // The daemon advertises a script event in `dataExtensions[].recordingScriptEvents` with
    // `supported = false` to signal a planned-but-unwired roadmap item. MCP rejects up front so the
    // agent doesn't watch a quiet `unsupported` evidence trail come back from a recording that
    // otherwise looks healthy. The daemon-side fallback (returning `unsupported` evidence) stays as
    // defense-in-depth for direct daemon clients.
    //
    // Constructed inline rather than read from `RecordingScriptDataExtensions.roadmapDescriptors`
    // because that list went empty once `state.{save,restore}` shipped real handlers (#749). The
    // rejection branch in `DaemonMcpServer.validateRecordingEvents` still needs coverage —
    // synthesise a descriptor with `supported = false` to exercise it independent of which
    // wire-name happens to be the current roadmap pick.
    factory.daemonConfigurer = { d ->
      d.advertisedDataExtensions =
        listOf(
          ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor(
            id = ee.schimke.composeai.data.render.extensions.DataExtensionId("state-roadmap"),
            displayName = "State (roadmap)",
            recordingScriptEvents =
              listOf(
                ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor(
                  id = "state.save",
                  displayName = "State save (planned)",
                  summary = "Planned state-save script event.",
                  supported = false,
                )
              ),
          )
        )
    }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "state.save")
                put("checkpointId", "before")
              }
            )
          }
        },
      )

    assertThat(resp.isError()).isTrue()
    val msg = resp.firstTextContent()
    assertThat(msg).contains("'state.save'")
    assertThat(msg).contains("not yet implemented")
    assertThat(msg).contains("supported=false")
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `record_preview rejects format the daemon does not advertise`() {
    // Daemon advertises only APNG (no ffmpeg on its PATH); agent asks for mp4. MCP rejects up
    // front with a clean diagnostic naming the supported formats — no `recording/start` round-
    // trip, no time wasted spinning up a session that would only fail at encode time.
    factory.daemonConfigurer = { d -> d.advertisedRecordingFormats = listOf("apng") }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("format", "mp4")
          putJsonArray("events") {}
        },
      )
    assertThat(resp.isError()).isTrue()
    val msg = resp.firstTextContent()
    assertThat(msg).contains("format 'mp4' not advertised")
    assertThat(msg).contains("[apng]")
    assertThat(msg).contains("ffmpeg")
    // No recording session was allocated — validation runs before any wire dispatch.
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `record_preview accepts mp4 when the daemon advertises it`() {
    // Capability advertises mp4 (i.e. the daemon's host detected ffmpeg at startup). MCP validates
    // and forwards the request; the fake daemon's `recording/encode` handler echoes the format
    // back. Asserts the format reaches the daemon as `RecordingFormat.MP4` (not silently coerced
    // to APNG).
    val recordingsDir = tmp.newFolder("mp4-recordings-out")
    factory.daemonConfigurer = { d ->
      d.advertisedRecordingFormats = listOf("apng", "mp4", "webm")
      d.advertisedDataExtensions =
        listOf(ee.schimke.composeai.daemon.InputTouchRecordingScriptEvents.descriptor)
      d.recordingEncodeDir = recordingsDir
      // Tiny canned payload — content doesn't matter for the wire-shape assertion.
      d.recordingEncodedBytes =
        byteArrayOf(
          0x00,
          0x00,
          0x00,
          0x18,
          'f'.code.toByte(),
          't'.code.toByte(),
          'y'.code.toByte(),
          'p'.code.toByte(),
        )
    }
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("format", "mp4")
          putJsonArray("events") {
            add(
              buildJsonObject {
                put("tMs", 0)
                put("kind", "input.click")
                put("pixelX", 10)
                put("pixelY", 10)
              }
            )
          }
        },
        timeoutMs = 10_000,
      )
    assertThat(resp.isError()).isFalse()
    val encodeCall = daemon.recordingEncodes.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(encodeCall).isNotNull()
    assertThat(encodeCall!!.format)
      .isEqualTo(ee.schimke.composeai.daemon.protocol.RecordingFormat.MP4)

    // Per the MCP 2025-06-18 spec, `video/mp4` belongs in an `EmbeddedResource` content block,
    // not a `ContentBlock.Image` (strict clients reject mismatched mimeTypes on `image`). The
    // sibling Text block still carries the metadata for callers that prefer the path.
    val (blob, mime, blobUri) = resp.firstEmbeddedResourceBlob()
    assertThat(mime).isEqualTo("video/mp4")
    assertThat(blobUri).startsWith("compose-preview-recording://")
    val decoded = Base64.getDecoder().decode(blob)
    assertThat(decoded.size).isEqualTo(8) // matches the canned 8-byte payload above
  }

  @Test
  fun `record_preview rejects malformed fps without spawning a session`() {
    // Regression for Codex P2 — `args["fps"]?.toIntOrNull()` silently dropped typo'd values like
    // "fast" into null and the daemon defaulted to 30 fps. We now distinguish absent (use default)
    // from present-but-unparseable (error). Same shape for `scale`.
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("fps", "fast") // string, not parseable as integer
          putJsonArray("events") {}
        },
      )
    assertThat(resp.isError()).isTrue()
    val msg = resp.firstTextContent()
    assertThat(msg).contains("invalid fps")
    assertThat(msg).contains("fast")
    // No daemon-side recording session was allocated when validation failed.
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `record_preview rejects malformed scale without spawning a session`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    val resp =
      client.callTool(
        "record_preview",
        buildJsonObject {
          put("uri", uri)
          put("scale", "2x") // string suffix, not parseable as float
          putJsonArray("events") {}
        },
      )
    assertThat(resp.isError()).isTrue()
    assertThat(resp.firstTextContent()).contains("invalid scale")
    assertThat(daemon.recordingStarts).isEmpty()
  }

  @Test
  fun `concurrent different-overrides render_preview calls are serialized per previewId`() {
    // Regression for the wrong-bytes hazard PR #432's "known limitation" note documented (and
    // mis-described as a hang). Pre-fix: two concurrent override-bearing render_preview calls
    // for the same URI would each fire a renderNow; the daemon coalesced the second; the MCP's
    // by-previewId fanout woke BOTH waiters with the FIRST render's bytes. Caller B (with O2)
    // silently received caller A's O1 bytes.
    //
    // Post-fix: previewQueues serializes per-previewId — caller B's renderNow doesn't fire
    // until caller A's renderFinished arrives. Each caller observes a renderNow with their
    // own overrides on the daemon, in the order they arrived.
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytesA = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 1, 1)
    val pngBytesB = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2, 2, 2)
    val pngFileA = tmp.newFile("a.png").also { Files.write(it.toPath(), pngBytesA) }
    val pngFileB = tmp.newFile("b.png").also { Files.write(it.toPath(), pngBytesB) }
    // Deliberately NO autoRenderPngPath — we drive renderFinished manually so the test
    // controls timing.
    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

    val callerExecutor =
      java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "test-render-caller").apply { isDaemon = true }
      }
    try {
      // Caller A — overrides {widthPx=100}.
      val callA =
        callerExecutor.submit<McpToolResult> {
          client.callTool(
            "render_preview",
            buildJsonObject {
              put("uri", uri)
              putJsonObject("overrides") { put("widthPx", 100) }
            },
            timeoutMs = 10_000,
          )
        }

      // Wait for the daemon to record A's renderNow before launching B — guarantees A is in
      // the queue first.
      val firstPreviews = daemon.renderRequests.poll(5, TimeUnit.SECONDS)
      assertThat(firstPreviews).isEqualTo(listOf(previewId))
      assertThat(daemon.renderOverrides).hasSize(1)
      assertThat(daemon.renderOverrides[0]?.widthPx).isEqualTo(100)

      // Caller B — overrides {widthPx=200}. Should queue behind A; no new renderNow yet.
      val callB =
        callerExecutor.submit<McpToolResult> {
          client.callTool(
            "render_preview",
            buildJsonObject {
              put("uri", uri)
              putJsonObject("overrides") { put("widthPx", 200) }
            },
            timeoutMs = 10_000,
          )
        }

      // Brief settle for B's awaitNextRender to register its future. Without serialization, a
      // second renderNow would fire here. With serialization, the queue holds B until A drains.
      Thread.sleep(300)
      assertThat(daemon.renderRequests.poll(0, TimeUnit.MILLISECONDS)).isNull()
      assertThat(daemon.renderOverrides).hasSize(1)

      // Drain A — the head pop should promote B and dispatch B's renderNow.
      daemon.emitRenderFinished(previewId, pngFileA.absolutePath)

      // B's renderNow now arrives, with B's overrides (not A's).
      val secondPreviews = daemon.renderRequests.poll(5, TimeUnit.SECONDS)
      assertThat(secondPreviews).isEqualTo(listOf(previewId))
      assertThat(daemon.renderOverrides).hasSize(2)
      assertThat(daemon.renderOverrides[1]?.widthPx).isEqualTo(200)

      // Drain B.
      daemon.emitRenderFinished(previewId, pngFileB.absolutePath)

      // Both calls returned successfully — pre-fix B would have completed early with A's bytes
      // (wrong-bytes); post-fix B blocks until its own renderFinished and gets B's bytes. The
      // load-bearing wire-side assertion is the order and count of renderNows the daemon
      // observed above; we just confirm both callers unblocked here.
      callA.get(5, TimeUnit.SECONDS)
      callB.get(5, TimeUnit.SECONDS)
    } finally {
      callerExecutor.shutdownNow()
    }
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

  @Test
  fun `resources read forwards fileChanged when preview source mtime moved since discovery`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText("@Preview fun Red() {}")
    val initialMtime = System.currentTimeMillis() - 10_000
    assertThat(sourceFile.setLastModified(initialMtime)).isTrue()
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    sourceFile.writeText("@Preview fun Red() { /* changed */ }")
    assertThat(sourceFile.setLastModified(initialMtime + 5_000)).isTrue()

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("fresh-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

    val fileChanged = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(fileChanged).isNotNull()
    assertThat(fileChanged!!["path"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo(sourceFile.canonicalPath)
    assertThat(fileChanged["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("source")
    assertThat(daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS))
      .isEqualTo(listOf(previewId))
  }

  // -------------------------------------------------------------------------
  // Edit-loop staleness regression — agents that edit a preview source repeatedly
  // and request a render after each edit observe stale renders when
  // ensureSourceFreshBeforeRender (DaemonMcpServer.kt:438) doesn't forward a
  // fileChanged. The daemon's UserClassLoaderHolder.swap fires only on
  // fileChanged({kind: "source"}), so without it the next render binds against
  // the previous bytecode.
  // -------------------------------------------------------------------------

  @Test
  fun `resources read forwards fileChanged on every iteration of an edit render loop`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText("@Preview fun Red() { /* v0 */ }")
    val baseMtime = System.currentTimeMillis() - 60_000
    assertThat(sourceFile.setLastModified(baseMtime)).isTrue()

    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("loop-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

    val iterations = 5
    repeat(iterations) { i ->
      sourceFile.writeText("@Preview fun Red() { /* v${i + 1} */ }")
      // Bump mtime forward each iteration. This is the happy path — File.lastModified()
      // is monotonic so the daemon-side classloader swap fires.
      assertThat(sourceFile.setLastModified(baseMtime + 1_000L * (i + 1))).isTrue()

      client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

      val fileChanged = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
      assertWithMessage(
          "iteration #${i + 1}: expected fileChanged forwarded for advancing-mtime edit"
        )
        .that(fileChanged)
        .isNotNull()
      assertThat(fileChanged!!["path"]?.jsonPrimitive?.contentOrNull)
        .isEqualTo(sourceFile.canonicalPath)
      assertThat(fileChanged["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("source")
      assertThat(daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS))
        .isEqualTo(listOf(previewId))
    }
  }

  @Test
  fun `resources read forwards fileChanged when content changes but mtime is preserved`() {
    // Tight edit loop where the source's mtime stays pinned across every iteration —
    // simulates same-millisecond writes on fast SSDs / tmpfs, mtime-preserving editors, and
    // agent harnesses that touch files programmatically without bumping mtime.
    // ensureSourceFreshBeforeRender's slow path hashes the file when mtime didn't move, so
    // content-only edits still fire `fileChanged` and the daemon's classloader rotates.
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText("@Preview fun Red() { /* v0 */ }")
    val frozenMtime = System.currentTimeMillis() - 60_000
    assertThat(sourceFile.setLastModified(frozenMtime)).isTrue()

    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("frozen-mtime-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

    val iterations = 5
    val notifiedIterations = mutableListOf<Int>()
    repeat(iterations) { i ->
      sourceFile.writeText("@Preview fun Red() { /* v${i + 1} */ }")
      // Pin mtime to the original value AFTER the write. Simulates a mtime-preserving
      // editor or two writes within the same millisecond.
      assertThat(sourceFile.setLastModified(frozenMtime)).isTrue()

      client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
      // Drain the renderNow so the next iteration's poll doesn't see a stale entry.
      daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS)

      val fileChanged = daemon.fileChanges.poll(500, TimeUnit.MILLISECONDS)
      if (fileChanged != null) notifiedIterations.add(i + 1)
    }

    assertWithMessage(
        "expected fileChanged to be forwarded for every edit (got ${notifiedIterations.size}/$iterations); " +
          "ensureSourceFreshBeforeRender currently relies on mtime advancing, so content-only edits leak through stale"
      )
      .that(notifiedIterations)
      .hasSize(iterations)
  }

  @Test
  fun `notify_file_changed forwards fileChanged regardless of mtime so agents can bypass staleness`() {
    // The documented workaround for the mtime-based staleness gap above: agents call
    // the explicit `notify_file_changed` MCP tool after every edit. This test pins the
    // workaround so a regression in the tool's forwarding path (DaemonMcpServer.kt:2766)
    // is caught early.
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText("@Preview fun Red() { /* v0 */ }")
    val frozenMtime = System.currentTimeMillis() - 60_000
    assertThat(sourceFile.setLastModified(frozenMtime)).isTrue()

    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val iterations = 5
    repeat(iterations) { i ->
      sourceFile.writeText("@Preview fun Red() { /* v${i + 1} */ }")
      assertThat(sourceFile.setLastModified(frozenMtime)).isTrue()

      client.callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("path", sourceFile.absolutePath)
          put("kind", "source")
          put("changeType", "modified")
        },
      )

      val fileChanged = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
      assertWithMessage("iteration #${i + 1}: notify_file_changed must always forward")
        .that(fileChanged)
        .isNotNull()
      assertThat(fileChanged!!["path"]?.jsonPrimitive?.contentOrNull)
        .isEqualTo(sourceFile.absolutePath)
      assertThat(fileChanged["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("source")
    }
  }

  // -------------------------------------------------------------------------
  // Freshness metrics, polling, and random-sampling determinism probe.
  // Background workers run on a scheduled executor in production; tests use `= 0` cadence to
  // disable scheduling and trigger the workers directly so timing isn't load-bearing.
  // -------------------------------------------------------------------------

  @Test
  fun `freshness metrics buckets every probe outcome and surfaces them via status`() {
    // Build a fresh server with the schedulers disabled so only on-demand probes count.
    val freshFactory = FakeDaemonClientFactory()
    val freshSupervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = freshFactory)
    val freshServer =
      DaemonMcpServer(
        supervisor = freshSupervisor,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val freshSession = freshServer.newSession(input = serverFromClient, output = serverToClient)
    freshSession.start()
    val freshClient = McpTestClient(input = clientFromServer, output = clientToServer)
    try {
      freshClient.initialize()
      val projectDir = tmp.newFolder("metrics-workspace")
      val moduleDir = tmp.newFolder("metrics-workspace", "module")
      val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
      sourceFile.parentFile.mkdirs()
      sourceFile.writeText("@Preview fun Red() { /* v0 */ }")
      val baseMtime = System.currentTimeMillis() - 60_000
      assertThat(sourceFile.setLastModified(baseMtime)).isTrue()

      val ws =
        json
          .parseToJsonElement(
            freshClient
              .callTool(
                "register_project",
                buildJsonObject {
                  put("path", projectDir.absolutePath)
                  put("rootProjectName", "metrics-demo")
                },
              )
              .firstTextContent()
          )
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)
      val workspaceId = WorkspaceId(ws)
      freshSupervisor.daemonFor(workspaceId, ":module")
      val daemon = freshFactory.daemons.getValue(workspaceId to ":module")
      val previewId = "com.example.Red"
      daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)

      val pngFile = tmp.newFile("metrics-render.png")
      Files.write(pngFile.toPath(), byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
      daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

      val uri = PreviewUri(workspaceId, ":module", previewId).toUri()

      // First read — discovery seeded the hash + mtime, so the probe sees no change.
      freshClient.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

      // Mtime-advancing edit.
      sourceFile.writeText("@Preview fun Red() { /* v1 */ }")
      assertThat(sourceFile.setLastModified(baseMtime + 5_000)).isTrue()
      freshClient.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

      // Frozen-mtime edit (slow path).
      sourceFile.writeText("@Preview fun Red() { /* v2 */ }")
      assertThat(sourceFile.setLastModified(baseMtime + 5_000)).isTrue()
      freshClient.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

      // No-op read — mtime same, content same.
      freshClient.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)

      val status =
        json
          .parseToJsonElement(freshClient.callTool("status").firstTextContent())
          .jsonObject["freshness"]!!
          .jsonObject
      val probes = status["probes"]!!.jsonObject
      assertThat(probes["total"]?.jsonPrimitive?.content?.toLong()).isEqualTo(4L)
      assertThat(probes["changedByMtime"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
      assertThat(probes["changedByHash"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
      assertThat(probes["unchangedByHash"]?.jsonPrimitive?.content?.toLong()).isEqualTo(2L)
      assertThat(probes["firstSighting"]?.jsonPrimitive?.content?.toLong()).isEqualTo(0L)
    } finally {
      runCatching { freshClient.close() }
      runCatching { freshSession.close() }
      runCatching { freshServer.shutdown() }
      runCatching { freshSupervisor.shutdown() }
    }
  }

  @Test
  fun `background poller forwards fileChanged when a frozen-mtime edit lands between renders`() {
    val freshFactory = FakeDaemonClientFactory()
    val freshSupervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = freshFactory)
    val freshServer =
      DaemonMcpServer(
        supervisor = freshSupervisor,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val freshSession = freshServer.newSession(input = serverFromClient, output = serverToClient)
    freshSession.start()
    val freshClient = McpTestClient(input = clientFromServer, output = clientToServer)
    try {
      freshClient.initialize()
      val projectDir = tmp.newFolder("poll-workspace")
      val moduleDir = tmp.newFolder("poll-workspace", "module")
      val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
      sourceFile.parentFile.mkdirs()
      sourceFile.writeText("@Preview fun Red() { /* v0 */ }")
      val baseMtime = System.currentTimeMillis() - 60_000
      assertThat(sourceFile.setLastModified(baseMtime)).isTrue()

      val ws =
        json
          .parseToJsonElement(
            freshClient
              .callTool(
                "register_project",
                buildJsonObject {
                  put("path", projectDir.absolutePath)
                  put("rootProjectName", "poll-demo")
                },
              )
              .firstTextContent()
          )
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)
      val workspaceId = WorkspaceId(ws)
      freshSupervisor.daemonFor(workspaceId, ":module")
      val daemon = freshFactory.daemons.getValue(workspaceId to ":module")
      val previewId = "com.example.Red"
      daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)

      // Edit content but freeze mtime — exactly the case the on-demand path would miss without
      // the hash fallback. The poller should nevertheless observe it.
      sourceFile.writeText("@Preview fun Red() { /* v1 */ }")
      assertThat(sourceFile.setLastModified(baseMtime)).isTrue()

      // Drain anything left over from setup.
      while (daemon.fileChanges.poll(0, TimeUnit.MILLISECONDS) != null) {}

      freshServer.runSourceFreshnessPoll()

      val fileChanged = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
      assertWithMessage("polling cycle should detect the frozen-mtime content edit")
        .that(fileChanged)
        .isNotNull()
      assertThat(fileChanged!!["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("source")

      val status =
        json
          .parseToJsonElement(freshClient.callTool("status").firstTextContent())
          .jsonObject["freshness"]!!
          .jsonObject
      val polling = status["polling"]!!.jsonObject
      assertThat(polling["cycles"]?.jsonPrimitive?.content?.toLong()).isAtLeast(1L)
      assertThat(polling["previewsScanned"]?.jsonPrimitive?.content?.toLong()).isAtLeast(1L)
      assertThat(polling["changesDetected"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
    } finally {
      runCatching { freshClient.close() }
      runCatching { freshSession.close() }
      runCatching { freshServer.shutdown() }
      runCatching { freshSupervisor.shutdown() }
    }
  }

  @Test
  fun `random-sampling probe classifies unchanged vs changed renders`() {
    val freshFactory = FakeDaemonClientFactory()
    val freshSupervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = freshFactory)
    val freshServer =
      DaemonMcpServer(
        supervisor = freshSupervisor,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val freshSession = freshServer.newSession(input = serverFromClient, output = serverToClient)
    freshSession.start()
    val freshClient = McpTestClient(input = clientFromServer, output = clientToServer)
    try {
      freshClient.initialize()
      val projectDir = tmp.newFolder("sampling-workspace")
      tmp.newFolder("sampling-workspace", "module")
      val ws =
        json
          .parseToJsonElement(
            freshClient
              .callTool(
                "register_project",
                buildJsonObject {
                  put("path", projectDir.absolutePath)
                  put("rootProjectName", "sampling-demo")
                },
              )
              .firstTextContent()
          )
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)
      val workspaceId = WorkspaceId(ws)
      freshSupervisor.daemonFor(workspaceId, ":module")
      val daemon = freshFactory.daemons.getValue(workspaceId to ":module")
      val previewId = "com.example.Red"
      daemon.emitDiscovery(previewId)
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)

      val pngFile = tmp.newFile("sampling-render.png")
      Files.write(pngFile.toPath(), byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
      daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

      // Probe 1 — daemon reports `unchanged: true` (deterministic preview).
      daemon.autoRenderUnchanged = { _ -> true }
      freshServer.runRandomSamplingProbe()
      assertThat(daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS))
        .isEqualTo(listOf(previewId))

      // Probe 2 — daemon reports `unchanged: false` (preview drifted with no source change).
      daemon.autoRenderUnchanged = { _ -> false }
      freshServer.runRandomSamplingProbe()
      assertThat(daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS))
        .isEqualTo(listOf(previewId))

      // Give the renderFinished notifications time to round-trip on the daemon's reader thread.
      val deadline = System.currentTimeMillis() + 2_000
      while (System.currentTimeMillis() < deadline) {
        val statusPayload =
          json
            .parseToJsonElement(freshClient.callTool("status").firstTextContent())
            .jsonObject["freshness"]!!
            .jsonObject["sampling"]!!
            .jsonObject
        val det = statusPayload["deterministic"]?.jsonPrimitive?.content?.toLong() ?: 0L
        val nondet = statusPayload["nondeterministic"]?.jsonPrimitive?.content?.toLong() ?: 0L
        if (det >= 1L && nondet >= 1L) break
        Thread.sleep(50)
      }

      val sampling =
        json
          .parseToJsonElement(freshClient.callTool("status").firstTextContent())
          .jsonObject["freshness"]!!
          .jsonObject["sampling"]!!
          .jsonObject
      assertThat(sampling["probes"]?.jsonPrimitive?.content?.toLong()).isEqualTo(2L)
      assertThat(sampling["deterministic"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
      assertThat(sampling["nondeterministic"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
      val recent =
        sampling["recentNondeterministicUris"]!!.jsonArray.mapNotNull {
          it.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
        }
      assertThat(recent).contains(PreviewUri(workspaceId, ":module", previewId).toUri())
    } finally {
      runCatching { freshClient.close() }
      runCatching { freshSession.close() }
      runCatching { freshServer.shutdown() }
      runCatching { freshSupervisor.shutdown() }
    }
  }

  @Test
  fun `manifest poller imports new previews when discoverPreviews rewrites previews-json`() {
    // Reproduces issue #834: a Gradle `discoverPreviews` re-run between renders rewrites the
    // module's `previews.json` to include new `@Preview` ids. The MCP server must pick that up
    // without a daemon restart so `render_preview` for the new id no longer fails with
    // `PreviewManifestRouter: no manifest entry`.
    val freshFactory = FakeDaemonClientFactory()
    val freshSupervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = freshFactory)
    val freshServer =
      DaemonMcpServer(
        supervisor = freshSupervisor,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val freshSession = freshServer.newSession(input = serverFromClient, output = serverToClient)
    freshSession.start()
    val freshClient = McpTestClient(input = clientFromServer, output = clientToServer)
    try {
      freshClient.initialize()
      val projectDir = tmp.newFolder("manifest-workspace")
      tmp.newFolder("manifest-workspace", "module")
      val manifestFile = tmp.newFile("manifest-workspace.previews.json")
      // Boot manifest — one entry. The supervisor's `synthesiseInitialDiscovery` reads it on
      // initialize, so the catalog already contains `Initial`.
      manifestFile.writeText(
        """{"previews":[{"id":"com.example.Initial","className":"com.example","methodName":"Initial","displayName":"Initial"}]}"""
      )
      val initialMtime = System.currentTimeMillis() - 60_000
      assertThat(manifestFile.setLastModified(initialMtime)).isTrue()

      freshFactory.daemonConfigurer = { fake ->
        fake.advertisedManifestPath = manifestFile.absolutePath
      }

      val ws =
        json
          .parseToJsonElement(
            freshClient
              .callTool(
                "register_project",
                buildJsonObject {
                  put("path", projectDir.absolutePath)
                  put("rootProjectName", "manifest-demo")
                },
              )
              .firstTextContent()
          )
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)
      val workspaceId = WorkspaceId(ws)
      freshSupervisor.daemonFor(workspaceId, ":module")

      // The synthesiseInitialDiscovery fan-out triggers a list_changed; drain it.
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)

      // Sanity: the boot id is in the catalog via `resources/list`.
      val listBefore =
        json
          .decodeFromJsonElement(
            ee.schimke.composeai.mcp.protocol.ListResourcesResult.serializer(),
            freshClient.request("resources/list"),
          )
          .resources
          .map { it.uri }
      assertThat(listBefore.any { it.contains("com.example.Initial") }).isTrue()

      // Gradle's `discoverPreviews` re-runs and rewrites the manifest with a new id.
      manifestFile.writeText(
        """
        {"previews":[
          {"id":"com.example.Initial","className":"com.example","methodName":"Initial","displayName":"Initial"},
          {"id":"com.example.WeatherForecast_Light","className":"com.example","methodName":"WeatherForecast_Light","displayName":"WeatherForecast_Light"}
        ]}
        """
          .trimIndent()
      )
      assertThat(manifestFile.setLastModified(initialMtime + 5_000)).isTrue()

      // Drive the poll explicitly (the scheduler is disabled).
      freshServer.runSourceFreshnessPoll()

      // The new id should now be visible to MCP clients without a restart.
      freshClient.expectNotification("notifications/resources/list_changed", 2_000)
      val listAfter =
        json
          .decodeFromJsonElement(
            ee.schimke.composeai.mcp.protocol.ListResourcesResult.serializer(),
            freshClient.request("resources/list"),
          )
          .resources
          .map { it.uri }
      assertThat(listAfter.any { it.contains("com.example.WeatherForecast_Light") }).isTrue()
      assertThat(listAfter.any { it.contains("com.example.Initial") }).isTrue()

      // And the manifest counters should reflect the reread + the diff.
      val manifest =
        json
          .parseToJsonElement(freshClient.callTool("status").firstTextContent())
          .jsonObject["freshness"]!!
          .jsonObject["manifest"]!!
          .jsonObject
      assertThat(manifest["rereads"]?.jsonPrimitive?.content?.toLong()).isAtLeast(1L)
      assertThat(manifest["previewsAdded"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
      assertThat(manifest["previewsRemoved"]?.jsonPrimitive?.content?.toLong()).isEqualTo(0L)

      // Idempotency: a second poll with no manifest change does not re-fire.
      freshServer.runSourceFreshnessPoll()
      val manifestAfter =
        json
          .parseToJsonElement(freshClient.callTool("status").firstTextContent())
          .jsonObject["freshness"]!!
          .jsonObject["manifest"]!!
          .jsonObject
      assertThat(manifestAfter["rereads"]?.jsonPrimitive?.content?.toLong()).isEqualTo(1L)
    } finally {
      runCatching { freshClient.close() }
      runCatching { freshSession.close() }
      runCatching { freshServer.shutdown() }
      runCatching { freshSupervisor.shutdown() }
    }
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
            kind = "layout/inspector",
            schemaVersion = 1,
            transport = ee.schimke.composeai.daemon.protocol.DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
      daemon.dataFetchHandler = { _, kind, perKindParams, _ ->
        // The wire call must receive the params verbatim — that's how kinds with sub-views (e.g.
        // layout/inspector filtered by nodeId) work.
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
          put("kind", "layout/inspector")
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
          put("kind", "layout/inspector")
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
        globalAttachDataProducts = listOf("a11y/atf", "layout/inspector"),
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
        .containsExactly("a11y/atf", "layout/inspector")
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
  // `render_preview.force` — sanctioned escape hatch for stale renders.
  // Issue #924 tracks reports; the field exists to take `rm -rf build/classes/`
  // off the table for agents who'd otherwise reach for it.
  // -------------------------------------------------------------------------

  @Test
  fun `render_preview with force forwards fileChanged classpath before renderNow`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val sourceFile = moduleDir.resolve("src/main/kotlin/com/example/Preview.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText("@Preview fun Red() {}")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId, sourceFile = "src/main/kotlin/com/example/Preview.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("force-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.callTool(
      "render_preview",
      buildJsonObject {
        put("uri", uri)
        putJsonObject("force") { put("reason", "edit to Preview.kt didn't reflect") }
      },
      timeoutMs = 10_000,
    )

    val fileChanged = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
    assertWithMessage("force must forward fileChanged before renderNow")
      .that(fileChanged)
      .isNotNull()
    assertThat(fileChanged!!["kind"]?.jsonPrimitive?.contentOrNull).isEqualTo("classpath")
    assertThat(fileChanged["changeType"]?.jsonPrimitive?.contentOrNull).isEqualTo("modified")
    // Path defaults to the catalogued source file when present.
    assertThat(fileChanged["path"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("src/main/kotlin/com/example/Preview.kt")

    assertThat(daemon.renderRequests.poll(2_000, TimeUnit.MILLISECONDS))
      .isEqualTo(listOf(previewId))
  }

  @Test
  fun `render_preview rejects force without a non-empty reason`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    warmDaemonFor(workspaceId, ":module")

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val missingReason =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("force") {}
        },
      )
    assertThat(missingReason.firstTextContent()).contains("'force' requires a non-empty 'reason'")

    val blankReason =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("force") { put("reason", "   ") }
        },
      )
    assertThat(blankReason.firstTextContent()).contains("'force' requires a non-empty 'reason'")
  }

  @Test
  fun `force usage bumps forces metric and surfaces the reason via status`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.Red"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)

    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    val pngFile = tmp.newFile("force-metric.png")
    Files.write(pngFile.toPath(), pngBytes)
    daemon.autoRenderPngPath = { id -> if (id == previewId) pngFile.absolutePath else null }

    val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
    client.callTool(
      "render_preview",
      buildJsonObject {
        put("uri", uri)
        putJsonObject("force") { put("reason", "agent reached for rm -rf — caught by linter") }
      },
      timeoutMs = 10_000,
    )

    val statusResp = client.callTool("status", buildJsonObject {})
    val payload = json.parseToJsonElement(statusResp.firstTextContent()).jsonObject
    val forces = payload["freshness"]?.jsonObject?.get("forces")?.jsonObject!!
    assertThat(forces["used"]?.jsonPrimitive?.content).isEqualTo("1")
    val recent = forces["recent"]!!.jsonArray
    assertThat(recent).hasSize(1)
    val first = recent[0].jsonObject
    assertThat(first["uri"]?.jsonPrimitive?.contentOrNull).isEqualTo(uri)
    assertThat(first["reason"]?.jsonPrimitive?.contentOrNull)
      .isEqualTo("agent reached for rm -rf — caught by linter")
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

  private fun writeSolidPng(file: java.io.File, argb: Int) {
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        image.setRGB(x, y, argb)
      }
    }
    ImageIO.write(image, "png", file)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
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
    sendMessage(payload.toString())
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

  fun awaitToolsContaining(name: String, timeoutMs: Long = 5_000): ListToolsResult {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: ListToolsResult? = null
    while (System.currentTimeMillis() < deadline) {
      val result = request("tools/list", timeoutMs = deadline - System.currentTimeMillis())
      val tools = json.decodeFromJsonElement(ListToolsResult.serializer(), result)
      if (tools.tools.any { it.name == name }) return tools
      last = tools
      runCatching { expectNotification("notifications/tools/list_changed", 250) }
    }
    error(
      "tools/list did not contain '$name' after ${timeoutMs}ms; last names=${last?.tools?.map { it.name }}"
    )
  }

  fun notifyOnly(method: String, params: JsonElement?) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      if (params != null) put("params", params)
    }
    sendMessage(payload.toString())
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
        val line = readMessage(input) ?: break
        val obj = json.parseToJsonElement(line).jsonObject
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

  private fun sendMessage(jsonText: String) {
    synchronized(output) {
      output.write(jsonText.toByteArray(Charsets.UTF_8))
      output.write('\n'.code)
      output.flush()
    }
  }

  private fun readMessage(input: InputStream): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val b = input.read()
      if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
      if (b == '\n'.code) {
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
          bytes.removeAt(bytes.lastIndex)
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
      }
      bytes.add(b.toByte())
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

  /** First image-content block; returns `(base64Data, mimeType)`. */
  fun firstImageContent(): Pair<String, String> {
    val content = raw["content"]?.jsonArray ?: error("tool result has no content array")
    val image =
      content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image" }
        ?: error("tool result has no image content block (content=$content)")
    val obj = image.jsonObject
    val data = obj["data"]?.jsonPrimitive?.content ?: error("image block has no 'data'")
    val mime = obj["mimeType"]?.jsonPrimitive?.content ?: error("image block has no 'mimeType'")
    return data to mime
  }

  /** Returns the first text-content block among the tool result's content blocks, if any. */
  fun textContents(): List<String> {
    val content = raw["content"]?.jsonArray ?: return emptyList()
    return content.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
  }

  /**
   * First `EmbeddedResource` content block (MCP `type: "resource"`); returns `(blobBase64,
   * mimeType, uri)`. Used for non-image binary payloads (mp4 / webm) where `ContentBlock.Image`
   * would be the wrong shape.
   */
  fun firstEmbeddedResourceBlob(): Triple<String, String, String> {
    val content = raw["content"]?.jsonArray ?: error("tool result has no content array")
    val block =
      content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "resource" }
        ?: error("tool result has no resource content block (content=$content)")
    val resource =
      block.jsonObject["resource"]?.jsonObject
        ?: error("embedded-resource block has no 'resource' object")
    val blob = resource["blob"]?.jsonPrimitive?.content ?: error("resource has no 'blob'")
    val mime = resource["mimeType"]?.jsonPrimitive?.content ?: error("resource has no 'mimeType'")
    val uri = resource["uri"]?.jsonPrimitive?.content ?: error("resource has no 'uri'")
    return Triple(blob, mime, uri)
  }

  fun isError(): Boolean = raw["isError"]?.jsonPrimitive?.contentOrNull == "true"
}
