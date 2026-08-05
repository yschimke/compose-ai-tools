package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.RenderTrace
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
    assertEquals(2, cap.schemaVersion)
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
    // v2 tells the client which shape it got rather than making it infer from `phases.size == 1`.
    assertEquals("metrics", payload["source"]!!.jsonPrimitive.content)
  }

  @Test
  fun `onRender projects engine spans into real phases and sections`() {
    val registry = RenderTraceDataProductRegistry()
    val originNanos = 5_000_000L
    registry.onRender(
      previewId = "preview",
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 2,
          classLoaderName = "test",
          metrics = mapOf("tookMs" to 12L),
          trace =
            RenderTrace.of(
              backend = "desktop",
              events =
                listOf(
                  // `render:once` encloses two `compose:frame` passes — the nesting and the repeat
                  // are the two things v1 could not express at all.
                  RenderTrace.Recorded(
                    "render:once",
                    "compose-preview",
                    originNanos,
                    originNanos + 9_000_000L,
                    depth = 0,
                  ),
                  RenderTrace.Recorded(
                    "compose:frame",
                    "compose-preview",
                    originNanos + 1_000_000L,
                    originNanos + 3_000_000L,
                    depth = 1,
                  ),
                  RenderTrace.Recorded(
                    "compose:frame",
                    "compose-preview",
                    originNanos + 4_000_000L,
                    originNanos + 8_000_000L,
                    depth = 1,
                  ),
                ),
            ),
        ),
    )

    val outcome =
      registry.fetch(
        previewId = "preview",
        kind = RenderTraceDataProductRegistry.KIND,
        params = null,
        inline = true,
      )
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject

    assertEquals("spans", payload["source"]!!.jsonPrimitive.content)
    assertEquals("desktop", payload["backend"]!!.jsonPrimitive.content)

    val phases = payload["phases"] as JsonArray
    assertEquals(3, phases.size)
    assertEquals("render:once", phases[0].jsonObject["name"]!!.jsonPrimitive.content)
    assertEquals("0", phases[0].jsonObject["depth"]!!.jsonPrimitive.content)
    assertEquals("1", phases[1].jsonObject["depth"]!!.jsonPrimitive.content)
    // Microseconds sit alongside the v1 millisecond fields; a 2ms phase must not round to nothing.
    assertEquals("1000", phases[1].jsonObject["startUs"]!!.jsonPrimitive.content)
    assertEquals("2000", phases[1].jsonObject["durationUs"]!!.jsonPrimitive.content)

    val sections = payload["sections"] as JsonArray
    val frame =
      sections
        .map { it.jsonObject }
        .single { s -> s["name"]!!.jsonPrimitive.content == "compose:frame" }
    assertEquals("2", frame["count"]!!.jsonPrimitive.content)
    assertEquals("6000", frame["totalUs"]!!.jsonPrimitive.content)
    assertEquals("3000", frame["meanUs"]!!.jsonPrimitive.content)
    assertEquals("4000", frame["maxUs"]!!.jsonPrimitive.content)

    // v1 fields keep their v1 meaning so a v1 client reads the same numbers it always did.
    assertEquals("12", payload["totalMs"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a render with spans but no metrics still reports a trace`() {
    val registry = RenderTraceDataProductRegistry()
    registry.onRender(
      previewId = "preview",
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 2,
          classLoaderName = "test",
          trace =
            RenderTrace.of(
              backend = "android",
              events =
                listOf(
                  RenderTrace.Recorded(
                    "render:captureRoboImage",
                    "compose-preview",
                    0L,
                    2_000_000L,
                    0,
                  )
                ),
            ),
        ),
    )

    val outcome =
      registry.fetch(
        previewId = "preview",
        kind = RenderTraceDataProductRegistry.KIND,
        params = null,
        inline = true,
      )
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals("spans", payload["source"]!!.jsonPrimitive.content)
    assertEquals("0", payload["totalMs"]!!.jsonPrimitive.content)
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
    assertEquals(2, attachment.schemaVersion)
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
