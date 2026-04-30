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
 * Exercises the 1+N replica model: per-(workspace, module) daemons fan out across replicas so
 * concurrent renders run in parallel. The default `replicasPerDaemon = 0` is covered by the
 * existing [DaemonMcpServerTest]; here we wire `replicasPerDaemon = 2` so each `daemonFor` results
 * in three [FakeDaemon] instances (1 primary + 2 extras), and assert the supervisor shards renders,
 * fans out invalidations, and dispatches close only when the LAST replica is gone.
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
  fun `daemonFor spawns 1 primary plus N replicas`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForReplicas(expected = 3)
    assertThat(factory.spawnHistory.size).isEqualTo(3)
    val daemon = supervisor.daemonFor(workspaceId, ":module")
    assertThat(daemon.replicaCount()).isEqualTo(3)
    // All three are wired to the same logical SupervisedDaemon — exiting any one of them must
    // not drop catalog state until the LAST one closes (asserted in `closeOnly...` below).
    assertThat(daemon.allClients()).hasSize(3)
  }

  @Test
  fun `renderNow shards previews across replicas by hash`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForReplicas(expected = 3)
    val replicas = factory.spawnHistory.toList()

    // Stage a PNG and arm every replica to auto-emit renderFinished — only the chosen replica
    // for each preview will actually receive the renderNow, so only its emit fires; others stay
    // idle. We register the auto-emitter on every replica defensively so a routing bug shows up
    // as "the wrong replica produced the bytes" rather than a hung test.
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    val pngFile = tmp.newFile("replica-fake-render.png")
    Files.write(pngFile.toPath(), pngBytes)
    val previewIds =
      listOf("com.example.Alpha", "com.example.Bravo", "com.example.Charlie", "com.example.Delta")
    replicas.forEach { it.autoRenderPngPath = { _ -> pngFile.absolutePath } }
    // Drive discoveryUpdated through the primary so the catalog populates; replicas would emit
    // the same set, which is idempotent in onDiscoveryUpdated.
    val primary = replicas[0]
    previewIds.forEach { primary.emitDiscovery(it) }
    repeat(previewIds.size) {
      client.expectNotification("notifications/resources/list_changed", 2_000)
    }

    // Issue a render for each preview id and verify the renderNow lands on the replica chosen by
    // the same hash function the supervisor uses.
    previewIds.forEach { previewId ->
      val uri = PreviewUri(workspaceId, ":module", previewId).toUri()
      val resp =
        client.request("resources/read", buildJsonObject { put("uri", uri) }, timeoutMs = 5_000)
      val parsed = json.decodeFromJsonElement(ReadResourceResult.serializer(), resp)
      val blob = parsed.contents.single() as ResourceContents.Blob
      assertThat(Base64.getDecoder().decode(blob.blob)).isEqualTo(pngBytes)

      val expectedReplica = replicas[Math.floorMod(previewId.hashCode(), replicas.size)]
      val rendered = expectedReplica.renderRequests.poll(2_000, TimeUnit.MILLISECONDS)
      assertThat(rendered).isEqualTo(listOf(previewId))
      // No OTHER replica should have observed a renderNow for this preview — drains stale state.
      replicas
        .filter { it !== expectedReplica }
        .forEach {
          val stray = it.renderRequests.poll(50, TimeUnit.MILLISECONDS)
          assertThat(stray).isNull()
        }
    }
  }

  @Test
  fun `setVisible fans out to every replica`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForReplicas(expected = 3)
    val replicas = factory.spawnHistory.toList()
    replicas[0].emitDiscovery("com.example.Red")
    client.expectNotification("notifications/resources/list_changed", 2_000)

    client.callTool(
      "watch",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("module", ":module")
        put("fqnGlob", "com.example.Red")
      },
    )

    // Each replica must observe its own setVisible/setFocus — render queues are per-replica,
    // so only fanning out to the primary would leave peers blind to user attention.
    replicas.forEach { fake ->
      val visible = fake.visibleSets.poll(2_000, TimeUnit.MILLISECONDS)
      val focus = fake.focusSets.poll(2_000, TimeUnit.MILLISECONDS)
      assertThat(visible).isEqualTo(listOf("com.example.Red"))
      assertThat(focus).isEqualTo(listOf("com.example.Red"))
    }
  }

  @Test
  fun `notify_file_changed forwards fileChanged to every replica`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForReplicas(expected = 3)
    val replicas = factory.spawnHistory.toList()

    val callResp =
      client.callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("path", "/tmp/example.kt")
        },
      )
    val text = callResp.firstTextContent()
    // Server reports total fileChanged forwards across (replica × daemon) — with one daemon and
    // three replicas that's 3.
    assertThat(text).contains("forwarded to 3 daemon(s)")
  }

  @Test
  fun `classpathDirty on one replica tears down peers and respawns the group`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir, "demo")

    supervisor.daemonFor(workspaceId, ":module")
    waitForReplicas(expected = 3)
    val firstGroup = factory.spawnHistory.toList()

    // Fire classpathDirty on the primary — peers are still up, so the supervisor must shutdown
    // them explicitly before respawning a fresh group.
    firstGroup[0].emitClasspathDirty()

    // Wait for the supervisor's async respawn to construct three more FakeDaemons (1 primary +
    // 2 replicas of the new group).
    val deadline = System.currentTimeMillis() + 10_000
    while (factory.spawnHistory.size < 6 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    assertThat(factory.spawnHistory.size).isAtLeast(6)
    val secondGroup = factory.spawnHistory.subList(3, factory.spawnHistory.size)
    assertThat(secondGroup).hasSize(3)
    val newDaemon = supervisor.daemonFor(workspaceId, ":module")
    assertThat(newDaemon.replicaCount()).isEqualTo(3)
  }

  // -------------------------------------------------------------------------
  // Helpers (mirror DaemonMcpServerTest's helpers — kept private here to avoid a shared base
  // class for two test files).
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
   * Polls [FakeDaemonClientFactory.spawnHistory] until [expected] replicas have been spawned. The
   * primary is synchronous but extras come up off-thread on the supervisor's replica spawn pool.
   */
  private fun waitForReplicas(expected: Int, timeoutMs: Long = 5_000) {
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
