package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end guard for the manifest-derived `uiMode` (the fix behind the "catalog SVG is randomly
 * light or dark" bug): a `_Light`/`_Dark` multipreview pair routed purely from `previews.json`
 * (`params.uiMode`, no inbound overrides — exactly how `bundle pack`'s semantics fetch renders)
 * must produce a light capture for the light id and a dark capture for the dark id, in that render
 * order (discovery lists the annotations top-down, so Light renders first in production too).
 *
 * [FigmaSvgPerVariantTest] only asserts the two SVGs *differ* under explicit inbound overrides; a
 * one-render lag in qualifier application (each render picking up the PREVIOUS render's night bit)
 * still passes that test while swapping every published variant — which is what the first 0.17.14
 * confetti-mobile catalog run shipped. This test pins the *assignment*, not just the difference.
 *
 * `DarkAwareSquare` paints `Color.White` in light mode and `Color.Black` in dark mode, so the
 * exported rect fill is the assignment oracle.
 */
class FigmaSvgManifestUiModeTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `manifest uiMode renders the light id light and the dark id dark`() {
    val outputDir = tempFolder.newFolder("renders-manifest-uimode")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            // Light first, matching discovery's annotation order for a Light/Dark multipreview.
            entry("square_Light", uiMode = 0x11), // UI_MODE_NIGHT_NO | TYPE_NORMAL
            entry("square_Dark", uiMode = 0x21), // UI_MODE_NIGHT_YES | TYPE_NORMAL
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=square_Light"), timeoutMs = 120_000)
      host.submit(RenderRequest.Render(payload = "previewId=square_Dark"), timeoutMs = 120_000)

      val dataDir = outputDir.parentFile!!.resolve("data")
      val light = dataDir.resolve("square_Light").resolve("compose-figma.svg")
      val dark = dataDir.resolve("square_Dark").resolve("compose-figma.svg")
      assertTrue("light figma SVG must be produced: ${light.absolutePath}", light.exists())
      assertTrue("dark figma SVG must be produced: ${dark.absolutePath}", dark.exists())
      val lightSvg = light.readText()
      val darkSvg = dark.readText()

      assertTrue(
        "the LIGHT id must capture the light theme (white square). svg=${lightSvg.take(2000)}",
        lightSvg.contains("fill=\"#FFFFFF\""),
      )
      assertTrue(
        "the DARK id must capture the dark theme (black square) — a swap here means the night " +
          "qualifier lagged one render behind. svg=${darkSvg.take(2000)}",
        darkSvg.contains("fill=\"#000000\""),
      )
    } finally {
      host.shutdown()
    }
  }

  private fun entry(id: String, uiMode: Int) =
    PreviewManifestEntry(
      id = id,
      className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
      functionName = "DarkAwareSquare",
      params = PreviewParamsEntry(widthDp = 48, heightDp = 48, density = 1.0f, uiMode = uiMode),
      outputBaseName = id,
    )

  /**
   * Same guarantee through the **production** resolver ([renderSpecFromInfo] +
   * [RobolectricHost.reshapeRenderPayload] — the lane `bundle pack`'s semantics fetch and the live
   * daemon actually use, unlike the harness-only [PreviewManifestRouter] above), in the order
   * discovery really emits a multipreview (Dark FIRST, then Light — annotation order reversed).
   *
   * This is the exact sequence that shipped theme-lagged in the 0.17.14 confetti-mobile catalog:
   * `renderSpecFromInfo` resolved a no-night preview to a null uiMode, so `_Light` emitted no
   * `uiMode=` token and inherited `_Dark`'s `night` qualifier — every capture wore the PREVIOUS
   * render's theme. The resolver now defaults to an explicit LIGHT, so each render resets the
   * night bit deterministically.
   */
  @Test
  fun `production resolver renders dark-then-light with the right themes`() {
    val outputDir = tempFolder.newFolder("renders-resolver-uimode")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    fun info(id: String, uiMode: Int) =
      PreviewInfoDto(
        id = id,
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        methodName = "DarkAwareSquare",
        params = PreviewParamsDto(widthDp = 48, heightDp = 48, density = 1.0f, uiMode = uiMode),
      )
    val byId =
      listOf(
          info("sq_Dark", uiMode = 0x21), // Dark first — discovery reverses annotation order.
          info("sq_Light", uiMode = 0x11),
        )
        .associateBy { it.id }
    val host =
      RobolectricHost(previewSpecResolver = { id -> byId[id]?.let { renderSpecFromInfo(it) } })
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=sq_Dark"), timeoutMs = 120_000)
      host.submit(RenderRequest.Render(payload = "previewId=sq_Light"), timeoutMs = 120_000)

      val dataDir = outputDir.parentFile!!.resolve("data")
      val dark = dataDir.resolve("sq_Dark").resolve("compose-figma.svg").readText()
      val light = dataDir.resolve("sq_Light").resolve("compose-figma.svg").readText()
      assertTrue(
        "the DARK id must capture the dark theme (black square). svg=${dark.take(2000)}",
        dark.contains("fill=\"#000000\""),
      )
      assertTrue(
        "the LIGHT id rendered after it must reset to notnight (white square) — dark here means " +
          "the previous render's night bit leaked. svg=${light.take(2000)}",
        light.contains("fill=\"#FFFFFF\""),
      )
    } finally {
      host.shutdown()
    }
  }
}
