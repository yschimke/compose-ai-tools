package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Covers `show`'s behaviour when gradle reports failure but render output is on disk.
 *
 * `composePreviewRenderAll` fails the whole task if any single preview fails to render, so a repo
 * with one persistently broken preview used to get nothing at all out of `compose-preview show
 * --json` — the command printed "Render failed" and exited before emitting the envelope, discarding
 * every preview that rendered perfectly well.
 *
 * The counterweight is staleness. On a warm checkout `build/compose-previews/` still holds the
 * previous run's PNGs, so "a file exists" proves nothing, and neither does "its mtime is recent" —
 * `buildResults` can itself rewrite PNGs via the image-size override, and a previous run finishing
 * inside the new invocation's start second would clear any wall-clock threshold. Freshness is
 * therefore decided by diffing a snapshot taken before the gradle run, per result.
 */
class ShowPartialResultsTest {

  private val fs = FakeFileSystem()

  private fun result(id: String, vararg pngPaths: String?): PreviewResult =
    PreviewResult(
      id = id,
      module = "samples:demo",
      functionName = id.substringAfterLast('.'),
      className = id.substringBeforeLast('.'),
      params = PreviewParams(kind = "COMPOSE"),
      captures = pngPaths.map { CaptureResult(pngPath = it) },
      pngPath = pngPaths.firstOrNull(),
    )

  private fun stamp(mtime: Long, size: Long) = RenderStamp(mtime, size)

  // --- freshRenderPaths -------------------------------------------------------

  @Test
  fun `a rewritten file is fresh`() {
    val before = mapOf("/r/a.png" to stamp(1000, 10))
    val after = mapOf("/r/a.png" to stamp(2000, 10))
    assertEquals(setOf("/r/a.png"), freshRenderPaths(before, after))
  }

  @Test
  fun `a file rewritten within the timestamp granularity is still fresh via size`() {
    // The case a pure-mtime check misses: same second, different content.
    val before = mapOf("/r/a.png" to stamp(1000, 10))
    val after = mapOf("/r/a.png" to stamp(1000, 4096))
    assertEquals(setOf("/r/a.png"), freshRenderPaths(before, after))
  }

  @Test
  fun `a newly created file is fresh`() {
    assertEquals(
      setOf("/r/new.png"),
      freshRenderPaths(emptyMap(), mapOf("/r/new.png" to stamp(1000, 10))),
    )
  }

  @Test
  fun `an untouched leftover is not fresh`() {
    val same = mapOf("/r/old.png" to stamp(1000, 10))
    assertTrue(freshRenderPaths(same, same).isEmpty())
  }

  @Test
  fun `a previous run finishing in the invocation's start second is not fresh`() {
    // This is what sank the wall-clock threshold: the file predates the run but shares its second.
    val leftover = mapOf("/r/old.png" to stamp(1_700_000_000_500, 10))
    assertTrue(
      freshRenderPaths(leftover, leftover).isEmpty(),
      "identity is compared against concrete prior state, so there is no start-second window",
    )
  }

  // --- reportableAfterBuildFailure --------------------------------------------

  @Test
  fun `a stale row is dropped and a freshly rendered one kept`() {
    val results = listOf(result("p.Fresh", "/r/fresh.png"), result("p.Stale", "/r/stale.png"))
    assertEquals(
      listOf("p.Fresh"),
      reportableAfterBuildFailure(results, setOf("/r/fresh.png")).map { it.id },
    )
  }

  @Test
  fun `one module's fresh render does not vouch for another module's stale rows`() {
    // Module A rendered; gradle then failed compiling module B, leaving B's previous PNGs in place.
    val results =
      listOf(
        result("a.Rendered", "/a/renders/x.png"),
        result("b.Leftover", "/b/renders/y.png"),
        result("b.AlsoLeftover", "/b/renders/z.png"),
      )
    assertEquals(
      listOf("a.Rendered"),
      reportableAfterBuildFailure(results, setOf("/a/renders/x.png")).map { it.id },
      "freshness must be per result, not admitted for the whole batch by one fresh file",
    )
  }

  @Test
  fun `a multi-capture result survives when any one capture is fresh`() {
    val results = listOf(result("p.Multi", "/r/frame0.png", "/r/frame1.png"))
    assertEquals(
      listOf("p.Multi"),
      reportableAfterBuildFailure(results, setOf("/r/frame1.png")).map { it.id },
    )
  }

  @Test
  fun `a preview that threw is kept so the failure stays visible`() {
    // This is the signal partial reporting exists to deliver: which preview is broken. The row has
    // no image, sha or changed flag, so there is nothing stale to mislead with.
    val results = listOf(result("p.Rendered", "/r/ok.png"), result("p.Broken", null))
    assertEquals(
      listOf("p.Rendered", "p.Broken"),
      reportableAfterBuildFailure(results, setOf("/r/ok.png")).map { it.id },
    )
  }

  @Test
  fun `a row keeping a stale PNG is dropped even alongside a null capture`() {
    val results = listOf(result("p.Mixed", null, "/r/stale.png"))
    assertTrue(
      reportableAfterBuildFailure(results, setOf("/r/other.png")).isEmpty(),
      "a stale PNG anywhere on the row still carries the previous run's sha into the output",
    )
  }

  // --- anyFreshRender ---------------------------------------------------------

  @Test
  fun `a run that wrote nothing reports nothing at all`() {
    val results = listOf(result("p.Stale", "/r/stale.png"), result("p.NoPng", null))
    assertFalse(
      anyFreshRender(results, emptySet()),
      "with no render written, even the no-PNG rows are a manifest nobody refreshed",
    )
  }

  @Test
  fun `one freshly written render opens the gate`() {
    val results = listOf(result("p.Fresh", "/r/fresh.png"))
    assertTrue(anyFreshRender(results, setOf("/r/fresh.png")))
    assertFalse(anyFreshRender(emptyList(), setOf("/r/fresh.png")))
  }

  // --- snapshotRenders --------------------------------------------------------

  @Test
  fun `snapshot walks each module's renders directory`() {
    val module = PreviewModule(gradlePath = "wear", projectDir = File("/proj/wear"))
    val dir = rendersDirOf(module).toPath()
    fs.createDirectories(dir)
    fs.write(dir / "a.png") { writeUtf8("aa") }
    fs.write(dir / "b.png") { writeUtf8("bbbb") }

    val snap = snapshotRenders(listOf(module), fs)

    assertEquals(setOf((dir / "a.png").toString(), (dir / "b.png").toString()), snap.keys)
    assertEquals(2L, snap.getValue((dir / "a.png").toString()).sizeBytes)
    assertEquals(4L, snap.getValue((dir / "b.png").toString()).sizeBytes)
  }

  @Test
  fun `a module with no renders directory yet snapshots empty and its first render is fresh`() {
    val module = PreviewModule(gradlePath = "app", projectDir = File("/proj/app"))
    val before = snapshotRenders(listOf(module), fs)
    assertTrue(before.isEmpty(), "a first-ever render must not require a pre-existing directory")

    val dir = rendersDirOf(module).toPath()
    fs.createDirectories(dir)
    fs.write(dir / "first.png") { writeUtf8("x") }

    assertEquals(
      setOf((dir / "first.png").toString()),
      freshRenderPaths(before, snapshotRenders(listOf(module), fs)),
    )
  }

  @Test
  fun `renders dir is canonicalised so its keys match PreviewResultBuilder's pngPath`() {
    // The snapshot keys are compared to `CaptureResult.pngPath` by string equality, and
    // PreviewResultBuilder builds that as `.canonicalFile.absolutePath`. If only one side resolved
    // symlinks every lookup would miss and `show` would report nothing on every failed build — a
    // silent, total regression. Exercised against the real filesystem because `canonicalFile` is a
    // java.io.File operation, not an okio one.
    val tmp = java.nio.file.Files.createTempDirectory("compose-preview-canon")
    val real = java.nio.file.Files.createDirectories(tmp.resolve("real"))
    val link = java.nio.file.Files.createSymbolicLink(tmp.resolve("link"), real)

    val module = PreviewModule(gradlePath = "m", projectDir = link.toFile())
    val pngPathAsBuilderWouldMakeIt =
      File(link.toFile(), "build/compose-previews/renders/x.png").canonicalFile.absolutePath

    assertEquals(
      File(pngPathAsBuilderWouldMakeIt).parent,
      rendersDirOf(module),
      "snapshot base must resolve symlinks the same way pngPath does",
    )
  }

  @Test
  fun `snapshot records files rather than directories`() {
    val module = PreviewModule(gradlePath = "wear", projectDir = File("/proj/wear"))
    val dir = rendersDirOf(module).toPath()
    fs.createDirectories(dir / "nested")
    fs.write(dir / "nested" / "deep.png") { writeUtf8("d") }

    val snap = snapshotRenders(listOf(module), fs)

    assertEquals(setOf((dir / "nested" / "deep.png").toString()), snap.keys)
    assertFalse(snap.containsKey((dir / "nested").toString()))
  }

  // --- exit codes -------------------------------------------------------------

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
