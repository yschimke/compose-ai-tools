package ee.schimke.composeai.daemon.harness

import java.io.File

/**
 * Minimal scenario abstraction — see
 * [TEST-HARNESS § 3](../../../docs/daemon/TEST-HARNESS.md#3-scenarios-catalogue).
 *
 * v0 shipped this with just `setUp` / `run`. v1 keeps the abstraction lightweight (the v1 task
 * brief explicitly says "don't over-engineer") but adds two scenario primitives that the new tests
 * call into:
 *
 * * [editSource] — temporarily mutates a fixture file with auto-revert in `finally`. Used by S3
 *   (render-after-edit). For fake-mode v1 the "edit" maps to swapping which `<previewId>.png`
 *   variant `FakeHost` serves; v1.5 will repoint this at a real source file. The "bytecode-visible"
 *   check from TEST-HARNESS § 5 doesn't apply in fake mode (there is no compile step), so we skip
 *   it and rely on the test's pixel-diff to fail loudly if the swap was a no-op.
 * * [LatencyRecorder] — collects per-preview wall-clock from `renderNow` to `renderFinished` and
 *   appends rows to the harness's CSV at `build/reports/daemon-harness/latency.csv`. Record-only
 *   per TEST-HARNESS § 11; no test fails on perf.
 *
 * Tests in `src/test` typically don't subclass [Scenario] directly any more — they use
 * [HarnessTestSupport] (a small helper bundled below) to wire fixture-dir / classpath / latency
 * recorder boilerplate per JUnit method, mirroring how v0's `S1LifecycleTest` was shaped.
 */
abstract class Scenario(val name: String) {

  /** Materialises the fixture directory and returns the manifest the daemon will serve. */
  abstract fun setUp(fixtureDir: File): Map<String, FakePreviewSpec>

  /** Drives the wire-level scenario and asserts. Throws on failure (JUnit's contract). */
  abstract fun run(context: ScenarioContext)

  /**
   * Top-level execute helper — most callers use this rather than wiring [setUp] / [run] manually.
   *
   * 1. Creates a clean [fixtureDir] / [reportsDir] (or reuses + clears them).
   * 2. Calls [setUp].
   * 3. Spawns a [HarnessClient] subprocess via [classpath].
   * 4. Calls [run] inside a try/finally that always closes the client and writes diff artefacts on
   *    failure.
   */
  fun execute(fixtureDir: File, reportsDir: File, classpath: List<File>) {
    fixtureDir.deleteRecursively()
    fixtureDir.mkdirs()
    reportsDir.deleteRecursively()
    reportsDir.mkdirs()
    val manifest = setUp(fixtureDir)
    val client = HarnessClient.start(fixtureDir = fixtureDir, classpath = classpath)
    try {
      val context =
        ScenarioContext(
          client = client,
          fixtureDir = fixtureDir,
          reportsDir = reportsDir,
          manifest = manifest,
        )
      run(context)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {
        // Best-effort cleanup.
      }
    }
  }
}

/** Per-execution context handed to [Scenario.run]. */
data class ScenarioContext(
  val client: HarnessClient,
  val fixtureDir: File,
  val reportsDir: File,
  val manifest: Map<String, FakePreviewSpec>,
)

/**
 * Replaces [target]'s contents with [newBytes] for the duration of [block], reverting in `finally`
 * even on exception. Used by S3 (render-after-edit) — for fake mode v1 the "source edit" maps to
 * swapping which `<previewId>.png` variant `FakeHost` serves. v1.5 (real-mode) will repurpose the
 * same primitive against a real `.kt` source file.
 *
 * **No bytecode-visibility check in fake mode** (TEST-HARNESS § 5). Real-mode v1.5 will SHA the
 * compiled `classes/` dir before/after and fail fast if the edit was a comment-only no-op; here in
 * fake mode the test's pixel-diff between the v1 and v2 fixtures is the equivalent loud check.
 */
fun editSource(target: File, newBytes: ByteArray, block: () -> Unit) {
  require(target.exists()) { "editSource: target ${target.absolutePath} does not exist" }
  val original = target.readBytes()
  target.writeBytes(newBytes)
  try {
    block()
  } finally {
    try {
      target.writeBytes(original)
    } catch (e: Throwable) {
      System.err.println(
        "editSource: failed to revert ${target.absolutePath}: ${e.message} " +
          "(test cleanup will not block on this; investigate manually)"
      )
    }
  }
}

/**
 * Convenience overload accepting the new file content as a string. Same revert-in-finally
 * semantics. Useful for textual fixture overrides (`previews.json`, `<previewId>.metrics.json`,
 * etc.); for binary swaps (`<previewId>.png`) prefer the [ByteArray] overload above.
 */
fun editSource(target: File, newText: String, block: () -> Unit) {
  editSource(target, newText.toByteArray(Charsets.UTF_8), block)
}

/**
 * Per-scenario latency capture. Each [record] call appends one row to the CSV file at
 * `build/reports/daemon-harness/latency.csv`, with per-preview wall-clock measured from the harness
 * sending `renderNow` to receiving `renderFinished` for that preview.
 *
 * **Record-only** per [TEST-HARNESS § 11](../../../docs/daemon/TEST-HARNESS.md#11-decisions-made):
 * the harness writes the CSV; humans (and a v3 weekly drift workflow) read it. No test fails on a
 * latency miss — CI machine noise across hosts and cold-vs-warm states would flap perf gates
 * without telling us anything actionable.
 *
 * **Baseline source.** The desktop median per-preview render baseline (~1100ms on the bench
 * machine, derived from the per-process javaexec rows in [`baseline-latency.csv`]). For fake mode
 * the actual will be ~50ms; the delta column documents the cost the daemon is amortising into.
 *
 * **CSV is shared across all scenarios** in a single test run. The harness writes the header once,
 * appends rows per scenario; the file is cleared at the start of each Gradle build by
 * `:daemon:harness:test` rerunning the suite.
 */
class LatencyRecorder(
  private val csvFile: File,
  // D-harness.v2 — default to whatever -Ptarget=… set in the Gradle build, so Android rows are
  // tagged correctly without each test having to pass it explicitly. Fake-mode tests run under
  // -Ptarget=desktop (the default) so their rows stay tagged "desktop" — accurate, since fake
  // mode doesn't drive a real Android renderer.
  private val target: String = HarnessTestSupport.harnessTarget(),
  private val baselineMsPerPreview: Long = DEFAULT_DESKTOP_BASELINE_MS,
) {

  init {
    if (!csvFile.exists()) {
      csvFile.parentFile.mkdirs()
      csvFile.writeText("target,scenario,preview,actualMs,baselineMs,deltaPct,notes\n")
    }
  }

  /**
   * Records one `(scenario, preview)` data point. [actualMs] is the wall-clock the harness
   * measured; [notes] is a free-form column captured for human readers (e.g. "fake-mode wire-only;
   * baseline is desktop real-render median").
   */
  fun record(scenario: String, preview: String, actualMs: Long, notes: String = FAKE_MODE_NOTE) {
    val deltaPct =
      if (baselineMsPerPreview <= 0L) 0.0
      else ((actualMs - baselineMsPerPreview).toDouble() / baselineMsPerPreview) * 100.0
    val row =
      "$target,$scenario,$preview,$actualMs,$baselineMsPerPreview,${"%.2f".format(deltaPct)},\"$notes\"\n"
    synchronized(csvFile) { csvFile.appendText(row) }
  }

  companion object {
    /**
     * Median desktop per-preview render baseline from
     * [`docs/daemon/baseline-latency.csv`](../../../docs/daemon/baseline-latency.csv): the `desktop
     * / render / cold` rows divided by the 5-preview suite — ~1880ms each. The full warm row is
     * dominated by Gradle config (no per-render work). 1100ms is the conservative working estimate
     * per the v1 task brief.
     */
    const val DEFAULT_DESKTOP_BASELINE_MS: Long = 1100L

    const val FAKE_MODE_NOTE: String =
      "fake-mode wire-only; baseline is desktop real-render median (P0.6)"
  }
}
