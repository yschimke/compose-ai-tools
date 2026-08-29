package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Wear curved run has to be placed at the node's **drawn** geometry, not its measured one.
 *
 * `Modifier.scrollAway` hides `TimeText` by scaling it to half size and lifting it off the top of
 * the screen. The capture records that faithfully — `transform = 0.5`, a `bounds` box mostly above
 * `y = 0` — but states the run itself in pre-transform root pixels, so the export drew a full-size
 * clock centred on the frame that the render does not have.
 */
class FigmaSvgCurvedTextTransformTest {

  private val clock =
    LayoutInspectorCurvedText(
      text = "10:10",
      centerXPx = 192.0,
      centerYPx = 192.0,
      radiusPx = 159.75,
      startAngleRadians = 4.462,
      sweepRadians = 0.5,
      clockwise = true,
      fontSizePx = 30.0,
    )

  /** The `CurvedLayout` under `AppScaffold(timeText = …)`, as captured at [transform]. */
  private fun timeText(bounds: LayoutInspectorBounds, transform: LayoutInspectorTransform?) =
    LayoutInspectorNode(
      nodeId = "11",
      component = "CurvedLayoutKt",
      bounds = bounds,
      size = LayoutInspectorSize(384, 384),
      transform = transform,
      curvedTexts = listOf(clock),
    )

  private fun screen(node: LayoutInspectorNode) =
    LayoutInspectorNode(
      nodeId = "1",
      component = "BoxMeasurePolicy",
      bounds = LayoutInspectorBounds(0, 0, 384, 384),
      size = LayoutInspectorSize(384, 384),
      children = listOf(node),
    )

  private fun curvedRun(node: LayoutInspectorNode): LayoutInspectorCurvedText =
    FigmaSvgModel.from(layout = LayoutInspectorPayload(screen(node)))
      .root
      .children
      .single()
      .curvedTexts
      .single()

  /**
   * Scrolled away: half scale, box lifted so its centre sits at y=48. The arc has to follow — at
   * radius 79.9 around (192,48) its top edge lands above y=0, where the device clip drops it,
   * exactly as the render shows.
   */
  @Test
  fun `a scrolled-away clock follows its node`() {
    val run =
      curvedRun(
        timeText(
          bounds = LayoutInspectorBounds(96, -48, 288, 144),
          transform = LayoutInspectorTransform(scaleX = 0.5f, scaleY = 0.5f),
        )
      )
    assertEquals(192.0, run.centerXPx, 0.01)
    assertEquals(48.0, run.centerYPx, 0.01)
    assertEquals(159.75 / 2, run.radiusPx, 0.01)
    assertEquals(15.0, run.fontSizePx, 0.01)
    // Above the top edge — the clock the PNG doesn't draw either.
    org.junit.Assert.assertTrue(
      "the arc must clear the top of the frame (top was ${run.centerYPx - run.radiusPx})",
      run.centerYPx - run.radiusPx < 0,
    )
  }

  /** At rest the capture is already right, and must come through byte-for-byte. */
  @Test
  fun `an untransformed clock is left exactly as captured`() {
    val run = curvedRun(timeText(bounds = LayoutInspectorBounds(0, 0, 384, 384), transform = null))
    assertEquals(clock, run)
  }

  /** An identity `transform` block is the same no-op as none at all. */
  @Test
  fun `an identity transform is a no-op`() {
    val run =
      curvedRun(
        timeText(
          bounds = LayoutInspectorBounds(0, 0, 384, 384),
          transform = LayoutInspectorTransform(scaleX = 1f, scaleY = 1f),
        )
      )
    assertEquals(clock, run)
  }
}
