package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

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
 * The counterweight is staleness: on a warm checkout the previous run's PNGs are still on disk, so
 * "results is non-empty" proves nothing. If gradle dies during configuration or compilation, those
 * leftovers must NOT be reported as this run's output — they would carry the last run's images and
 * hashes with every `changed` reading `false`, i.e. "nothing changed" for a build that never ran.
 *
 * The exit code deliberately does **not** change: a failed build still exits 2.
 */
class ShowPartialResultsTest {

  private val fs = FakeFileSystem()

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

  /** Writes a PNG into the fake filesystem and returns its actual recorded mtime. */
  private fun writePng(path: String): Long {
    val p = path.toPath()
    p.parent?.let { fs.createDirectories(it) }
    fs.write(p) { writeUtf8("png") }
    return fs.metadata(p).lastModifiedAtMillis!!
  }

  @Test
  fun `a render written by this invocation is reported despite the failed build`() {
    // The shape this actually fixes: one preview blew up, the other rendered fine, and the task
    // failure applies to both.
    val mtime = writePng("/r/ok.png")
    val results = listOf(result("p.Ok", "/r/ok.png"), result("p.Broken", null))
    assertTrue(
      canReportAfterBuildFailure(results, renderStartedAtMillis = mtime, fileSystem = fs),
      "one broken preview must not suppress the ones that rendered",
    )
  }

  @Test
  fun `leftover renders from a previous run are not reported as this run's output`() {
    val mtime = writePng("/r/stale.png")
    val results = listOf(result("p.Stale", "/r/stale.png"))
    assertFalse(
      canReportAfterBuildFailure(results, renderStartedAtMillis = mtime + 1, fileSystem = fs),
      "a build that died before the renderer ran must not pass off the previous run's PNGs",
    )
  }

  @Test
  fun `results whose PNGs are gone entirely are not reportable`() {
    val results = listOf(result("p.Missing", "/r/never-written.png"))
    assertFalse(canReportAfterBuildFailure(results, renderStartedAtMillis = 0L, fileSystem = fs))
  }

  @Test
  fun `a build that produced no results has nothing to report`() {
    assertFalse(
      canReportAfterBuildFailure(emptyList(), renderStartedAtMillis = 0L, fileSystem = fs),
      "gradle died before any manifest was written — there is genuinely nothing to show",
    )
  }

  @Test
  fun `previews that never emit a PNG cannot vouch for freshness on their own`() {
    val results = listOf(result("p.Broken", null))
    assertFalse(
      canReportAfterBuildFailure(results, renderStartedAtMillis = 0L, fileSystem = fs),
      "a null pngPath carries no mtime, so it is no evidence the render ran",
    )
  }

  @Test
  fun `floorToSecond absorbs second-granular filesystem mtimes`() {
    // A PNG written 700ms after an unfloored start would otherwise look stale on a filesystem that
    // truncates mtimes to whole seconds.
    assertEquals(1_000L, floorToSecond(1_999L))
    assertEquals(0L, floorToSecond(999L))
    assertEquals(2_000L, floorToSecond(2_000L))
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
