package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canary for the private Compose internals [ModifierTokenResolver] reads by name.
 *
 * The resolver recovers design tokens (background colour, corner radius, elevation, border width,
 * arrangement gap, …) that Compose's inspector data doesn't expose, by reflecting on the *private
 * backing fields* of modifier elements — `BackgroundElement.color`, `ShadowGraphicsLayerElement
 * .elevation`, `Arrangement.spacedBy(...).spacing`, and a dozen more — and by matching some types
 * on `javaClass.simpleName`. None of that is public API. androidx can rename any of it in a patch
 * release without breaking our compile.
 *
 * Every one of those reads is wrapped in `runCatching { … }.getOrNull()`, so a rename doesn't
 * throw: the token silently resolves to `null` and the figma-svg / semantics export quietly stops
 * emitting that property. The resolver's own unit tests can't catch it either — they feed
 * hand-written fakes (`FakeGraphicsLayer(@JvmField val shadowElevation: Float)`) that mirror the
 * *assumed* names, so they stay green precisely when the assumption has broken.
 *
 * This test closes that gap by asserting the names against **real** modifiers built from the pinned
 * Compose. It is deliberately about the field's *existence*, not the resolver's arithmetic — the
 * behavioural tests own that half.
 *
 * **When this fails after a Compose bump, that is the test working.** Find what the field or class
 * was renamed to, update both the resolver and the expectation here, and add a behavioural test if
 * the shape (not just the name) changed. Do not delete the case.
 */
class ComposeInternalFieldContractTest {

  /** Every modifier element in [modifier], innermost first. */
  private fun elementsOf(modifier: Modifier): List<Any> =
    modifier.foldIn(mutableListOf<Any>()) { acc, element -> acc.apply { add(element) } }

  /** Walks the class hierarchy the same way [ModifierTokenResolver] does. */
  private fun hasDeclaredField(target: Any, name: String): Boolean {
    var cls: Class<*>? = target.javaClass
    while (cls != null && cls != Any::class.java) {
      if (cls.declaredFields.any { it.name == name }) return true
      cls = cls.superclass
    }
    return false
  }

  private fun assertSomeElementHasField(modifier: Modifier, field: String, what: String) {
    val elements = elementsOf(modifier)
    assertTrue(
      "$what: no element in the chain declares a `$field` field. " +
        "ModifierTokenResolver reflects on that name and will now silently resolve null. " +
        "Elements present: ${elements.map { it.javaClass.name }}",
      elements.any { hasDeclaredField(it, field) },
    )
  }

  @Test
  fun `background colour is kept in a color field`() {
    // ModifierTokenResolver.backgroundColorHex reads the packed ULong out of `color`.
    assertSomeElementHasField(
      Modifier.background(Color.Red),
      field = "color",
      what = "Modifier.background(Color)",
    )
  }

  @Test
  fun `background brush is kept in a brush field`() {
    // ModifierTokenResolver's gradient path reads `brush` to recover linear-gradient stops.
    assertSomeElementHasField(
      Modifier.background(Brush.horizontalGradient(listOf(Color.Red, Color.Blue))),
      field = "brush",
      what = "Modifier.background(Brush)",
    )
  }

  @Test
  fun `background element is still named BackgroundElement`() {
    // Matched by simpleName in ComposeSemanticsDataProduct and ModifierTokenResolver.
    val names = elementsOf(Modifier.background(Color.Red)).map { it.javaClass.simpleName }
    assertTrue(
      "Modifier.background no longer produces a `BackgroundElement`; " +
        "simpleName matches in ModifierTokenResolver / ComposeSemanticsDataProduct are now " +
        "dead. Elements present: $names",
      names.contains("BackgroundElement"),
    )
  }

  @Test
  fun `Modifier shadow keeps a Dp elevation field`() {
    // The px `shadowElevation` and the dp `elevation` are different sources with different units
    // — see ModifierTokenResolverShadowTest. This pins the dp one's name.
    assertSomeElementHasField(
      Modifier.shadow(4.dp),
      field = "elevation",
      what = "Modifier.shadow(Dp)",
    )
  }

  @Test
  fun `graphicsLayer keeps px shadowElevation alpha and clip fields`() {
    val modifier =
      Modifier.graphicsLayer(shadowElevation = 8f, alpha = 0.5f, clip = true, scaleX = 1f)
    for (field in listOf("shadowElevation", "alpha", "clip")) {
      assertSomeElementHasField(modifier, field, what = "Modifier.graphicsLayer($field = …)")
    }
  }

  @Test
  fun `clip keeps a shape field`() {
    assertSomeElementHasField(
      Modifier.clip(RoundedCornerShape(8.dp)),
      field = "shape",
      what = "Modifier.clip(Shape)",
    )
  }

  @Test
  fun `border keeps a width field`() {
    assertSomeElementHasField(
      Modifier.border(2.dp, Color.Red),
      field = "width",
      what = "Modifier.border(Dp, Color)",
    )
  }

  @Test
  fun `arrangement spacedBy keeps a spacing field`() {
    // ModifierTokenResolver.arrangementGapWire scans a Row/Column measure policy for an
    // `Arrangement.*` value and reads `spacing` off it — the value-class getter is name-mangled,
    // so the field is the only reachable route.
    val arrangement: Any = Arrangement.spacedBy(8.dp)
    assertTrue(
      "Arrangement.spacedBy no longer keeps its gap in a `spacing` field " +
        "(${arrangement.javaClass.name}); ModifierTokenResolver.arrangementGapWire is now blind.",
      hasDeclaredField(arrangement, "spacing"),
    )
  }

  @Test
  fun `Dp value class still inlines to a value field`() {
    // ModifierTokenResolver.floatValue unwraps any value class by reading `value`.
    val boxed: Any = Dp(4f)
    assertTrue(
      "Dp no longer stores its float in a `value` field (${boxed.javaClass.name}); " +
        "ModifierTokenResolver.floatValue can no longer unwrap Dp/value-class tokens.",
      hasDeclaredField(boxed, "value"),
    )
  }

  @Test
  fun `ColorPainter is still named ColorPainter and keeps a color field`() {
    val painter: Any = ColorPainter(Color.Red)
    assertTrue(
      "ColorPainter renamed to ${painter.javaClass.simpleName}; the simpleName match in " +
        "ModifierTokenResolver.unwrapPainter no longer fires.",
      painter.javaClass.simpleName == "ColorPainter",
    )
    assertTrue("ColorPainter no longer keeps a `color` field.", hasDeclaredField(painter, "color"))
  }

  @Test
  fun `CutCornerShape is still named CutCornerShape`() {
    // ModifierTokenResolver maps corner *style* purely off this simpleName.
    val shape: Any = CutCornerShape(4.dp)
    assertTrue(
      "CutCornerShape renamed to ${shape.javaClass.simpleName}; ModifierTokenResolver would " +
        "now report a cut-corner shape as rounded.",
      shape.javaClass.simpleName == "CutCornerShape",
    )
  }

  @Test
  fun `corner sizes are still reachable by a size field`() {
    // ModifierTokenResolver reads `size` off each CornerSize to recover the radius.
    val corner: Any = RoundedCornerShape(12.dp).topStart
    assertTrue(
      "CornerSize no longer keeps its value in a `size` field (${corner.javaClass.name}); " +
        "corner-radius tokens will resolve null.",
      hasDeclaredField(corner, "size"),
    )
  }
}
