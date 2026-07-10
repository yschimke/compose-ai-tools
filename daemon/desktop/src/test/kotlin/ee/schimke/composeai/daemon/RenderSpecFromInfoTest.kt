package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #420 — pins the per-field translation from [PreviewInfoDto] (widened with the optional
 * [PreviewParamsDto] block in the same change) to [RenderSpec] inside the desktop daemon's
 * `previewIndexBackedSpecResolver`. The lambda itself is private to `DaemonMain.kt`; the conversion
 * proper is in the `internal` [renderSpecFromInfo] helper so this test can exercise it without
 * standing up a [PreviewIndex] or threading an inputs lambda through.
 *
 * Coverage matrix (one assertion clump per row):
 * - No size + no device (or an empty / absent params block) ⇒ wrap-content on both axes at the
 *   400×800 dp sandbox bound, mirroring the bake ([PreviewManifestEntry.resolved]) so a catalog
 *   sticker's live stream matches its wrap-cropped baked snapshot instead of shifting.
 * - Empty params block ⇒ identical to passing no block at all (both wrap).
 * - An explicit `device` ⇒ pinned: neither axis wraps, px dims fall back to defaults.
 * - `widthDp` / `heightDp` / `density` set ⇒ pixel dimensions multiply through and wrap is off.
 * - `density` defaulted ⇒ default 2.0x is used for the dp→px conversion of `widthDp`.
 * - `widthDp` set without `heightDp` ⇒ the width axis pins, the absent height axis wraps.
 * - `uiMode = 0x20` (UI_MODE_NIGHT_YES) ⇒ DARK enum on the spec; other values ⇒ default (null).
 * - `fontScale` / `locale` / `device` / `showBackground` / `backgroundColor` ⇒ verbatim through.
 */
class RenderSpecFromInfoTest {

  private fun info(params: PreviewParamsDto?): PreviewInfoDto =
    PreviewInfoDto(id = "Foo", className = "com.example.FooKt", methodName = "Foo", params = params)

  @Test
  fun `no-size no-device preview wraps to content in the sandbox bound`() {
    // A preview that declares neither an explicit size nor a device (a catalog sticker) renders
    // wrap-content, mirroring the offline bake (PreviewManifestEntry.resolved): both axes wrap and
    // the px dims are the 400x800 dp sandbox bound (× default 2x density), so the held-session /
    // stream render crops to the composable's intrinsic size instead of leaving it small in the
    // top-left of the old fixed 320² frame.
    val spec = renderSpecFromInfo(info(params = null))
    assertEquals(true, spec.wrapWidth)
    assertEquals(true, spec.wrapHeight)
    assertEquals(PreviewManifestEntry.WRAP_SANDBOX_WIDTH_DP * 2, spec.widthPx)
    assertEquals(PreviewManifestEntry.WRAP_SANDBOX_HEIGHT_DP * 2, spec.heightPx)
    assertEquals(2.0f, spec.density, 0.0f)
    assertNull(spec.uiMode)
    assertNull(spec.localeTag)
    assertNull(spec.fontScale)
    assertNull(spec.device)
    assertEquals("Foo", spec.previewId)
    assertEquals("com.example.FooKt", spec.className)
    assertEquals("Foo", spec.functionName)
  }

  @Test
  fun `empty params block is identical to null params`() {
    val nullSpec = renderSpecFromInfo(info(params = null))
    val emptySpec = renderSpecFromInfo(info(params = PreviewParamsDto()))
    // Same shape; we don't compare data classes directly because outputBaseName is computed on
    // both sides identically. A null block is treated exactly like an empty one — both wrap.
    assertEquals(nullSpec.widthPx, emptySpec.widthPx)
    assertEquals(nullSpec.heightPx, emptySpec.heightPx)
    assertEquals(nullSpec.wrapWidth, emptySpec.wrapWidth)
    assertEquals(nullSpec.wrapHeight, emptySpec.wrapHeight)
    assertEquals(nullSpec.density, emptySpec.density, 0.0f)
    assertEquals(nullSpec.uiMode, emptySpec.uiMode)
    assertEquals(nullSpec.localeTag, emptySpec.localeTag)
    assertEquals(nullSpec.fontScale, emptySpec.fontScale)
  }

  @Test
  fun `an explicit device pins the frame so neither axis wraps`() {
    // A device frame is "pinned": the fixed frame is kept (device sizing is applied downstream), so
    // the wrap flags stay off and the px dims fall back to the RenderSpec defaults.
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(device = "id:pixel_5")))
    assertEquals(false, spec.wrapWidth)
    assertEquals(false, spec.wrapHeight)
    assertEquals(320, spec.widthPx)
    assertEquals(320, spec.heightPx)
    assertEquals("id:pixel_5", spec.device)
  }

  @Test
  fun `an explicit size on both axes disables wrap`() {
    val spec =
      renderSpecFromInfo(
        info(params = PreviewParamsDto(widthDp = 200, heightDp = 600, density = 3.0f))
      )
    assertEquals(false, spec.wrapWidth)
    assertEquals(false, spec.wrapHeight)
    assertEquals(600, spec.widthPx)
    assertEquals(1800, spec.heightPx)
  }

  @Test
  fun `widthDp heightDp density multiply through to pixel dimensions`() {
    val spec =
      renderSpecFromInfo(
        info(params = PreviewParamsDto(widthDp = 200, heightDp = 600, density = 3.0f))
      )
    assertEquals(600, spec.widthPx)
    assertEquals(1800, spec.heightPx)
    assertEquals(3.0f, spec.density, 0.0f)
  }

  @Test
  fun `widthDp without density uses the default 2x density for the conversion`() {
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(widthDp = 200, heightDp = 600)))
    assertEquals(400, spec.widthPx)
    assertEquals(1200, spec.heightPx)
    assertEquals(2.0f, spec.density, 0.0f)
  }

  @Test
  fun `widthDp set heightDp null wraps only the unspecified axis`() {
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(widthDp = 500, density = 1.0f)))
    // The explicit width axis is pinned (no wrap); the absent height axis wraps to the sandbox
    // bound (× density), mirroring the bake — the render then crops height to intrinsic content.
    assertEquals(500, spec.widthPx)
    assertEquals(false, spec.wrapWidth)
    assertEquals(PreviewManifestEntry.WRAP_SANDBOX_HEIGHT_DP * 1, spec.heightPx)
    assertEquals(true, spec.wrapHeight)
    assertEquals(1.0f, spec.density, 0.0f)
  }

  @Test
  fun `uiMode night bit decodes to DARK enum`() {
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(uiMode = 0x20)))
    assertEquals(RenderSpec.SpecUiMode.DARK, spec.uiMode)
  }

  @Test
  fun `uiMode 0 leaves spec uiMode null`() {
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(uiMode = 0)))
    assertNull(spec.uiMode)
  }

  @Test
  fun `uiMode null leaves spec uiMode null`() {
    val spec = renderSpecFromInfo(info(params = PreviewParamsDto(uiMode = null)))
    assertNull(spec.uiMode)
  }

  @Test
  fun `fontScale locale device showBackground backgroundColor pass through verbatim`() {
    val spec =
      renderSpecFromInfo(
        info(
          params =
            PreviewParamsDto(
              fontScale = 1.3f,
              locale = "ja-JP",
              device = "id:pixel_5",
              showBackground = true,
              backgroundColor = 0xFFEEEEEE,
            )
        )
      )
    assertEquals(1.3f, spec.fontScale!!, 0.0f)
    assertEquals("ja-JP", spec.localeTag)
    assertEquals("id:pixel_5", spec.device)
    assertEquals(true, spec.showBackground)
    assertEquals(0xFFEEEEEE, spec.backgroundColor)
  }
}
