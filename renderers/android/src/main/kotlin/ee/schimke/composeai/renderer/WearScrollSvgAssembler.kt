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
   * list items (always ≥ 1 — [assemble] returns `null` rather than an empty capsule).
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
   * assembles the slices into a capsule [Assembled] of [deviceWidthPx] wide. **All coordinates are
   * captured-frame pixels** — the layout/semantics bounds, the stitcher width, and the crescent PNG
   * crop share one px space, so [deviceWidthPx] must be the frame's *pixel* width (`spec.widthPx`),
   * not its dp width. Scratch PNGs (per-slice draws, the settled frame, the composited crescent) are
   * written under [workDir].
   *
   * Returns `null` when the preview has no vertical scrollable **or** when the stitcher classified no
   * list items (e.g. a `Modifier.verticalScroll` column, or rows without a background token) — in
   * both cases the caller should fall back to its plain viewport / grow-tall export rather than emit a
   * near-empty capsule. Otherwise the stitched trees, sized to hold every row, its clock arc, and
   * (when an EdgeButton is present) the composited crescent frame.
   *
   * @param edgeButtonBackground the EdgeButton container fill to locate for the crescent raster, or
   *   null to skip the bottom control entirely.
   * @param defaultEdgeCropTop crescent crop-top (px) to use when [edgeButtonBackground] is set but no
   *   matching node is found; null means "no EdgeButton found ⇒ no crescent".
   */
  @Suppress("LongParameterList")
  fun assemble(
    rule: AndroidComposeTestRule<*, *>,
    deviceWidthPx: Int,
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
        stepPx = deviceWidthPx * stepFraction,
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
        edgeBounds != null -> (edgeBounds.top - EDGE_CROP_HEADROOM).coerceIn(0, deviceWidthPx - 1)
        else -> defaultEdgeCropTop?.coerceIn(0, deviceWidthPx - 1)
      }

    val stitched =
      WearScrollSliceStitcher.stitch(
        rootId = rootId,
        width = deviceWidthPx,
        slices = slices,
        edgeCropTop = edgeCropTop,
      )

    // A scrollable was present but no list items were classified (no item container found): there's
    // no capsule to build, so signal a fallback rather than writing a clock-and-face-only frame. The
    // clock arc rides a `curvedTexts` node and the crescent is the edge node; a real list row is
    // neither, so they don't count toward "items placed".
    val itemCount =
      stitched.layout.root.children.count {
        it.nodeId != WearScrollSliceStitcher.EDGE_NODE_ID && it.curvedTexts.isEmpty()
      }
    if (itemCount == 0) return null

    // Composite the settled crescent onto a transparent tall canvas at the stitcher's dest bounds;
    // the source band is black-backed, so it drops cleanly onto the black capsule face.
    val framePng =
      stitched.edge?.let { edge ->
        val settled = ImageIO.read(settledFrame)
        val crop =
          settled.getSubimage(0, edge.sourceTop, deviceWidthPx, deviceWidthPx - edge.sourceTop)
        val composited = BufferedImage(deviceWidthPx, stitched.height, BufferedImage.TYPE_INT_ARGB)
        composited.createGraphics().apply {
          drawImage(crop, edge.dest.left, edge.dest.top, null)
          dispose()
        }
        File(workDir, "frame.png").also { ImageIO.write(composited, "png", it) }
      }

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
