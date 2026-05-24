package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration regression guard for the daemon's `@PreviewWrapper` render path — closes gap #1 from
 * issue #1436. The unit-level [PreviewWrapperResolutionTest] covers
 * [resolveWrapperOrNull][ee.schimke.composeai.daemon.resolveWrapperOrNull] in isolation, but
 * doesn't prove [RenderEngine]'s `setContent` actually drives the resolved wrapper around the
 * preview body. A future refactor could quietly swap `InvokeWithOptionalWrapper` for raw
 * `InvokeComposable` and the unit test would stay green while production silently regressed back to
 * the original "Invalid applier" crash on samples like
 * [`RemoteButtonWithBorderPreview`][../../../../../../samples/remotecompose] (the bug that motivated
 * commit `006269f`).
 *
 * This test drives [PreviewManifestRouter] end-to-end with a manifest entry that nominates
 * [GreenBorderWrapper] via `params.wrapperClassName` — the same production path the gradle plugin's
 * `extractWrapperFqn` emits into `previews.json`. The router rewrites the payload, [RenderEngine]
 * threads the FQN into `RenderSpec.wrapperClassName`, `InvokeWithOptionalWrapper` resolves it via
 * `loadPreviewWrapperClass`, and the wrapper's `Wrap(content)` runs around
 * [WrappedFixturePreview]'s red fill. The rendered PNG must therefore show green at the edges (the
 * wrapper's `fillMaxSize().background(Color(0xFF1B5E20))`) and red in the centre (the body, inside
 * the wrapper's 8.dp padding).
 *
 * If a refactor breaks the `setContent` call site the rendered PNG will be uniformly red — the body
 * rendered without its wrapper — and the green-edge assertion fails.
 *
 * **Parallel to** `:renderer-android`'s
 * [PreviewWrapperTest][ee.schimke.composeai.renderer.PreviewWrapperTest], which guards the same
 * path on the non-daemon side. The daemon test goes through [PreviewManifestRouter] (production
 * shape) rather than calling `resolveWrapper` directly, because the spec-driven FQN path is the
 * only one that fires for binary-retained `@PreviewWrapper` annotations in production (issue
 * #1440).
 */
class WrappedPreviewRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun wrappedPreviewRendersGreenBorderAroundRedBody() {
    val outputDir = tempFolder.newFolder("renders-wrapped")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wrapped",
              className = "ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt",
              functionName = "WrappedFixturePreview",
              // Production manifests carry `wrapperClassName` under nested `params` — same shape
              // the gradle plugin's discovery JSON emits. The router hoists it into the routed
              // payload as a top-level `wrapperClassName=…` token (covered in isolation by
              // PreviewManifestRouterRoutingTest).
              params =
                PreviewParamsEntry(
                  widthDp = 32,
                  heightDp = 32,
                  density = 1.0f,
                  wrapperClassName =
                    "ee.schimke.composeai.daemon.GreenBorderWrapper",
                ),
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val img = renderAndDecode(host, "previewId=wrapped", "wrapped")
      // 32×32 at density 1.0 → 8.dp wrapper padding ≈ 8px green band around a 16×16 red body.
      // Sample edge pixels (inside the green band) and centre pixels (inside the red body); the
      // assertion is robust to anti-aliasing because the band and body are both ≥ several pixels
      // wide. If the daemon stops routing through the wrapper, all pixels come out red and the
      // edge assertion fails.
      assertEdgePixelsAreGreen(img)
      assertCentreIsRed(img)
    } finally {
      host.shutdown()
    }
  }

  private fun renderAndDecode(
    host: PreviewManifestRouter,
    payload: String,
    label: String,
  ): BufferedImage {
    val request = RenderRequest.Render(payload = payload)
    val result = host.submit(request, timeoutMs = 120_000)
    assertNotNull("$label: pngPath must be populated", result.pngPath)
    val pngFile = File(result.pngPath!!)
    assertTrue("$label: rendered PNG must exist", pngFile.exists())
    return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      ?: error("$label: PNG failed to decode")
  }

  /**
   * Asserts the rendered PNG's outer band is dominated by the wrapper's green. Samples the four
   * mid-edge pixels (top/bottom/left/right midpoints) at offset 2 from each edge — well inside the
   * 8.dp green band even after Robolectric AA. Targets `0xFF1B5E20`; per-channel tolerance ±32
   * absorbs AA blending.
   */
  private fun assertEdgePixelsAreGreen(img: BufferedImage) {
    val w = img.width
    val h = img.height
    val samples =
      listOf(
        w / 2 to 2,
        w / 2 to (h - 3),
        2 to (h / 2),
        (w - 3) to (h / 2),
      )
    for ((x, y) in samples) {
      val rgb = img.getRGB(x, y)
      val r = (rgb shr 16) and 0xFF
      val g = (rgb shr 8) and 0xFF
      val b = rgb and 0xFF
      assertTrue(
        "edge pixel ($x,$y) should be wrapper-green (~0x1B5E20) but was rgb($r,$g,$b). " +
          "If body-red, the daemon skipped @PreviewWrapper's Wrap{} — regression in " +
          "RenderEngine.setContent's InvokeWithOptionalWrapper call site.",
        g > r && g > b && g > 60,
      )
    }
  }

  /**
   * Asserts the rendered PNG's centre — well inside the 8.dp green band — is dominated by the
   * body's red. Confirms the wrapper is invoking `content()` rather than swallowing it; without
   * this we'd green-pass even if `Wrap` did `Box { /* drop content */ }`.
   */
  private fun assertCentreIsRed(img: BufferedImage) {
    val centre = img.getRGB(img.width / 2, img.height / 2)
    val r = (centre shr 16) and 0xFF
    val g = (centre shr 8) and 0xFF
    val b = centre and 0xFF
    assertTrue(
      "centre pixel should be body-red (~0xEF5350) but was rgb($r,$g,$b). If wrapper-green, the " +
        "wrapper isn't invoking content().",
      r > g && r > b && r > 120 && abs(g - b) < 60,
    )
  }
}
