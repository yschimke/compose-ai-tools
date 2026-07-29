package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how [ModifierTokenResolver.linearGradient] resolves a Compose `LinearGradient`'s endpoints
 * into the `objectBoundingBox` fractions the SVG `<linearGradient>` uses (issue #2852).
 *
 * The subtle case is the *unspecified* endpoint. `Brush.linearGradient(colors)` with no explicit
 * `start`/`end` stores `Offset.Infinite` — infinite on **both** axes — which Compose resolves to
 * the far corner of the box, i.e. the diagonal gradient. Treating only X that way and defaulting Y
 * to 0 flattened those to horizontal, so several samples' exported gradients ran the wrong way
 * against their render.
 */
class LinearGradientEndpointTest {

  /** Stands in for `androidx.compose.ui.graphics.LinearGradient` — matched by simple name. */
  @Suppress("unused")
  private class LinearGradient(
    @JvmField val colors: List<Color>,
    @JvmField val stops: List<Float>?,
    @JvmField val start: Long,
    @JvmField val end: Long,
  )

  /** Compose's `Offset` is a value class over two floats packed into a `Long`. */
  private fun offset(x: Float, y: Float): Long =
    (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)

  private val infinite = offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

  private fun resolve(start: Long, end: Long) =
    ModifierTokenResolver.linearGradient(
      mod = Any(),
      elements =
        mapOf(
          "brush" to
            LinearGradient(colors = listOf(Color.Red, Color.Blue), stops = null, start, end)
        ),
      widthPx = 100,
      heightPx = 40,
    )

  @Test
  fun `an unspecified endpoint resolves to the far corner, keeping the gradient diagonal`() {
    val g = requireNotNull(resolve(start = offset(0f, 0f), end = infinite))
    assertEquals(0f, g.startX, 0f)
    assertEquals(0f, g.startY, 0f)
    assertEquals(1f, g.endX, 0f)
    assertEquals("an infinite Y endpoint is the bottom edge, not 0", 1f, g.endY, 0f)
  }

  @Test
  fun `a horizontal gradient keeps its finite zero Y endpoint`() {
    // `Brush.horizontalGradient` leaves Y finite at 0 — only X runs to the edge.
    val g =
      requireNotNull(resolve(start = offset(0f, 0f), end = offset(Float.POSITIVE_INFINITY, 0f)))
    assertEquals(1f, g.endX, 0f)
    assertEquals(0f, g.endY, 0f)
  }

  @Test
  fun `a vertical gradient keeps its finite zero X endpoint`() {
    val g =
      requireNotNull(resolve(start = offset(0f, 0f), end = offset(0f, Float.POSITIVE_INFINITY)))
    assertEquals(0f, g.endX, 0f)
    assertEquals(1f, g.endY, 0f)
  }

  @Test
  fun `explicit endpoints are expressed as fractions of the box`() {
    val g = requireNotNull(resolve(start = offset(25f, 10f), end = offset(75f, 30f)))
    assertEquals(0.25f, g.startX, 1e-6f)
    assertEquals(0.25f, g.startY, 1e-6f)
    assertEquals(0.75f, g.endX, 1e-6f)
    assertEquals(0.75f, g.endY, 1e-6f)
  }
}
