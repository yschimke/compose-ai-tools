package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how the Android bundle render path reconciles produced PNGs back to previews: by the
 * manifest capture's `renderOutput` leaf — the exact name `RobolectricRenderTest.outputFileFor`
 * writes — not a name derived from the preview id. Getting this wrong silently marks successful
 * renders as failures (Codex review on #1651).
 */
class BundleRendererAndroidOutputTest {

  private fun preview(id: String, renderOutput: String): PreviewInfo =
    PreviewInfo(
      id = id,
      functionName = "CardPreview",
      className = "com.example.FooKt",
      captures = listOf(Capture(renderOutput = renderOutput)),
    )

  @Test
  fun `leaf is the basename of the capture renderOutput`() {
    // Discovery normalizes `com.example.FooKt.CardPreview` to a `renders/CardPreview.png` leaf.
    val p = preview("com.example.FooKt.CardPreview", "renders/CardPreview.png")
    assertEquals("CardPreview.png", BundleRenderer.androidOutputLeaf(p))
  }

  @Test
  fun `empty renderOutput falls back to the raw id dot png`() {
    // Matches RobolectricRenderTest.outputFileFor's `${preview.id}.png` fallback (raw id).
    val p = preview("MyPreview", "")
    assertEquals("MyPreview.png", BundleRenderer.androidOutputLeaf(p))
  }

  @Test
  fun `no captures falls back to the raw id dot png`() {
    val p =
      PreviewInfo(
        id = "Lonely",
        functionName = "Lonely",
        className = "pkg.Kt",
        captures = emptyList(),
      )
    assertEquals("Lonely.png", BundleRenderer.androidOutputLeaf(p))
  }
}
