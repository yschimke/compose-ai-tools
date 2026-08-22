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

  @Test
  fun `capture gutter grows the scene on both fixed and wrapped axes`() {
    val gutter = PreviewCaptureGutter(startPx = 8, topPx = 8, endPx = 8, bottomPx = 10)
    assertEquals(
      "a fixed frame keeps its declared size and gains the gutter around it",
      IntSize(416, 818),
      composePreviewSceneSize(400, 800, wrapWidth = false, wrapHeight = false, gutter = gutter),
    )
    assertEquals(
      "a wrapped axis gains it on top of the sandbox, so the wrap measure is unchanged",
      IntSize(416, 818),
      composePreviewSceneSize(400, 800, wrapWidth = true, wrapHeight = true, gutter = gutter),
    )
  }

  @Test
  fun `capture gutter stacks on an enlarged size-bound scene`() {
    assertEquals(
      "the min bound widens the scene, then the gutter is added to that",
      IntSize(916, 800),
      composePreviewSceneSize(
        400,
        800,
        wrapWidth = true,
        wrapHeight = false,
        sizeBounds = PreviewSizeBounds(minWidthPx = 900),
        gutter = PreviewCaptureGutter(startPx = 8, endPx = 8),
      ),
    )
  }

  @Test
  fun `a dp gutter resolves per edge at the render density`() {
    val gutter = PreviewCaptureGutter.ofDp(startDp = 4, topDp = 4, endDp = 4, bottomDp = 5, 2.625f)
    assertEquals(11, gutter.startPx)
    assertEquals(11, gutter.topPx)
    assertEquals(11, gutter.endPx)
    assertEquals(13, gutter.bottomPx)
    assertEquals("horizontal is the sum of the two edges", 22, gutter.horizontalPx)
    assertEquals("vertical is the sum of the two edges", 24, gutter.verticalPx)
  }

  @Test
  fun `an all-zero gutter is empty and leaves the scene alone`() {
    val none = PreviewCaptureGutter.ofDp(0, 0, 0, 0, density = 2.625f)
    assertEquals(PreviewCaptureGutter.None, none)
    assertEquals(
      composePreviewSceneSize(400, 800, wrapWidth = true, wrapHeight = true),
      composePreviewSceneSize(400, 800, wrapWidth = true, wrapHeight = true, gutter = none),
    )
  }
}
