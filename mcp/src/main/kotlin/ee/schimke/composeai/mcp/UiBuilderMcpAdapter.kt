package ee.schimke.composeai.mcp

import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetDeltaRequestV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_SCHEMA_VERSION_V1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Thin MCP facade over preview-server's versioned UI-builder Design API.
 *
 * This adapter owns no design state, reducer, renderer, or persistence. Browser and agent edits
 * therefore meet at the same authenticated service boundary and observe the same revisions.
 */
class UiBuilderMcpAdapter internal constructor(private val client: UiBuilderDesignApiClient) {
  fun toolDefs(): List<ToolDef> =
    listOf(
      tool(
        "create_design",
        "Create and persist a UI-builder design from a complete v1 design document.",
        """{"type":"object","properties":{"document":{"type":"object"}},"required":["document"]}""",
      ),
      tool(
        "open_design",
        "Open the latest committed snapshot of a persisted UI-builder design.",
        designIdSchema,
      ),
      tool(
        "list_components",
        "List the server's pinned UI-builder catalogs and component capability schemas.",
        emptySchema,
      ),
      tool(
        "apply_design_operations",
        "Atomically apply a typed v1 operation submission. Omit the submission's actorId — it is " +
          "bound to this connection's authenticated actor; supplying a different one is refused. " +
          "Returns committed revision, sequence, document hash, and validation/conflict diagnostics.",
        """{"type":"object","properties":{"submission":{"type":"object"}},"required":["submission"]}""",
      ),
      tool(
        "render_design",
        "Render a committed design revision as a revision-pinned PNG artifact.",
        revisionSchema,
      ),
      tool(
        "export_svg",
        "Export a committed design revision as Figma-compatible, revision-pinned SVG.",
        revisionSchema,
      ),
      tool(
        "export_compose",
        "Export a committed design revision as deterministic Compose source with diagnostics.",
        revisionSchema,
      ),
      tool(
        "get_revision_diff",
        "Read durable UI-builder events after an exclusive sequence cursor.",
        """{"type":"object","properties":{"designId":{"type":"string"},"afterSequence":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":1000}},"required":["designId","afterSequence"]}""",
      ),
    )

  /** Returns null when [name] is not owned by this adapter. */
  fun handle(name: String, args: JsonObject): CallToolResult? {
    val request =
      try {
        when (name) {
          "create_design" ->
            CreateDesignRequestV1(
              json.decodeFromJsonElement<DesignDocumentV1>(args.required("document"))
            )
          "open_design" -> OpenDesignRequestV1(args.requiredString("designId"))
          "list_components" -> ListCatalogsRequestV1
          "apply_design_operations" ->
            ApplyOperationRequestV1(
              json.decodeFromJsonElement<DesignSubmissionV1>(args.boundSubmission())
            )
          "render_design" -> args.exportRequest(ExportFormatV1.PNG)
          "export_svg" -> args.exportRequest(ExportFormatV1.SVG)
          "export_compose" -> args.exportRequest(ExportFormatV1.COMPOSE)
          "get_revision_diff" ->
            GetDeltaRequestV1(
              designId = args.requiredString("designId"),
              afterSequence = args.requiredLong("afterSequence", minimum = 0),
              limit = args.optionalInt("limit", range = 1..1000) ?: 200,
            )
          else -> return null
        }
      } catch (e: SerializationException) {
        return error(name, "invalid v1 protocol payload: ${e.message}")
      } catch (e: IllegalArgumentException) {
        return error(name, e.message ?: "invalid arguments")
      }

    return try {
      val response = client.execute(request)
      CallToolResult(content = listOf(ContentBlock.Text(json.encodeToString(response))))
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      error(name, "Design API request interrupted")
    } catch (e: Exception) {
      error(name, e.message ?: "Design API request failed")
    }
  }

  /** A remote-safe MCP server containing only the shared UI-builder tools. */
  internal fun sdkServer(): Server =
    Server(
      serverInfo = Implementation(name = "compose-preview-ui-builder", version = "v1"),
      options =
        ServerOptions(
          capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
        ),
    ) {
      toolDefs().forEach { definition ->
        addTool(definition.toSdkTool()) { request ->
          val arguments = request.arguments ?: JsonObject(emptyMap())
          (handle(request.name, arguments) ?: errorCallToolResult("unknown tool: ${request.name}"))
            .toSdkCallToolResult()
        }
      }
    }

  /**
   * The caller's submission with `actorId` filled in from the authenticated client.
   *
   * Every `DesignSubmissionV1` carries a mandatory `actorId`, and the client refuses to transport
   * one that disagrees with the actor it authenticated as. That pair is correct but was, on its
   * own, unusable: the actor is derived from the environment's grant token and appears nowhere in
   * `tools/list`, so a client had no way to author its FIRST mutation except by guessing or by
   * deliberately provoking the mismatch error to read the expected value out of it.
   *
   * Binding it here closes that: omit `actorId` and the authenticated identity is what gets sent.
   * The mismatch check stays exactly as strict for a submission that names a DIFFERENT actor — that
   * is a spoofing attempt, not a convenience — so this widens what a legitimate caller can express
   * without widening what it can claim.
   */
  private fun JsonObject.boundSubmission(): JsonObject {
    val submission =
      required("submission") as? JsonObject
        ?: throw IllegalArgumentException("'submission' must be an object")
    val declared =
      (submission["actorId"] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
    return if (declared != null) submission
    else JsonObject(submission + ("actorId" to JsonPrimitive(client.actorId)))
  }

  private fun JsonObject.exportRequest(format: ExportFormatV1): ExportDesignRequestV1 =
    ExportDesignRequestV1(
      designId = requiredString("designId"),
      revision = requiredLong("revision", minimum = 0),
      format = format,
    )

  private fun tool(name: String, description: String, schema: String) =
    ToolDef(name, description, json.parseToJsonElement(schema))

  private fun error(name: String, message: String) =
    CallToolResult(
      content = listOf(ContentBlock.Text("$name: $message")),
      isError = true,
    )

  private companion object {
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = false
    }
    const val emptySchema = """{"type":"object","properties":{}}"""
    const val designIdSchema =
      """{"type":"object","properties":{"designId":{"type":"string"}},"required":["designId"]}"""
    const val revisionSchema =
      """{"type":"object","properties":{"designId":{"type":"string"},"revision":{"type":"integer","minimum":0}},"required":["designId","revision"]}"""
  }
}

/**
 * Authenticates one remote MCP session against the authoritative UI-builder service.
 *
 * The catalog read happens before an MCP session is allocated: it proves that the bearer is
 * currently accepted and has `ui-builder-read`. Every later operation still carries the same
 * bearer, so expiry and revocation remain enforced by preview-server.
 */
internal fun authenticatedUiBuilderMcp(baseUrl: String, token: String): UiBuilderMcpAdapter {
  val client = UiBuilderDesignApiClient.remote(baseUrl, token)
  client.execute(ListCatalogsRequestV1)
  return UiBuilderMcpAdapter(client)
}

/** Authenticated HTTP client for one remote preview-server UI-builder service. */
internal class UiBuilderDesignApiClient(
  val actorId: String,
  private val transport: UiBuilderHttpTransport,
  private val requestId: () -> String = { UUID.randomUUID().toString() },
) {
  init {
    require(actorId.isNotBlank()) { "UI-builder actor id must not be blank" }
  }

  fun execute(request: UiBuilderRequestV1): HttpResponseEnvelopeV1 {
    request.requesterActorId()?.let { requesterActorId ->
      require(requesterActorId == actorId) {
        "requester actor '$requesterActorId' must match authenticated actor '$actorId'"
      }
    }
    val id = requestId()
    require(id.isNotBlank()) { "UI-builder request id must not be blank" }
    val body =
      json.encodeToString(
        HttpRequestEnvelopeV1(requestId = id, actorId = actorId, request = request)
      )
    val response = transport.post(body)
    val envelope =
      try {
        json.decodeFromString(HttpResponseEnvelopeV1.serializer(), response.body)
      } catch (e: SerializationException) {
        throw UiBuilderApiException("Design API returned an invalid response", e)
      }
    if (envelope.requestId != id) {
      throw UiBuilderApiException(
        "Design API response id '${envelope.requestId}' did not match request '$id'"
      )
    }
    if (envelope.schemaVersion != UI_BUILDER_SCHEMA_VERSION_V1) {
      throw UiBuilderApiException(
        "Design API returned unsupported schema version ${envelope.schemaVersion}"
      )
    }
    if (response.status !in 200..299) {
      throw UiBuilderApiException("Design API returned HTTP ${response.status}")
    }
    return envelope
  }

  companion object {
    fun remote(
      baseUrl: String,
      token: String,
      actorId: String = "agent:${AgentGrantProtocol.fingerprintOf(token)}",
    ): UiBuilderDesignApiClient =
      UiBuilderDesignApiClient(
        actorId = actorId,
        transport = JdkUiBuilderHttpTransport(baseUrl, token),
      )

    private val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = false
    }
  }
}

internal fun interface UiBuilderHttpTransport {
  fun post(body: String): UiBuilderHttpResponse
}

internal data class UiBuilderHttpResponse(val status: Int, val body: String)

internal class UiBuilderApiException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

private class JdkUiBuilderHttpTransport(baseUrl: String, private val token: String) :
  UiBuilderHttpTransport {
  private val endpoint = designEndpoint(baseUrl)
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  init {
    require(token.isNotBlank()) { "UI-builder token must not be blank" }
  }

  override fun post(body: String): UiBuilderHttpResponse {
    val request =
      HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    val bytes = response.body().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
    if (bytes.size > MAX_RESPONSE_BYTES) {
      throw UiBuilderApiException("Design API response exceeded $MAX_RESPONSE_BYTES bytes")
    }
    return UiBuilderHttpResponse(response.statusCode(), bytes.toString(Charsets.UTF_8))
  }

  private companion object {
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    fun designEndpoint(baseUrl: String): URI {
      val base = URI(baseUrl.trim().trimEnd('/') + "/")
      require(base.scheme == "http" || base.scheme == "https") {
        "UI-builder URL must use http or https"
      }
      require(!base.host.isNullOrBlank()) { "UI-builder URL must include a host" }
      require(base.userInfo == null && base.fragment == null && base.query == null) {
        "UI-builder URL must not contain credentials, query, or fragment"
      }
      return base.resolve("api/ui-builder/v1/requests")
    }
  }
}

private fun UiBuilderRequestV1.requesterActorId(): String? =
  when (this) {
    is ApplyOperationRequestV1 -> submission.actorId()
    else -> null
  }

private fun DesignSubmissionV1.actorId(): String =
  when (this) {
    is ee.schimke.composeai.uibuilder.protocol.DesignCommandV1 -> actorId
    is ee.schimke.composeai.uibuilder.protocol.UndoCommandV1 -> actorId
    is ee.schimke.composeai.uibuilder.protocol.RedoCommandV1 -> actorId
  }

private fun JsonObject.required(name: String): JsonElement =
  this[name] ?: throw IllegalArgumentException("missing '$name'")

private fun JsonObject.requiredString(name: String): String =
  (required(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("'$name' must be a non-blank string")

private fun JsonObject.requiredLong(name: String, minimum: Long? = null): Long {
  val value =
    (required(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toLongOrNull()
      ?: throw IllegalArgumentException("'$name' must be an integer")
  if (minimum != null && value < minimum) {
    throw IllegalArgumentException("'$name' must be at least $minimum")
  }
  return value
}

private fun JsonObject.optionalInt(name: String, range: IntRange): Int? {
  if (name !in this) return null
  val value =
    (required(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
      ?: throw IllegalArgumentException("'$name' must be an integer")
  if (value !in range) {
    throw IllegalArgumentException("'$name' must be between ${range.first} and ${range.last}")
  }
  return value
}
