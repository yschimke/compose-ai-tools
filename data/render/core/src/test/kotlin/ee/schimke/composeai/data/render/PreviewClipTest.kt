package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [PreviewClip]. Both halves matter for different reasons: [PreviewClip.resolve] is the shape
 * three consumers share — the daemon's `render/deviceClip` product, serve's stage and the fidelity
 * scorer's mask — and [PreviewClip.cssClipPath] is the one place a percentage basis can be got
 * quietly wrong in a way that still looks like a circle.
 */
class PreviewClipTest {

  @Test
  fun `a round device clips to the circle inscribed in its shorter side`() {
    val shape = PreviewClip.resolve(isRound = true, widthDp = 227.0, heightDp = 227.0)
    assertEquals(PreviewClip.Shape.Circle(113.5, 113.5, 113.5), shape)
  }

  @Test
  fun `a non-square round device still clips to ONE circle, not an ellipse`() {
    // Wear device ids resolve square, but a `spec:` device is free to state anything and half of
    // each side independently would be an ellipse — a shape no watch has.
    val shape = PreviewClip.resolve(isRound = true, widthDp = 240.0, heightDp = 200.0)
    assertEquals(PreviewClip.Shape.Circle(120.0, 100.0, 100.0), shape)
  }

  @Test
  fun `a device that is not round has no clip at all`() {
    assertNull(PreviewClip.resolve(isRound = false, widthDp = 411.0, heightDp = 891.0))
  }

  @Test
  fun `a round device with no resolved size does not get an invented circle`() {
    // The honest answer is the square everyone drew before this existed. Guessing a radius here
    // would crop real screen content on a device whose dimensions simply never resolved.
    assertNull(PreviewClip.resolve(isRound = true, widthDp = null, heightDp = 227.0))
    assertNull(PreviewClip.resolve(isRound = true, widthDp = 227.0, heightDp = null))
    assertNull(PreviewClip.resolve(isRound = true, widthDp = 0.0, heightDp = 0.0))
  }

  @Test
  fun `the css radius uses the diagonal basis a circle percentage actually resolves against`() {
    // On a SQUARE box the basis `sqrt(w² + h²) / sqrt(2)` equals the side, so a half-width radius
    // is a clean 50% and the trap below stays invisible.
    val shape = PreviewClip.Shape.Circle(113.5, 113.5, 113.5)
    assertEquals("circle(50% at 50% 50%)", PreviewClip.cssClipPath(shape, 227.0, 227.0))
  }

  @Test
  fun `a non-square box does NOT get the naive half-the-shorter-side percentage`() {
    // 200-tall box, 100dp radius. Against the shorter side that would read 50%; CSS resolves a
    // circle percentage against sqrt(240² + 200²)/sqrt(2) = 220.9, so the honest answer is 45.27%.
    // Emitting 50% here would over-clip by ~10dp — a bezel that looks intentional and is not.
    val shape = PreviewClip.Shape.Circle(120.0, 100.0, 100.0)
    assertEquals("circle(45.27% at 50% 50%)", PreviewClip.cssClipPath(shape, 240.0, 200.0))
  }

  @Test
  fun `an off-centre circle keeps its own centre`() {
    // Square box, so the basis is the side: a 50dp radius in a 200dp box is a plain 25%.
    val shape = PreviewClip.Shape.Circle(60.0, 100.0, 50.0)
    assertEquals("circle(25% at 30% 50%)", PreviewClip.cssClipPath(shape, 200.0, 200.0))
  }

  @Test
  fun `a box with no area has no clip path rather than a divide by zero`() {
    val shape = PreviewClip.Shape.Circle(113.5, 113.5, 113.5)
    assertNull(PreviewClip.cssClipPath(shape, 0.0, 227.0))
  }
}
