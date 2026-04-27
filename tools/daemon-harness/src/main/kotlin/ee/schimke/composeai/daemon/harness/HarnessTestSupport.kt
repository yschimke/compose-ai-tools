package ee.schimke.composeai.daemon.harness

import java.io.File

/**
 * Tiny support layer the v1 scenario tests share. Keeps the seven test files (S1 from v0; S2-S5,
 * S7-S8 added in v1) readable by hoisting the fixture-dir / reports-dir / classpath / latency-CSV
 * boilerplate out of every JUnit method body. Not a DSL — just convenience.
 *
 * Each test method calls [scenario] with the scenario's name; that returns a [ScenarioPaths] which
 * carries the freshly-cleared `fixtureDir`, the per-scenario `reportsDir`, the (lazy) classpath the
 * subprocess inherits from this JVM's `java.class.path`, and the shared [LatencyRecorder] that
 * appends to `build/reports/daemon-harness/latency.csv`. The recorder is intentionally process-wide
 * — every scenario × preview pair writes one row to the same CSV (TEST-HARNESS § 11).
 */
object HarnessTestSupport {

  /** Always relative to the harness module's `build/`. */
  private val MODULE_BUILD = File("build")

  /** Shared by every scenario in a single test-suite run. */
  val LATENCY_CSV: File = File(MODULE_BUILD, "reports/daemon-harness/latency.csv")

  /**
   * Resets the latency CSV for this test run. Called once per JVM before any scenario runs;
   * idempotent if invoked multiple times within a single Gradle invocation via the per-PID marker
   * file.
   *
   * Without a reset, repeated `./gradlew test` calls would accumulate rows across runs and confuse
   * the v3 drift-report consumer. With a per-scenario reset, scenarios after the first would wipe
   * earlier rows. The marker file is the lock that gives us "wipe once per JVM, then append".
   *
   * The marker path is keyed by `pid` so re-runs in the same `build/` directory across separate
   * JVMs always reset (different pids → different marker files); the build step's standard
   * `./gradlew clean` also clears the marker when the whole `build/reports/` tree goes away.
   */
  fun resetLatencyCsvIfStale() {
    val marker =
      File(LATENCY_CSV.parentFile, ".harness-csv-marker-${ProcessHandle.current().pid()}")
    if (marker.exists()) return
    marker.parentFile.mkdirs()
    if (LATENCY_CSV.exists()) LATENCY_CSV.delete()
    // Always write the marker — even when the CSV didn't exist — so the subsequent scenarios in
    // this same JVM see "already reset" and append rather than re-wiping.
    marker.writeText("reset")
  }

  fun scenario(name: String): ScenarioPaths {
    resetLatencyCsvIfStale()
    val fixtureDir = File(MODULE_BUILD, "daemon-harness/fixtures/$name")
    val reportsDir = File(MODULE_BUILD, "reports/daemon-harness/$name")
    fixtureDir.deleteRecursively()
    fixtureDir.mkdirs()
    reportsDir.deleteRecursively()
    reportsDir.mkdirs()
    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
    val recorder = LatencyRecorder(csvFile = LATENCY_CSV)
    return ScenarioPaths(
      name = name,
      fixtureDir = fixtureDir,
      reportsDir = reportsDir,
      classpath = classpath,
      latency = recorder,
    )
  }

  /**
   * Returns the configured harness host — `"fake"` (default) or `"real"`. Driven by
   * `-Pharness.host=…` (see `tools/daemon-harness/build.gradle.kts`). Real-mode-only tests gate
   * themselves with `JUnit Assume.assumeTrue(host == "real")` rather than failing under fake mode.
   */
  fun harnessHost(): String = System.getProperty("composeai.harness.host") ?: "fake"
}

/**
 * Bundle of paths + the latency recorder a scenario test usually needs. Constructed by
 * [HarnessTestSupport.scenario] at the top of each `@Test` method.
 */
data class ScenarioPaths(
  val name: String,
  val fixtureDir: File,
  val reportsDir: File,
  val classpath: List<File>,
  val latency: LatencyRecorder,
)

/**
 * Writes a `previews.json` fixture into [fixtureDir] listing the supplied preview ids. Used by
 * every v1 scenario (each one produces its own ids). Convenience over hand-rolling the JSON in each
 * test body.
 */
fun writePreviewsManifest(fixtureDir: File, previewIds: List<String>) {
  val rows =
    previewIds.joinToString(",") { id ->
      """{"id":"$id","className":"fake.${id.replace("-", "_").replaceFirstChar { it.uppercase() }}","functionName":"Preview"}"""
    }
  File(fixtureDir, "previews.json").writeText("[$rows]")
}
