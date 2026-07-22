package ee.schimke.composeai.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ModifierTokenResolver.isPlaceholderShapeModifier], the guard that stops a Wear M3
 * `Modifier.placeholder`/`placeholderShimmer` from dictating the exported container corner.
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
    assertTrue(
      ModifierTokenResolver.isPlaceholderShapeModifier("placeholder", "PlaceholderElement")
    )
    assertTrue(
      ModifierTokenResolver.isPlaceholderShapeModifier(
        "placeholderShimmer",
        "PlaceholderShimmerElement",
      )
    )
  }

  @Test
  fun `placeholder elements by class name are skipped when inspector name is absent`() {
    assertTrue(ModifierTokenResolver.isPlaceholderShapeModifier(null, "PlaceholderElement"))
    assertTrue(
      ModifierTokenResolver.isPlaceholderShapeModifier(
        null,
        "PlaceholderShimmerModifierNodeElement",
      )
    )
  }

  @Test
  fun `real container-shape modifiers stay eligible`() {
    assertFalse(ModifierTokenResolver.isPlaceholderShapeModifier("background", "BackgroundElement"))
    assertFalse(ModifierTokenResolver.isPlaceholderShapeModifier("clip", "GraphicsLayerElement"))
    assertFalse(ModifierTokenResolver.isPlaceholderShapeModifier("border", "BorderModifierElement"))
    assertFalse(ModifierTokenResolver.isPlaceholderShapeModifier("paint", "PainterElement"))
  }
}
