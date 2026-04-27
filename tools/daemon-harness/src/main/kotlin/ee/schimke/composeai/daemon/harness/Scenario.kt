package ee.schimke.composeai.daemon.harness

import java.io.File

/**
 * Minimal scenario abstraction — see
 * [TEST-HARNESS § 3](../../../docs/daemon/TEST-HARNESS.md#3-scenarios-catalogue).
 *
 * v0 only ships [Scenario.S1Lifecycle]; the abstraction exists so v1's scenarios (S2 drain, S3
 * render-after-edit, S4 visibility, S5 renderFailed, S7 latency-record, S8 cost parity) can drop in
 * without rewriting the runner. A `Scenario` carries:
 *
 * * [name] — used to namespace per-scenario fixtures (under `build/daemon-harness/fixtures/<name>`)
 *   and report directories (`build/reports/daemon-harness/<name>`).
 * * [setUp] — generates the fixture PNGs + `previews.json` in a fresh directory; returns the
 *   [FakePreviewSpec] manifest the harness expects to find served by `FakeHost`.
 * * [run] — drives a [HarnessClient] through the scenario's wire-level steps and asserts on the
 *   results. Receives a `ScenarioContext` carrying the fixture dir, expected manifest, and a place
 *   to stash artefacts on failure.
 *
 * Tests in `src/test` instantiate one `Scenario`, call [Scenario.execute] from a JUnit method, and
 * let the runner manage subprocess lifecycle + diff-artefact writing on failure.
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
