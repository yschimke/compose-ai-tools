package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The projection of our flat diagnostics into the stock kotlin-compiler-server `errors` map. */
class PlaygroundErrorsWireTest {

  @Test
  fun `diagnostics are grouped by file into the stock map shape`() {
    val errors =
      PlaygroundErrorsWire.project(
        listOf(
          PlaygroundDiagnostic(
            PlaygroundSeverity.ERROR,
            "unresolved",
            file = "A.kt",
            line = 3,
            ch = 5,
          ),
          PlaygroundDiagnostic(
            PlaygroundSeverity.WARNING,
            "unused",
            file = "A.kt",
            line = 7,
            ch = 0,
          ),
          PlaygroundDiagnostic(
            PlaygroundSeverity.ERROR,
            "type mismatch",
            file = "B.kt",
            line = 1,
            ch = 2,
          ),
        )
      )

    assertEquals(setOf("A.kt", "B.kt"), errors.keys)
    assertEquals(2, errors.getValue("A.kt").size)
    assertEquals(1, errors.getValue("B.kt").size)
  }

  @Test
  fun `position nests under interval and severity uses the upstream spelling`() {
    val error =
      PlaygroundErrorsWire.project(
          listOf(
            PlaygroundDiagnostic(
              PlaygroundSeverity.ERROR,
              "unresolved reference",
              file = "A.kt",
              line = 3,
              ch = 5,
              endLine = 3,
              endCh = 12,
            )
          )
        )
        .getValue("A.kt")
        .single()

    assertEquals(PlaygroundPosition(3, 5), error.interval.start)
    assertEquals(PlaygroundPosition(3, 12), error.interval.end)
    assertEquals("ERROR", error.severity)
    assertEquals("red_wavy_line", error.className)
    assertEquals("unresolved reference", error.message)
  }

  @Test
  fun `a diagnostic with no end collapses to a zero-width caret at the start`() {
    val error =
      PlaygroundErrorsWire.project(
          listOf(
            PlaygroundDiagnostic(PlaygroundSeverity.WARNING, "w", file = "A.kt", line = 4, ch = 2)
          )
        )
        .getValue("A.kt")
        .single()

    assertEquals(PlaygroundPosition(4, 2), error.interval.start)
    assertEquals(PlaygroundPosition(4, 2), error.interval.end, "end defaults to the start")
    assertEquals("yellow_wavy_line", error.className)
  }

  @Test
  fun `a file-less diagnostic lands under the default file key at the origin`() {
    val errors =
      PlaygroundErrorsWire.project(
        listOf(PlaygroundDiagnostic(PlaygroundSeverity.ERROR, "module-level failure"))
      )

    val error = errors.getValue(PlaygroundErrorsWire.DEFAULT_FILE).single()
    assertEquals(PlaygroundPosition(0, 0), error.interval.start)
    assertEquals(PlaygroundPosition(0, 0), error.interval.end)
  }

  @Test
  fun `no diagnostics projects to an empty map`() {
    assertTrue(PlaygroundErrorsWire.project(emptyList()).isEmpty())
  }
}
