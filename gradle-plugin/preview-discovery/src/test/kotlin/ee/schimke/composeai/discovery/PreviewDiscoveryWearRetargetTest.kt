package ee.schimke.composeai.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [PreviewDiscovery.retargetWearStickers] (issue #1985): on a Wear module, a frame-less,
 * device-less component `@Preview` inherits Studio's phone default device (400×800dp @ 2.625x),
 * which renders a Wear sticker on a phone canvas. Discovery retargets such previews to the Wear
 * default (227dp @ 2.0x) so they render at wear scale, while leaving device-pinned and fixed-size
 * previews (and every preview off Wear) untouched.
 */
class PreviewDiscoveryWearRetargetTest {

  private fun preview(
    id: String,
    device: String? = null,
    widthDp: Int? = null,
    heightDp: Int? = null,
    density: Float? = DeviceDimensions.DEFAULT_DENSITY,
    kind: PreviewKind = PreviewKind.COMPOSE,
  ) =
    PreviewInfo(
      id = id,
      functionName = id,
      className = "com.example.CatalogKt",
      params =
        PreviewParams(
          device = device,
          widthDp = widthDp,
          heightDp = heightDp,
          density = density,
          kind = kind,
        ),
    )

  @Test
  fun `off Wear, previews are unchanged`() {
    val input = listOf(preview("FilledButton"))
    assertEquals(input, PreviewDiscovery.retargetWearStickers(isWear = false, previews = input))
  }

  @Test
  fun `on Wear, a device-less wrap-content compose preview is retargeted to the wear default`() {
    val out =
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          previews = listOf(preview("FilledButton")),
        )
        .single()
        .params

    assertEquals(DeviceDimensions.DEFAULT_WEAR.widthDp, out.widthDp)
    assertEquals(DeviceDimensions.DEFAULT_WEAR.heightDp, out.heightDp)
    assertEquals(DeviceDimensions.DEFAULT_WEAR.density, out.density)
    // The id is untouched — a device-less preview never encodes a device, so catalog references and
    // delivery filenames stay stable.
    assertEquals(
      "FilledButton",
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          previews = listOf(preview("FilledButton")),
        )
        .single()
        .id,
    )
  }

  @Test
  fun `on Wear, a device-pinned preview is left untouched`() {
    val breakpoint = preview("Card_Large Round", device = "id:wearos_large_round", density = 2.0f)
    val out =
      PreviewDiscovery.retargetWearStickers(isWear = true, previews = listOf(breakpoint)).single()
    assertEquals(breakpoint, out)
  }

  @Test
  fun `on Wear, a fixed-size preview keeps its own dimensions`() {
    val sized = preview("Specimen", widthDp = 120, heightDp = 40)
    val out =
      PreviewDiscovery.retargetWearStickers(isWear = true, previews = listOf(sized)).single()
    assertEquals(120, out.params.widthDp)
    assertEquals(40, out.params.heightDp)
  }

  @Test
  fun `on Wear, a non-compose preview (e_g_ lottie) is left untouched`() {
    val lottie = preview("spin", kind = PreviewKind.LOTTIE, density = null)
    val out =
      PreviewDiscovery.retargetWearStickers(isWear = true, previews = listOf(lottie)).single()
    assertNull(out.params.widthDp)
    assertEquals(PreviewKind.LOTTIE, out.params.kind)
  }
}
