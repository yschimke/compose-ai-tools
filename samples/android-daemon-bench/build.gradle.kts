// Latency baseline harness for the preview daemon work — see docs/daemon/DESIGN.md § 13.
//
// This module is deliberately small (5 trivial @Preview functions, no
// animations / scrolls / Wear / @PreviewParameter) so its `composePreviewRender`
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

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.w3c.dom.Element

plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; see the matching block in `:samples:android` for the JDK 17
  // toolchain rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)
}

android {
  namespace = "com.example.daemonbench"

  defaultConfig {
    applicationId = "com.example.daemonbench"
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
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
//   config       — `:bench:composePreviewRender --dry-run` wall time (config + up-to-date checks).
//   compile      — `compileDebugKotlin` wall time (isolated).
//   discovery    — `composePreviewDiscover` wall time (isolated).
//   forkAndInit  — derived: composePreviewRender wall - sum(per-test render time).
//   render       — sum of per-testcase `time=` attrs in the JUnit XML.
//
// Scenarios:
//   cold                  — `clean` first, `--no-build-cache --no-configuration-cache`.
//   warm-no-edit          — second run, everything up-to-date, normal cache flags.
//   warm-after-1-line-edit — touch a preview file (append + remove a newline), re-run.

abstract class BenchPreviewLatencyTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.settingsDirectory)

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
      .convention(project.layout.settingsDirectory.file("docs/daemon/baseline-latency.csv"))

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
    // kotlinc and downstream tasks (composePreviewRender, composePreviewDiscover)
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
      val configRes = gradle("$benchPath:composePreviewRender", *dryFlags)
      rows +=
        Row("config", scenario, run, configRes.wallMs, "wall of composePreviewRender --dry-run")

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

      // Phase 3: composePreviewDiscover in isolation.
      val discoveryRes = gradle("$benchPath:composePreviewDiscover", *cacheFlags)
      val discoveryRan = didTaskRun(discoveryRes.output, "$benchPath:composePreviewDiscover")
      rows +=
        Row(
          "discovery",
          scenario,
          run,
          discoveryRes.wallMs,
          if (discoveryRan) "wall of composePreviewDiscover task (incl. config)"
          else "composePreviewDiscover UP-TO-DATE; wall is config + up-to-date checks",
        )

      // Phase 4 + 5: composePreviewRender wall, then split via JUnit XML per-test times.
      val renderRes = gradle("$benchPath:composePreviewRender", *cacheFlags)
      val renderRan = didTaskRun(renderRes.output, "$benchPath:composePreviewRender")

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
            "composePreviewRender wall - sum(per-test) = JVM fork + sandbox init + Gradle overhead"
          else
            "composePreviewRender UP-TO-DATE; whole wall is Gradle overhead (no fork, no sandbox)",
        )
      rows +=
        Row(
          "render",
          scenario,
          run,
          renderTotalMs,
          if (renderRan) "sum of $renderCount JUnit testcase time= attrs (full preview set)"
          else "composePreviewRender UP-TO-DATE; no per-test work (0 by definition)",
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
      gradle("$benchPath:composePreviewRender")
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
    val dir = rootDir.resolve("$rel/build/test-results/composePreviewRender")
    val xml =
      dir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.firstOrNull()
        ?: error("no JUnit XML under $dir — did composePreviewRender run?")
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
    sb.appendLine("# See docs/daemon/DESIGN.md § 13. Hardware/JDK/etc captured")
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
    "Times the existing composePreviewRender path under cold / warm-no-edit / " +
      "warm-after-1-line-edit scenarios; writes docs/daemon/baseline-latency.csv."
  // No inputs/outputs declared — bench is always-stale by design (forces a
  // re-run when invoked explicitly).
  notCompatibleWithConfigurationCache(
    "BenchPreviewLatencyTask shells out to a nested ./gradlew invocation"
  )
  outputs.upToDateWhen { false }
}

// --- Stage-1 + stage-2 compile-leg bench (issue #1586) --------------------------------------
//
// Android counterpart to :samples:desktop-daemon-bench's BenchCompileStagesTask — kept in lockstep
// (same scenarios, same output protocol, same verdict). `benchPreviewLatency` (above) measures
// stage 0 (per-save `./gradlew`); this sibling drives the two faster save loops that shipped behind
// experimental flags and emits the rows COMPILE-IN-PROCESS.md § "What we expect to measure" asks
// for, then evaluates the promote/demote thresholds (< 2 s save→pixel on Android) and prints a
// verdict:
//
//   * stage 1 (`composePreview.daemon.continuousCompile`): a resident `gradle --continuous`
//     invocation; we prime its warm-up build then time edit→`BUILD SUCCESSFUL in N` per rep.
//   * stage 2 (`composePreview.daemon.compileInProcess`): the in-process Build Tools API compile;
//     we read the `btaCompile` block from `daemon-launch.json` and `javaexec` `:daemon:core`'s
//     `BtaBenchMain`, driving the real `BtaCompileSession.compileIncremental()`.
//
// The render leg is unchanged from stage 0, so the verdict reuses the stage-0
// `render,warm-after-1-line-edit` median already in the CSV — run `benchPreviewLatency` first.

abstract class BenchCompileStagesTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.settingsDirectory)

  @get:Input
  val benchModulePath: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention(":samples:android-daemon-bench")

  @get:Input
  val target: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention("android")

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
  val sourceDir: org.gradle.api.file.DirectoryProperty =
    project.objects
      .directoryProperty()
      .convention(project.layout.projectDirectory.dir("src/main/kotlin"))

  @get:Internal
  val outputCsv: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(project.layout.settingsDirectory.file("docs/daemon/baseline-latency.csv"))

  @get:Internal
  val daemonLaunchJson: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(project.layout.buildDirectory.file("compose-previews/daemon-launch.json"))

  @get:Internal abstract val daemonCoreClasspath: org.gradle.api.file.ConfigurableFileCollection

  @get:Internal
  val javaLauncher: org.gradle.api.provider.Property<String> =
    project.objects
      .property(String::class.java)
      .convention(project.providers.systemProperty("java.home").map { "$it/bin/java" })

  @get:Input
  val runsPerScenario: org.gradle.api.provider.Property<Int> =
    project.objects.property(Int::class.java).convention(5)

  @TaskAction
  fun run() {
    val csv = outputCsv.get().asFile
    csv.parentFile.mkdirs()
    val rootDir = rootProjectDir.get().asFile
    val benchPath = benchModulePath.get()
    val tgt = target.get()
    val previewFile = previewSourceFile.get().asFile
    val gradlew = rootDir.resolve("gradlew").also { check(it.exists()) { "missing $it" } }
    val runs = runsPerScenario.get()
    val marker = "\"three\""

    val rows = mutableListOf<StageRow>()
    val notes = mutableListOf<String>()
    var stage2UsedMb: Long? = null

    fun gradle(vararg args: String): Int {
      val cmd = mutableListOf(gradlew.absolutePath) + args
      logger.lifecycle("bench> {}", cmd.joinToString(" "))
      val proc = ProcessBuilder(cmd).directory(rootDir).redirectErrorStream(true).start()
      val output = proc.inputStream.bufferedReader().readText()
      val rc = proc.waitFor()
      if (rc != 0) logger.error(output)
      return rc
    }

    gradle("$benchPath:composePreviewCompile")
    gradle("$benchPath:composePreviewDaemonStart")

    // --- Stage 1: gradle --continuous resident recompile ------------------------------------
    run {
      val cmd =
        listOf(
          gradlew.absolutePath,
          "--continuous",
          "--console=plain",
          "$benchPath:composePreviewCompile",
        )
      logger.lifecycle("bench> {}", cmd.joinToString(" "))
      val proc = ProcessBuilder(cmd).directory(rootDir).redirectErrorStream(true).start()
      val builds = LinkedBlockingQueue<Long>()
      Thread {
          proc.inputStream.bufferedReader().forEachLine { line ->
            val ms = parseBuildSuccessful(line)
            when {
              ms != null -> builds.offer(ms)
              line.contains("BUILD FAILED") -> builds.offer(-1L)
            }
          }
        }
        .apply {
          isDaemon = true
          start()
        }
      try {
        val warm = builds.poll(180, TimeUnit.SECONDS)
        if (warm == null) {
          notes += "stage-1: `gradle --continuous` warm-up build never completed within 180s"
        } else {
          for (run in 1..runs) {
            // Drain rebuilds a previous revert may have triggered: poll until the watcher
            // goes quiet for 2 s, so the next poll observes only this rep's edit build.
            while (builds.poll(2, TimeUnit.SECONDS) != null) {
              /* discard stale build */
            }
            val original = previewFile.readText()
            check(marker in original) {
              "${previewFile.name} no longer contains $marker — update the bench marker"
            }
            previewFile.writeText(
              original.replace(marker, "\"three-${System.nanoTime() % 1_000_000}\"")
            )
            try {
              val ms = builds.poll(120, TimeUnit.SECONDS)
              when {
                ms == null ->
                  notes +=
                    "stage-1 run $run: no rebuild within 120s (Gradle's file watcher missed the save?)"
                ms < 0 -> notes += "stage-1 run $run: BUILD FAILED"
                else ->
                  rows +=
                    StageRow(
                      "compile",
                      "stage-1-warm-after-1-line-edit",
                      run,
                      ms,
                      "gradle --continuous resident rebuild (BUILD SUCCESSFUL in N)",
                    )
              }
            } finally {
              previewFile.writeText(original)
            }
          }
        }
      } finally {
        proc.destroy()
        if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
      }
    }

    // --- Stage 2: in-process BTA compile via :daemon:core BtaBenchMain ----------------------
    run {
      val descriptor = daemonLaunchJson.get().asFile
      val bta =
        if (!descriptor.exists()) null
        else (groovy.json.JsonSlurper().parse(descriptor) as Map<*, *>)["btaCompile"] as? Map<*, *>
      if (bta == null) {
        notes +=
          "stage-2: daemon-launch.json carried no btaCompile block (BTA classpath not resolved for $benchPath)"
      } else {
        fun joined(key: String) =
          (bta[key] as? List<*>).orEmpty().joinToString(File.pathSeparator) { it.toString() }
        val sources =
          sourceDir
            .get()
            .asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absolutePath }
            .toList()
            .joinToString(File.pathSeparator)
        val cp = daemonCoreClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val cmd = mutableListOf(javaLauncher.get())
        cmd += "-Dcomposeai.daemon.bta.implClasspath=${joined("implClasspath")}"
        cmd += "-Dcomposeai.daemon.bta.compileClasspath=${joined("compileClasspath")}"
        cmd += "-Dcomposeai.daemon.bta.compilerPlugins=${joined("compilerPlugins")}"
        cmd += "-Dcomposeai.daemon.bta.moduleName=${bta["moduleName"]}"
        cmd += "-Dcomposeai.daemon.bta.outputDir=${bta["outputDir"]}"
        cmd += "-Dcomposeai.daemon.bta.icWorkingDir=${bta["icWorkingDir"]}"
        (bta["ineligibilityReason"] as? String)?.takeIf { it.isNotEmpty() }?.let {
          cmd += "-Dcomposeai.daemon.bta.ineligibilityReason=$it"
        }
        cmd += "-Dcomposeai.bench.sources=$sources"
        cmd += "-Dcomposeai.bench.editFile=${previewFile.absolutePath}"
        cmd += "-Dcomposeai.bench.runs=$runs"
        cmd += listOf("-cp", cp, "ee.schimke.composeai.daemon.bta.BtaBenchMain")
        logger.lifecycle("bench> java … ee.schimke.composeai.daemon.bta.BtaBenchMain")
        val proc =
          ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        out.lineSequence().forEach { line ->
          val p = line.split("\t")
          when (p.getOrNull(0)) {
            "BENCHROW" ->
              if (p.size >= 6)
                rows +=
                  StageRow(p[1], p[2], p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0L, p[5])
            "BENCHMEM" -> stage2UsedMb = p.getOrNull(1)?.toLongOrNull()
            "BENCHNOTE" -> notes += p.getOrNull(1).orEmpty()
          }
        }
        if (rc != 0) notes += "stage-2: BtaBenchMain exited $rc"
      }
    }

    appendCsv(csv, rows, tgt)
    writeVerdict(csv, rows, notes, tgt, stage2UsedMb)
    logger.lifecycle("bench: wrote ${rows.size} stage-1/stage-2 rows for {} to {}", tgt, csv)
  }

  private fun writeVerdict(
    csv: java.io.File,
    rows: List<StageRow>,
    notes: List<String>,
    target: String,
    stage2UsedMb: Long?,
  ) {
    fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]
    fun medianFor(phase: String, scenario: String) =
      median(rows.filter { it.phase == phase && it.scenario == scenario && it.ms >= 0 }.map { it.ms })

    val s1 = medianFor("compile", "stage-1-warm-after-1-line-edit")
    val s2compile = medianFor("compile", "stage-2-warm-after-1-line-edit")
    val s2swap = medianFor("classloader-swap", "stage-2-warm")
    val renderBaseline = readRenderBaseline(csv, target)
    val budget = if (target == "desktop") 1000L else 2000L

    fun fmt(v: Long?) = v?.let { "$it ms" } ?: "—"
    fun verdict(ok: Boolean?) = if (ok == null) "UNKNOWN" else if (ok) "PASS" else "FAIL"

    val s2SavePixel =
      if (s2compile != null && s2swap != null && renderBaseline != null)
        s2compile + s2swap + renderBaseline
      else null
    val s1SavePixel = if (s1 != null && renderBaseline != null) s1 + renderBaseline else null
    val latencyOk = s2SavePixel?.let { it < budget }
    val advantage = if (s1 != null && s2compile != null) s1 - s2compile else null
    val demoteSignal = if (target == "desktop" && advantage != null) advantage < 200 else false

    val sb = StringBuilder()
    sb.appendLine("# Stage-2 graduation verdict — $target")
    sb.appendLine()
    sb.appendLine(
      "Generated by `:${benchModulePath.get().removePrefix(":")}:benchCompileStages`. " +
        "Thresholds from [COMPILE-IN-PROCESS.md](COMPILE-IN-PROCESS.md) § \"Promote / demote criteria\"."
    )
    sb.appendLine()
    sb.appendLine("## Measured medians")
    sb.appendLine()
    sb.appendLine("| Leg | Stage 1 | Stage 2 |")
    sb.appendLine("| --- | --- | --- |")
    sb.appendLine("| compile (warm, 1-line edit) | ${fmt(s1)} | ${fmt(s2compile)} |")
    sb.appendLine("| classloader-swap | — | ${fmt(s2swap)} |")
    sb.appendLine("| render (warm, from stage-0 baseline) | ${fmt(renderBaseline)} | ${fmt(renderBaseline)} |")
    sb.appendLine("| **save → pixel total** | **${fmt(s1SavePixel)}** | **${fmt(s2SavePixel)}** |")
    sb.appendLine()
    sb.appendLine("## Promote criteria")
    sb.appendLine()
    sb.appendLine(
      "- ${verdict(latencyOk)} — save→pixel < ${budget} ms ($target): measured ${fmt(s2SavePixel)}."
    )
    sb.appendLine(
      "- INFORMATIONAL — memory delta vs stage 1 < +250 MB: stage-2 BTA-frontend used heap " +
        "= ${stage2UsedMb?.let { "$it MB" } ?: "—"} (compare manually to a stage-1 daemon on the same workspace; this harness can't observe the stage-1 daemon's resident set)."
    )
    sb.appendLine(
      "- OUT OF SCOPE (manual) — sustained 10 min editing without wedging; a real KSP module exercises the fallback predicate cleanly."
    )
    sb.appendLine()
    sb.appendLine("## Demote signal")
    sb.appendLine()
    sb.appendLine(
      "- Warm-path advantage over stage 1: ${fmt(advantage)} " +
        "(< 200 ms on desktop ⇒ BTA's win has collapsed)." +
        if (demoteSignal) " ⚠️ DEMOTE SIGNAL TRIPPED." else ""
    )
    sb.appendLine()
    val overall =
      when {
        latencyOk == true && demoteSignal != true ->
          "PROMOTE CANDIDATE — latency threshold met; confirm the manual criteria before flipping the default."
        latencyOk == false || demoteSignal == true -> "DO NOT PROMOTE — see failed criteria above."
        else -> "INCONCLUSIVE — missing measurements (did `benchPreviewLatency` run first for the render baseline?)."
      }
    sb.appendLine("## Verdict: $overall")
    if (notes.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Notes")
      sb.appendLine()
      notes.forEach { sb.appendLine("- $it") }
    }

    val verdictFile = csv.parentFile.resolve("stage-2-verdict-$target.md")
    verdictFile.writeText(sb.toString())
    logger.lifecycle("bench: stage-2 verdict ({}) -> {}", target, verdictFile)
    logger.lifecycle("bench: {}", overall)
    notes.forEach { logger.warn("bench note: {}", it) }
  }

  private fun readRenderBaseline(csv: java.io.File, target: String): Long? {
    if (!csv.exists()) return null
    val times =
      csv
        .readLines()
        .filterNot { it.startsWith("#") || it.isBlank() }
        .mapNotNull { line ->
          val c = line.split(",")
          if (
            c.size >= 5 &&
              c[0] == target &&
              c[1] == "render" &&
              c[2] == "warm-after-1-line-edit"
          )
            c[4].toLongOrNull()
          else null
        }
        .filter { it > 0 }
    return if (times.isEmpty()) null else times.sorted()[times.size / 2]
  }

  private fun parseBuildSuccessful(line: String): Long? {
    val idx = line.indexOf("BUILD SUCCESSFUL in ")
    if (idx < 0) return null
    return parseGradleDuration(line.substring(idx + "BUILD SUCCESSFUL in ".length))
  }

  private fun parseGradleDuration(s: String): Long {
    var total = 0L
    for (tok in s.trim().split(Regex("\\s+"))) {
      val m = Regex("([0-9.]+)(ms|h|m|s)").find(tok) ?: continue
      val v = m.groupValues[1].toDoubleOrNull() ?: continue
      total +=
        when (m.groupValues[2]) {
          "h" -> (v * 3_600_000).toLong()
          "m" -> (v * 60_000).toLong()
          "s" -> (v * 1000).toLong()
          "ms" -> v.toLong()
          else -> 0L
        }
    }
    return total
  }

  private fun appendCsv(csv: java.io.File, rows: List<StageRow>, target: String) {
    val newHeader = "target,phase,scenario,run,milliseconds,notes"
    val existing = if (csv.exists()) csv.readText() else ""
    val sb = StringBuilder()
    if (existing.isBlank()) {
      sb.appendLine("# baseline-latency.csv — captured by the daemon-bench :benchPreviewLatency and")
      sb.appendLine("# :benchCompileStages tasks. See docs/daemon/baseline-latency.md for methodology.")
      sb.appendLine(newHeader)
    } else {
      val lines = existing.lineSequence().toList()
      val headerIdx = lines.indexOfFirst { !it.startsWith("#") && it.isNotBlank() }
      check(headerIdx >= 0) { "baseline-latency.csv has no header row" }
      val header = lines[headerIdx].trim()
      when (header) {
        newHeader -> {
          sb.append(existing)
          if (!existing.endsWith("\n")) sb.appendLine()
        }
        "phase,scenario,run,milliseconds,notes" ->
          for ((i, line) in lines.withIndex()) {
            when {
              line.startsWith("#") -> sb.appendLine(line)
              i == headerIdx -> sb.appendLine(newHeader)
              line.isBlank() -> {}
              else -> sb.appendLine("android,$line")
            }
          }
        else -> error("baseline-latency.csv has an unexpected header: '$header'")
      }
    }
    for (r in rows) {
      sb.appendLine("$target,${r.phase},${r.scenario},${r.run},${r.ms},${r.notes.replace(",", ";")}")
    }
    csv.writeText(sb.toString())
  }

  private data class StageRow(
    val phase: String,
    val scenario: String,
    val run: Int,
    val ms: Long,
    val notes: String,
  )
}

// `:daemon:core` runtime (BtaBenchMain + BtaCompileSession + kotlin-build-tools-api). Isolated in
// its own resolvable configuration so it never leaks onto the module's real compile/runtime path.
configurations.create("daemonBench") {
  isCanBeResolved = true
  isCanBeConsumed = false
}

dependencies { add("daemonBench", project(":daemon:core")) }

tasks.register<BenchCompileStagesTask>("benchCompileStages") {
  group = "verification"
  description =
    "Drives the stage-1 (gradle --continuous) and stage-2 (in-process BTA) compile legs, appends " +
      "their rows to docs/daemon/baseline-latency.csv, and writes a stage-2 graduation verdict."
  daemonCoreClasspath.from(configurations.named("daemonBench"))
  dependsOn("composePreviewDaemonStart")
  notCompatibleWithConfigurationCache(
    "BenchCompileStagesTask shells out to nested ./gradlew + a daemon JVM"
  )
  outputs.upToDateWhen { false }
}

// --- CI smoke (issue #1586) -----------------------------------------------------------------
// The full benches are deliberately slow (run on the reference machine, not per-PR). To keep this
// module from bit-rotting, `check` renders the five trivial previews — a cheap proof the module
// builds, discovery wires up, and the renderer path is intact.
tasks.named("check") { dependsOn("composePreviewRender") }
