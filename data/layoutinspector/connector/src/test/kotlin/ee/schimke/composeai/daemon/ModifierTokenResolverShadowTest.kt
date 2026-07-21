package ee.schimke.composeai.daemon

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [ModifierTokenResolver.shadowElevationDp]'s two shadow sources (issue #2357):
 * - a `graphicsLayer { shadowElevation = … }` (Surface/Card/FAB Material shadow) exposes a raw
 *   **pixel** `shadowElevation`, recovered to dp by dividing by density, and
 * - a bare `Modifier.shadow(elevation: Dp)` lowers to a `ShadowGraphicsLayerElement` that keeps a
 *   **`Dp` `elevation`** instead — already dp, so it must be taken verbatim (no `/ density`).
 *
 * Before the fix only the px `shadowElevation` was probed, so a `Modifier.shadow(...)` node
 * resolved null and the figma-svg export dropped its `feDropShadow`. Each shape is fed both as an
 * inspector element and as the reflected backing field, matching the resolver's two lookup paths.
 */
class ModifierTokenResolverShadowTest {

  /** A `graphicsLayer` node: a raw px `shadowElevation` float field. */
  private class FakeGraphicsLayer(@JvmField val shadowElevation: Float)

  /** A `Modifier.shadow` node: a `Dp` `elevation` (inlined to a float field), NOT px. */
  private class FakeShadowElement(@JvmField val elevation: Float)

  @Test
  fun `graphicsLayer shadowElevation element is px and divided by density`() {
    // 16px at density 2 → 8dp.
    assertEquals(
      8.0,
      ModifierTokenResolver.shadowElevationDp(Any(), mapOf("shadowElevation" to 16f), density = 2f),
    )
  }

  @Test
  fun `graphicsLayer shadowElevation reflected field is px and divided by density`() {
    assertEquals(
      8.0,
      ModifierTokenResolver.shadowElevationDp(FakeGraphicsLayer(16f), emptyMap(), density = 2f),
    )
  }

  @Test
  fun `Modifier shadow elevation element is dp and taken verbatim`() {
    // 6dp stays 6dp regardless of density — it is NOT a pixel value.
    assertEquals(
      6.0,
      ModifierTokenResolver.shadowElevationDp(Any(), mapOf("elevation" to 6.dp), density = 2f),
    )
  }

  @Test
  fun `Modifier shadow elevation reflected field is dp and taken verbatim`() {
    assertEquals(
      4.0,
      ModifierTokenResolver.shadowElevationDp(FakeShadowElement(4f), emptyMap(), density = 3f),
    )
  }

  @Test
  fun `a clip-only node with zero elevation casts no shadow`() {
    assertNull(ModifierTokenResolver.shadowElevationDp(FakeShadowElement(0f), emptyMap(), 2f))
    assertNull(ModifierTokenResolver.shadowElevationDp(FakeGraphicsLayer(0f), emptyMap(), 2f))
  }

  @Test
  fun `a node with no shadow field resolves null`() {
    assertNull(ModifierTokenResolver.shadowElevationDp(Any(), emptyMap(), density = 2f))
  }
}
