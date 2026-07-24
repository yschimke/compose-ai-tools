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
    previewParameterProviderClassName: String? = null,
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
          previewParameterProviderClassName = previewParameterProviderClassName,
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
  fun `on Wear with the canvas pin opted out, a device-less preview stays wrap-content at wear density`() {
    val out =
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          pinWearCanvas = false,
          previews = listOf(preview("ImageWidget")),
        )
        .single()
        .params

    // Dimensions stay null → the renderer crops the PNG to the widget's intrinsic layout bounds
    // (issue #2670) instead of pinning the 227dp watch canvas.
    assertNull(out.widthDp)
    assertNull(out.heightDp)
    // Density is still swapped to the Wear default (2.0x), not the inherited phone default
    // (2.625x), so a fixed-size Wear widget asset scales to watch-density px.
    assertEquals(DeviceDimensions.DEFAULT_WEAR.density, out.density)
  }

  @Test
  fun `on Wear with the canvas pin opted out, a device-pinned preview is still untouched`() {
    val breakpoint = preview("Card_Large Round", device = "id:wearos_large_round", density = 2.0f)
    val out =
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          pinWearCanvas = false,
          previews = listOf(breakpoint),
        )
        .single()
    assertEquals(breakpoint, out)
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
  fun `on Wear, a glance-wear widget preview crops at wear density even with the canvas pin on`() {
    // Auto-detected via its androidx.glance.wear @PreviewParameter provider: a widget sticker must
    // never occupy the 227dp watch canvas, so it crops regardless of pinWearCanvas (#2670).
    val widget =
      preview(
        "ImageWidgetSquirclePreview",
        previewParameterProviderClassName =
          "androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams",
      )
    val out =
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          pinWearCanvas = true,
          previews = listOf(widget),
        )
        .single()
        .params

    // Dimensions stay null (wrap-content → cropped), density swapped to the wear default.
    assertNull(out.widthDp)
    assertNull(out.heightDp)
    assertEquals(DeviceDimensions.DEFAULT_WEAR.density, out.density)
  }

  @Test
  fun `on Wear, a non-widget device-less preview still pins the canvas with the flag on`() {
    // A regular catalog component (a non-glance @PreviewParameter provider) is unaffected by the
    // widget auto-detect — it still pins the watch canvas under the default pinWearCanvas = true.
    val component =
      preview(
        "UserCardPreview",
        previewParameterProviderClassName = "com.example.CatalogKt\$UserCardProvider",
      )
    val out =
      PreviewDiscovery.retargetWearStickers(
          isWear = true,
          pinWearCanvas = true,
          previews = listOf(component),
        )
        .single()
        .params

    assertEquals(DeviceDimensions.DEFAULT_WEAR.widthDp, out.widthDp)
    assertEquals(DeviceDimensions.DEFAULT_WEAR.heightDp, out.heightDp)
    assertEquals(DeviceDimensions.DEFAULT_WEAR.density, out.density)
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
