package ee.schimke.composeai.daemon

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.PlaceholderDrawLambdaDouble
import androidx.wear.compose.material3.PlaceholderStateDouble
import ee.schimke.composeai.data.layoutinspector.PlaceholderModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ModifierTokenResolver.resolvePlaceholderElements] — the capture half of the state-aware
 * placeholder model (issue #2646).
 *
 * The exporter can't tell the ideal state from the loading one by looking at the modifier chain:
 * both carry the same `drawWithContent` and the same 50%-pill `shape`. Two things have to be
 * recovered by reflection:
 * - **which** modifier is the placeholder. `Modifier.placeholderShimmer` has its own element;
 *   `Modifier.placeholder` lowers to a bare `drawWithContent { … }.graphicsLayer { … }`, so it is
 *   recognised only by the origin of the lambda that draw holds
 *   (`androidx.wear.compose.material3.Placeholder…`).
 * - **what state** it is in. `PlaceholderState`'s API has moved across Wear releases (`isVisible`
 *   today, the inverted `isShowContent` earlier) and may be backed by a Compose `State`, so all
 *   those shapes are probed; an unreadable state degrades to `visible = null` (treated downstream
 *   as "not visible": never blank real content on a guess).
 */
class ModifierTokenResolverPlaceholderStateTest {

  /** The older spelling: `isShowContent` is the *inverse* of visible. */
  private class PlaceholderStateShowContent(@JvmField val isShowContent: Boolean)

  /** A Compose-`State`-backed flag, as a snapshot-observable placeholder state would hold it. */
  private class PlaceholderStateSnapshot(visible: Boolean) {
    @JvmField val isVisible = mutableStateOf(visible)
  }

  /** A state object exposing nothing this resolver knows how to read. */
  private class PlaceholderStateOpaque(@JvmField val phase: String = "wipe-off")

  /** `Modifier.placeholderShimmer`'s own element (`PlaceholderShimmerElement` in Wear). */
  private class PlaceholderShimmerElement(@JvmField val placeholderState: Any)

  /**
   * `Modifier.placeholder`'s captured shape: an anonymous `drawWithContent` element holding the
   * Wear-compiled draw lambda.
   */
  private class DrawWithContentElement(@JvmField val onDraw: Any)

  private fun block(state: Any, color: Color? = null) =
    DrawWithContentElement(PlaceholderDrawLambdaDouble(state, color?.value?.toLong() ?: 0L))

  private fun resolve(vararg elements: Any) =
    ModifierTokenResolver.resolvePlaceholderElements(
      elements.toList(),
      sizeWidthPx = 200,
      sizeHeightPx = 50,
      density = 2f,
    )

  @Test
  fun `a node with no placeholder modifier resolves null`() {
    assertNull(resolve(Any(), "BackgroundElement"))
  }

  @Test
  fun `a plain drawWithContent leaf is not a placeholder`() {
    // The progress track / slider groove case: an imperative draw with no placeholder origin must
    // stay un-placeholdered, so the export keeps rasterising it.
    class AppDrawLambda(@JvmField val unrelated: String = "")

    assertNull(resolve(DrawWithContentElement(AppDrawLambda())))
  }

  @Test
  fun `per-entry identity marks the placeholder chrome and nothing else`() {
    // What lets the export drop only the placeholder's own pass-through draw: an app's
    // `Modifier.drawBehind` on the same chain must not be marked, or its art vanishes from the SVG.
    class AppDrawLambda(@JvmField val unrelated: String = "")

    assertTrue(ModifierTokenResolver.isPlaceholderElement(block(PlaceholderStateDouble(false))))
    assertTrue(
      ModifierTokenResolver.isPlaceholderElement(
        PlaceholderShimmerElement(PlaceholderStateDouble(false))
      )
    )
    assertFalse(ModifierTokenResolver.isPlaceholderElement(DrawWithContentElement(AppDrawLambda())))
    assertFalse(ModifierTokenResolver.isPlaceholderElement(Any()))
  }

  @Test
  fun `an active placeholder block reports visible with its own colour`() {
    val ph = resolve(block(PlaceholderStateDouble(true), Color.Red))!!
    assertEquals(PlaceholderModifiers.KIND_PLACEHOLDER, ph.kind)
    assertEquals(true, ph.visible)
    assertEquals("#FFFF0000", ph.colorArgb)
  }

  @Test
  fun `an inactive placeholder reports not visible`() {
    // The ideal / content-loaded state — the `__ideal__` render variants. The export must keep the
    // node's real content here, so this must never come back `true`.
    assertEquals(false, resolve(block(PlaceholderStateDouble(false)))!!.visible)
  }

  @Test
  fun `the older isShowContent spelling is read inverted`() {
    assertEquals(false, resolve(block(PlaceholderStateShowContent(true)))!!.visible)
    assertEquals(true, resolve(block(PlaceholderStateShowContent(false)))!!.visible)
  }

  @Test
  fun `a snapshot-State-backed flag is unwrapped`() {
    assertEquals(true, resolve(block(PlaceholderStateSnapshot(true)))!!.visible)
  }

  @Test
  fun `an unreadable state still reports the placeholder, with an unknown visibility`() {
    // Reporting the placeholder (rather than dropping it) is what keeps the ideal-state guards
    // working — the export suppresses the `drawWithContent` raster on any placeholdered node.
    val ph = resolve(block(PlaceholderStateOpaque()))!!
    assertEquals(PlaceholderModifiers.KIND_PLACEHOLDER, ph.kind)
    assertNull(ph.visible)
  }

  @Test
  fun `a shimmer-only chain reports the shimmer`() {
    val ph = resolve(PlaceholderShimmerElement(PlaceholderStateDouble(true)))!!
    assertEquals(PlaceholderModifiers.KIND_SHIMMER, ph.kind)
    assertEquals(true, ph.visible)
  }

  @Test
  fun `the block wins over a shimmer on the same chain`() {
    // A placeholdered card carries both (`Modifier.placeholderShimmer(...).placeholder(...)`); the
    // block is what actually paints over the content, so its identity and colour must win.
    val ph =
      resolve(
        PlaceholderShimmerElement(PlaceholderStateDouble(true)),
        block(PlaceholderStateDouble(false), Color.Blue),
      )!!
    assertEquals(PlaceholderModifiers.KIND_PLACEHOLDER, ph.kind)
    assertEquals(false, ph.visible)
    assertEquals("#FF0000FF", ph.colorArgb)
  }

  @Test
  fun `a shimmer fills in a state the block could not report`() {
    val ph =
      resolve(
        PlaceholderShimmerElement(PlaceholderStateDouble(true)),
        block(PlaceholderStateOpaque()),
      )!!
    assertEquals(PlaceholderModifiers.KIND_PLACEHOLDER, ph.kind)
    assertTrue(ph.visible == true)
  }
}
