package ee.schimke.composeai.renderer

import ee.schimke.composeai.scroll.ScrollSeam
import ee.schimke.composeai.scroll.ScrollStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The scroll half of the warnings sidecar — what a LONG drive records and how it is encoded. */
class ScrollDriveDiagnosticsTest {

  @Before
  fun reset() {
    ScrollDriveDiagnostics.beginPreview()
  }

  private fun step(landed: Boolean, measured: Float? = 297f) =
    ScrollStep(
      index = 3,
      requestedPx = 307.2f,
      measuredPx = measured,
      reportedPx = 500f,
      corrections = 2,
      correctedPx = 20.4f,
      settleFrames = 4,
      settled = true,
      landed = landed,
      anchors = 9,
    )

  private fun seam(signal: Double, residual: Double, measuredHint: Boolean = false) =
    ScrollSeam(
      index = 1,
      hintPx = 307,
      shiftPx = 372,
      overlapRows = 12,
      informativeRows = 0,
      signal = signal,
      weightedSadPerPixel = residual,
      plainSadPerPixel = residual,
      measuredHint = measuredHint,
    )

  @Test
  fun `a landed stride and a verified seam record nothing`() {
    ScrollDriveDiagnostics.record("scroll LONG", step(landed = true))
    ScrollDriveDiagnostics.recordSeam("scroll LONG", seam(signal = 2000.0, residual = 5.0))
    assertTrue(ScrollDriveDiagnostics.drainPreview().isEmpty())
    assertTrue(ScrollDriveDiagnostics.drainSeams().isEmpty())
  }

  @Test
  fun `an unlanded stride and an unverified seam are recorded and drained once`() {
    ScrollDriveDiagnostics.record("scroll LONG", step(landed = false))
    ScrollDriveDiagnostics.recordSeam("scroll LONG", seam(signal = 0.0, residual = 0.3))
    val steps = ScrollDriveDiagnostics.drainPreview()
    val seams = ScrollDriveDiagnostics.drainSeams()
    assertEquals(1, steps.size)
    assertEquals(1, seams.size)
    assertEquals(ScrollSeam.Verdict.LOW_SIGNAL, seams.single().seam.verdict)
    assertTrue(ScrollDriveDiagnostics.drainPreview().isEmpty())
    assertTrue(ScrollDriveDiagnostics.drainSeams().isEmpty())
  }

  @Test
  fun `a low-signal overlap on a measured stride is trusted`() {
    val measured = seam(signal = 0.0, residual = 0.3, measuredHint = true)
    assertEquals(ScrollSeam.Verdict.MEASURED, measured.verdict)
    assertTrue(measured.verified)
    val mismatch = seam(signal = 2000.0, residual = 60.0, measuredHint = true)
    assertEquals(ScrollSeam.Verdict.MISMATCH, mismatch.verdict)
    assertFalse(mismatch.verified)
  }

  @Test
  fun `the sidecar carries both scroll arrays with their verdicts`() {
    val json =
      RenderWarningsSidecar.encode(
        fallbacks = emptyList(),
        unlandedScrollSteps =
          listOf(ScrollDriveDiagnostics.UnlandedStep("scroll LONG", step(landed = false))),
        unverifiedScrollSeams =
          listOf(
            ScrollDriveDiagnostics.UnverifiedSeam("scroll LONG", seam(signal = 0.0, residual = 0.3))
          ),
      )
    assertTrue(
      json,
      json.contains("\"unlandedScrollSteps\":[{\"role\":\"scroll LONG\",\"step\":3,"),
    )
    assertTrue(json, json.contains("\"requestedPx\":307.2,\"measuredPx\":297.0,\"corrections\":2,"))
    assertTrue(json, json.contains("DID NOT LAND"))
    assertTrue(
      json,
      json.contains("\"unverifiedScrollSeams\":[{\"role\":\"scroll LONG\",\"seam\":1,"),
    )
    assertTrue(json, json.contains("\"verdict\":\"low_signal\",\"hintPx\":307,\"shiftPx\":372,"))
    assertTrue(json, json.contains("\"overlapRows\":12,\"informativeRows\":0,"))
  }

  @Test
  fun `a clean render writes empty scroll arrays rather than omitting them`() {
    val json = RenderWarningsSidecar.encode(fallbacks = emptyList())
    assertTrue(json, json.contains("\"unlandedScrollSteps\":[]"))
    assertTrue(json, json.endsWith("\"unverifiedScrollSeams\":[]}"))
  }
}
