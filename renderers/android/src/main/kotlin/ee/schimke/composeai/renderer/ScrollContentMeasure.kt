package ee.schimke.composeai.renderer

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Measures how far a vertically-scrollable screen's content actually reaches, from a captured
 * semantics tree — the geometry the `figma-svg-long` growth loop grows the frame to fit. Shared so
 * the daemon's [`RenderEngine`] growth loop and the `:renderer-android` Wear coverage measure content
 * identically instead of each carrying a copy.
 *
 * Why measured geometry rather than the `VerticalScrollAxisRange` estimate: a `LazyColumn` /
 * `TransformingLazyColumn` only reports an *estimated* max-scroll (it hasn't composed the off-screen
 * rows), so that number is unreliable for sizing. The deepest composed descendant's bottom, read
 * after each grown render, is exact for what's on screen and converges as the loop composes more
 * rows.
 */
object ScrollContentMeasure {

  /** Geometry of the main vertical scroll container in a rendered scene (root-pixel space). */
  data class Measure(
    /** The scroll container's own bottom edge — where any pinned bottom chrome begins. */
    val scrollNodeBottom: Int,
    /** The deepest composed descendant's bottom — how far the list content actually reaches. */
    val contentBottom: Int,
  )

  /**
   * The tallest vertically-scrollable node under [root] and how far its composed content reaches
   * (root-pixel space), or null when nothing is vertically scrollable. Picks the scroll node by
   * largest height (a nested inner scroller is shorter), then takes the deepest composed descendant's
   * bottom as the content extent.
   */
  fun measureVerticalScroll(root: SemanticsNode): Measure? {
    // Pick the tallest node carrying a VerticalScrollAxisRange — the screen's main scroll container.
    var scroll: SemanticsNode? = null
    var tallest = -1f
    fun findScroll(node: SemanticsNode) {
      if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) {
        val h = node.boundsInRoot.height
        if (h > tallest) {
          tallest = h
          scroll = node
        }
      }
      node.children.forEach(::findScroll)
    }
    findScroll(root)
    val scrollNode = scroll ?: return null
    // Deepest composed *descendant* bottom = the real content extent (grows as more items compose).
    // Seed with the scroll node's TOP, not its bottom: the scroll container itself fills its viewport
    // (a `fillMaxSize` list), so seeding with its bottom would track the viewport and the growth loop
    // would never converge.
    var maxBottom = scrollNode.boundsInRoot.top
    fun deepest(node: SemanticsNode) {
      val b = node.boundsInRoot.bottom
      if (b.isFinite() && b > maxBottom) maxBottom = b
      node.children.forEach(::deepest)
    }
    scrollNode.children.forEach(::deepest)
    return Measure(
      scrollNodeBottom = scrollNode.boundsInRoot.bottom.toInt(),
      contentBottom = maxBottom.toInt(),
    )
  }
}
