package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTraceTest {
  @Test
  fun `spans are rebased onto the first span and ordered parents-first`() {
    val trace =
      RenderTrace.of(
        backend = "desktop",
        events =
          listOf(
            // Recorded in closing order — the child closes before the parent it sits inside.
            RenderTrace.Recorded("compose:setContent", "c", 1_500_000L, 2_500_000L, depth = 1),
            RenderTrace.Recorded("render:once", "c", 1_000_000L, 4_000_000L, depth = 0),
          ),
      )

    assertEquals(listOf("render:once", "compose:setContent"), trace.spans.map { it.name })
    assertEquals(0L, trace.spans[0].startMicros)
    assertEquals(500L, trace.spans[1].startMicros)
    assertEquals(3000L, trace.spans[0].durationMicros)
    assertEquals(3000L, trace.totalMicros)
  }

  @Test
  fun `sections aggregate repeated phases and sort by total time`() {
    val trace =
      RenderTrace.of(
        backend = "desktop",
        events =
          listOf(
            RenderTrace.Recorded("compose:frame", "c", 0L, 1_000_000L, depth = 1),
            RenderTrace.Recorded("compose:frame", "c", 2_000_000L, 5_000_000L, depth = 1),
            RenderTrace.Recorded("render:encodePng", "c", 6_000_000L, 6_500_000L, depth = 1),
          ),
      )

    assertEquals(listOf("compose:frame", "render:encodePng"), trace.sections.map { it.name })
    val frame = trace.sections.first()
    assertEquals(2, frame.count)
    assertEquals(4000L, frame.totalMicros)
    assertEquals(2000L, frame.meanMicros)
    assertEquals(3000L, frame.maxMicros)
  }

  @Test
  fun `an empty trace is well-formed rather than absent`() {
    val trace = RenderTrace.of(backend = "android", events = emptyList())

    assertEquals(0L, trace.totalMicros)
    assertTrue(trace.spans.isEmpty())
    assertTrue(trace.sections.isEmpty())
  }

  @Test
  fun `recorder collects spans with nesting even when the disk artefact is off`() {
    // The whole point of the v2 split: `enabled = false` means "don't write the Perfetto JSON",
    // not "don't measure". A daemon with the artefact switched off must still answer render/trace.
    val recorder =
      PerfettoTraceDataProducer.recorder(previewId = "p", backend = "desktop", enabled = false)

    recorder.section("render:once") {
      recorder.section("compose:setContent") {}
      recorder.section("compose:frame") {}
      recorder.section("compose:frame") {}
    }

    val trace = recorder.renderTrace()
    assertEquals("desktop", trace.backend)
    assertEquals(listOf(0, 1, 1, 1), trace.spans.map { it.depth })
    assertEquals("render:once", trace.spans.first().name)
    assertEquals(2, trace.sections.single { it.name == "compose:frame" }.count)
    // The Chrome-trace event list stays empty — that one really is behind the disk opt-in.
    assertTrue(recorder.payload().traceEvents.isEmpty())
  }

  @Test
  fun `a section that throws still closes its span and restores depth`() {
    val recorder =
      PerfettoTraceDataProducer.recorder(previewId = "p", backend = "desktop", enabled = false)

    runCatching { recorder.section("render:once") { error("boom") } }
    recorder.section("compose:tearDown") {}

    val trace = recorder.renderTrace()
    assertEquals(listOf("render:once", "compose:tearDown"), trace.spans.map { it.name })
    assertEquals(listOf(0, 0), trace.spans.map { it.depth })
  }

  @Test
  fun `payload keeps the v1 shape when no spans were recorded`() {
    val payload = RenderTraceDataProduct.payloadFrom(mapOf("tookMs" to 7L), trace = null)

    val json = payload.toString()
    assertTrue(json.contains("\"source\":\"metrics\""), json)
    assertTrue(json.contains("\"name\":\"render\""), json)
    assertTrue(json.contains("\"durationMs\":7"), json)
  }
}
