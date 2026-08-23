package ee.schimke.composeai.daemon

import java.awt.image.BufferedImage
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
 * `@CaptureGutter` on the **live daemon** lane, Android half (issue #4443).
 *
 * Twin of `:daemon:desktop`'s `DesktopCaptureGutterLaneTest`, and deliberately asserting the same
 * arithmetic: the whole point of the change is that the four lanes — batch desktop, batch Android,
 * daemon desktop, daemon Android — grow a guttered capture by the same pixels, so a preview does
 * not change size when a viewer toggles PNG↔Live or a catalog switches backends
 * (RENDER_LANE_PARITY.md).
 */
class AndroidCaptureGutterLaneTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

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
              density = 2.0f,
              captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5),
            ),
        )
      )
    assertEquals(4, spec.gutterStartDp)
    assertEquals(5, spec.gutterBottomDp)
    assertTrue(spec.hasCaptureGutter())
    // 4+4 dp across and 4+5 dp down at density 2 ⇒ 16 px and 18 px, per-edge rounded — the same
    // pixels the desktop lane resolves for the same annotation.
    assertEquals(16, spec.gutterHorizontalPx())
    assertEquals(18, spec.gutterVerticalPx())
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
    assertTrue(!spec.hasCaptureGutter())
    assertEquals(0, spec.gutterHorizontalPx())
  }

  @Test
  fun `the payload token round-trips, and an older client's payload decodes to no gutter`() {
    val parsed =
      RenderSpec.parseFromPayloadOrNull(
        "className=com.example.FooKt;functionName=Foo;captureGutter=1,2,3,4"
      )!!
    assertEquals(1, parsed.gutterStartDp)
    assertEquals(2, parsed.gutterTopDp)
    assertEquals(3, parsed.gutterEndDp)
    assertEquals(4, parsed.gutterBottomDp)

    // No token at all — every payload written before this field existed.
    val legacy = RenderSpec.parseFromPayloadOrNull("className=com.example.FooKt;functionName=Foo")!!
    assertTrue(!legacy.hasCaptureGutter())

    // A malformed token is no gutter rather than a partial one: half a gutter would silently
    // publish a lopsided canvas, which is harder to notice than none at all.
    val malformed =
      RenderSpec.parseFromPayloadOrNull(
        "className=com.example.FooKt;functionName=Foo;captureGutter=1,2"
      )!!
    assertTrue(!malformed.hasCaptureGutter())
  }

  @Test
  fun `the router resolves a manifest-declared gutter and omits an absent one`() {
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
  fun aGutteredRenderGrowsTheCanvasAndLeavesTheComponentAlone() {
    val outputDir = tempFolder.newFolder("renders-gutter")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val bare = renderFixture("bare", captureGutter = null)
    val guttered =
      renderFixture(
        "guttered",
        captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5),
      )

    // 32×32 dp at density 1 with a 4 dp gutter a side and 5 dp at the bottom ⇒ 40×41.
    assertEquals(bare.width + 8, guttered.width)
    assertEquals(bare.height + 9, guttered.height)
    assertEquals(40, guttered.width)
    assertEquals(41, guttered.height)
  }

  /**
   * Renders the fixed-size red fixture through the real router → `RenderEngine` path, which is what
   * a `renderNow` from the VS Code panel takes.
   */
  private fun renderFixture(id: String, captureGutter: CaptureGutterDto?): BufferedImage {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = id,
              className = "ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt",
              functionName = "WrappedFixturePreview",
              params =
                PreviewParamsEntry(
                  widthDp = 32,
                  heightDp = 32,
                  density = 1.0f,
                  captureGutter = captureGutter,
                ),
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val result = host.submit(RenderRequest.Render(payload = "previewId=$id"), timeoutMs = 120_000)
      assertNotNull("$id: pngPath must be populated", result.pngPath)
      val png = File(result.pngPath!!)
      assertTrue("$id: rendered PNG must exist", png.exists())
      return ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
        ?: error("$id: PNG failed to decode")
    } finally {
      host.shutdown()
    }
  }
}
