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
  fun `prunes catalog-token sidecars for sheets absent from the manifest`() {
    val catalogDir = tempDir.root.resolve("build/compose-previews/data/catalog-tokens")
    catalogDir.mkdirs()
    // Two sheets are current; one (`typographycatalog__Removed`) was renamed/deleted, plus an
    // unrelated file that must be left alone.
    val current1 = catalogDir.resolve("colorcatalog__Brand.catalog.json").apply { writeText("{}") }
    val current2 =
      catalogDir.resolve("typographycatalog__Body.catalog.json").apply { writeText("{}") }
    val stale =
      catalogDir.resolve("typographycatalog__Removed.catalog.json").apply { writeText("{}") }
    val unrelated = catalogDir.resolve("notes.txt").apply { writeText("keep") }

    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "colorcatalog__Brand",
              functionName = "colorcatalog__Brand",
              className = "com.example.ColorTokensKt",
              params = PreviewParams(kind = PreviewKind.CATALOG),
            ),
            PreviewInfo(
              id = "typographycatalog__Body",
              functionName = "typographycatalog__Body",
              className = "com.example.TypographyTokensKt",
              params = PreviewParams(kind = PreviewKind.CATALOG),
            ),
          ),
      )

    ComposePreviewTasks.cleanStaleCatalogTokens(
      catalogDir,
      manifest,
      org.gradle.api.logging.Logging.getLogger(CleanStaleRendersTest::class.java),
    )

    assertThat(current1.exists()).isTrue()
    assertThat(current2.exists()).isTrue()
    assertThat(stale.exists()).isFalse()
    assertThat(unrelated.exists()).isTrue()
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
}
