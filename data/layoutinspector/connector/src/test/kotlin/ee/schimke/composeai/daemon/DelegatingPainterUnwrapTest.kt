package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

  /** A painter that delegates its drawing to exactly one other painter, and holds nothing else. */
  private class WrappingPainter(@JvmField val delegate: Painter) : Painter() {
    override val intrinsicSize: Size = delegate.intrinsicSize

    override fun DrawScope.onDraw() {
      with(delegate) { draw(size) }
    }
  }

  /** A wrapper holding two painters — which one is the fill is a guess, so it must not resolve. */
  private class AmbiguousPainter(@JvmField val a: Painter, @JvmField val b: Painter) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = Unit
  }

  /**
   * A wrapper that also re-tints what it forwards. The inner colour is *not* what the render
   * painted, so recovering it would draw the container in the wrong colour.
   */
  private class TintingPainter(@JvmField val delegate: Painter, @JvmField val tint: Long) :
    Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() = Unit
  }

  /**
   * A minimal [Shape] — this module is deliberately foundation-free, so no `RoundedCornerShape`.
   */
  private object BoxShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
      Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
  }

  /** A wrapper that clips what it forwards to its own shape. */
  private class ShapingPainter(@JvmField val delegate: Painter, @JvmField val outline: Shape) :
    Painter() {
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
  fun `a wrapper carrying paint-altering state is left for the raster fallback`() {
    val painter = TintingPainter(ColorPainter(Color(0xFF112233)), tint = 0x7F000000L)
    assertNull("a re-tinting wrapper's inner colour is not what the render drew", resolve(painter))
  }

  @Test
  fun `a wrapper that clips to its own shape is left for the raster fallback`() {
    // The type-based half of the check: a `Shape` field is a paint concept with a real type, so
    // it's caught without relying on the field's name.
    val painter = ShapingPainter(ColorPainter(Color(0xFF112233)), BoxShape)
    assertNull(resolve(painter))
  }

  @Test
  fun `extra state anywhere in the chain stops the descent`() {
    val painter = WrappingPainter(TintingPainter(ColorPainter(Color(0xFF112233)), tint = 0L))
    assertNull(resolve(painter))
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
