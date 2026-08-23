package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@CaptureGutter` on the **live daemon** lane (issue #4443).
 *
 * The annotation was honoured by both static render lanes and by neither daemon, so a guttered
 * preview streamed into `compose-preview serve` / the VS Code panel came back at its un-guttered
 * size while the published PNG beside it carried the gutter. RENDER_LANE_PARITY.md's rule is that
 * switching lanes changes font antialiasing, not layout, so that divergence is a bug in this daemon
 * rather than a missing feature.
 *
 * Three hops have to hold for the annotation to reach a render, and each gets its own test:
 * 1. `previews.json` → `RenderSpec` (this is the production `serve` path — it resolves through
 *    `PreviewIndex`, not through the harness-only `PreviewManifestRouter`),
 * 2. the `captureGutter=` payload token, so a router-rewritten payload survives the wire,
 * 3. the engine itself, where the scene grows and the component is placed inset.
 */
class DesktopCaptureGutterLaneTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val stickerClass = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"

  private fun render(
    engine: RenderEngine,
    baseName: String,
    gutterStartDp: Int = 0,
    gutterTopDp: Int = 0,
    gutterEndDp: Int = 0,
    gutterBottomDp: Int = 0,
    wrap: Boolean = true,
    widthPx: Int = 800,
    heightPx: Int = 1600,
  ): File {
    val spec =
      RenderSpec(
        previewId = "sticker",
        className = stickerClass,
        functionName = "WrapContentStickerPreview",
        widthPx = widthPx,
        heightPx = heightPx,
        wrapWidth = wrap,
        wrapHeight = wrap,
        density = 2.0f,
        showBackground = true,
        outputBaseName = baseName,
        gutterStartDp = gutterStartDp,
        gutterTopDp = gutterTopDp,
        gutterEndDp = gutterEndDp,
        gutterBottomDp = gutterBottomDp,
      )
    val result = engine.render(spec, requestId = 1L, classLoader = javaClass.classLoader)
    assertNotNull("pngPath must be populated", result.pngPath)
    val png = File(result.pngPath!!)
    assertTrue("rendered PNG must exist: ${png.absolutePath}", png.exists())
    return png
  }

  /** Persist a render to `build/gutter-evidence/` for the PR's before/after strip. */
  private fun keepEvidence(png: File, name: String) {
    val dir = File("build/gutter-evidence").apply { mkdirs() }
    png.copyTo(File(dir, "$name.png"), overwrite = true)
  }

  private fun dims(png: File): Pair<Int, Int> {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    return img.width to img.height
  }

  @Test
  fun `a declared gutter reaches the spec through the production previews-json path`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "Foo",
          className = "com.example.FooKt",
          methodName = "Foo",
          params =
            PreviewParamsDto(
              captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5)
            ),
        )
      )
    assertEquals(4, spec.gutterStartDp)
    assertEquals(4, spec.gutterTopDp)
    assertEquals(4, spec.gutterEndDp)
    assertEquals(5, spec.gutterBottomDp)
    // 4+4 dp across and 4+5 dp down at density 2 ⇒ 16 px and 18 px. Resolved per edge, the same
    // rounding the standalone renderer's `PreviewCaptureGutter.ofDp` applies, so the two lanes
    // cannot land a pixel apart.
    assertEquals(16, spec.captureGutterPx().horizontalPx)
    assertEquals(18, spec.captureGutterPx().verticalPx)
  }

  @Test
  fun `an un-annotated preview keeps a zero gutter`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "Foo",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = PreviewParamsDto(),
        )
      )
    assertEquals(0, spec.gutterStartDp)
    assertEquals(0, spec.captureGutterPx().horizontalPx)
    assertEquals(0, spec.captureGutterPx().verticalPx)
  }

  @Test
  fun `the payload token round-trips, and an older client's payload decodes to no gutter`() {
    val parsed =
      RenderSpec.parseFromPayload(
        "className=com.example.FooKt;functionName=Foo;captureGutter=1,2,3,4"
      )
    assertEquals(1, parsed.gutterStartDp)
    assertEquals(2, parsed.gutterTopDp)
    assertEquals(3, parsed.gutterEndDp)
    assertEquals(4, parsed.gutterBottomDp)

    // No token at all — every payload written before this field existed.
    val legacy = RenderSpec.parseFromPayload("className=com.example.FooKt;functionName=Foo")
    assertEquals(0, legacy.gutterStartDp)
    assertEquals(0, legacy.gutterBottomDp)

    // A malformed token is no gutter rather than a partial one: half a gutter would silently
    // publish a lopsided canvas, which is harder to notice than none at all.
    val malformed =
      RenderSpec.parseFromPayload("className=com.example.FooKt;functionName=Foo;captureGutter=1,2")
    assertEquals(0, malformed.gutterStartDp)
  }

  @Test
  fun `the router forwards a manifest-declared gutter onto the payload`() {
    val entry =
      PreviewManifestEntry(
        id = "sticker",
        className = "com.example.FooKt",
        functionName = "Foo",
        params =
          PreviewParamsEntry(
            captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5)
          ),
      )
    assertEquals(CaptureGutterDto(4, 4, 4, 5), entry.resolved().captureGutter)
    // …and an un-annotated entry resolves to the all-zero gutter, which the payload omits.
    assertTrue(
      PreviewManifestEntry(
          id = "b",
          className = "c",
          functionName = "d",
          params = PreviewParamsEntry(),
        )
        .resolved()
        .captureGutter
        .isEmpty()
    )
  }

  @Test
  fun `a wrapped render grows by the gutter and the component keeps its own size`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    val barePng = render(engine, "sticker-bare")
    val gutteredPng =
      render(
        engine,
        "sticker-guttered",
        gutterStartDp = 4,
        gutterTopDp = 4,
        gutterEndDp = 4,
        gutterBottomDp = 5,
      )
    // The bare render IS the pre-fix behaviour for a guttered preview on this lane — the gutter
    // never reached the spec, so the daemon drew the tight canvas. Kept for the PR's before/after.
    keepEvidence(barePng, "daemon-desktop-bare")
    keepEvidence(gutteredPng, "daemon-desktop-guttered")
    val (bareW, bareH) = dims(barePng)
    val (gutteredW, gutteredH) = dims(gutteredPng)
    // The sticker's own intrinsic size is unchanged; only the canvas around it grew. At density 2
    // that is +16 across and +18 down — byte-identical to what the standalone renderer produces
    // for the same annotation, which is the whole parity claim.
    assertEquals(bareW + 16, gutteredW)
    assertEquals(bareH + 18, gutteredH)
    // Absolute, because RENDER_LANE_PARITY.md quotes these: the sticker's intrinsic size is 176 px
    // (56 dp badge + 16 dp padding a side, × density 2).
    assertEquals(176, bareW)
    assertEquals(176, bareH)
    assertEquals(192, gutteredW)
    assertEquals(194, gutteredH)
  }

  @Test
  fun `a fixed-axis render adds the gutter to the declared frame`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders-fixed"))
    val (w, h) =
      dims(
        render(
          engine,
          "sticker-fixed",
          gutterStartDp = 4,
          gutterTopDp = 4,
          gutterEndDp = 4,
          gutterBottomDp = 5,
          wrap = false,
          widthPx = 320,
          heightPx = 320,
        )
      )
    // A fixed axis still measures the component against its declared frame; the gutter is canvas
    // added around it, so a 320 px frame with a 4 dp gutter a side comes out 336 px at density 2.
    assertEquals(336, w)
    assertEquals(338, h)
  }
}
