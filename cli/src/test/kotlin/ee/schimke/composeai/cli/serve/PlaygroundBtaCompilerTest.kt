package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure BTA-diagnostic → PlaygroundDiagnostic mapping (positions, basename, severity). */
class PlaygroundBtaCompilerTest {

  @Test
  fun `kotlinc 1-based positions map to codemirror 0-based, keyed by basename`() {
    val details =
      listOf(
        CompileErrorDetail(
          file = "/tmp/pg/abc/src/Snippet.kt",
          line = 3,
          column = 5,
          message = "unresolved reference: Bttn",
        ),
        CompileErrorDetail(
          file = "/tmp/pg/abc/src/Snippet.kt",
          line = 1,
          column = 1,
          message = "expecting a top level declaration",
        ),
      )

    val diags = PlaygroundBtaCompiler.mapDiagnostics(details)

    assertEquals(2, diags.size)
    assertEquals(PlaygroundSeverity.ERROR, diags[0].severity)
    assertEquals(
      "Snippet.kt",
      diags[0].file,
      "the editor sees the snippet basename, not the temp path",
    )
    assertEquals(2, diags[0].line, "line 3 (1-based) → 2 (0-based)")
    assertEquals(4, diags[0].ch, "column 5 (1-based) → 4 (0-based)")
    assertEquals("unresolved reference: Bttn", diags[0].message)
    assertEquals(0, diags[1].line)
    assertEquals(0, diags[1].ch)
  }

  @Test
  fun `positions never go negative`() {
    val diags =
      PlaygroundBtaCompiler.mapDiagnostics(
        listOf(CompileErrorDetail(file = "X.kt", line = 0, column = 0, message = "m"))
      )
    assertEquals(0, diags.single().line)
    assertEquals(0, diags.single().ch)
  }
}
