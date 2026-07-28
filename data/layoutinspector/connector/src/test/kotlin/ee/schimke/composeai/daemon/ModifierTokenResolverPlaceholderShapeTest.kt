package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.PlaceholderModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the placeholder identity [ModifierTokenResolver] resolves shapes through — the guard that
 * stops a Wear M3 `Modifier.placeholder`/`placeholderShimmer` from dictating the exported container
 * corner (issue #2645), now expressed once in [PlaceholderModifiers] and shared with the export
 * side (issue #2646).
 *
 * Those modifiers expose `PlaceholderDefaults.shape` (= `ShapeTokens.CornerFull`, a full 50% pill)
 * as an inspectable `shape` and sit on the caller's chain ahead of the component's own Surface
 * shape, so without the guard they win as the first shape-bearing modifier and a placeholdered
 * `TitleCard` (modest corner) exports as a full pill (`rx = height/2`). The container shapes
 * (`background`/`clip`/`border`) must stay eligible so the real corner is still captured.
 */
class ModifierTokenResolverPlaceholderShapeTest {

  @Test
  fun `placeholder and placeholderShimmer by inspector name are skipped`() {
    assertTrue(PlaceholderModifiers.isPlaceholderModifier("placeholder", "PlaceholderElement"))
    assertTrue(
      PlaceholderModifiers.isPlaceholderModifier("placeholderShimmer", "PlaceholderShimmerElement")
    )
  }

  @Test
  fun `placeholder elements by class name are skipped when inspector name is absent`() {
    assertTrue(PlaceholderModifiers.isPlaceholderModifier(null, "PlaceholderElement"))
    assertTrue(
      PlaceholderModifiers.isPlaceholderModifier(null, "PlaceholderShimmerModifierNodeElement")
    )
  }

  @Test
  fun `real container-shape modifiers stay eligible`() {
    assertFalse(PlaceholderModifiers.isPlaceholderModifier("background", "BackgroundElement"))
    assertFalse(PlaceholderModifiers.isPlaceholderModifier("clip", "GraphicsLayerElement"))
    assertFalse(PlaceholderModifiers.isPlaceholderModifier("border", "BorderModifierElement"))
    assertFalse(PlaceholderModifiers.isPlaceholderModifier("paint", "PainterElement"))
  }

  @Test
  fun `the shimmer sweep and the placeholder block are distinguished`() {
    // Both are placeholder-family, but they are not the same thing: the block is what an active
    // placeholder paints (and what the export emits as its own layer), the shimmer only sweeps
    // over it. `PlaceholderShimmerElement` also starts with `Placeholder`, so order matters.
    assertEquals(
      PlaceholderModifiers.KIND_SHIMMER,
      PlaceholderModifiers.kindOf("placeholderShimmer", "PlaceholderShimmerElement"),
    )
    assertEquals(
      PlaceholderModifiers.KIND_SHIMMER,
      PlaceholderModifiers.kindOf(null, "PlaceholderShimmerElement"),
    )
    assertEquals(
      PlaceholderModifiers.KIND_PLACEHOLDER,
      PlaceholderModifiers.kindOf("placeholder", "PlaceholderElement"),
    )
    assertNull(PlaceholderModifiers.kindOf("background", "BackgroundElement"))
  }
}
