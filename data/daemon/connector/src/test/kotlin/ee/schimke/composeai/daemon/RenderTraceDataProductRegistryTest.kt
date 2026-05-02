package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTraceDataProductRegistryTest {
  @Test
  fun `capability advertises inline fetchable attachable render trace`() {
    val registry = RenderTraceDataProductRegistry()

    val cap = registry.capabilities.single()
    assertEquals(RenderTraceDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns not available before a metric-bearing render lands`() {
    val registry = RenderTraceDataProductRegistry()

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "preview",
        kind = RenderTraceDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }

  @Test
  fun `onRender stores latest metrics as trace-shaped payload`() {
    val registry = RenderTraceDataProductRegistry()
    registry.onRender(
      previewId = "preview",
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 2,
          classLoaderName = "test",
          metrics = mapOf("tookMs" to 42L, "heapAfterGcMb" to 9L),
        ),
    )

    val outcome =
      registry.fetch(
        previewId = "preview",
        kind = RenderTraceDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals("42", payload["totalMs"]!!.jsonPrimitive.content)
    val phases = payload["phases"] as JsonArray
    assertEquals(1, phases.size)
    val renderPhase = phases.single().jsonObject
    assertEquals("render", renderPhase["name"]!!.jsonPrimitive.content)
    assertEquals("0", renderPhase["startMs"]!!.jsonPrimitive.content)
    assertEquals("42", renderPhase["durationMs"]!!.jsonPrimitive.content)
    assertEquals("9", payload["metrics"]!!.jsonObject["heapAfterGcMb"]!!.jsonPrimitive.content)
  }

  @Test
  fun `attachments mirror latest trace payload when subscribed`() {
    val registry = RenderTraceDataProductRegistry()
    registry.onRender(
      previewId = "preview",
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 2,
          classLoaderName = "test",
          metrics = mapOf("tookMs" to 17L),
        ),
    )

    val attachment =
      registry.attachmentsFor("preview", setOf(RenderTraceDataProductRegistry.KIND)).single()

    assertEquals(RenderTraceDataProductRegistry.KIND, attachment.kind)
    assertEquals(1, attachment.schemaVersion)
    assertNull(attachment.path)
    assertEquals("17", attachment.payload!!.jsonObject["totalMs"]!!.jsonPrimitive.content)
  }

  @Test
  fun `metricless render clears stale trace`() {
    val registry = RenderTraceDataProductRegistry()
    registry.onRender(
      previewId = "preview",
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 2,
          classLoaderName = "test",
          metrics = mapOf("tookMs" to 17L),
        ),
    )
    registry.onRender(
      previewId = "preview",
      result = RenderResult(id = 2L, classLoaderHashCode = 2, classLoaderName = "test"),
    )

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "preview",
        kind = RenderTraceDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }
}
