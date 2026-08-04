package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the two ways a shape whose corners aren't directly readable still reaches the export
 * (issue #3254) — the fix for a Horologist `VolumeScreen` whose volume buttons exported as sharp
 * blue squares painted over their correctly-rounded raster.
 *
 * Wear M3 routes every `RoundButton`-family container through `RoundButtonKt.animateButtonShape`,
 * which wraps the resting/pressed pair in an `AnimatedMorphShape` whenever both are
 * `CornerBasedShape`. `Stepper` — the volume buttons — always passes both, so it is always wrapped.
 * The wrapper is a [Shape] but not a `CornerBasedShape`, so every corner getter misses it, and once
 * the node has been measured its `createOutline` returns an `Outline.Generic` morph path, which the
 * rounded-outline fallback also refuses. All three paths missed and the layer fell through to a
 * plain `<rect>`.
 *
 * Everything here is a hand-rolled stand-in rather than the real Compose types:
 * [ModifierTokenResolver] deliberately carries no `compose.foundation` dependency and reads corners
 * purely reflectively, so the fakes below reproduce the *reflective surface* the resolver actually
 * probes — a shape with `getTopStart()`-style getters returning
 * `DpCornerSize`/`PercentCornerSize`-named corner objects.
 */
class ModifierTokenResolverAnimatedShapeTest {

  /** Stands in for `androidx.compose.foundation.shape.DpCornerSize` (matched by simple name). */
  private class DpCornerSize(@JvmField val size: Float)

  /** Stands in for `PercentCornerSize` — what `CircleShape` / `CornerSize(50%)` use. */
  private class PercentCornerSize(@JvmField val percent: Float)

  /** Stands in for a `CornerBasedShape`: four corners behind no-arg getters. */
  private class FakeCornerShape(private val corner: Any) : Shape {
    @Suppress("unused") fun getTopStart(): Any = corner

    @Suppress("unused") fun getTopEnd(): Any = corner

    @Suppress("unused") fun getBottomEnd(): Any = corner

    @Suppress("unused") fun getBottomStart(): Any = corner

    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
  }

  /**
   * Field-for-field stand-in for `androidx.wear.compose.material3.AnimatedMorphShape`: a private
   * resting `shape` and `pressedShape` pair, plus a `morphState`. That last field matters — it ends
   * in `state`, so it matches the pre-existing `getMorphedShape()` filter and falls through it. The
   * resting-field unwrap has to run *after* that miss, which is the ordering this test protects.
   */
  @Suppress("unused")
  private class FakeAnimatedMorphShape(
    private val shape: Shape,
    private val pressedShape: Shape,
    private val progress: () -> Float = { 0f },
    private val morphState: MutableMap<Size, Any> = mutableMapOf(),
  ) : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline =
      // The real wrapper returns the morph as a *generic* path once measured — deliberately not an
      // `Outline.Rounded`, which is why the rounded-outline fallback can't rescue it either. Only
      // the shape of the result matters to the unwrap under test, and building a real `Path` here
      // would need a graphics backend this pure-JVM module has no skiko runtime for, so the
      // end-to-end coverage of the sampled-outline branch lives in `RenderEngineTest` instead.
      error("the corner unwrap must resolve without ever asking the wrapper for an outline")
  }

  /** The M3-expressive idiom the unwrap already handled — must keep working. */
  @Suppress("unused")
  private class FakeMorphedShapeState(private val morphedShape: Shape) {
    fun getMorphedShape(): Shape = morphedShape
  }

  @Suppress("unused")
  private class FakeAnimatedShape(private val state: FakeMorphedShapeState) : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
  }

  private val circle = FakeCornerShape(PercentCornerSize(50f))
  private val small = FakeCornerShape(DpCornerSize(8f))

  @Test
  fun `wear animated morph wrapper resolves to its resting corner shape`() {
    val wrapper = FakeAnimatedMorphShape(shape = circle, pressedShape = small)
    with(ModifierTokenResolver) {
      assertSame(circle, wrapper.effectiveCornerShape())
      // 50% of the 96px shorter side at density 2 → the 24dp corner a 60×48dp pill button wants.
      assertEquals("24.0dp", wrapper.effectiveCornerShape().cornerRadiusWire(96, density = 2f))
    }
  }

  @Test
  fun `the pressed shape is never taken as the resting corner`() {
    // A still export shows the resting state; taking `pressedShape` would round a full pill down
    // to the pressed `Shapes.small` corner.
    val wrapper = FakeAnimatedMorphShape(shape = circle, pressedShape = small)
    with(ModifierTokenResolver) { assertSame(circle, wrapper.restingCornerShape()) }
  }

  @Test
  fun `the m3 expressive getMorphedShape wrapper still resolves`() {
    val wrapper = FakeAnimatedShape(FakeMorphedShapeState(small))
    with(ModifierTokenResolver) {
      assertEquals("8.0dp", wrapper.effectiveCornerShape().cornerRadiusWire(96, density = 2f))
    }
  }

  @Test
  fun `an ordinary corner shape is returned untouched`() {
    with(ModifierTokenResolver) {
      assertSame(small, small.effectiveCornerShape())
      assertNull(small.restingCornerShape())
    }
  }

  @Test
  fun `a rounded outline keeps its corner radii instead of degrading to a polyline`() {
    // Ordering guard: the sampled path is a last resort, so a shape that can still report real
    // corners must never reach it — an editable `<rect rx>` beats a polyline.
    val rounded =
      object : Shape {
        override fun createOutline(
          size: Size,
          layoutDirection: LayoutDirection,
          density: Density,
        ): Outline =
          Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
              Rect(0f, 0f, size.width, size.height),
              androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            )
          )
      }
    with(ModifierTokenResolver) {
      assertEquals("12.0px", rounded.outlineCornerRadiusPxWire(120, 96, density = 2f))
      assertNull(rounded.outlineShapePathWire(widthPx = 120, heightPx = 96, density = 2f))
    }
  }
}
