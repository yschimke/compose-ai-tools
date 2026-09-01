package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetDeltaRequestV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class UiBuilderMcpAdapterTest {
  private val requests = mutableListOf<HttpRequestEnvelopeV1>()
  private val transport = UiBuilderHttpTransport { body ->
    val request = json.decodeFromString(HttpRequestEnvelopeV1.serializer(), body)
    requests += request
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
  }
  private val sequence = AtomicInteger()
  private val client =
    UiBuilderDesignApiClient(
      actorId = ACTOR,
      transport = transport,
      requestId = { "call-${sequence.incrementAndGet()}" },
    )
  private val adapter = UiBuilderMcpAdapter(client)

  @Test
  fun `advertises the exact eight UI builder tools with object schemas`() {
    val tools = adapter.toolDefs()

    assertThat(tools.map { it.name })
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
    assertThat(tools.map { it.inputSchema.jsonObject["type"]?.jsonPrimitive?.content }.distinct())
      .containsExactly("object")
    listOf("render_design", "export_svg", "export_compose").forEach { name ->
      assertThat(
          tools
            .single { it.name == name }
            .inputSchema
            .jsonObject
            .getValue("required")
            .jsonArray
            .map { it.jsonPrimitive.content }
        )
        .containsExactly("designId", "revision")
        .inOrder()
    }
  }

  @Test
  fun `maps all eight tools to typed requests and exact authenticated envelopes`() {
    val calls =
      listOf(
        "create_design" to
          buildJsonObject {
            put("document", json.encodeToJsonElement(DesignDocumentV1.serializer(), document))
          },
        "open_design" to buildJsonObject { put("designId", "design-1") },
        "list_components" to buildJsonObject {},
        "apply_design_operations" to
          buildJsonObject {
            put("submission", json.encodeToJsonElement(DesignSubmissionV1.serializer(), command))
          },
        "render_design" to revisionArgs,
        "export_svg" to revisionArgs,
        "export_compose" to revisionArgs,
        "get_revision_diff" to
          buildJsonObject {
            put("designId", "design-1")
            put("afterSequence", 41)
            put("limit", 37)
          },
      )

    calls.forEach { (name, arguments) ->
      val result = requireNotNull(adapter.handle(name, arguments))
      assertThat(result.isError).isNull()
    }

    assertThat(requests.map { it.schemaVersion }.distinct()).containsExactly(1)
    assertThat(requests.map { it.requestId })
      .containsExactly(
        "call-1",
        "call-2",
        "call-3",
        "call-4",
        "call-5",
        "call-6",
        "call-7",
        "call-8",
      )
      .inOrder()
    assertThat(requests.map { it.actorId }.distinct()).containsExactly(ACTOR)
    assertThat(requests[0].request).isEqualTo(CreateDesignRequestV1(document))
    assertThat(requests[1].request).isEqualTo(OpenDesignRequestV1("design-1"))
    assertThat(requests[2].request).isEqualTo(ListCatalogsRequestV1)
    assertThat(requests[3].request).isEqualTo(ApplyOperationRequestV1(command))
    assertThat(requests.drop(4).take(3).map { (it.request as ExportDesignRequestV1).format })
      .containsExactly(ExportFormatV1.PNG, ExportFormatV1.SVG, ExportFormatV1.COMPOSE)
      .inOrder()
    assertThat(requests.drop(4).take(3).map { (it.request as ExportDesignRequestV1).revision })
      .containsExactly(7L, 7L, 7L)
    assertThat(requests[7].request).isEqualTo(GetDeltaRequestV1("design-1", 41, 37))
  }

  @Test
  fun `read write and export tools remain distinct protocol capability lanes`() {
    val byName = adapter.toolDefs().associateBy { it.name }

    assertThat(byName.keys.intersect(READ_TOOLS)).containsExactlyElementsIn(READ_TOOLS)
    assertThat(byName.keys.intersect(WRITE_TOOLS)).containsExactlyElementsIn(WRITE_TOOLS)
    assertThat(byName.keys.intersect(EXPORT_TOOLS)).containsExactlyElementsIn(EXPORT_TOOLS)
    assertThat(READ_TOOLS + WRITE_TOOLS + EXPORT_TOOLS).containsExactlyElementsIn(byName.keys)
  }

  @Test
  fun `binds the authenticated actor when the submission omits one`() {
    // The actor is derived from the environment's grant token and is advertised nowhere in
    // tools/list, so requiring the caller to restate it made the first mutation unauthorable. It is
    // now optional, and omitting it means "whoever this connection authenticated as".
    val submission =
      json.encodeToJsonElement(DesignSubmissionV1.serializer(), command).jsonObject.filterKeys {
        it != "actorId"
      }

    val result =
      requireNotNull(
        adapter.handle(
          "apply_design_operations",
          buildJsonObject { put("submission", JsonObject(submission)) },
        )
      )

    assertThat(result.isError).isNull()
    assertThat(requests.single().request).isEqualTo(ApplyOperationRequestV1(command))
  }

  @Test
  fun `binds the authenticated actor over a blank one rather than failing the call`() {
    val submission =
      json.encodeToJsonElement(DesignSubmissionV1.serializer(), command).jsonObject.toMutableMap()
    submission["actorId"] = JsonPrimitive("   ")

    val result =
      requireNotNull(
        adapter.handle(
          "apply_design_operations",
          buildJsonObject { put("submission", JsonObject(submission)) },
        )
      )

    assertThat(result.isError).isNull()
    assertThat(requests.single().request).isEqualTo(ApplyOperationRequestV1(command))
  }

  @Test
  fun `apply_design_operations says the actor is bound rather than caller-supplied`() {
    val apply = adapter.toolDefs().single { it.name == "apply_design_operations" }

    assertThat(apply.description).contains("Omit the submission's actorId")
  }

  @Test
  fun `rejects nested requester actor spoofing before transport`() {
    val spoofed = command.copy(actorId = "agent:someone-else")

    val result =
      requireNotNull(
        adapter.handle(
          "apply_design_operations",
          buildJsonObject {
            put("submission", json.encodeToJsonElement(DesignSubmissionV1.serializer(), spoofed))
          },
        )
      )

    assertThat(result.isError).isTrue()
    assertThat(requests).isEmpty()
    assertThat(result.content.single().toString()).contains("must match authenticated actor")
  }

  @Test
  fun `rejects malformed and out of range arguments without transport`() {
    val malformed =
      listOf(
        "open_design" to buildJsonObject {},
        "render_design" to buildJsonObject { put("designId", "design-1") },
        "export_svg" to buildJsonObject { put("designId", "design-1") },
        "export_compose" to buildJsonObject { put("designId", "design-1") },
        "render_design" to
          buildJsonObject {
            put("designId", "design-1")
            put("revision", -1)
          },
        "get_revision_diff" to
          buildJsonObject {
            put("designId", "design-1")
            put("afterSequence", -1)
          },
        "get_revision_diff" to
          buildJsonObject {
            put("designId", "design-1")
            put("afterSequence", 0)
            put("limit", 1001)
          },
      )

    malformed.forEach { (name, arguments) ->
      assertThat(requireNotNull(adapter.handle(name, arguments)).isError).isTrue()
    }
    assertThat(requests).isEmpty()
  }

  @Test
  fun `rejects mismatched response correlation and hides HTTP response bodies`() {
    val mismatch =
      UiBuilderMcpAdapter(
        UiBuilderDesignApiClient(
          ACTOR,
          UiBuilderHttpTransport {
            UiBuilderHttpResponse(
              200,
              json.encodeToString(
                HttpResponseEnvelopeV1(
                  requestId = "different-call",
                  response = CatalogsResponseV1(emptyList()),
                )
              ),
            )
          },
          requestId = { "expected-call" },
        )
      )
    val mismatchResult = requireNotNull(mismatch.handle("list_components", JsonObject(emptyMap())))
    assertThat(mismatchResult.isError).isTrue()
    assertThat(mismatchResult.content.single().toString()).contains("did not match")

    val secret = "server-body-must-not-escape"
    val failed =
      UiBuilderMcpAdapter(
        UiBuilderDesignApiClient(
          ACTOR,
          UiBuilderHttpTransport {
            UiBuilderHttpResponse(
              403,
              json.encodeToString(
                HttpResponseEnvelopeV1(
                  requestId = "failed-call",
                  response =
                    ErrorResponseV1(ServiceErrorV1(ServiceErrorCodeV1.FORBIDDEN, message = secret)),
                )
              ),
            )
          },
          requestId = { "failed-call" },
        )
      )
    val failedResult = requireNotNull(failed.handle("list_components", JsonObject(emptyMap())))
    assertThat(failedResult.isError).isTrue()
    assertThat(failedResult.content.single().toString()).doesNotContain(secret)
  }

  @Test
  fun `default remote actor is the released grant fingerprint convention`() {
    val token = "not-a-real-secret"
    val remote = UiBuilderDesignApiClient.remote("https://preview.example/", token)

    assertThat(remote.actorId).isEqualTo("agent:${AgentGrantProtocol.fingerprintOf(token)}")
  }

  private companion object {
    const val ACTOR = "agent:0123456789ab"
    val READ_TOOLS = setOf("open_design", "list_components", "get_revision_diff")
    val WRITE_TOOLS = setOf("create_design", "apply_design_operations")
    val EXPORT_TOOLS = setOf("render_design", "export_svg", "export_compose")
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = false
    }
    val document =
      DesignDocumentV1(
        schema = "compose-preview/ui-builder/v1",
        id = "design-1",
        title = "Test design",
        revision = 0,
        catalogPin = CatalogReferenceV1("m3-catalog", "rev-1", "digest-1", "runtime-1"),
        environment =
          DesignEnvironmentV1(
            widthDp = 360,
            heightDp = 800,
            density = 1.0,
            theme = ThemeV1.LIGHT,
            locale = "en-US",
            fontScale = 1.0,
            layoutDirection = LayoutDirectionV1.LTR,
          ),
        roots = emptyList(),
        nodes = emptyMap(),
      )
    val command =
      DesignCommandV1(
        designId = "design-1",
        operationId = "operation-1",
        actorId = ACTOR,
        clientId = "mcp-test",
        baseRevision = 0,
        operations = emptyList(),
      )
    val revisionArgs = buildJsonObject {
      put("designId", "design-1")
      put("revision", 7)
    }
  }
}
