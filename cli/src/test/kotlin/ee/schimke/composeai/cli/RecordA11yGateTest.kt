package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure `record --fail-on a11y` gate logic (issue #1966). */
class RecordA11yGateTest {

  private fun finding(level: String) =
    AccessibilityFinding(level = level, type = "t", message = "m")

  @Test
  fun `threshold parsing accepts singular and plural, rejects junk`() {
    assertEquals(A11yThreshold.ERRORS, A11yThreshold.parse("errors"))
    assertEquals(A11yThreshold.ERRORS, A11yThreshold.parse("ERROR"))
    assertEquals(A11yThreshold.WARNINGS, A11yThreshold.parse("warnings"))
    assertEquals(A11yThreshold.WARNINGS, A11yThreshold.parse("Warning"))
    assertNull(A11yThreshold.parse("nope"))
  }

  @Test
  fun `errors threshold trips only on errors`() {
    val onlyWarnings =
      evaluateA11yGate(listOf(finding("WARNING"), finding("WARNING")), A11yThreshold.ERRORS)
    assertEquals(0, onlyWarnings.errorCount)
    assertEquals(2, onlyWarnings.warningCount)
    assertFalse("errors threshold ignores warnings", onlyWarnings.tripped)

    val withError =
      evaluateA11yGate(listOf(finding("ERROR"), finding("WARNING")), A11yThreshold.ERRORS)
    assertTrue("an error trips the errors threshold", withError.tripped)
  }

  @Test
  fun `warnings threshold trips on warnings or errors`() {
    val warn = evaluateA11yGate(listOf(finding("WARNING")), A11yThreshold.WARNINGS)
    assertTrue("a warning trips the warnings threshold", warn.tripped)

    val err = evaluateA11yGate(listOf(finding("ERROR")), A11yThreshold.WARNINGS)
    assertTrue("an error also trips the warnings threshold", err.tripped)
  }

  @Test
  fun `empty findings never trip - desktop no-ATF case`() {
    assertFalse(evaluateA11yGate(emptyList(), A11yThreshold.ERRORS).tripped)
    assertFalse(evaluateA11yGate(emptyList(), A11yThreshold.WARNINGS).tripped)
  }

  @Test
  fun `level matching is case-insensitive`() {
    val gate = evaluateA11yGate(listOf(finding("error"), finding("warning")), A11yThreshold.ERRORS)
    assertEquals(1, gate.errorCount)
    assertEquals(1, gate.warningCount)
    assertTrue(gate.tripped)
  }

  @Test
  fun `summary reports counts and threshold`() {
    val s = evaluateA11yGate(listOf(finding("ERROR")), A11yThreshold.WARNINGS).summary()
    assertTrue(s.contains("1 error"))
    assertTrue(s.contains("warnings"))
  }
}
