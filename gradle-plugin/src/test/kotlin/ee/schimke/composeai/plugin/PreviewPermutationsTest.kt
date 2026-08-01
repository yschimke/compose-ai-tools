package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewDataProduct
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewKind
import ee.schimke.composeai.discovery.PreviewParams
import org.junit.Test

class PreviewPermutationsTest {

  @Test
  fun `accessibility expands compose previews to dark rtl and large font siblings`() {
    val preview =
      PreviewInfo(
        id = "ButtonPreview",
        functionName = "ButtonPreview",
        className = "com.example.ButtonKt",
        params = PreviewParams(uiMode = 0x11),
        captures = listOf(Capture(renderOutput = "renders/ButtonPreview.png")),
        dataProducts =
          listOf(
            PreviewDataProduct(
              kind = "render/scroll/long",
              output = "data/scroll/ButtonPreview.png",
            )
          ),
      )

    val expanded = PreviewPermutations.expand(listOf(preview), listOf("accessibility"))

    assertThat(expanded.map { it.id })
      .containsExactly(
        "ButtonPreview",
        "ButtonPreview_dark",
        "ButtonPreview_rtl",
        "ButtonPreview_fontscale-2x",
      )
      .inOrder()
    assertThat(expanded[1].params.uiMode).isEqualTo(0x21)
    assertThat(expanded[1].captures.single().renderOutput)
      .isEqualTo("renders/ButtonPreview_dark.png")
    assertThat(expanded[2].params.locale).isEqualTo("ar-XB")
    assertThat(expanded[2].captures.single().renderOutput)
      .isEqualTo("renders/ButtonPreview_rtl.png")
    assertThat(expanded[3].params.fontScale).isEqualTo(2.0f)
    assertThat(expanded[3].dataProducts.single().output)
      .isEqualTo("data/scroll/ButtonPreview_fontscale-2x.png")
  }

  @Test
  fun `accessibility leaves non compose preview kinds alone`() {
    val lottie =
      PreviewInfo(
        id = "Anim",
        functionName = "Anim",
        className = "com.example.AnimKt",
        params = PreviewParams(kind = PreviewKind.LOTTIE),
      )

    assertThat(PreviewPermutations.expand(listOf(lottie), listOf("accessibility")))
      .containsExactly(lottie)
  }

  @Test
  fun `blank or unknown permutations are a no-op`() {
    val preview = PreviewInfo(id = "ButtonPreview", functionName = "ButtonPreview", className = "C")

    assertThat(PreviewPermutations.expand(listOf(preview), listOf(" ", "contrast")))
      .containsExactly(preview)
  }
}
