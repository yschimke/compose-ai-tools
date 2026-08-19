package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VisualSettleDiagnostics] and the `unsettledCaptures` half of
 * [RenderWarningsSidecar]'s payload — the channel that turns "the render printed a line nobody
 * reads" into something a consumer's build can fail on (issue #4239).
 */
class VisualSettleDiagnosticsTest {

  @Before
  fun reset() {
    VisualSettleDiagnostics.beginPreview()
  }

  @Test
  fun `a quiescent outcome records nothing`() {
    VisualSettleDiagnostics.record("preview still", VisualSettleOutcome.SETTLED)
    VisualSettleDiagnostics.record("preview still", VisualSettleOutcome.NEVER_CHANGED)
    assertTrue(VisualSettleDiagnostics.drainPreview().isEmpty())
  }

  @Test
  fun `an exhausted budget is recorded and drained once`() {
    VisualSettleDiagnostics.record("preview still", VisualSettleOutcome.STILL_CHANGING)
    val drained = VisualSettleDiagnostics.drainPreview()
    assertEquals(1, drained.size)
    assertEquals("preview still", drained.single().role)
    assertEquals(VisualSettleOutcome.STILL_CHANGING, drained.single().outcome)
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, drained.single().samples)
    // Drained, not merely read: the next preview must not inherit this one's warning.
    assertTrue(VisualSettleDiagnostics.drainPreview().isEmpty())
  }

  @Test
  fun `the sidecar carries the outcome and its message`() {
    val json =
      RenderWarningsSidecar.encode(
        fallbacks = emptyList(),
        imageLoads = emptyList(),
        unsettled =
          listOf(
            VisualSettleDiagnostics.UnsettledCapture(
              role = "preview still",
              outcome = VisualSettleOutcome.STILL_CHANGING,
            )
          ),
      )
    assertTrue(json.contains("\"schema\":\"compose-preview-warnings/v1\""))
    assertTrue(json.contains("\"outcome\":\"still_changing\""))
    assertTrue(json.contains("\"role\":\"preview still\""))
    assertTrue(json.contains("\"samples\":$VISUAL_SETTLE_MAX_SAMPLES"))
    assertTrue(json.contains("did not become visually quiescent"))
    // The pre-existing arrays stay present and empty, so a reader that predates this field is
    // unaffected.
    assertTrue(json.contains("\"fontFallbacks\":[]"))
    assertTrue(json.contains("\"unresolvedImages\":[]"))
  }

  @Test
  fun `a clean render writes an empty array rather than omitting it`() {
    val json = RenderWarningsSidecar.encode(fallbacks = emptyList(), imageLoads = emptyList())
    assertTrue(json.contains("\"unsettledCaptures\":[]"))
  }

  @Test
  fun `only the non-quiescent outcomes carry a diagnostic`() {
    assertEquals(null, VisualSettleOutcome.SETTLED.describe("still"))
    assertEquals(null, VisualSettleOutcome.NEVER_CHANGED.describe("still"))
    assertFalse(VisualSettleOutcome.STILL_CHANGING.describe("still").isNullOrBlank())
  }
}
