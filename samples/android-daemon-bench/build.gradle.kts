// Latency baseline harness for the preview daemon work — see
// docs/daemon/TODO.md task P0.1 and docs/daemon/DESIGN.md § 13.
//
// This module is deliberately small (5 trivial @Preview functions, no
// animations / scrolls / Wear / @PreviewParameter) so its `renderPreviews`
// wall time isolates the per-render cost from sandbox-init and configuration
// noise. The :samples:android workload is a different beast — it has scroll
// GIFs, animations, and PreviewParameter providers that each add hundreds of
// ms to the render row. Keep them separate.
//
// `benchPreviewLatency` shells out to `./gradlew` repeatedly under different
// scenarios (cold / warm-no-edit / warm-after-1-line-edit) and writes a CSV
// to docs/daemon/baseline-latency.csv. See README.md in this module for the
// scenario definitions.
@file:Suppress("UnstableApiUsage")

import java.time.Duration
import java.time.Instant
import org.w3c.dom.Element

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

android {
  namespace = "com.example.daemonbench"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example.daemonbench"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")
}

// --- Bench task ---------------------------------------------------------

// One row per (phase, scenario, run). Captured to docs/daemon/baseline-latency.csv.
// Phases mirror the headings in DESIGN.md § 13's latency table:
//   config       — `:bench:renderPreviews --dry-run` wall time (config + up-to-date checks).
//   compile      — `compileDebugKotlin` wall time (isolated).
//   discovery    — `discoverPreviews` wall time (isolated).
//   forkAndInit  — derived: renderPreviews wall - sum(per-test render time).
//   render       — sum of per-testcase `time=` attrs in the JUnit XML.
//
// Scenarios:
//   cold                  — `clean` first, `--no-build-cache --no-configuration-cache`.
//   warm-no-edit          — second run, everything up-to-date, normal cache flags.
//   warm-after-1-line-edit — touch a preview file (append + remove a newline), re-run.

abstract class BenchPreviewLatencyTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.rootProject.layout.projectDirectory)

  @get:Internal
  val benchModulePath: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention(":samples:android-daemon-bench")

  @get:Internal
  val previewSourceFile: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(
        project.layout.projectDirectory.file(
          "src/main/kotlin/com/example/daemonbench/BenchPreviews.kt"
        )
      )

  @get:Internal
  val outputCsv: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(
        project.rootProject.layout.projectDirectory.file("docs/daemon/baseline-latency.csv")
      )

  @get:Input
  val runsPerScenario: org.gradle.api.provider.Property<Int> =
    project.objects.property(Int::class.java).convention(3)

  @TaskAction
  fun run() {
    val csv = outputCsv.get().asFile
    csv.parentFile.mkdirs()

    val rows = mutableListOf<Row>()
    val runs = runsPerScenario.get()
    val rootDir = rootProjectDir.get().asFile
    val benchPath = benchModulePath.get()
    val previewFile = previewSourceFile.get().asFile

    val gradlew = rootDir.resolve("gradlew").also { check(it.exists()) { "missing $it" } }

    fun gradle(vararg args: String): RunResult {
      val cmd = mutableListOf<String>(gradlew.absolutePath)
      cmd += args
      logger.lifecycle("bench> {}", cmd.joinToString(" "))
      val started = Instant.now()
      val proc = ProcessBuilder(cmd).directory(rootDir).redirectErrorStream(true).start()
      val output = proc.inputStream.bufferedReader().readText()
      val rc = proc.waitFor()
      val tookMs = Duration.between(started, Instant.now()).toMillis()
      if (rc != 0) {
        logger.error(output)
        error("gradle exited with $rc: ${cmd.joinToString(" ")}")
      }
      return RunResult(tookMs, output)
    }

    // Per-scenario shared state ------------------------------------------------
    fun cold() {
      gradle("$benchPath:clean")
    }

    // Replace a single string literal in BenchPreviews.kt with a unique
    // marker, run the scenario body, then revert. We need the edit to
    // produce *different bytecode* — comment-only edits get stripped by
    // kotlinc and downstream tasks (renderPreviews, discoverPreviews)
    // stay UP-TO-DATE because their input snapshots hash the .class
    // files. A varying string literal is the smallest meaningful change
    // that kotlinc must propagate.
    val literalMarker = "\"three\""
    fun <T> withPreviewEdit(block: () -> T): T {
      val originalText = previewFile.readText()
      check(literalMarker in originalText) {
        "BenchPreviews.kt no longer contains $literalMarker — update bench task."
      }
      try {
        val edited =
          originalText.replace(literalMarker, "\"three-${System.nanoTime() % 1_000_000}\"")
        previewFile.writeText(edited)
        return block()
      } finally {
        previewFile.writeText(originalText)
      }
    }

    // Detect "Gradle skipped the task entirely" (UP-TO-DATE / NO-SOURCE / FROM-CACHE).
    // A skipped task contributes nothing to the wall-clock above pure config /
    // up-to-date checking, and crucially does NOT rewrite the JUnit XML — so
    // re-reading the XML would charge the warm scenario for the *previous*
    // (cold) run's per-test times.
    fun didTaskRun(output: String, task: String): Boolean {
      val line = output.lineSequence().firstOrNull { it.contains("> Task $task") } ?: return false
      // Lines look like `> Task :path:taskName` or `> Task :path:taskName UP-TO-DATE`.
      // Bare task line (no suffix) means it executed.
      val suffix = line.substringAfter("> Task $task").trim()
      return suffix.isEmpty() ||
        !suffix.startsWith("UP-TO-DATE") &&
          !suffix.startsWith("NO-SOURCE") &&
          !suffix.startsWith("FROM-CACHE") &&
          !suffix.startsWith("SKIPPED")
    }

    fun measureOnePass(scenario: String, run: Int, isCold: Boolean) {
      val cacheFlags =
        if (isCold) arrayOf("--no-build-cache", "--no-configuration-cache") else emptyArray()

      // Phase 1: config (dry-run, no actions executed).
      val dryFlags = arrayOf("--dry-run") + cacheFlags
      val configRes = gradle("$benchPath:renderPreviews", *dryFlags)
      rows += Row("config", scenario, run, configRes.wallMs, "wall of renderPreviews --dry-run")

      // Phase 2: compileDebugKotlin in isolation.
      val compileRes = gradle("$benchPath:compileDebugKotlin", *cacheFlags)
      val compileRan = didTaskRun(compileRes.output, "$benchPath:compileDebugKotlin")
      rows +=
        Row(
          "compile",
          scenario,
          run,
          compileRes.wallMs,
          if (compileRan) "wall of compileDebugKotlin task (incl. config)"
          else "compileDebugKotlin UP-TO-DATE; wall is config + up-to-date checks",
        )

      // Phase 3: discoverPreviews in isolation.
      val discoveryRes = gradle("$benchPath:discoverPreviews", *cacheFlags)
      val discoveryRan = didTaskRun(discoveryRes.output, "$benchPath:discoverPreviews")
      rows +=
        Row(
          "discovery",
          scenario,
          run,
          discoveryRes.wallMs,
          if (discoveryRan) "wall of discoverPreviews task (incl. config)"
          else "discoverPreviews UP-TO-DATE; wall is config + up-to-date checks",
        )

      // Phase 4 + 5: renderPreviews wall, then split via JUnit XML per-test times.
      val renderRes = gradle("$benchPath:renderPreviews", *cacheFlags)
      val renderRan = didTaskRun(renderRes.output, "$benchPath:renderPreviews")

      val renderTotalMs: Long
      val renderCount: Int
      if (renderRan) {
        val xml = locateJUnitXml(rootDir, benchPath)
        val parsed = sumTestCaseMillis(xml)
        renderTotalMs = parsed.first
        renderCount = parsed.second
      } else {
        // Task UP-TO-DATE: no per-test work happened, no new XML written.
        renderTotalMs = 0
        renderCount = 0
      }
      val forkInitMs = (renderRes.wallMs - renderTotalMs).coerceAtLeast(0)
      rows +=
        Row(
          "forkAndInit",
          scenario,
          run,
          forkInitMs,
          if (renderRan)
            "renderPreviews wall - sum(per-test) = JVM fork + sandbox init + Gradle overhead"
          else "renderPreviews UP-TO-DATE; whole wall is Gradle overhead (no fork, no sandbox)",
        )
      rows +=
        Row(
          "render",
          scenario,
          run,
          renderTotalMs,
          if (renderRan) "sum of $renderCount JUnit testcase time= attrs (full preview set)"
          else "renderPreviews UP-TO-DATE; no per-test work (0 by definition)",
        )
    }

    fun measureScenario(scenario: String, run: Int) {
      when (scenario) {
        "cold" -> {
          // Cold = clean before each rep so every rep measures cold-from-clean.
          cold()
          measureOnePass(scenario, run, isCold = true)
        }
        "warm-no-edit" -> {
          // Caller has primed warm state. Just measure.
          measureOnePass(scenario, run, isCold = false)
        }
        "warm-after-1-line-edit" -> {
          // Edit lives for the duration of the four sub-measurements so
          // every phase observes the same dirty input. Reverted in finally.
          withPreviewEdit { measureOnePass(scenario, run, isCold = false) }
        }
        else -> error("unknown scenario: $scenario")
      }
    }

    val scenarioNames = listOf("cold", "warm-no-edit", "warm-after-1-line-edit")

    // Prime warm caches before warm scenarios so the very first warm rep doesn't
    // include leftover cold cost from whichever scenario ran before it.
    fun primeWarm() {
      gradle("$benchPath:renderPreviews")
    }

    for (name in scenarioNames) {
      if (name != "cold") primeWarm()
      for (run in 1..runs) {
        measureScenario(name, run)
      }
    }

    writeCsv(csv, rows)
    logger.lifecycle("bench: wrote ${rows.size} rows to {}", csv)
    logger.lifecycle("bench: medians (ms) per (phase, scenario):")
    rows
      .groupBy { it.phase to it.scenario }
      .toSortedMap(compareBy({ it.first }, { it.second }))
      .forEach { (key, group) ->
        val sorted = group.map { it.ms }.sorted()
        val median = sorted[sorted.size / 2]
        logger.lifecycle("  {} / {} -> {} ms (n={})", key.first, key.second, median, group.size)
      }
  }

  private fun locateJUnitXml(rootDir: java.io.File, modulePath: String): java.io.File {
    val rel = modulePath.removePrefix(":").replace(":", "/")
    val dir = rootDir.resolve("$rel/build/test-results/renderPreviews")
    val xml =
      dir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.firstOrNull()
        ?: error("no JUnit XML under $dir — did renderPreviews run?")
    return xml
  }

  private fun sumTestCaseMillis(xml: java.io.File): Pair<Long, Int> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
    val cases = doc.getElementsByTagName("testcase")
    var total = 0.0
    var count = 0
    for (i in 0 until cases.length) {
      val el = cases.item(i) as Element
      val t = el.getAttribute("time").toDoubleOrNull() ?: continue
      total += t
      count += 1
    }
    return (total * 1000).toLong() to count
  }

  private fun writeCsv(csv: java.io.File, rows: List<Row>) {
    val sb = StringBuilder()
    sb.appendLine(
      "# baseline-latency.csv — captured by :samples:android-daemon-bench:benchPreviewLatency"
    )
    sb.appendLine("# See docs/daemon/TODO.md P0.1 and DESIGN.md § 13. Hardware/JDK/etc captured")
    sb.appendLine("# in docs/daemon/baseline-latency.md sidecar.")
    sb.appendLine("phase,scenario,run,milliseconds,notes")
    for (r in rows) {
      sb.appendLine("${r.phase},${r.scenario},${r.run},${r.ms},${r.notes.replace(",", ";")}")
    }
    csv.writeText(sb.toString())
  }

  private data class Row(
    val phase: String,
    val scenario: String,
    val run: Int,
    val ms: Long,
    val notes: String,
  )

  private data class RunResult(val wallMs: Long, val output: String)
}

tasks.register<BenchPreviewLatencyTask>("benchPreviewLatency") {
  group = "verification"
  description =
    "Times the existing renderPreviews path under cold / warm-no-edit / " +
      "warm-after-1-line-edit scenarios; writes docs/daemon/baseline-latency.csv."
  // No inputs/outputs declared — bench is always-stale by design (forces a
  // re-run when invoked explicitly).
  notCompatibleWithConfigurationCache(
    "BenchPreviewLatencyTask shells out to a nested ./gradlew invocation"
  )
  outputs.upToDateWhen { false }
}
