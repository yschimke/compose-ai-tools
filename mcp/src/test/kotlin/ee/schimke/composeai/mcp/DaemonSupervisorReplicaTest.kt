package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SANDBOX-POOL.md Layer 3 — exercises the supervisor's single-JVM-with-sandbox-pool model.
 * `replicasPerDaemon = 2` no longer spawns 1+2 JVM subprocesses; it spawns **one** subprocess
 * configured (via the launch descriptor's `composeai.daemon.sandboxCount` sysprop) to host 3
 * sandboxes in-JVM. The daemon's `RobolectricHost` dispatches concurrent renders across those
 * sandboxes internally, so the wire-protocol-visible behaviour (one client per (workspace, module),
 * fan-out broadcasts, classpathDirty respawn) is preserved.
 *
 * The default `replicasPerDaemon = 0` is covered by [DaemonMcpServerTest]. Here we wire
 * `replicasPerDaemon = 2` and assert:
 *
 * - `daemonFor` produces exactly **one** [FakeDaemon] (not three) and its descriptor carries
 *   `composeai.daemon.sandboxCount = "3"`.
 * - `setVisible` / `notify_file_changed` fan out to that single daemon (callers that previously
 *   iterated `allClients()` see a singleton list — the loop runs once).
 * - `renderNow` lands on the single daemon for any preview id.
 * - `classpathDirty` tears down the single subprocess and respawns one fresh subprocess (still
 *   carrying the sandboxCount sysprop).
 */
class DaemonSupervisorReplicaTest {

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
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = factory,
        replicasPerDaemon = 2,
      )
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
  fun `daemonFor spawns one subprocess with sandboxCount sysprop`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForSpawns(expected = 1)
    // Layer 3 — one subprocess, not 1 + replicasPerDaemon. Concurrent capacity comes from the
    // daemon's in-JVM sandbox pool, not extra subprocesses.
    assertThat(factory.spawnHistory.size).isEqualTo(1)
    val daemon = supervisor.daemonFor(workspaceId, ":module")
    assertThat(daemon.replicaCount()).isEqualTo(1)
    assertThat(daemon.allClients()).hasSize(1)

    // The descriptor handed to `clientFactory.spawn` must carry the sandboxCount sysprop the
    // daemon's DaemonMain reads on boot. With replicasPerDaemon = 2 that's 1 + 2 = 3.
    val descriptor = factory.spawnDescriptors.single()
    assertThat(descriptor.systemProperties)
      .containsEntry(DaemonLaunchDescriptor.SANDBOX_COUNT_PROP, "3")
  }

  @Test
  fun `renderNow lands on the single daemon for any preview id`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForSpawns(expected = 1)
    val fake = factory.spawnHistory.single()

    // Stage a PNG and arm the daemon to auto-emit renderFinished. With Layer 3, sharding across
    // sandboxes happens INSIDE the daemon (RobolectricHost.submit dispatches via slot id);
    // from the supervisor's vantage there's just one client, so every render request lands here.
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    val pngFile = tmp.newFile("layer3-fake-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    val previewIds =
      listOf("com.example.Alpha", "com.example.Bravo", "com.example.Charlie", "com.example.Delta")
    fake.autoRenderPngPath = { _ -> pngFile.absolutePath }
    previewIds.forEach { fake.emitDiscovery(it) }
    repeat(previewIds.size) {
      client.expectNotification("notifications/resources/list_changed", 2_000)
    }

    previewIds.forEach { previewId ->
      val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
      val resp =
        client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 5_000)
      val parsed = json.decodeFromJsonElement(ReadResourceResult.serializer(), resp)
      val blob = parsed.contents.single() as ResourceContents.Blob
      assertThat(Base64.getDecoder().decode(blob.blob)).isEqualTo(pngBytes)

      val rendered = fake.renderRequests.poll(2_000, TimeUnit.MILLISECONDS)
      assertThat(rendered).isEqualTo(listOf(previewId))
    }
  }

  @Test
  fun `setVisible reaches the single daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForSpawns(expected = 1)
    val fake = factory.spawnHistory.single()
    fake.emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    client.callTool(
      "watch",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("module", ":module")
        put("fqnGlob", "com.example.Red")
      },
    )

    // Fan-out callers (`daemon.allClients().forEach { … }` in DaemonMcpServer / WatchPropagator)
    // iterate a singleton list under Layer 3. The single daemon must observe its setVisible /
    // setFocus exactly once.
    val visible = fake.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
    val focus = fake.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(visible).isEqualTo(listOf("com.example.Red"))
    assertThat(focus).isEqualTo(listOf("com.example.Red"))
  }

  @Test
  fun `notify_file_changed forwards fileChanged to the single daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForSpawns(expected = 1)

    val callResp =
      client.callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("path", "/tmp/example.kt")
        },
      )
    val text = callResp.firstTextContent()
    // Layer 3: one daemon per module, so the count drops from 1 + replicasPerDaemon to 1 — the
    // forwarded-to count reflects the new single-subprocess shape.
    assertThat(text).contains("forwarded to 1 daemon(s)")
  }

  @Test
  fun `classpathDirty tears down the single subprocess and respawns one`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForSpawns(expected = 1)
    val firstSpawn = factory.spawnHistory.single()

    firstSpawn.emitClasspathDirty()

    // Wait for the supervisor's async respawn to construct a single fresh FakeDaemon.
    val deadline = System.currentTimeMillis() + 10_000
    while (factory.spawnHistory.size < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    assertThat(factory.spawnHistory.size).isAtLeast(2)
    val second = factory.spawnHistory[1]
    assertThat(second).isNotSameInstanceAs(firstSpawn)
    val newDaemon = supervisor.daemonFor(workspaceId, ":module")
    assertThat(newDaemon.replicaCount()).isEqualTo(1)

    // The respawn descriptor must still carry the sandboxCount sysprop — otherwise a respawned
    // pool would silently downgrade to a single-sandbox daemon and the user's render concurrency
    // would halve after the first classpathDirty event.
    val secondDescriptor = factory.spawnDescriptors[1]
    assertThat(secondDescriptor.systemProperties)
      .containsEntry(DaemonLaunchDescriptor.SANDBOX_COUNT_PROP, "3")
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
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    val ws = payload["workspaceId"]!!.jsonPrimitive.content
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(ws)
  }

  /**
   * Polls [FakeDaemonClientFactory.spawnHistory] until [expected] subprocesses have been spawned.
   */
  private fun waitForSpawns(expected: Int, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (factory.spawnHistory.size < expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }
    assertThat(factory.spawnHistory.size).isAtLeast(expected)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val out = PipedOutputStream()
    val input = PipedInputStream(out, 64 * 1024)
    return out to input
  }
}
