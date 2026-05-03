package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.daemon.protocol.BackendKind
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
 * MCP integration coverage for issue #470: capability fields must flow from a real [JsonRpcServer]
 * initialize response, through [DaemonSupervisor], into the MCP tool behavior.
 *
 * This stays CI-friendly by using a real daemon protocol server with a tiny in-process
 * [RenderHost]. Fake-daemon tests still cover most MCP behavior, but they bypass the daemon
 * initialize projection that maps [RenderHost.supportedOverrides], known devices, and backend kind
 * into the wire shape.
 */
class DaemonMcpLiveCapabilityTest {

  @get:Rule val tmp = TemporaryFolder()

  private lateinit var supervisor: DaemonSupervisor
  private lateinit var factory: JsonRpcDaemonClientFactory
  private lateinit var server: DaemonMcpServer
  private lateinit var session: McpSession
  private lateinit var client: McpTestClient

  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    val pngFile = tmp.newFile("live-capability.png")
    pngFile.writeBytes(ONE_PIXEL_PNG)
    factory = JsonRpcDaemonClientFactory(pngFile)
    supervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = factory,
        replicasPerDaemon = 0,
      )
    server = DaemonMcpServer(supervisor, renderTimeoutMs = 10_000)

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
  fun `mcp tool behavior uses capabilities advertised by live json rpc daemon`() {
    client.initialize()
    val projectDir = tmp.newFolder("workspace")
    val workspaceId = registerWorkspace(projectDir, "demo")
    val daemon = supervisor.daemonFor(workspaceId, ":module")
    val host = factory.hosts.single()

    assertThat(daemon.backendKind).isEqualTo(BackendKind.DESKTOP)
    assertThat(daemon.supportedOverrides)
      .containsExactly("widthPx", "heightPx", "density", "device")
    assertThat(daemon.knownDeviceIds).isNotEmpty()

    val listDevicesPayload =
      json.parseToJsonElement(client.callTool("list_devices").firstTextContent())
    val listedDeviceIds =
      listDevicesPayload.jsonObject["devices"]!!.jsonArray.map {
        it.jsonObject["id"]!!.jsonPrimitive.content
      }
    assertThat(listedDeviceIds).containsExactlyElementsIn(daemon.knownDeviceIds.sorted()).inOrder()

    val uri = PreviewUri(workspaceId, ":module", "com.example.Red").toUri()
    val rejected =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("overrides") { put("orientation", "landscape") }
        },
      )
    assertThat(rejected.isError()).isTrue()
    assertThat(rejected.firstTextContent()).contains("does not apply 'orientation'")
    assertThat(host.renderRequests).isEmpty()

    val rendered =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          putJsonObject("overrides") { put("widthPx", 123) }
        },
        timeoutMs = 10_000,
      )
    assertThat(rendered.isError()).isFalse()
    val (imageBase64, mimeType) = rendered.firstImageContent()
    assertThat(mimeType).isEqualTo("image/png")
    assertThat(Base64.getDecoder().decode(imageBase64)).isEqualTo(ONE_PIXEL_PNG)
    val acceptedRequest = host.renderRequests.poll(2, TimeUnit.SECONDS)
    assertThat(acceptedRequest).isNotNull()
  }

  private fun registerWorkspace(projectDir: File, rootName: String): WorkspaceId {
    val resp =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", projectDir.absolutePath)
          put("rootProjectName", rootName)
        },
      )
    val payload = json.parseToJsonElement(resp.firstTextContent()).jsonObject
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(payload["workspaceId"]!!.jsonPrimitive.content)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val out = PipedOutputStream()
    val input = PipedInputStream(out, BUFFER)
    return out to input
  }

  private companion object {
    val ONE_PIXEL_PNG: ByteArray =
      Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxBk3QAAAABJRU5ErkJggg=="
        )
  }
}

private class JsonRpcDaemonClientFactory(private val pngFile: File) : DaemonClientFactory {
  val hosts = java.util.concurrent.CopyOnWriteArrayList<CapabilityRenderHost>()

  override fun spawn(project: RegisteredProject, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    val host = CapabilityRenderHost(pngFile)
    hosts += host
    return JsonRpcDaemonSpawn(host)
  }
}

private class JsonRpcDaemonSpawn(private val host: CapabilityRenderHost) : DaemonSpawn {
  private val clientToDaemon = PipedOutputStream()
  private val daemonInput = PipedInputStream(clientToDaemon, BUFFER)
  private val daemonToClient = PipedOutputStream()
  private val clientInput = PipedInputStream(daemonToClient, BUFFER)
  private lateinit var serverThread: Thread
  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = clientInput,
        output = clientToDaemon,
        onNotification = onNotification,
        onClose = onClose,
        threadName = "mcp-jsonrpc-capability-daemon-client",
      )
    val server =
      JsonRpcServer(
        input = daemonInput,
        output = daemonToClient,
        host = host,
        idleTimeoutMs = 1_000,
        onExit = {},
      )
    serverThread =
      Thread({ server.run() }, "mcp-jsonrpc-capability-daemon").apply {
        isDaemon = true
        start()
      }
    return _client
  }

  override fun shutdown() {
    runCatching { _client.shutdownAndExit() }
    if (::serverThread.isInitialized) {
      serverThread.join(5_000)
    }
    runCatching { _client.close() }
    runCatching { clientToDaemon.close() }
    runCatching { daemonToClient.close() }
    runCatching { daemonInput.close() }
    runCatching { clientInput.close() }
  }
}

private class CapabilityRenderHost(private val pngFile: File) : RenderHost {
  val renderRequests = LinkedBlockingQueue<RenderRequest.Render>()

  override val supportedOverrides: Set<String> = setOf("widthPx", "heightPx", "density", "device")
  override val backendKind: BackendKind = BackendKind.DESKTOP

  override fun start() = Unit

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    val render = request as RenderRequest.Render
    renderRequests.put(render)
    return RenderResult(
      id = render.id,
      classLoaderHashCode = System.identityHashCode(javaClass.classLoader),
      classLoaderName = javaClass.classLoader?.javaClass?.name ?: "bootstrap",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to 1L),
    )
  }

  override fun shutdown(timeoutMs: Long) = Unit
}

private const val BUFFER = 64 * 1024
