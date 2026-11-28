package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParams
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewPermutationsCliTest {
  private fun preview(id: String, provider: String? = null) =
    PreviewInfo(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      params = PreviewParams(kind = "COMPOSE", previewParameterProviderClassName = provider),
    )

  @Test
  fun `a phone preview's large font sibling is 2x`() {
    val base = preview("HomePreview")
    val large = PreviewPermutationsCli.expand(listOf(base), listOf("accessibility")).last()

    assertEquals("HomePreview_fontscale-2x", large.id)
    assertEquals(2.0f, PreviewPermutationsCli.overridesFor(base, large)?.fontScale)
  }

  @Test
  fun `a Glance Wear widget's large font sibling uses the Wear check scale`() {
    // Named exactly as the Gradle plugin's PreviewPermutations names it, so a CLI read of the
    // plugin's renders finds the same file.
    val base =
      preview(
        "WeatherWidgetRoundPreview",
        provider = "androidx.glance.wear.tooling.preview.RoundAllWidgetPreviewParams",
      )
    val large = PreviewPermutationsCli.expand(listOf(base), listOf("accessibility")).last()

    assertEquals("WeatherWidgetRoundPreview_fontscale-1.24x", large.id)
    assertEquals(1.24f, PreviewPermutationsCli.overridesFor(base, large)?.fontScale)
  }

  @Test
  fun `PreviewPermutationsCli skips DWF watch face entries when expanding accessibility`() {
    val previews =
      listOf(
        PreviewInfo(
          id = "dwf__raw_sample_dwf_watchface",
          functionName = "sample_dwf_watchface",
          className = "",
          includeInA11y = false,
          params = PreviewParams(name = "sample_dwf_watchface", kind = "COMPOSE"),
          captures =
            listOf(Capture(renderOutput = "dwf-renders/dwf__raw_sample_dwf_watchface.png")),
        ),
        PreviewInfo(
          id = "com.example.WearKt.CardPreview",
          functionName = "CardPreview",
          className = "com.example.WearKt",
          params = PreviewParams(name = "CardPreview", kind = "COMPOSE"),
          captures = listOf(Capture(renderOutput = "renders/CardPreview.png")),
        ),
      )

    val expanded = PreviewPermutationsCli.expand(previews, listOf("accessibility"))
    assertEquals(
      listOf(
        "dwf__raw_sample_dwf_watchface",
        "com.example.WearKt.CardPreview",
        "com.example.WearKt.CardPreview_dark",
        "com.example.WearKt.CardPreview_rtl",
        "com.example.WearKt.CardPreview_fontscale-2x",
      ),
      expanded.map { it.id },
    )
  }
}
