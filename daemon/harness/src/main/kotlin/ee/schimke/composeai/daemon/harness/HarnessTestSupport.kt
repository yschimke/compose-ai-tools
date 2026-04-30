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
   * `-Pharness.host=…` (see `daemon/harness/build.gradle.kts`). Real-mode-only tests gate
   * themselves with `JUnit Assume.assumeTrue(host == "real")` rather than failing under fake mode.
   */
  fun harnessHost(): String = System.getProperty("composeai.harness.host") ?: "fake"

  /**
   * Returns the configured harness target — `"desktop"` (default) or `"android"`. Driven by
   * `-Ptarget=…` (see `daemon/harness/build.gradle.kts`). D-harness.v2 added the parallel
   * `*AndroidRealModeTest.kt` test classes; both target sets coexist in the same JUnit suite and
   * skip via `Assume.assumeTrue(target == "<expected>")` when the requested target doesn't match.
   *
   * The renderer-agnostic claim from
   * [DESIGN § 4](../../../docs/daemon/DESIGN.md#renderer-agnostic-surface) is enforced at the
   * harness level once both targets pass the same scenarios.
   */
  fun harnessTarget(): String = System.getProperty("composeai.harness.target") ?: "desktop"

  /**
   * Whether the `regenerateBaselines` Gradle task is driving this run. Set via
   * `-Dcomposeai.harness.regenerate=true` on the spawning JVM. When `true`, real-mode tests skip
   * the pixel-diff against the in-repo baseline and overwrite the baseline file with the freshly
   * captured PNG instead — see `daemon/harness/CONTRIBUTING.md`.
   *
   * The default `false` keeps the existing v1.5a auto-capture-on-first-run flow intact: the first
   * run after deleting a baseline captures, and subsequent runs pixel-diff. `regenerate=true`
   * promotes that to "always overwrite", so a deliberate regen produces deterministic output even
   * when a baseline already exists.
   */
  fun regenerateBaselines(): Boolean =
    System.getProperty("composeai.harness.regenerate")?.equals("true", ignoreCase = true) == true

  /**
   * Resolves a real-mode baseline PNG path under `daemon/harness/baselines/<target>/<scenario>`
   * relative to the harness module's working directory. The path is materialised lazily by
   * [diffOrCaptureBaseline] — callers don't need to mkdir. Defaults to the current `-Ptarget=`
   * (desktop unless overridden); D-harness.v2 added the per-target split so Robolectric's bitmap
   * output and Skiko's bitmap output (which won't be byte-identical for "the same composable") have
   * separate baselines per target.
   */
  fun baselineFile(scenario: String, name: String, target: String = harnessTarget()): File {
    val rel = File("baselines/$target/$scenario/$name")
    return if (rel.isAbsolute) rel else File(System.getProperty("user.dir"), rel.path)
  }
}

/**
 * Shared "diff against in-repo baseline; capture if missing or `regenerate=true`" flow used by the
 * D-harness.v1.5b real-mode tests. Centralises the capture-vs-diff branching so each scenario test
 * stays focused on its wire-level assertions.
 *
 * - If [HarnessTestSupport.regenerateBaselines] is `true`, **always** writes [actualBytes] to
 *   [baseline] (overwrites if present) and returns. The pixel-diff is skipped — a deliberate
 *   regeneration run is treated as the new ground truth.
 * - Else if [baseline] does not exist yet, captures [actualBytes] into it (mirrors the v1.5a
 *   auto-capture-on-first-run behaviour from `S1LifecycleRealModeTest`).
 * - Else compares [actualBytes] against the baseline bytes via [PixelDiff]. On failure writes
 *   `actual.png` / `expected.png` / `diff.png` artefacts under [reportsDir] and throws an
 *   [AssertionError] including [stderrSupplier]'s output for diagnostic context.
 */
fun diffOrCaptureBaseline(
  actualBytes: ByteArray,
  baseline: File,
  reportsDir: File,
  scenario: String,
  stderrSupplier: () -> String = { "" },
) {
  if (HarnessTestSupport.regenerateBaselines()) {
    baseline.parentFile.mkdirs()
    baseline.writeBytes(actualBytes)
    System.err.println(
      "$scenario: regenerate=true — overwrote baseline at ${baseline.absolutePath}"
    )
    return
  }
  if (!baseline.exists()) {
    baseline.parentFile.mkdirs()
    baseline.writeBytes(actualBytes)
    System.err.println(
      "$scenario: captured baseline at ${baseline.absolutePath} (first run; subsequent runs " +
        "will pixel-diff against it)"
    )
    return
  }
  val expectedBytes = baseline.readBytes()
  val diff = PixelDiff.compare(actual = actualBytes, expected = expectedBytes)
  if (!diff.ok) {
    PixelDiff.writeDiffArtefacts(
      actual = actualBytes,
      expected = expectedBytes,
      outDir = reportsDir,
    )
    throw AssertionError(
      "$scenario real-mode pixel diff failed: ${diff.message} " +
        "(maxDelta=${diff.maxDelta}, offending=${diff.offendingPixelCount}). " +
        "Baseline=${baseline.absolutePath}. Artefacts under ${reportsDir.absolutePath}. " +
        "Stderr=\n${stderrSupplier()}"
    )
  }
}

/**
 * Builds the launcher payload-side bits of a real-mode scenario: a freshly-cleared `rendersDir` and
 * a `manifestFile` pre-populated with [previews] entries. The `manifestFile` already conforms to
 * [PreviewManifestRouter][ee.schimke.composeai.daemon.PreviewManifestRouter]'s schema; pass it to
 * [RealDesktopHarnessLauncher] verbatim. [previews] are serialised in the order supplied; the
 * `outputBaseName` for each entry defaults to its `id` so the resulting PNGs land at
 * `<rendersDir>/<id>.png`.
 *
 * Used by every D-harness.v1.5b real-mode test except S1 (which inlines the manifest for KDoc
 * provenance).
 */
data class RealModePreview(
  val id: String,
  val className: String,
  val functionName: String,
  val widthPx: Int = 64,
  val heightPx: Int = 64,
  val density: Double = 1.0,
  val showBackground: Boolean = true,
)

data class RealModeScenarioPaths(
  val name: String,
  val rendersDir: File,
  val reportsDir: File,
  val manifestFile: File,
  val classpath: List<File>,
  /**
   * Either [RealDesktopHarnessLauncher] (constructed by [realModeScenario]) or
   * [RealAndroidHarnessLauncher] (constructed by [realAndroidModeScenario], D-harness.v2). Tests
   * that need to introspect launcher-specific state should downcast — the existing seven desktop
   * tests only call `HarnessClient.start(launcher)` with no further use of the field.
   */
  val launcher: HarnessLauncher,
)

fun realModeScenario(name: String, previews: List<RealModePreview>): RealModeScenarioPaths {
  val (rendersDir, reportsDir, manifestFile, classpath) =
    prepareRealModeScenarioPaths(name, previews)
  val launcher =
    RealDesktopHarnessLauncher(
      rendersDir = rendersDir,
      previewsManifest = manifestFile,
      classpath = classpath,
    )
  return RealModeScenarioPaths(
    name = name,
    rendersDir = rendersDir,
    reportsDir = reportsDir,
    manifestFile = manifestFile,
    classpath = classpath,
    launcher = launcher,
  )
}

/**
 * Android counterpart of [realModeScenario] — D-harness.v2. Same setup (clears `rendersDir`, writes
 * a `previewsManifest` JSON, captures the test JVM's classpath) but constructs a
 * [RealAndroidHarnessLauncher] instead of [RealDesktopHarnessLauncher]. Used by the parallel
 * `*AndroidRealModeTest.kt` scenario classes.
 *
 * The returned [RealModeScenarioPaths.launcher] is the Android variant; its `name` field is
 * `"real-android"` (vs `"real"` for desktop) so failure diagnostics in [HarnessClient]'s reader
 * threads make obvious which target produced the failure.
 */
fun realAndroidModeScenario(name: String, previews: List<RealModePreview>): RealModeScenarioPaths {
  val (rendersDir, reportsDir, manifestFile, _) = prepareRealModeScenarioPaths(name, previews)
  // The Android daemon needs its runtime classpath resolved through AGP's variant model — the
  // harness's plain JVM `java.class.path` doesn't include AAR-derived JARs (e.g. roborazzi,
  // androidx.compose.foundation). D-harness.v2's harness build wires
  // `composeai.harness.androidDaemonClasspath` to a file listing the resolved JARs. Use that.
  val androidClasspath =
    RealAndroidHarnessLauncher.classpathFromProperty()
      ?: error(
        "realAndroidModeScenario requires `composeai.harness.androidDaemonClasspath` to be set " +
          "by the harness Gradle build (writeAndroidDaemonClasspath task). The system property " +
          "carries the file listing :daemon:android's runtime + testFixtures classpath; " +
          "without it the spawned daemon can't load Robolectric / Compose / RedFixturePreviews."
      )
  val launcher =
    RealAndroidHarnessLauncher(
      rendersDir = rendersDir,
      previewsManifest = manifestFile,
      classpath = androidClasspath,
    )
  return RealModeScenarioPaths(
    name = name,
    rendersDir = rendersDir,
    reportsDir = reportsDir,
    manifestFile = manifestFile,
    classpath = androidClasspath,
    launcher = launcher,
  )
}

private data class RealModeScenarioFiles(
  val rendersDir: File,
  val reportsDir: File,
  val manifestFile: File,
  val classpath: List<File>,
)

private fun prepareRealModeScenarioPaths(
  name: String,
  previews: List<RealModePreview>,
): RealModeScenarioFiles {
  val moduleBuildDir = File("build")
  val rendersDir =
    File(moduleBuildDir, "daemon-harness/renders/$name").apply {
      deleteRecursively()
      mkdirs()
    }
  val reportsDir =
    File(moduleBuildDir, "reports/daemon-harness/$name").apply {
      deleteRecursively()
      mkdirs()
    }
  val manifestFile =
    File(moduleBuildDir, "daemon-harness/manifests/$name-previews.json").apply {
      parentFile.mkdirs()
    }
  val previewsJson =
    previews.joinToString(",") { p ->
      """
      {
        "id": "${p.id}",
        "className": "${p.className}",
        "functionName": "${p.functionName}",
        "widthPx": ${p.widthPx},
        "heightPx": ${p.heightPx},
        "density": ${p.density},
        "showBackground": ${p.showBackground},
        "outputBaseName": "${p.id}"
      }
      """
        .trimIndent()
    }
  manifestFile.writeText("""{"previews":[$previewsJson]}""")
  val classpath =
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .filter { it.isNotBlank() }
      .map { File(it) }
  return RealModeScenarioFiles(rendersDir, reportsDir, manifestFile, classpath)
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
  writePreviewsManifest(fixtureDir, previewIds.map { id -> id to null })
}

/**
 * **B2.2 phase 2 overload** — emits one preview row per `(id, sourceFile)` pair. When `sourceFile`
 * is non-null it's serialised into the row so the daemon-side [PreviewIndex] anchors the preview to
 * that file path; the fake-mode S3 scenario uses this so a `fileChanged` against the same path
 * produces a `discoveryUpdated` with `removed = [id]`.
 */
@JvmName("writePreviewsManifestWithSources")
fun writePreviewsManifest(fixtureDir: File, previews: List<Pair<String, String?>>) {
  val rows =
    previews.joinToString(",") { (id, sourceFile) ->
      val cls = "fake.${id.replace("-", "_").replaceFirstChar { it.uppercase() }}"
      val sourceField =
        if (sourceFile != null) {
          ",\"sourceFile\":\"${sourceFile.replace("\\", "\\\\")}\""
        } else ""
      "{\"id\":\"$id\",\"className\":\"$cls\",\"functionName\":\"Preview\"$sourceField}"
    }
  File(fixtureDir, "previews.json").writeText("[$rows]")
}
