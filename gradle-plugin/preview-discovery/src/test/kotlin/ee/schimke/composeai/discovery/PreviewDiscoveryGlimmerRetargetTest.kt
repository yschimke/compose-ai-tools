package ee.schimke.composeai.discovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PreviewDiscovery.retargetGlimmerStickers] (yschimke/m3-catalog#367): on a module drawing
 * with `androidx.xr.glimmer`, a device-less component `@Preview` inherits the renderer's 400dp
 * phone sandbox at 2.625x — the wrong measuring bound, and a density that misstates the angular
 * size of a UI Glimmer specifies in degrees rather than dp.
 *
 * The retarget moves the *wrap sandbox*, not the frame, exactly as the Wear one does:
 * `widthDp`/`heightDp` MUST stay null so both axes keep wrapping and the renderer crops each
 * sticker to its measured bounds. Pinning the canvas instead is what #367 looked like — 19 stickers
 * each adrift in a 960x720 frame, 4.1% mean content coverage.
 */
class PreviewDiscoveryGlimmerRetargetTest {

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
      className = "ee.schimke.m3catalog.glimmer.ButtonsKt",
      params =
        PreviewParams(
          device = device,
          widthDp = widthDp,
          heightDp = heightDp,
          density = density,
          kind = kind,
        ),
    )

  /**
   * Only the coordinate map matters here; the rest is the minimum [PreviewDiscovery.Input] needs.
   */
  private fun input(vararg coordinates: String) =
    PreviewDiscovery.Input(
      classDirs = emptyList(),
      dependencyJars = emptyList(),
      sourceFiles = emptyList(),
      moduleName = "glimmer-catalog",
      variantName = "debug",
      projectDirectory = File("."),
      failOnEmpty = false,
      dependencyJarCoordinates =
        coordinates.withIndex().associate { (i, c) -> "/jars/$i.jar" to c },
    )

  @Test
  fun `a module without glimmer on the classpath is not a glimmer module`() {
    assertEquals(
      false,
      PreviewDiscovery.isGlimmerModule(
        input("androidx.compose.material3:material3:1.5.0", "androidx.compose.ui:ui:1.10.0")
      ),
    )
  }

  @Test
  fun `glimmer is detected by group, so its sibling artifacts count too`() {
    assertTrue(PreviewDiscovery.isGlimmerModule(input("androidx.xr.glimmer:glimmer:1.0.0-alpha19")))
    // Matched by GROUP prefix on purpose: the toolkit ships more than one artifact, and a module
    // that only pulled the fonts one is still drawing with Glimmer.
    assertTrue(
      PreviewDiscovery.isGlimmerModule(
        input("androidx.xr.glimmer:glimmer-google-fonts:1.0.0-alpha19")
      )
    )
  }

  @Test
  fun `a lookalike group does not match`() {
    // `androidx.xr.` alone would drag in the XR runtime modules, which are not Glimmer and draw for
    // a headset rather than glasses.
    assertEquals(
      false,
      PreviewDiscovery.isGlimmerModule(input("androidx.xr.scenecore:scenecore:1.0.0")),
    )
  }

  @Test
  fun `off glimmer, previews are unchanged`() {
    val previews = listOf(preview("ButtonSticker"))
    assertEquals(
      previews,
      PreviewDiscovery.retargetGlimmerStickers(isGlimmer = false, previews = previews),
    )
  }

  @Test
  fun `a device-less compose preview is measured against the glasses display at density 1`() {
    val out =
      PreviewDiscovery.retargetGlimmerStickers(
          isGlimmer = true,
          previews = listOf(preview("ButtonSticker")),
        )
        .single()
        .params

    assertEquals(DeviceDimensions.DEFAULT_GLASSES.widthDp, out.wrapSandboxWidthDp)
    assertEquals(DeviceDimensions.DEFAULT_GLASSES.heightDp, out.wrapSandboxHeightDp)
    // The calibration, not a scale factor: Glimmer sizes UI in visual angle and the ~30 PPD
    // identity holds only at density 1.0.
    assertEquals(1.0f, out.density)
    // Load-bearing: the axes stay WRAPPED. Pinning them is #367 — a 118x48 toggle button in a
    // 691,200-pixel frame.
    assertNull(out.widthDp)
    assertNull(out.heightDp)
  }

  @Test
  fun `the preview id is untouched, so catalog references and filenames stay stable`() {
    val out =
      PreviewDiscovery.retargetGlimmerStickers(
          isGlimmer = true,
          previews = listOf(preview("ButtonSticker")),
        )
        .single()
    assertEquals("ButtonSticker", out.id)
  }

  @Test
  fun `a preview that names its own device is left alone`() {
    val pinned = preview("EnvironmentSticker", device = "spec:width=960,height=720,dpi=160")
    val out =
      PreviewDiscovery.retargetGlimmerStickers(isGlimmer = true, previews = listOf(pinned)).single()
    assertEquals(pinned, out)
    assertNull(out.params.wrapSandboxWidthDp)
  }

  @Test
  fun `a preview pinned to a measured width is left alone`() {
    val pinned = preview("ListItemSpecimen", widthDp = 360, heightDp = 200)
    assertEquals(
      pinned,
      PreviewDiscovery.retargetGlimmerStickers(isGlimmer = true, previews = listOf(pinned))
        .single(),
    )
  }

  @Test
  fun `a non-compose preview is left alone`() {
    // Lottie and SVG assets carry their own intrinsic size; a glasses sandbox means nothing to one.
    val asset = preview("LogoAsset", kind = PreviewKind.SVG)
    assertEquals(
      asset,
      PreviewDiscovery.retargetGlimmerStickers(isGlimmer = true, previews = listOf(asset)).single(),
    )
  }
}
