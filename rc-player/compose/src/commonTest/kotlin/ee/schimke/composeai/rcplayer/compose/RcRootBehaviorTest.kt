package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RcRootBehaviorTest {
  @Test
  fun absentSizingDoesNotScaleCanvasOperations() {
    assertEquals(RcRootTransform(1f, 1f, 0f, 0f), computeRootTransform(100f, 50f, 400f, 400f, null))
  }

  @Test
  fun scaleFitCentersUsingAndroidXAlignmentBits() {
    val behavior =
      RcRootContentBehavior(
        scroll = RcRootContentBehavior.NONE,
        alignment = RcRootContentBehavior.ALIGNMENT_CENTER,
        sizing = RcRootContentBehavior.SIZING_SCALE,
        mode = RcRootContentBehavior.SCALE_FIT,
      )

    assertEquals(
      RcRootTransform(4f, 4f, 0f, 100f),
      computeRootTransform(100f, 50f, 400f, 400f, behavior),
    )
  }

  @Test
  fun scaleFillBoundsUsesIndependentAxesAndEndBottomAlignment() {
    val behavior =
      RcRootContentBehavior(
        scroll = RcRootContentBehavior.NONE,
        alignment = RcRootContentBehavior.ALIGNMENT_END + RcRootContentBehavior.ALIGNMENT_BOTTOM,
        sizing = RcRootContentBehavior.SIZING_SCALE,
        mode = RcRootContentBehavior.SCALE_FILL_BOUNDS,
      )

    assertEquals(
      RcRootTransform(4f, 8f, 0f, 0f),
      computeRootTransform(100f, 50f, 400f, 400f, behavior),
    )
  }

  @Test
  fun themeSectionsMatchAndroidXPaintFiltering() {
    assertTrue(isThemeVisible(RcTheme.UNSPECIFIED, RcTheme.DARK))
    assertTrue(isThemeVisible(RcTheme.DARK, RcTheme.UNSPECIFIED))
    assertTrue(isThemeVisible(RcTheme.DARK, RcTheme.DARK))
    assertFalse(isThemeVisible(RcTheme.DARK, RcTheme.LIGHT))
  }
}
