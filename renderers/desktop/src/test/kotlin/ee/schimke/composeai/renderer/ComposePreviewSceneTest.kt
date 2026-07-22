package ee.schimke.composeai.renderer

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [composePreviewSceneSize] — the scene-dimension math shared by the daemon desktop
 * `RenderEngine` and the one-shot [DesktopRendererMain] fork. The wrap-measure box
 * ([ComposePreviewContentBox]) is exercised end-to-end by the renderer's size-bounds render tests
 * and the daemon's wrap-content tests; this covers the pure sizing rule directly.
 */
class ComposePreviewSceneTest {

  @Test
  fun `fixed axes use the requested frame size and ignore bounds`() {
    val bounds = PreviewSizeBounds(minWidthPx = 5000, minHeightPx = 5000, maxWidthPx = 9000)
    assertEquals(
      IntSize(400, 800),
      composePreviewSceneSize(400, 800, wrapWidth = false, wrapHeight = false, sizeBounds = bounds),
    )
  }

  @Test
  fun `a wrapped axis widens to a min bound larger than the frame`() {
    val size =
      composePreviewSceneSize(
        widthPx = 300,
        heightPx = 600,
        wrapWidth = true,
        wrapHeight = false,
        sizeBounds = PreviewSizeBounds(minWidthPx = 900),
      )
    assertEquals(IntSize(900, 600), size)
  }

  @Test
  fun `a wrapped axis widens to a max bound larger than the frame`() {
    // The scene must fit the largest extent any mode can ask for, so it widens to a max bound too;
    // the intrinsic-size crop trims the PNG back down afterwards.
    val size =
      composePreviewSceneSize(
        widthPx = 300,
        heightPx = 600,
        wrapWidth = false,
        wrapHeight = true,
        sizeBounds = PreviewSizeBounds(maxHeightPx = 1500),
      )
    assertEquals(IntSize(300, 1500), size)
  }

  @Test
  fun `bounds smaller than the frame never shrink the scene`() {
    val size =
      composePreviewSceneSize(
        widthPx = 800,
        heightPx = 800,
        wrapWidth = true,
        wrapHeight = true,
        sizeBounds = PreviewSizeBounds(minWidthPx = 100, maxWidthPx = 200, minHeightPx = 50),
      )
    assertEquals(IntSize(800, 800), size)
  }

  @Test
  fun `absent bounds keep the frame size on a wrapped axis`() {
    val size = composePreviewSceneSize(500, 700, wrapWidth = true, wrapHeight = true)
    assertEquals(IntSize(500, 700), size)
  }
}
