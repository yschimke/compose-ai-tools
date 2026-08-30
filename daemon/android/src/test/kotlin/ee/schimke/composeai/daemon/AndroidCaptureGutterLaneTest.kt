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
  fun `the host-side reshape carries the gutter, which is the lane production takes`() {
    // The router below is the harness lane. The Android bundle daemon and `compose-preview serve`
    // never mount one: they resolve `previewId=…` through `RobolectricHost.reshapeRenderPayload`,
    // which re-serialises the spec into a payload string. That round-trip dropped the gutter
    // (#4822) — and dropped it silently, because `parseFromPayloadOrNull` defaults every edge to
    // 0 — so any override that forced a request off the baked lane (a theme, a knob, a Remote
    // Compose seed) came back clipped to the composable's own frame.
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.FooKt",
            functionName = "Foo",
            widthPx = 384,
            heightPx = 128,
            density = 2.0f,
            gutterStartDp = 0,
            gutterTopDp = 8,
            gutterEndDp = 0,
            gutterBottomDp = 8,
          )
        }
      )

    val reshaped = host.reshapeRenderPayload("previewId=media-podcastcontrolbuttons;uiMode=light")
    val spec = RenderSpec.parseFromPayloadOrNull(reshaped)

    assertNotNull("reshaped payload must remain a parseable RenderSpec: $reshaped", spec)
    assertTrue("the gutter must survive the string round-trip", spec!!.hasCaptureGutter())
    assertEquals(0, spec.gutterStartDp)
    assertEquals(8, spec.gutterTopDp)
    assertEquals(0, spec.gutterEndDp)
    assertEquals(8, spec.gutterBottomDp)
    // 8+8 dp down at density 2 ⇒ 32 px, the difference between the reported 384×160 baked render
    // and the 384×128 the live daemon was returning.
    assertEquals(0, spec.gutterHorizontalPx())
    assertEquals(32, spec.gutterVerticalPx())
  }

  @Test
  fun `the host-side reshape emits no gutter token for an un-annotated preview`() {
    // A payload for a preview that declares no gutter must be byte-identical to what it was before
    // the token existed — the same guarantee the router makes.
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(previewId = it, className = "com.example.FooKt", functionName = "Foo")
        }
      )

    assertTrue(!host.reshapeRenderPayload("previewId=plain").contains("captureGutter="))
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

  @Test
  fun `a guttered dialog capture keeps the component at its un-guttered scale`() {
    // A fixed-size dialog preview is deliberately rescaled back to its declared Studio frame on
    // this lane (`DialogWindowRenderTest` pins it), and what gets rescaled is the dialog crop. Once
    // that crop carries a gutter, targeting `frame + gutter` would scale the capture by
    // `(frame + gutter) / (dialog + gutter)` rather than the un-guttered `frame / dialog` — so
    // adding the annotation would resize the component. The target has to grow with the crop.
    val engine = RenderEngine(outputDir = tempFolder.newFolder("unused"))
    // The `DialogWindowSurface` fixture is a 64 dp dialog; declared frame 96 px at density 1.
    assertEquals(96, engine.fixedAxisTargetPx(96, 0, 64))
    // 4+4 dp across ⇒ crop 72, scaled by the same 96/64 ⇒ 108. 4+5 dp down ⇒ crop 73 ⇒ 109.5 ⇒ 110.
    assertEquals(108, engine.fixedAxisTargetPx(96, 8, 64))
    assertEquals(110, engine.fixedAxisTargetPx(96, 9, 64))
    // Off the dialog path the capture is already `frame + gutter`, so the resize is a no-op.
    assertEquals(104, engine.fixedAxisTargetPx(96, 8, null))
  }

  @Test
  fun `the viewport qualifier grows by the combined pixel extent, not by quantized dp`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("unused-qualifier"))
    // An un-guttered render must not move at all — the qualifier stays what it was.
    assertEquals(0, engine.gutterQualifierDp(101, 0, 2.0f))
    // 101 px at density 2 truncates to 50 dp for the base. The content plus a 4+4 dp gutter needs
    // 117 px, i.e. 59 dp — so the gutter must contribute 9 dp, not the 8 dp an independently
    // ceilinged gutter would give (58 dp = 116 px leaves the window a pixel short and the layout
    // clamps a gutter pixel away).
    assertEquals(9, engine.gutterQualifierDp(101, 16, 2.0f))
    // The exactly-divisible case is unchanged: 100 px + 16 px = 116 px = 58 dp, base 50 dp ⇒ 8 dp.
    assertEquals(8, engine.gutterQualifierDp(100, 16, 2.0f))
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
