package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfettoTraceDataProductRegistryTest {
  @Test
  fun advertisesComposeAiTraceAsPathAndInlineProduct() {
    val registry = PerfettoTraceDataProductRegistry(Files.createTempDirectory("trace-reg").toFile())

    val capability = registry.capabilities.single()

    assertEquals("render/composeAiTrace", capability.kind)
    assertEquals(DataProductTransport.BOTH, capability.transport)
    assertTrue(capability.attachable)
    assertTrue(capability.fetchable)
  }

  @Test
  fun fetchReturnsPathOrInlineTrace() {
    val root = Files.createTempDirectory("trace-reg").toFile()
    val previewId = "preview-1"
    val trace =
      TracePayload(
        traceEvents =
          listOf(
            TraceEvent(
              name = "render:capture",
              category = "compose-preview",
              timestampMicros = 1.0,
              durationMicros = 2.0,
            )
          ),
        metadata = TraceMetadata(previewId = previewId, backend = "test"),
      )
    PerfettoTraceDataProducer.writeArtifacts(root, previewId, trace)
    val registry = PerfettoTraceDataProductRegistry(root)

    val pathOutcome =
      registry.fetch(previewId, PerfettoTraceDataProducer.KIND, params = null, inline = false)
        as DataProductRegistry.Outcome.Ok
    assertNotNull(pathOutcome.result.path)
    assertEquals("perfetto", pathOutcome.result.extras?.single()?.name)
    assertEquals(
      "application/json",
      pathOutcome.result.payload?.jsonObject?.get("mediaType")?.jsonPrimitive?.content,
    )

    val inlineOutcome =
      registry.fetch(previewId, PerfettoTraceDataProducer.KIND, params = null, inline = true)
        as DataProductRegistry.Outcome.Ok
    assertEquals("render/composeAiTrace", inlineOutcome.result.kind)
    assertNotNull(inlineOutcome.result.payload?.jsonObject?.get("traceEvents"))
  }

  @Test
  fun attachmentsReturnTracePathWhenRequested() {
    val root = Files.createTempDirectory("trace-reg").toFile()
    val previewId = "preview-1"
    PerfettoTraceDataProducer.writeArtifacts(
      root,
      previewId,
      TracePayload(traceEvents = emptyList(), metadata = TraceMetadata(previewId, backend = "test")),
    )
    val registry = PerfettoTraceDataProductRegistry(root)

    val attachments = registry.attachmentsFor(previewId, setOf(PerfettoTraceDataProducer.KIND))

    assertEquals(1, attachments.size)
    assertEquals("render/composeAiTrace", attachments.single().kind)
    assertNotNull(attachments.single().path)
    assertNull(attachments.single().payload)
    assertEquals("perfetto", attachments.single().extras?.single()?.name)
  }
}
