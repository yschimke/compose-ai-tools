package ee.schimke.composeai.mcp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------
// Internal DTOs for the daemon-facing tool/resource catalog.
//
// The MCP Kotlin SDK owns the wire and session layer — transport, JSON-RPC
// framing, request dispatch, and the `initialize` handshake. These types are
// deliberately NOT a second implementation of that: they are the boundary
// between the SDK and the ~5k lines of tool/resource code in `DaemonMcpServer`,
// so an SDK bump touches one adapter (`McpServer.kt`'s `toSdk*` functions)
// rather than every tool. That indirection is the reason the file still exists.
//
// What it must NOT hold is a parallel copy of a shape the SDK already owns on
// the wire. It used to: an entire JSON-RPC envelope layer (`McpRequest` /
// `McpResponse` / `McpNotification` / `McpError` / `McpErrorCodes`), the
// `initialize` handshake (`InitializeParams` / `InitializeResult` /
// `Implementation` / `ClientCapabilities` / `ServerCapabilities` /
// `ToolsCapability` / `ResourcesCapability`), and the request-param types
// (`CallToolParams` / `ReadResourceParams` / `SubscribeParams` /
// `UnsubscribeParams` / `ResourceUpdatedParams`). Every one was superseded by
// the SDK and left behind, referenced by nothing.
//
// Several were actively hazardous rather than merely dead, because they shared
// a simple name with a live type from another package: `InitializeResult`,
// `ServerCapabilities`, `ClientCapabilities` and `InitializeParams` all also
// exist in `ee.schimke.composeai.daemon.protocol` (the daemon's own wire
// contract, which is a different protocol), and `Implementation` /
// `ServerCapabilities` also exist in the SDK. An IDE auto-import had three
// candidates for one name, only one of them correct, and picking the dead one
// compiled clean.
//
// Keep that shape: types here exist to decouple the tool catalog, not to
// restate the SDK. If a new type would just mirror an SDK wire shape, use the
// SDK's.
//
// References:
// - https://modelcontextprotocol.io/specification/2025-06-18/basic
// - https://modelcontextprotocol.io/specification/2025-06-18/server/resources
// - https://modelcontextprotocol.io/specification/2025-06-18/server/tools
// ---------------------------------------------------------------------------

// =====================================================================
// tools/list, tools/call
// =====================================================================

@Serializable
data class ToolDef(val name: String, val description: String, val inputSchema: JsonElement)

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
// resources/list, resources/read
// =====================================================================

@Serializable
data class ResourceDescriptor(
  val uri: String,
  val name: String,
  val description: String? = null,
  val mimeType: String? = null,
  val size: Long? = null,
)

@Serializable data class ReadResourceResult(val contents: List<ResourceContents>)

@Serializable(with = ResourceContentsSerializer::class)
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

object ResourceContentsSerializer : KSerializer<ResourceContents> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun deserialize(decoder: Decoder): ResourceContents {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("ResourceContents can only be decoded from JSON")
    val element = jsonDecoder.decodeJsonElement()
    val obj = element.jsonObject
    return when {
      "text" in obj -> jsonDecoder.json.decodeFromJsonElement<ResourceContents.Text>(element)
      "blob" in obj -> jsonDecoder.json.decodeFromJsonElement<ResourceContents.Blob>(element)
      else -> throw SerializationException("ResourceContents must contain either 'text' or 'blob'")
    }
  }

  override fun serialize(encoder: Encoder, value: ResourceContents) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw SerializationException("ResourceContents can only be encoded to JSON")
    when (value) {
      is ResourceContents.Text ->
        jsonEncoder.encodeSerializableValue(ResourceContents.Text.serializer(), value)
      is ResourceContents.Blob ->
        jsonEncoder.encodeSerializableValue(ResourceContents.Blob.serializer(), value)
    }
  }
}
