package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `show`'s behaviour when gradle reports failure but results were still produced.
 *
 * `composePreviewRenderAll` fails the whole task if any single preview fails to render, so a repo
 * with one persistently broken preview (a `@Preview` on an Activity whose ViewModel can't be
 * constructed, say) used to get nothing at all out of `compose-preview show --json` — the command
 * printed "Render failed" and exited before emitting the envelope, discarding every preview that
 * rendered perfectly well. That breaks the documented iterate loop, which tells agents to re-render
 * and read the entries marked `changed`.
 *
 * The exit code deliberately does **not** change: a failed build still exits 2.
 */
class ShowPartialResultsTest {

  private fun result(id: String, pngPath: String?): PreviewResult =
    PreviewResult(
      id = id,
      module = "samples:demo",
      functionName = id.substringAfterLast('.'),
      className = id.substringBeforeLast('.'),
      params = PreviewParams(kind = "COMPOSE"),
      captures = listOf(CaptureResult(pngPath = pngPath)),
      pngPath = pngPath,
    )

  @Test
  fun `results present after a failed build are still worth reporting`() {
    // The shape this actually fixes: one preview blew up, the other rendered fine, and the task
    // failure applies to both.
    val results = listOf(result("p.Ok", "/r/ok.png"), result("p.Broken", null))
    assertTrue(
      canReportAfterBuildFailure(results),
      "one broken preview must not suppress the ones that rendered",
    )
  }

  @Test
  fun `a build that produced no results has nothing to report`() {
    assertFalse(
      canReportAfterBuildFailure(emptyList()),
      "gradle died before any manifest was written — there is genuinely nothing to show",
    )
  }

  @Test
  fun `a failed build exits 2 even though output was emitted`() {
    assertEquals(2, showExitCode(buildOk = false, naturalCode = 0))
  }

  @Test
  fun `a failed build outranks the no-previews-matched exit code`() {
    assertEquals(
      2,
      showExitCode(buildOk = false, naturalCode = 3),
      "exit 3 would advertise a healthy build that simply had no match",
    )
  }

  @Test
  fun `a healthy build keeps its natural exit code`() {
    assertEquals(0, showExitCode(buildOk = true, naturalCode = 0))
    assertEquals(3, showExitCode(buildOk = true, naturalCode = 3))
  }
}
