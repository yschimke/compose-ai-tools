package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderSpecFromInfoTest {

  @Test
  fun `preview id is preserved without params`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "preview-id",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = null,
        )
      )

    assertEquals("preview-id", spec.previewId)
  }

  @Test
  fun `preview id is preserved with params`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "preview-id",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = PreviewParamsDto(widthDp = 200, density = 1.5f),
        )
      )

    assertEquals("preview-id", spec.previewId)
    assertEquals(300, spec.widthPx)
  }

  @Test
  fun `a wrap sandbox narrows the bound without pinning either axis`() {
    // The Wear lane: `retargetWearStickers` hands a Wear module's device-less previews the 227dp
    // watch screen as their wrap sandbox. `bundle daemon` / `compose-preview serve` resolve through
    // PreviewIndex rather than PreviewManifestRouter, so this resolver must honour it too —
    // otherwise a fillMaxWidth sticker measures against 400dp live while its baked render used
    // 227dp. Both axes must STILL wrap: the sandbox is a bound, not a frame.
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "FilledButton",
          className = "com.example.CatalogPreviewsKt",
          methodName = "FilledButton",
          params =
            PreviewParamsDto(
              wrapSandboxWidthDp = 227,
              wrapSandboxHeightDp = 227,
              density = 2.0f,
            ),
        )
      )

    assertEquals(true, spec.wrapWidth)
    assertEquals(true, spec.wrapHeight)
    assertEquals(454, spec.widthPx)
    assertEquals(454, spec.heightPx)
  }

  @Test
  fun `an explicit widthDp still wins over the wrap sandbox`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "Specimen",
          className = "com.example.CatalogPreviewsKt",
          methodName = "Specimen",
          params = PreviewParamsDto(widthDp = 120, wrapSandboxWidthDp = 227, density = 2.0f),
        )
      )

    assertEquals(false, spec.wrapWidth)
    assertEquals(240, spec.widthPx)
    // Per-axis: only the width carried a sandbox, so the height axis still wraps against the
    // generic default rather than inheriting the width's 227dp.
    assertEquals(true, spec.wrapHeight)
    assertEquals(PreviewManifestEntry.WRAP_SANDBOX_HEIGHT_DP * 2, spec.heightPx)
  }
}
