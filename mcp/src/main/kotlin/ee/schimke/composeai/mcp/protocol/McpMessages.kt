package ee.schimke.composeai.mcp.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Trimmed MCP message shapes for the v0 server. We intentionally do not pull
// in `io.modelcontextprotocol:kotlin-sdk`: the surface we need is small and
// the SDK's auto-registered internal handlers complicate the dynamic catalog
// model the supervisor wants. See ../build.gradle.kts for rationale.
//
// References:
// - https://modelcontextprotocol.io/specification/2025-06-18/basic
// - https://modelcontextprotocol.io/specification/2025-06-18/server/resources
// - https://modelcontextprotocol.io/specification/2025-06-18/server/tools
// ---------------------------------------------------------------------------

// =====================================================================
// JSON-RPC envelope (id is string | number per spec; we accept both via
// JsonElement and let the caller round-trip the original on responses).
// =====================================================================

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpRequest(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: JsonElement,
  val method: String,
  val params: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpResponse(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: JsonElement,
  val result: JsonElement? = null,
  val error: McpError? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpNotification(
  @EncodeDefault val jsonrpc: String = "2.0",
  val method: String,
  val params: JsonElement? = null,
)

@Serializable data class McpError(val code: Int, val message: String, val data: JsonElement? = null)

object McpErrorCodes {
  const val PARSE_ERROR: Int = -32700
  const val INVALID_REQUEST: Int = -32600
  const val METHOD_NOT_FOUND: Int = -32601
  const val INVALID_PARAMS: Int = -32602
  const val INTERNAL_ERROR: Int = -32603
}

// =====================================================================
// initialize
// =====================================================================

@Serializable
data class InitializeParams(
  val protocolVersion: String,
  val capabilities: ClientCapabilities = ClientCapabilities(),
  val clientInfo: Implementation? = null,
)

@Serializable
data class InitializeResult(
  val protocolVersion: String,
  val capabilities: ServerCapabilities,
  val serverInfo: Implementation,
  val instructions: String? = null,
)

@Serializable data class Implementation(val name: String, val version: String)

@Serializable data class ClientCapabilities(val experimental: Map<String, JsonElement>? = null)

@Serializable
data class ServerCapabilities(
  val tools: ToolsCapability? = null,
  val resources: ResourcesCapability? = null,
)

@Serializable data class ToolsCapability(val listChanged: Boolean = false)

@Serializable
data class ResourcesCapability(val subscribe: Boolean = false, val listChanged: Boolean = false)

// =====================================================================
// tools/list, tools/call
// =====================================================================

@Serializable data class ListToolsResult(val tools: List<ToolDef>)

@Serializable
data class ToolDef(val name: String, val description: String, val inputSchema: JsonElement)

@Serializable data class CallToolParams(val name: String, val arguments: JsonElement? = null)

@Serializable
data class CallToolResult(val content: List<ContentBlock>, val isError: Boolean? = null)

@Serializable
sealed interface ContentBlock {
  @Serializable @SerialName("text") data class Text(val text: String) : ContentBlock

  @Serializable
  @SerialName("image")
  data class Image(val data: String, val mimeType: String) : ContentBlock

  /**
   * MCP 2025-06-18 spec — `EmbeddedResource` content block. Wraps a [ResourceContents] (text or
   * blob) so a tool can return non-image binary payloads (audio, video, arbitrary `application`
   * mime types) without misusing the `image` block — strict clients reject mismatched mimeTypes on
   * `image`.
   *
   * Use this for `record_preview` mp4/webm responses (mimeType `video/mp4` / `video/webm`) and any
   * other tool that needs to inline non-image bytes. The wrapped [ResourceContents.Blob] carries
   * the same `{uri, mimeType, blob}` shape `resources/read` uses, so a client that already knows
   * how to render resources reads the same code path.
   */
  @Serializable
  @SerialName("resource")
  data class EmbeddedResource(val resource: ResourceContents) : ContentBlock
}

// =====================================================================
// resources/list, resources/read, resources/subscribe, resources/unsubscribe
// =====================================================================

@Serializable
data class ListResourcesResult(
  val resources: List<ResourceDescriptor>,
  val nextCursor: String? = null,
)

@Serializable
data class ResourceDescriptor(
  val uri: String,
  val name: String,
  val description: String? = null,
  val mimeType: String? = null,
  val size: Long? = null,
)

@Serializable data class ReadResourceParams(val uri: String)

@Serializable data class ReadResourceResult(val contents: List<ResourceContents>)

@Serializable
sealed interface ResourceContents {
  @Serializable
  @SerialName("text")
  data class Text(val uri: String, val mimeType: String? = null, val text: String) :
    ResourceContents

  @Serializable
  @SerialName("blob")
  data class Blob(val uri: String, val mimeType: String? = null, val blob: String) :
    ResourceContents
}

@Serializable data class SubscribeParams(val uri: String)

@Serializable data class UnsubscribeParams(val uri: String)

// =====================================================================
// notifications: resources/updated, resources/list_changed
// =====================================================================

@Serializable data class ResourceUpdatedParams(val uri: String)
