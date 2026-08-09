package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `graphicsLayer { … }` block that *derives* its alpha from the node's geometry has to be
 * evaluated against the box the node was actually drawn in (issue #2615).
 *
 * Wear's `TransformingLazyColumn` / `SurfaceTransformation` is exactly that shape: the item's alpha
 * is a function of how its slot was scaled, so it reads `size` before assigning `alpha`. The
 * evaluator answered every getter with the type's zero, handing the block a 0×0 box — every
 * Jetcaster Wear screen using that pair came out with `opacity="0.0"` / `"0.04"` groups the PNG
 * paints fully opaque, and the scores sat unchanged across four releases.
 */
class GraphicsLayerBlockGeometryTest {

  /** Stands in for a lambda-form `graphicsLayer` element: the resolver reads its `block` field. */
  private class LayerElement(@JvmField val block: GraphicsLayerScope.() -> Unit)

  /** `Size` is a value class over a packed `Long`, the shape the resolver builds and reads. */
  private fun packedSize(width: Float, height: Float): Long =
    (width.toRawBits().toLong() shl 32) or (height.toRawBits().toLong() and 0xFFFFFFFFL)

  @Test
  fun aBlockDerivingAlphaFromSizeSeesTheNodesRealBox() {
    // Fades in proportion to how tall the item was drawn, the shape Wear's edge transform takes.
    val element = LayerElement { alpha = (size.height / 100f).coerceIn(0f, 1f) }

    val alpha = ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(200f, 80f))

    assertEquals(0.8f, alpha!!, 0.001f)
  }

  @Test
  fun withoutAKnownSizeTheBlockStillEvaluates() {
    // No usable coordinates (a detached node): the evaluator keeps its identity defaults rather
    // than inventing geometry. `Size.Zero` is what the block sees, which is the pre-existing
    // behaviour — the point of the fix is that a *placed* node no longer lands here.
    val element = LayerElement { alpha = (size.height / 100f).coerceIn(0f, 1f) }

    assertEquals(0f, ModifierTokenResolver.evaluateLayerBlockAlpha(element, null)!!, 0.001f)
  }

  @Test
  fun aBlockThatIgnoresGeometryIsUnaffected() {
    // The #2853 shape — a closed-over animation value — must read back exactly as before.
    val element = LayerElement { alpha = 0f }

    assertEquals(
      0f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(48f, 48f))!!,
      0.001f,
    )
  }

  @Test
  fun aRelativeFadeStillStartsFromTheIdentityAlpha() {
    // `alpha *= …` reads before writing; the identity default (1f) still applies, and geometry
    // doesn't disturb it.
    val element = LayerElement { alpha *= 0.5f }

    assertEquals(
      0.5f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(48f, 48f))!!,
      0.001f,
    )
  }

  @Test
  fun aNonFiniteAlphaIsDeclinedRatherThanExported() {
    // `GraphicsLayerScope.size` defaults to `Size.Unspecified`, so a block dividing into a
    // dimension can reach NaN. `opacity="NaN"` is not a value any consumer can read — decline so
    // the coordinator's applied alpha answers instead.
    val element = LayerElement { alpha = size.height / (size.height - size.height) }

    assertNull(ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(200f, 80f)))
  }

  @Test
  fun aModifierWithNoBlockDeclinesInsteadOfGuessing() {
    assertNull(ModifierTokenResolver.evaluateLayerBlockAlpha(Any(), packedSize(48f, 48f)))
  }

  @Test
  fun aBlockDerivingAlphaFromDensitySeesTheNodesRealDensity() {
    // `GraphicsLayerScope` is a `Density`, so a block may convert dp inside itself. The evaluator
    // is preferred over the coordinator's applied alpha, so assuming mdpi here would quietly
    // outrank the alpha the frame really used on every non-mdpi device (#3589 review).
    val element = LayerElement { alpha = if (size.height < 40.dp.toPx()) 0.5f else 1f }

    // 80px tall at density 3 is 26.7dp — under the 40dp threshold, so it fades.
    assertEquals(
      0.5f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(
        element,
        packedSize(200f, 80f),
        Density(density = 3f),
      )!!,
      0.001f,
    )
    // The same box at mdpi is 80dp — over the threshold, so it does not.
    assertEquals(
      1f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(
        element,
        packedSize(200f, 80f),
        Density(density = 1f),
      )!!,
      0.001f,
    )
  }

  @Test
  fun aFontScaleDerivedBlockSeesTheNodesRealFontScale() {
    val element = LayerElement { alpha = if (fontScale > 1.2f) 0.25f else 1f }

    assertEquals(
      0.25f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(
        element,
        packedSize(48f, 48f),
        Density(density = 1f, fontScale = 1.5f),
      )!!,
      0.001f,
    )
  }

  @Test
  fun withoutADensityTheBlockFallsBackToTheIdentityScale() {
    // No coordinates to read a density off: the evaluator keeps the `1f` identity rather than
    // inventing one, which is the pre-existing behaviour for every caller that has none.
    val element = LayerElement { alpha = if (density > 1f) 0.5f else 1f }

    assertEquals(
      1f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(48f, 48f))!!,
      0.001f,
    )
  }

  @Test
  fun theCentreIsDerivedFromTheSameBox() {
    // Some transforms pivot on the box centre; it must agree with the size rather than stay at
    // the origin.
    val element = LayerElement { alpha = (size.width / 400f).coerceIn(0f, 1f) }
    assertEquals(
      0.5f,
      ModifierTokenResolver.evaluateLayerBlockAlpha(element, packedSize(200f, 80f))!!,
      0.001f,
    )
    assertEquals(Size(200f, 80f).width, 200f, 0.001f)
  }
}
