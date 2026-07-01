package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CleanStaleRendersTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val expected = setOf("Foo.png", "sub/Bar.png", "Baz_TIME_500ms.png")

  @Test
  fun `keeps a11y sibling of registered render`() {
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.a11y.png", expected)).isTrue()
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("sub/Bar.a11y.png", expected)).isTrue()
  }

  @Test
  fun `keeps a11y sibling of fan-out capture`() {
    // Multi-capture previews encode dimensions in the basename
    // (`_TIME_500ms`); the a11y overlay sits next to the same basename.
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Baz_TIME_500ms.a11y.png", expected))
      .isTrue()
  }

  @Test
  fun `drops a11y png with no clean sibling`() {
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Removed.a11y.png", expected)).isFalse()
  }

  @Test
  fun `ignores non-a11y png entirely`() {
    // Plain renders are handled by the exact-match branch in
    // cleanStaleRenders; this helper should pass them through (false)
    // so the caller decides.
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.png", expected)).isFalse()
    assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.gif", expected)).isFalse()
  }

  @Test
  fun `missing output check includes scroll data products`() {
    val outDir = tempDir.root.resolve("build/compose-previews")
    // `@ScrollingPreview(modes = [LONG])` alone produces ONLY a data product — discovery
    // no longer emits a phantom `renders/<id>.png` capture (issue #1524). The product must
    // still exist on disk for the preview to be considered rendered.
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "Foo",
              functionName = "Foo",
              className = "com.example.PreviewsKt",
              captures = emptyList(),
              dataProducts =
                listOf(
                  PreviewDataProduct(
                    kind = "render/scroll/long",
                    output = "data/render-scroll-long/Foo.png",
                    cost = SCROLL_LONG_COST,
                  )
                ),
            )
          ),
      )

    assertThat(ComposePreviewTasks.missingPreviewOutputIds(manifest, outDir, isFastTier = false))
      .containsExactly("Foo")

    outDir.resolve("data/render-scroll-long/Foo.png").also {
      it.parentFile.mkdirs()
      it.writeBytes(byteArrayOf(2))
    }

    assertThat(ComposePreviewTasks.missingPreviewOutputIds(manifest, outDir, isFastTier = false))
      .isEmpty()
  }

  @Test
  fun `fast tier tolerates missing heavy data products`() {
    val outDir = tempDir.root.resolve("build/compose-previews")
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "Foo",
              functionName = "Foo",
              className = "com.example.PreviewsKt",
              captures = emptyList(),
              dataProducts =
                listOf(
                  PreviewDataProduct(
                    kind = "render/scroll/long",
                    output = "data/render-scroll-long/Foo.png",
                    cost = SCROLL_LONG_COST,
                  )
                ),
            )
          ),
      )

    assertThat(ComposePreviewTasks.missingPreviewOutputIds(manifest, outDir, isFastTier = true))
      .isEmpty()
  }

  @Test
  fun `optional capture never counted as missing`() {
    val outDir = tempDir.root.resolve("build/compose-previews")
    // The XR composite is an `optional = true` capture: best-effort, baked out-of-band by the
    // native `xr-composite` tool. When the file is absent (no binary / display / software GL) the
    // missing-render gate must not flag it — same graceful degradation as before the capture
    // existed.
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "com.example.PreviewsKt.SpatialPreview",
              functionName = "SpatialPreview",
              className = "com.example.PreviewsKt",
              params = PreviewParams(kind = PreviewKind.XR_SUBSPACE),
              captures =
                listOf(
                  Capture(
                    renderOutput = "renders/com.example.PreviewsKt.SpatialPreview/composite.png",
                    optional = true,
                  )
                ),
            )
          ),
      )

    assertThat(ComposePreviewTasks.missingPreviewOutputIds(manifest, outDir, isFastTier = false))
      .isEmpty()
  }

  @Test
  fun `catalog render required on android, excluded on desktop`() {
    val outDir = tempDir.root.resolve("build/compose-previews")
    // A CATALOG sheet with no PNG on disk. The Android backend renders catalog sheets, so a missing
    // one is a real regression and must be flagged (requireCatalog = true, the default). The
    // desktop
    // backend can't render them yet (#2135) — its render task skips them, so its validation pass
    // must not demand the PNG (requireCatalog = false). This is the split that replaced marking the
    // capture globally `optional`, which would have blinded the Android gate too.
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "colorcatalog__Brand",
              functionName = "Brand colours",
              className = "com.example.TokensKt",
              params = PreviewParams(kind = PreviewKind.CATALOG),
              captures = listOf(Capture(renderOutput = "renders/colorcatalog__Brand.png")),
            )
          ),
      )

    assertThat(
        ComposePreviewTasks.missingPreviewOutputIds(
          manifest,
          outDir,
          isFastTier = false,
          requireCatalog = true,
        )
      )
      .containsExactly("colorcatalog__Brand")
    assertThat(
        ComposePreviewTasks.missingPreviewOutputIds(
          manifest,
          outDir,
          isFastTier = false,
          requireCatalog = false,
        )
      )
      .isEmpty()
  }
}
