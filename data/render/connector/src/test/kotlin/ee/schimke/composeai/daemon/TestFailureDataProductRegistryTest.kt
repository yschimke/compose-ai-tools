package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestFailureDataProductRegistryTest {
  @Test
  fun `capability advertises inline fetchable failure product`() {
    val registry = TestFailureDataProductRegistry()

    val cap = registry.capabilities.single()
    assertEquals(TestFailureDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(!cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns not available before a failure lands`() {
    val registry = TestFailureDataProductRegistry()

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "preview",
        kind = TestFailureDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }

  @Test
  fun `onRenderFailed stores a failure postmortem payload`() {
    val registry = TestFailureDataProductRegistry()
    val cause =
      IllegalStateException("Missing PreviewParameter value").also {
        it.stackTrace =
          arrayOf(
            StackTraceElement(
              "com.example.ExamplePreviewKt",
              "ExamplePreview",
              "ExamplePreview.kt",
              37,
            )
          )
      }

    registry.onRenderFailed(previewId = "preview", cause = cause)

    val outcome =
      registry.fetch(
        previewId = "preview",
        kind = TestFailureDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals("failed", payload["status"]!!.jsonPrimitive.content)
    assertEquals("unknown", payload["phase"]!!.jsonPrimitive.content)
    val error = payload["error"]!!.jsonObject
    assertEquals("IllegalStateException", error["type"]!!.jsonPrimitive.content)
    assertEquals("Missing PreviewParameter value", error["message"]!!.jsonPrimitive.content)
    assertEquals("ExamplePreview.kt:37", error["topFrame"]!!.jsonPrimitive.content)
    assertEquals(1, (error["stackTrace"] as JsonArray).size)
    assertEquals("false", payload["partialScreenshotAvailable"]!!.jsonPrimitive.content)
    val snapshot = payload["lastSnapshotSummary"]!!.jsonObject
    assertEquals("false", snapshot["valuesCaptured"]!!.jsonPrimitive.content)
  }

  @Test
  fun `successful render clears stale failure`() {
    val registry = TestFailureDataProductRegistry()
    registry.onRenderFailed(previewId = "preview", cause = IllegalStateException("boom"))

    registry.onRender(
      previewId = "preview",
      result = RenderResult(id = 1L, classLoaderHashCode = 2, classLoaderName = "test"),
    )

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "preview",
        kind = TestFailureDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }

  @Test
  fun `attachments are empty because failures are fetched after renderFailed`() {
    val registry = TestFailureDataProductRegistry()
    registry.onRenderFailed(previewId = "preview", cause = IllegalStateException("boom"))

    assertTrue(
      registry.attachmentsFor("preview", setOf(TestFailureDataProductRegistry.KIND)).isEmpty()
    )
  }
}
