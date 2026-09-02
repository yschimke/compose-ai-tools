package ee.schimke.composeai.daemon

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reflective read of Material's **keyboard focus indicator** — the ring a Tab draws around the
 * focused component, `RippleThemeConfiguration.Focus.InsetRing`.
 *
 * Nothing here can be type-checked against a compiled API: this module compiles against neither
 * material line, and the node the ring lives on is internal to material3 in any case. What the
 * production code matches on is therefore a set of class and field *names*, and this test pins them
 * — including the three homes the ripple node ships under, which is the whole of issue #4980:
 * matching only `material.ripple`'s original left every interaction state out of the
 * `compose/figma-svg` export of any catalog on material3 1.5+ / CMP 1.12+, so a `focus-ring`
 * sticker exported byte-identical to its resting one.
 *
 * The doubles below mirror the real shapes exactly — same class simple names, same private field
 * names, same accessor spellings — because those names *are* the contract.
 */
class MaterialFocusRingTest {

  /** Material's own defaults: `InsetRing(0.dp, 2.dp, 1.dp, 3.dp)`, secondary over the container. */
  private fun insetRing(shape: Any? = RoundedCornerShape(8.dp)) =
    InsetRing(
      shape = shape,
      outerStrokeInset = 0f,
      outerStrokeWidth = 2f,
      outerStrokeColor = ColorProducerLike(Color(0xFF625B71).value.toLong()),
      innerStrokeInset = 1f,
      innerStrokeWidth = 3f,
      innerStrokeColor = ColorProducerLike(Color(0xFFFFFFFF).value.toLong()),
    )

  private fun ring(
    node: Any,
    config: Any?,
    density: Float = 2f,
  ): ComposeLayoutInspector.LayoutTreeAccess.FocusRing? =
    ComposeLayoutInspector.LayoutTreeAccess.focusRing(
      node = node,
      config = config,
      bounds = LayoutInspectorBounds(0, 0, 100, 50),
      localWidth = 100,
      localHeight = 50,
      transform = null,
      modifierIndex = 3,
      density = density,
    )

  @Test
  fun `the inset ring reads as two bands, inner first`() {
    val ring = ring(RippleNodeLike(interpolation = 1f), ConfigLike(focus = insetRing()))

    requireNotNull(ring)
    assertEquals(3, ring.modifierIndex)
    assertEquals(2, ring.strokes.size)

    val inner = ring.strokes[0]
    val outer = ring.strokes[1]
    // 1.dp at density 2 = 2px in from every edge; the band itself stays authored in dp so the
    // emitter scales it with the render like any other border.
    assertEquals(LayoutInspectorBounds(2, 2, 98, 48), inner.bounds)
    assertEquals(96, inner.localWidthPx)
    assertEquals(46, inner.localHeightPx)
    assertEquals(3f, inner.widthDp, 0.0001f)
    assertEquals(Color(0xFFFFFFFF).toArgb(), inner.argb)
    // The outer band sits on the node's own edge — inset 0 — and paints over the inner one, which
    // is what leaves a gap between the ring and the component.
    assertEquals(LayoutInspectorBounds(0, 0, 100, 50), outer.bounds)
    assertEquals(2f, outer.widthDp, 0.0001f)
    assertEquals(Color(0xFF625B71).toArgb(), outer.argb)
  }

  @Test
  fun `each band carries the ring's own shape, resolved at its own box`() {
    val ring = ring(RippleNodeLike(interpolation = 1f), ConfigLike(focus = insetRing()))

    requireNotNull(ring)
    // 8.dp at density 2 = 16px, and a rounded-rect ring keeps editable corners rather than
    // degrading to a sampled outline.
    ring.strokes.forEach { stroke ->
      assertEquals("16.0px", stroke.cornerRadiusPx)
      assertNull(stroke.shapePath)
    }
  }

  @Test
  fun `a mid-animation ring is exported at the width it was drawn at`() {
    val ring = ring(RippleNodeLike(interpolation = 0.5f), ConfigLike(focus = insetRing()))

    requireNotNull(ring)
    assertEquals(1.5f, ring.strokes[0].widthDp, 0.0001f)
    assertEquals(1f, ring.strokes[1].widthDp, 0.0001f)
    // Half of 1.dp at density 2 rounds to 1px.
    assertEquals(LayoutInspectorBounds(1, 1, 99, 49), ring.strokes[0].bounds)
  }

  @Test
  fun `the AndroidX spelling of the focus accessor reads the same ring`() {
    val ring =
      ring(
        RippleNodeLike(interpolation = 1f),
        ConfigurationLike(focusConfiguration = insetRing()),
      )

    requireNotNull(ring)
    assertEquals(2, ring.strokes.size)
  }

  @Test
  fun `an unfocused node, the opacity style and the pre-fork node all export no ring`() {
    assertNull(
      "a settled-at-zero focus animation is an unfocused component",
      ring(RippleNodeLike(interpolation = 0f), ConfigLike(focus = insetRing())),
    )
    assertNull(
      "the opacity focus style is the state layer, which is captured separately",
      ring(RippleNodeLike(interpolation = 1f), ConfigLike(focus = Opacity(0.1f))),
    )
    assertNull(
      "`material.ripple`'s original node resolves no configuration at all",
      ring(RippleNodeLike(interpolation = 1f), config = null),
    )
  }

  @Test
  fun `the configuration is read from a cached instance or from either producing lambda`() {
    val config = ConfigLike(focus = insetRing())
    assertEquals(
      config,
      ComposeLayoutInspector.LayoutTreeAccess.rippleNodeConfiguration(
        CachedConfigNode(_rippleNodeConfiguration = config)
      ),
    )
    assertEquals(
      config,
      ComposeLayoutInspector.LayoutTreeAccess.rippleNodeConfiguration(
        AndroidXConfigNode(rippleNodeConfiguration = { config })
      ),
    )
    assertEquals(
      config,
      ComposeLayoutInspector.LayoutTreeAccess.rippleNodeConfiguration(
        MultiplatformConfigNode(rippleNodeConfig = { config })
      ),
    )
    assertNull(ComposeLayoutInspector.LayoutTreeAccess.rippleNodeConfiguration(Any()))
  }

  @Test
  fun `every home Material's ripple node ships under is matched`() {
    assertEquals(
      setOf(
        // material-ripple, the original.
        "androidx.compose.material.ripple.RippleNode",
        // The copy material3 1.5.0-alpha took into its own package.
        "androidx.compose.material3.ripple.RippleNode",
        // The copy Compose Multiplatform's material3 1.12 took into `material3.internal`.
        "androidx.compose.material3.internal.ripple.RippleNode",
      ),
      ComposeLayoutInspector.LayoutTreeAccess.RIPPLE_NODE_CLASSES,
    )
    assertTrue(
      "the walk matches on the node's own class name, so every fork has to be named",
      ComposeLayoutInspector.LayoutTreeAccess.RIPPLE_NODE_CLASSES.all {
        it.endsWith(".RippleNode")
      },
    )
  }

  // --- doubles, named exactly as the real types are ------------------------------------------

  @Suppress("unused")
  private class ColorProducerLike(private val packed: Long) {
    fun `invoke-0d7_KjU`(): Long = packed
  }

  @Suppress("unused")
  private class AnimatableLike(private val value: Float) {
    fun getValue(): Float = value
  }

  @Suppress("unused")
  private class InsetRing(
    private val shape: Any?,
    private val outerStrokeInset: Float,
    private val outerStrokeWidth: Float,
    private val outerStrokeColor: Any,
    private val innerStrokeInset: Float,
    private val innerStrokeWidth: Float,
    private val innerStrokeColor: Any,
  )

  @Suppress("unused") private class Opacity(private val alpha: Float)

  /** `RippleNodeConfig` as Compose Multiplatform's material3 spells it. */
  @Suppress("unused")
  private class ConfigLike(private val focus: Any) {
    fun getFocus(): Any = focus
  }

  /** `RippleNodeConfiguration` as AndroidX material3 spells it. */
  @Suppress("unused")
  private class ConfigurationLike(private val focusConfiguration: Any) {
    fun getFocusConfiguration(): Any = focusConfiguration
  }

  @Suppress("unused")
  private class RippleNodeLike(interpolation: Float) {
    private val animatedFocusRingInterpolation = AnimatableLike(interpolation)
  }

  @Suppress("unused", "PropertyName")
  private class CachedConfigNode(private val _rippleNodeConfiguration: Any)

  @Suppress("unused")
  private class AndroidXConfigNode(private val rippleNodeConfiguration: () -> Any)

  @Suppress("unused") private class MultiplatformConfigNode(private val rippleNodeConfig: () -> Any)
}
