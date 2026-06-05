package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the `animation/lottie` metadata product: it reads a `kind=LOTTIE` preview's timeline
 * (frames / frame rate / duration / canvas) straight from the asset, with no render. The fixture
 * `lottie/spin.json` is 60 frames at 30fps on a 200×200 canvas → 2000ms.
 */
class LottieTimelineDataProductRegistryTest {

  private fun indexWith(vararg previews: PreviewInfoDto): PreviewIndex =
    PreviewIndex.fromMap(path = null, byId = previews.associateBy { it.id })

  private fun lottiePreview(id: String, asset: String): PreviewInfoDto =
    PreviewInfoDto(
      id = id,
      className = "",
      methodName = asset.substringAfterLast('/'),
      params = PreviewParamsDto(kind = "LOTTIE", assetPath = asset),
    )

  @Test
  fun `capability advertises inline fetchable attachable lottie timeline`() {
    val cap = LottieTimelineDataProductRegistry(PreviewIndex.empty()).capabilities.single()
    assertEquals(LottieTimelineDataProductRegistry.KIND, cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch reports the asset timeline`() {
    val registry =
      LottieTimelineDataProductRegistry(
        indexWith(lottiePreview("lottie__spin", "lottie/spin.json"))
      )

    val outcome =
      registry.fetch(
        "lottie__spin",
        LottieTimelineDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals(60, payload.getValue("totalFrames").jsonPrimitive.float.toInt())
    assertEquals(30, payload.getValue("frameRate").jsonPrimitive.float.toInt())
    assertEquals(2000, payload.getValue("durationMillis").jsonPrimitive.int)
    assertEquals(200, payload.getValue("width").jsonPrimitive.int)
    assertEquals(200, payload.getValue("height").jsonPrimitive.int)
  }

  @Test
  fun `fetch for an unknown kind is Unknown`() {
    val registry =
      LottieTimelineDataProductRegistry(
        indexWith(lottiePreview("lottie__spin", "lottie/spin.json"))
      )
    assertEquals(
      DataProductRegistry.Outcome.Unknown,
      registry.fetch("lottie__spin", "render/trace", params = null, inline = true),
    )
  }

  @Test
  fun `fetch for a non-lottie preview is NotAvailable`() {
    val compose =
      PreviewInfoDto(
        id = "compose",
        className = "com.example.FooKt",
        methodName = "Bar",
        params = PreviewParamsDto(kind = "COMPOSE"),
      )
    val registry = LottieTimelineDataProductRegistry(indexWith(compose))
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        "compose",
        LottieTimelineDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
  }

  @Test
  fun `fetch for an unknown preview id is NotAvailable`() {
    val registry = LottieTimelineDataProductRegistry(PreviewIndex.empty())
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("nope", LottieTimelineDataProductRegistry.KIND, params = null, inline = true),
    )
  }

  @Test
  fun `attachmentsFor ships the timeline for a lottie preview`() {
    val registry =
      LottieTimelineDataProductRegistry(
        indexWith(lottiePreview("lottie__spin", "lottie/spin.json"))
      )
    val attachments =
      registry.attachmentsFor("lottie__spin", setOf(LottieTimelineDataProductRegistry.KIND))
    assertEquals(1, attachments.size)
    assertEquals(LottieTimelineDataProductRegistry.KIND, attachments.single().kind)
  }
}
