package ee.schimke.composeai.renderer

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.WearScrollSliceStitcher
import ee.schimke.composeai.scroll.ScrollAxis
import ee.schimke.composeai.scroll.ScrollDriveResult
import ee.schimke.composeai.scroll.driveScrollByViewport
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Drives a Wear scrolling preview one viewport-step at a time and assembles the captured slices into
 * one tall capsule via the production [WearScrollSliceStitcher] — the render-side orchestration that
 * turns a live composition into the stitched layout + semantics trees (plus the composited EdgeButton
 * crescent) the `compose/figma-svg-long` export then bakes to SVG.
 *
 * It's deliberately backend-thin: the caller owns the `setContent` (with `LocalReduceMotion` on so
 * items are unscaled) and supplies two capabilities —
 * - [captureFrame]: force a draw and write the current frame PNG (so the layout inspector sees
 *   z-sorted children), and
 * - [captureTree]: walk the drawn composition into a ([LayoutInspectorNode], [ComposeSemanticsNode])
 *   pair.
 *
 * so both the daemon's `RenderEngine` and the `:renderer-android` end-to-end test exercise the same
 * capture → chain → place → raster path. This object never touches SVG serialisation; it returns the
 * assembled trees + the composited crescent frame for the caller to hand to `ComposeFigmaSvgProduct`.
 */
object WearScrollSvgAssembler {
  /**
   * The assembled capsule: combined layout + semantics trees and the settled EdgeButton crescent
   * composited onto a transparent tall canvas ([framePng], null when the screen has no bottom
   * control). [width]/[height] are the capsule's px extent; [itemCount] is the number of placed
   * scrollable items (0 signals nothing scrollable was captured).
   */
  data class Assembled(
    val layout: LayoutInspectorPayload,
    val semantics: ComposeSemanticsPayload,
    val width: Int,
    val height: Int,
    val framePng: File?,
    val itemCount: Int,
  )

  /** M3 `EdgeButton` container fill — the crescent's background token, used to locate its bounds. */
  const val DEFAULT_EDGE_BUTTON_BACKGROUND: String = "#FFE9DDFF"

  /** px above the located EdgeButton to start the crescent crop, so its upper curve is included. */
  private const val EDGE_CROP_HEADROOM = 40

  /** Fraction of a viewport to advance per slice — overlapping so shared items chain reliably. */
  private const val DEFAULT_STEP_FRACTION = 0.8f

  /** ms to settle the composition after the last scroll so the EdgeButton reveal animation lands. */
  private const val DEFAULT_SETTLE_MS = 2000L

  /**
   * Captures [rule]'s already-composed scrolling preview at viewport-steps down its scroll and
   * assembles the slices into a capsule [Assembled] of [deviceDp] px width. Scratch PNGs (per-slice
   * draws, the settled frame, the composited crescent) are written under [workDir].
   *
   * Returns `null` when the preview has no vertical scrollable (the caller should fall back to its
   * plain viewport export) — otherwise the stitched trees, sized to hold every row, its clock arc,
   * and (when an EdgeButton is present) the composited crescent frame.
   *
   * @param edgeButtonBackground the EdgeButton container fill to locate for the crescent raster, or
   *   null to skip the bottom control entirely.
   * @param defaultEdgeCropTop crescent crop-top to use when [edgeButtonBackground] is set but no
   *   matching node is found; null means "no EdgeButton found ⇒ no crescent".
   */
  @Suppress("LongParameterList")
  fun assemble(
    rule: AndroidComposeTestRule<*, *>,
    deviceDp: Int,
    workDir: File,
    captureFrame: (File) -> Unit,
    captureTree: () -> Pair<LayoutInspectorNode, ComposeSemanticsNode>,
    rootId: String = "wear-slice",
    edgeButtonBackground: String? = DEFAULT_EDGE_BUTTON_BACKGROUND,
    defaultEdgeCropTop: Int? = null,
    stepFraction: Float = DEFAULT_STEP_FRACTION,
    settleMs: Long = DEFAULT_SETTLE_MS,
  ): Assembled? {
    workDir.mkdirs()
    val slices = mutableListOf<WearScrollSliceStitcher.Slice>()
    val driveResult =
      driveScrollByViewport(
        rule = rule,
        axis = ScrollAxis.VERTICAL,
        stepPx = deviceDp * stepFraction,
        maxScrollPx = 0,
      ) { _ ->
        captureFrame(File(workDir, "slice-${slices.size}.png"))
        val (layout, semantics) = captureTree()
        slices.add(WearScrollSliceStitcher.Slice(layout, semantics))
      }
    if (driveResult is ScrollDriveResult.NoScrollable) return null

    // The EdgeButton reveals with an animation that lands after the scroll settles (the last scroll
    // slice would catch a grey nub), so settle the clock, then capture the crescent's "final frame"
    // and its bounds — mirroring the raster LONG path's settled capture.
    rule.mainClock.advanceTimeBy(settleMs)
    rule.waitForIdle()
    val settledFrame = File(workDir, "settled.png")
    captureFrame(settledFrame)
    val (settledLayout, _) = captureTree()

    val edgeBounds = edgeButtonBackground?.let { findByBackground(settledLayout, it) }
    val edgeCropTop =
      when {
        edgeBounds != null -> (edgeBounds.top - EDGE_CROP_HEADROOM).coerceIn(0, deviceDp - 1)
        else -> defaultEdgeCropTop?.coerceIn(0, deviceDp - 1)
      }

    val stitched =
      WearScrollSliceStitcher.stitch(
        rootId = rootId,
        width = deviceDp,
        slices = slices,
        edgeCropTop = edgeCropTop,
      )

    // Composite the settled crescent onto a transparent tall canvas at the stitcher's dest bounds;
    // the source band is black-backed, so it drops cleanly onto the black capsule face.
    val framePng =
      stitched.edge?.let { edge ->
        val settled = ImageIO.read(settledFrame)
        val crop = settled.getSubimage(0, edge.sourceTop, deviceDp, deviceDp - edge.sourceTop)
        val composited = BufferedImage(deviceDp, stitched.height, BufferedImage.TYPE_INT_ARGB)
        composited.createGraphics().apply {
          drawImage(crop, edge.dest.left, edge.dest.top, null)
          dispose()
        }
        File(workDir, "frame.png").also { ImageIO.write(composited, "png", it) }
      }

    val itemCount = stitched.layout.root.children.count { it.nodeId != WearScrollSliceStitcher.EDGE_NODE_ID }
    return Assembled(
      layout = stitched.layout,
      semantics = stitched.semantics,
      width = stitched.width,
      height = stitched.height,
      framePng = framePng,
      itemCount = itemCount,
    )
  }

  /** First node (depth-first) whose resolved container background token equals [background]. */
  private fun findByBackground(node: LayoutInspectorNode, background: String): LayoutInspectorBounds? {
    if (node.tokens?.backgroundColor == background && node.bounds.bottom > node.bounds.top) {
      return node.bounds
    }
    node.children.forEach { child -> findByBackground(child, background)?.let { return it } }
    return null
  }
}
