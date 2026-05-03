package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock as SdkContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.SubscribeRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.UnsubscribeRequest
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transport-agnostic session surface. The concrete stdio implementation now comes from the official
 * Kotlin MCP SDK; daemon orchestration only needs this notification surface.
 */
interface Session {
  /** Sends `notifications/resources/updated` for [uri]. */
  fun notifyResourceUpdated(uri: String)

  /** Sends `notifications/resources/list_changed`. */
  fun notifyResourceListChanged()

  /** Sends `notifications/tools/list_changed`. */
  fun notifyToolListChanged()

  /**
   * Sends a `notifications/progress` for the request identified by [token]. No-op when the client
   * didn't opt in or sent a token shape the SDK cannot represent.
   */
  fun notifyProgress(
    token: JsonElement,
    progress: Double,
    total: Double? = null,
    message: String? = null,
  )
}

/** SDK-backed stdio session with the same lifecycle shape the old hand-rolled session exposed. */
class McpSession(
  private val server: Server,
  private val input: InputStream,
  private val output: OutputStream,
  private val configure: (ServerSession) -> Unit,
  private val onClose: () -> Unit,
) : Closeable, Session {
  private val closed = CompletableFuture<Unit>()
  @Volatile private var sdkSession: ServerSession? = null
  private val thread =
    Thread(
        {
          try {
            runBlocking(Dispatchers.IO) {
              val session =
                server.createSession(
                  StdioServerTransport(input.asSource().buffered(), output.asSink().buffered())
                )
              sdkSession = session
              session.onClose {
                closed.complete(Unit)
                onClose()
              }
              configure(session)
              while (!closed.isDone) {
                delay(100)
              }
            }
          } catch (t: Throwable) {
            System.err.println("compose-preview-mcp: SDK stdio session failed: ${t.message}")
            t.printStackTrace(System.err)
          } finally {
            onClose()
          }
        },
        "mcp-sdk-stdio-session",
      )
      .apply { isDaemon = true }

  fun start() {
    thread.start()
  }

  fun awaitClose() {
    thread.join()
  }

  override fun close() {
    runBlocking { server.close() }
    closed.complete(Unit)
    thread.join(2_000)
  }

  override fun notifyResourceUpdated(uri: String) {
    val session = sdkSession ?: return
    runBlocking {
      session.sendResourceUpdated(
        ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = uri))
      )
    }
  }

  override fun notifyResourceListChanged() {
    val session = sdkSession ?: return
    runBlocking { session.sendResourceListChanged() }
  }

  override fun notifyToolListChanged() {
    val session = sdkSession ?: return
    runBlocking { session.sendToolListChanged() }
  }

  override fun notifyProgress(
    token: JsonElement,
    progress: Double,
    total: Double?,
    message: String?,
  ) {
    val progressToken = token.toRequestId() ?: return
    val session = sdkSession ?: return
    runBlocking {
      session.notification(
        ProgressNotification(
          ProgressNotificationParams(
            progressToken = progressToken,
            progress = progress,
            total = total,
            message = message,
          )
        ),
        progressToken,
      )
    }
  }
}

/** Tracks every live [Session] so notifications can fan out to multiple connected clients. */
class SessionRegistry {
  private val sessions = ConcurrentHashMap.newKeySet<Session>()

  fun register(session: Session) {
    sessions.add(session)
  }

  fun unregister(session: Session) {
    sessions.remove(session)
  }

  fun forEach(block: (Session) -> Unit) {
    sessions.forEach { runCatching { block(it) } }
  }
}

internal fun Server.installComposePreviewHandlers(
  sdkSession: ServerSession,
  session: Session,
  listTools: () -> List<ToolDef>,
  callTool: (name: String, arguments: JsonElement?) -> CallToolResult,
  listResources: () -> List<ee.schimke.composeai.mcp.protocol.ResourceDescriptor>,
  readResource:
    (
      uri: String, progressToken: JsonElement?,
    ) -> ee.schimke.composeai.mcp.protocol.ReadResourceResult,
  subscribe: (uri: String) -> Unit,
  unsubscribe: (uri: String) -> Unit,
) {
  sdkSession.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ ->
    ListToolsResult(tools = listTools().map { it.toSdkTool() }, nextCursor = null)
  }
  sdkSession.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
    callTool(request.name, request.arguments).toSdkCallToolResult()
  }
  sdkSession.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, _ ->
    ListResourcesResult(resources = listResources().map { it.toSdkResource() }, nextCursor = null)
  }
  sdkSession.setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, _ ->
    val progressToken = request.meta?.json?.get("progressToken")
    readResource(request.uri, progressToken).toSdkReadResourceResult()
  }
  sdkSession.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
    subscribe(request.uri)
    EmptyResult()
  }
  sdkSession.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _
    ->
    unsubscribe(request.uri)
    EmptyResult()
  }
}

internal fun composePreviewSdkServer(serverInfo: Implementation): Server =
  Server(
    serverInfo = serverInfo,
    options =
      ServerOptions(
        capabilities =
          ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
            resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
          )
      ),
  )

private fun ToolDef.toSdkTool(): Tool {
  val schemaObject = inputSchema.jsonObject
  return Tool(
    name = name,
    inputSchema =
      ToolSchema(
        properties = schemaObject["properties"] as? JsonObject ?: JsonObject(emptyMap()),
        required =
          (schemaObject["required"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          },
      ),
    description = description,
  )
}

private fun ee.schimke.composeai.mcp.protocol.ResourceDescriptor.toSdkResource(): Resource =
  Resource(uri = uri, name = name, description = description, mimeType = mimeType)

private fun ee.schimke.composeai.mcp.protocol.ReadResourceResult.toSdkReadResourceResult():
  ReadResourceResult = ReadResourceResult(contents = contents.map { it.toSdkResourceContents() })

private fun ee.schimke.composeai.mcp.protocol.ResourceContents.toSdkResourceContents():
  ResourceContents =
  when (this) {
    is ee.schimke.composeai.mcp.protocol.ResourceContents.Text ->
      TextResourceContents(text = text, uri = uri, mimeType = mimeType)
    is ee.schimke.composeai.mcp.protocol.ResourceContents.Blob ->
      BlobResourceContents(blob = blob, uri = uri, mimeType = mimeType)
  }

private fun CallToolResult.toSdkCallToolResult():
  io.modelcontextprotocol.kotlin.sdk.types.CallToolResult =
  io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(
    content = content.map { it.toSdkContent() },
    isError = isError ?: false,
  )

private fun ContentBlock.toSdkContent(): SdkContentBlock =
  when (this) {
    is ContentBlock.Text -> TextContent(text = text)
    is ContentBlock.Image -> ImageContent(data = data, mimeType = mimeType)
    is ContentBlock.EmbeddedResource ->
      EmbeddedResource(resource = resource.toSdkResourceContents())
  }

private fun JsonElement.toRequestId(): RequestId? =
  when (this) {
    is JsonPrimitive -> {
      contentOrNull?.toLongOrNull()?.let { RequestId.NumberId(it) }
        ?: contentOrNull?.let { RequestId.StringId(it) }
    }
    else -> null
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
