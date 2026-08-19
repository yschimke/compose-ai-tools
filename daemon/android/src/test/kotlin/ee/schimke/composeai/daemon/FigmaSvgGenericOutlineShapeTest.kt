package ee.schimke.composeai.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Android half of the generic-outline shape export: a `clip(shape)` whose outline is an
 * `Outline.Generic` must reach the SVG as its sampled **path**, never as a bare `<rect>`.
 *
 * Issue #3254 built that fallback (`ModifierTokenResolver.outlineShapePathWire` →
 * `FigmaSvgLayer.shapePathData`) and unit-tested the *resolution order* with fakes. What no test
 * covered was whether the sampler runs at all on Android — and it did not. The sampler called
 * Compose's common `PathMeasure()` factory; this module's connector compiles against the
 * desktop/skiko `compose.ui`, so that call linked to `SkiaBackedPathMeasure_skikoKt`, which is
 * absent on Android. It threw `NoClassDefFoundError` inside the sampler's own `runCatching`, no
 * `shapePath` resolved, and every `MaterialShapes` star, morph and squircle exported as a confident
 * rectangle painted over its correctly-shaped pixels. [PlatformPathMeasure] is the fix; this is the
 * test that fails without it.
 *
 * It has to be an **end-to-end render** on the Android lane. A JVM unit test cannot see this: on
 * that classpath the skiko actual is present and the old code worked, which is exactly how the bug
 * survived. So drive a real render of [RedFixturePreviewsKt.GenericOutlineShapeSquare] and read the
 * emitted vector.
 */
class FigmaSvgGenericOutlineShapeTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `a generic-outline clip exports its sampled path, not a rectangle`() {
    val outputDir = tempFolder.newFolder("renders-generic-outline")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "previewId=generic-outline;" +
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=GenericOutlineShapeSquare;" +
              "widthPx=64;heightPx=64;density=1.0;showBackground=false;" +
              "outputBaseName=generic-outline"
        ),
        timeoutMs = 120_000,
      )

      val svgFile =
        outputDir.parentFile!!
          .resolve("data")
          .resolve("generic-outline")
          .resolve("compose-figma.svg")
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()

      // The diamond's four straight edges are sampled into a polyline, so the fill arrives as a
      // `<path>`. Asserting on the element (not on coordinates) keeps this about *which geometry
      // was established*, which is what the bug destroyed — the sampling cadence is free to change.
      assertTrue(
        "the clip shape must export as a sampled <path>; got:\n$svg",
        svg.contains("<path"),
      )
      // The regression's signature: the shape resolved to nothing and the layer fell back to a
      // rectangle covering its whole box. A `<rect>` carrying the node's fill is the exact wrong
      // answer, so no filled rect may appear at all in this one-layer export.
      assertFalse(
        "a generic-outline shape must not degrade to a filled <rect>; got:\n$svg",
        Regex("<rect[^>]*fill=\"#7E57C2\"", RegexOption.IGNORE_CASE).containsMatchIn(svg),
      )
    } finally {
      host.shutdown()
    }
  }
}
