package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #2615: a Wear surface whose fill arrives wrapped in a painter the resolver doesn't
 * recognise resolves no colour, so its container collapses to a whole-layer raster and takes the
 * editable subtree with it — the "transformed surfaces absent, reduced to isolated raster/icon
 * leaves" symptom on Jetcaster Wear's `TransformingLazyColumn` + `SurfaceTransformation` screens.
 *
 * `BackgroundPainter` was already unwrapped by name. These pin the *structural* unwrap that covers
 * whatever wrapper a given Wear component actually uses, without the resolver having to know its
 * class.
 */
class DelegatingPainterUnwrapTest {

  /** A painter that delegates its drawing to exactly one other painter. */
  private class WrappingPainter(@JvmField val delegate: Painter) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = Unit
  }

  /** A wrapper holding two painters — which one is the fill is a guess, so it must not resolve. */
  private class AmbiguousPainter(@JvmField val a: Painter, @JvmField val b: Painter) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = Unit
  }

  private fun resolve(painter: Painter): String? =
    ModifierTokenResolver.painterFillHexForTest(painter)

  @Test
  fun `a bare ColorPainter still resolves`() {
    assertEquals("#FF112233", resolve(ColorPainter(Color(0xFF112233))))
  }

  @Test
  fun `a single-level wrapper resolves to the colour it delegates to`() {
    assertEquals("#FF112233", resolve(WrappingPainter(ColorPainter(Color(0xFF112233)))))
  }

  @Test
  fun `a nested wrapper resolves through both levels`() {
    val painter = WrappingPainter(WrappingPainter(ColorPainter(Color(0xFF445566))))
    assertEquals("#FF445566", resolve(painter))
  }

  @Test
  fun `a wrapper with two painter fields is left for the raster fallback`() {
    val painter = AmbiguousPainter(ColorPainter(Color(0xFF112233)), ColorPainter(Color(0xFF445566)))
    assertNull("ambiguous delegate must not be guessed at", resolve(painter))
  }

  @Test
  fun `a wrapper that bottoms out in a non-solid painter stays unresolved`() {
    // Nothing flat to recover — the raster fallback is the correct outcome here.
    val painter = WrappingPainter(WrappingPainter(NonSolidPainter()))
    assertNull(resolve(painter))
  }

  private class NonSolidPainter : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = Unit
  }
}
