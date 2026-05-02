package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HistoryDiffRegionsDataProductRegistryTest {

  private lateinit var rootDir: java.io.File
  private lateinit var historyManager: HistoryManager

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("history-diff-data-product-test").toFile()
    historyManager =
      HistoryManager(
        sources = listOf(LocalFsHistorySource(rootDir.toPath())),
        module = ":test",
        gitProvenance = null,
      )
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capabilities advertise inline history diff regions`() {
    val registry = HistoryDiffRegionsDataProductRegistry(historyManager)
    val cap = registry.capabilities.single()

    assertEquals(HistoryDiffRegionsDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns changed regions against explicit baseline`() {
    val baseline =
      historyManager.recordRender(
        previewId = "com.example.Preview",
        pngBytes = png(6, 6) { _, _ -> argb(255, 0, 0, 0) },
        trigger = "test",
        renderTookMs = 1,
        timestamp = Instant.parse("2026-05-02T10:00:00Z"),
      )!!
    historyManager.recordRender(
      previewId = "com.example.Preview",
      pngBytes =
        png(6, 6) { x, y ->
          when {
            x in 1..2 && y in 1..2 -> argb(255, 255, 0, 0)
            x == 5 && y == 5 -> argb(255, 0, 0, 255)
            else -> argb(255, 0, 0, 0)
          }
        },
      trigger = "test",
      renderTookMs = 1,
      timestamp = Instant.parse("2026-05-02T10:00:01Z"),
    )

    val outcome =
      HistoryDiffRegionsDataProductRegistry(historyManager)
        .fetch(
          previewId = "com.example.Preview",
          kind = HistoryDiffRegionsDataProductRegistry.KIND,
          params = params(baseline.id),
          inline = false,
        )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertNotNull(result.payload)
    assertNull(result.path)
    val payload =
      Json.decodeFromJsonElement(
        HistoryDiffRegionsDataProductRegistry.DiffPayload.serializer(),
        result.payload!!,
      )

    assertEquals(baseline.id, payload.baselineHistoryId)
    assertEquals(5, payload.totalPixelsChanged)
    assertEquals(5.0 / 36.0, payload.changedFraction, 0.0001)
    assertEquals(listOf("1,1,3,3", "5,5,6,6"), payload.regions.map { it.bounds })
    assertEquals(4, payload.regions[0].pixelCount)
    assertEquals(255.0, payload.regions[0].avgDelta.r, 0.0001)
    assertEquals(1, payload.regions[1].pixelCount)
    assertEquals(255.0, payload.regions[1].avgDelta.b, 0.0001)
  }

  @Test
  fun `attachments use subscribed baseline params`() {
    val baseline =
      historyManager.recordRender(
        previewId = "preview",
        pngBytes = png(2, 2) { _, _ -> argb(255, 0, 0, 0) },
        trigger = "test",
        renderTookMs = 1,
        timestamp = Instant.parse("2026-05-02T10:00:00Z"),
      )!!
    historyManager.recordRender(
      previewId = "preview",
      pngBytes = png(2, 2) { x, _ -> if (x == 0) argb(255, 9, 0, 0) else argb(255, 0, 0, 0) },
      trigger = "test",
      renderTookMs = 1,
      timestamp = Instant.parse("2026-05-02T10:00:01Z"),
    )
    val registry = HistoryDiffRegionsDataProductRegistry(historyManager)

    registry.onSubscribe("preview", HistoryDiffRegionsDataProductRegistry.KIND, params(baseline.id))
    val attachments =
      registry.attachmentsFor("preview", setOf(HistoryDiffRegionsDataProductRegistry.KIND))

    assertEquals(1, attachments.size)
    assertEquals(HistoryDiffRegionsDataProductRegistry.KIND, attachments.single().kind)
    assertNotNull(attachments.single().payload)
  }

  @Test
  fun `fetch returns failed when baseline is missing`() {
    val registry = HistoryDiffRegionsDataProductRegistry(historyManager)
    historyManager.recordRender(
      previewId = "preview",
      pngBytes = png(1, 1) { _, _ -> argb(255, 0, 0, 0) },
      trigger = "test",
      renderTookMs = 1,
    )

    val outcome =
      registry.fetch(
        previewId = "preview",
        kind = HistoryDiffRegionsDataProductRegistry.KIND,
        params = params("missing"),
        inline = false,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.FetchFailed)
  }

  private fun params(baselineHistoryId: String) = buildJsonObject {
    put("baselineHistoryId", baselineHistoryId)
    put("thresholdAlphaDelta", 4)
  }

  private fun png(width: Int, height: Int, colorAt: (Int, Int) -> Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
      for (x in 0 until width) {
        image.setRGB(x, y, colorAt(x, y))
      }
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}
