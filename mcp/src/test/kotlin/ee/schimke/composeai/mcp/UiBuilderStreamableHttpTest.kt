package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class UiBuilderStreamableHttpTest {
  @Test
  fun `streamable HTTP authenticates and binds a grant to the MCP session`() = testApplication {
    val upstreamRequests = mutableListOf<HttpRequestEnvelopeV1>()
    val adapter =
      UiBuilderMcpAdapter(
        UiBuilderDesignApiClient(
          actorId = "agent:${AgentGrantProtocol.fingerprintOf(TOKEN_A)}",
          transport =
            UiBuilderHttpTransport { body ->
              val request = json.decodeFromString(HttpRequestEnvelopeV1.serializer(), body)
              upstreamRequests += request
              UiBuilderHttpResponse(
                status = 200,
                body =
                  json.encodeToString(
                    HttpResponseEnvelopeV1(
                      requestId = request.requestId,
                      response = CatalogsResponseV1(emptyList()),
                    )
                  ),
              )
            },
        )
      )
    application {
      installUiBuilderStreamableHttp(
        UiBuilderStreamableHttpConfig(uiBuilderUrl = "https://preview.example/"),
        UiBuilderSessionAuthenticator { token ->
          require(token == TOKEN_A) { "rejected" }
          AuthenticatedUiBuilderSession(tokenSessionBinding(token), adapter)
        },
      )
    }

    val missing = client.post("/ui-builder/mcp") { mcpBody(INITIALIZE) }
    assertThat(missing.status).isEqualTo(HttpStatusCode.Unauthorized)

    val initialized =
      client.post("/ui-builder/mcp") {
        bearer(TOKEN_A)
        mcpBody(INITIALIZE)
      }
    assertWithMessage(initialized.bodyAsText())
      .that(initialized.status)
      .isEqualTo(HttpStatusCode.OK)
    val sessionId = initialized.headers[MCP_SESSION_ID_HEADER]
    assertThat(sessionId).isNotNull()
    assertThat(initialized.bodyAsText()).contains("compose-preview-ui-builder")

    val tools =
      client.post("/ui-builder/mcp") {
        bearer(TOKEN_A)
        header(MCP_SESSION_ID_HEADER, sessionId!!)
        mcpBody(TOOLS_LIST)
      }
    assertThat(tools.status).isEqualTo(HttpStatusCode.OK)
    val toolNames =
      json
        .parseToJsonElement(tools.bodyAsText())
        .jsonObject
        .getValue("result")
        .jsonObject
        .getValue("tools")
        .jsonArray
        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
    assertThat(toolNames)
      .containsExactly(
        "create_design",
        "open_design",
        "list_components",
        "apply_design_operations",
        "render_design",
        "export_svg",
        "export_compose",
        "get_revision_diff",
      )
      .inOrder()

    val crossedGrant =
      client.post("/ui-builder/mcp") {
        bearer(TOKEN_B)
        header(MCP_SESSION_ID_HEADER, sessionId)
        mcpBody(TOOLS_LIST)
      }
    assertThat(crossedGrant.status).isEqualTo(HttpStatusCode.Forbidden)

    val called =
      client.post("/ui-builder/mcp") {
        bearer(TOKEN_A)
        header(MCP_SESSION_ID_HEADER, sessionId)
        mcpBody(LIST_COMPONENTS)
      }
    assertThat(called.status).isEqualTo(HttpStatusCode.OK)
    assertThat(called.bodyAsText()).contains("catalogs")
    assertThat(upstreamRequests).hasSize(1)

    val deleted =
      client.delete("/ui-builder/mcp") {
        bearer(TOKEN_A)
        header(MCP_SESSION_ID_HEADER, sessionId)
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
      }
    assertThat(deleted.status.value).isIn(listOf(200, 202, 204))

    val afterDelete =
      client.post("/ui-builder/mcp") {
        bearer(TOKEN_A)
        header(MCP_SESSION_ID_HEADER, sessionId)
        mcpBody(TOOLS_LIST)
      }
    assertThat(afterDelete.status).isEqualTo(HttpStatusCode.NotFound)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private fun io.ktor.client.request.HttpRequestBuilder.mcpBody(body: String) {
    header(HttpHeaders.Host, "localhost")
    contentType(ContentType.Application.Json)
    header(
      HttpHeaders.Accept,
      "${ContentType.Application.Json}, ${ContentType.Text.EventStream}",
    )
    setBody(body)
  }

  private companion object {
    const val TOKEN_A = "grant-a"
    const val TOKEN_B = "grant-b"
    const val INITIALIZE =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
    const val LIST_COMPONENTS =
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_components","arguments":{}}}"""
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = false
    }
  }
}
