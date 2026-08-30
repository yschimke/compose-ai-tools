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
  fun `a phase pin is recorded on its own channel, and drained once`() {
    // #4829. An exact `@SettledPreview(afterMs = …)` skips the quiescence probe — the coordinate is
    // the answer — so nothing used to be recorded at all, and a spinner pinned to a deliberate
    // phase was indistinguishable from an ordinary static preview.
    VisualSettleDiagnostics.recordPinnedPhase("preview still", 600L)
    // Not a warning: it must not appear among the unsettled captures a consumer fails its build on.
    assertTrue(VisualSettleDiagnostics.drainPreview().isEmpty())
    val drained = VisualSettleDiagnostics.drainPinned()
    assertEquals(1, drained.size)
    assertEquals("preview still", drained.single().role)
    assertEquals(600L, drained.single().atMs)
    assertTrue(VisualSettleDiagnostics.drainPinned().isEmpty())
  }

  @Test
  fun `the sidecar distinguishes a chosen phase from a failed settle`() {
    // The whole point of the issue: `still_changing` used to mean both "your reveal is broken" and
    // "this is a spinner, working as designed", so a consumer could act on neither.
    val json =
      RenderWarningsSidecar.encode(
        fallbacks = emptyList(),
        imageLoads = emptyList(),
        unsettled = emptyList(),
        pinned = listOf(VisualSettleDiagnostics.PinnedCapture(role = "preview still", atMs = 600L)),
      )
    assertTrue(json, json.contains("\"phasePinnedCaptures\":["))
    assertTrue(json, json.contains("\"outcome\":\"phase_pinned\""))
    assertTrue(json, json.contains("\"atMs\":600"))
    assertTrue(json, json.contains("a chosen coordinate, not a failed settle"))
    // The warning channel stays empty — a pin is not a warning, and a catalog that fails its build
    // on `unsettledCaptures` must not go red for a spinner that said where its phase is.
    assertTrue(json, json.contains("\"unsettledCaptures\":[]"))
  }

  @Test
  fun `a clean render with a phase pin still writes the sidecar`() {
    // Withholding the pin whenever the render was otherwise clean would make it available only on
    // previews that also had something wrong with them — i.e. never on the spinners it is for.
    val json =
      RenderWarningsSidecar.encode(
        fallbacks = emptyList(),
        pinned = listOf(VisualSettleDiagnostics.PinnedCapture(role = "preview still", atMs = 250L)),
      )
    assertTrue(json, json.contains("\"atMs\":250"))
    assertTrue(json, json.contains("\"fontFallbacks\":[]"))
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
