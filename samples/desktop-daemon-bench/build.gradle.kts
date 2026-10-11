// Desktop latency baseline harness for the preview daemon (compose-preview-daemon's
// docs/daemon/DESIGN.md § 13). Mirrors :samples:android-daemon-bench through the Compose-Desktop
// renderer: five trivial @Previews, so rows in baseline-latency.csv compare like-for-like.
//
// `benchPreviewLatency` runs `./gradlew` under cold / warm-no-edit / warm-after-1-line-edit
// scenarios and appends desktop rows to build/daemon-bench/baseline-latency.csv. See README.md.
@file:Suppress("UnstableApiUsage", "DEPRECATION")

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)
}

// --- Bench task ---------------------------------------------------------

// One row per (target, phase, scenario, run). Desktop phases:
//   config       — `:bench:composePreviewRender --dry-run` wall.
//   compile      — `compileKotlin` wall.
//   discovery    — `composePreviewDiscover` wall.
//   forkAndInit  — composePreviewRender wall minus the per-preview javaexec walls: Gradle's
//                  orchestration between forks (desktop forks one JVM per preview).
//   render       — sum of per-preview renderer probes, each including JVM + Skiko init + draw.
//
// Unlike Android (one shared Robolectric sandbox), every desktop preview bootstraps its own
// runtime, so the daemon's addressable surface on desktop is `render` itself.

abstract class BenchPreviewLatencyTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.settingsDirectory)

  @get:Internal
  val benchModulePath: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention(":samples:desktop-daemon-bench")

  @get:Internal
  val previewSourceFile: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(
        project.layout.projectDirectory.file(
          "src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt"
        )
      )

  @get:Internal
  val outputCsv: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(project.layout.settingsDirectory.file("build/daemon-bench/baseline-latency.csv"))

  @get:Internal
  val previewsJsonFile: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(project.layout.buildDirectory.file("compose-previews/previews.json"))

  @get:Internal
  val rendersDir: org.gradle.api.file.DirectoryProperty =
    project.objects
      .directoryProperty()
      .convention(project.layout.buildDirectory.dir("compose-previews/renders"))

  // Renderer classpath resolved at config time, so probes can `java -cp` the renderer directly
  // without a resolvable configuration at execution time.
  @get:Internal abstract val rendererClasspath: org.gradle.api.file.ConfigurableFileCollection

  @get:Internal
  val javaLauncher: org.gradle.api.provider.Property<String> =
    project.objects
      .property(String::class.java)
      .convention(project.providers.systemProperty("java.home").map { "$it/bin/java" })

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
    val javaBin = javaLauncher.get()
    val classpath = rendererClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }

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

    fun cold() {
      gradle("$benchPath:clean")
    }

    // A string-literal change, since comment-only edits leave bytecode (and downstream
    // class-hashing tasks) unchanged.
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

    fun didTaskRun(output: String, task: String): Boolean {
      val line = output.lineSequence().firstOrNull { it.contains("> Task $task") } ?: return false
      val suffix = line.substringAfter("> Task $task").trim()
      return suffix.isEmpty() ||
        !suffix.startsWith("UP-TO-DATE") &&
          !suffix.startsWith("NO-SOURCE") &&
          !suffix.startsWith("FROM-CACHE") &&
          !suffix.startsWith("SKIPPED")
    }

    // Spawn the renderer once per preview (args as RenderPreviewsTask builds them) and time each
    // call.
    fun probeRenders(): Pair<Long, Int> {
      val previewsJson = previewsJsonFile.get().asFile
      check(previewsJson.exists()) {
        "previews.json missing at $previewsJson — discovery hasn't run"
      }
      // Hand-parsed: this task runs in the build script's classloader, without a serialization dep.
      val text = previewsJson.readText()
      val previews = parsePreviewsJson(text)
      val outDir = rendersDir.get().asFile
      outDir.mkdirs()
      var total = 0L
      var count = 0
      for (p in previews) {
        val outputFile = outDir.resolve(p.renderOutputRel)
        val args =
          listOf(
            javaBin,
            "-cp",
            classpath,
            "ee.schimke.composeai.renderer.DesktopRendererMainKt",
            p.className,
            p.functionName,
            p.widthPx.toString(),
            p.heightPx.toString(),
            p.density.toString(),
            p.showBackground.toString(),
            p.backgroundColor.toString(),
            outputFile.absolutePath,
            "", // wrapper
            p.wrapWidth.toString(),
            p.wrapHeight.toString(),
            "", // previewParameter provider FQN
            Int.MAX_VALUE.toString(),
          )
        val started = Instant.now()
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val procOut = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        val took = Duration.between(started, Instant.now()).toMillis()
        if (rc != 0) {
          logger.error(procOut)
          error("renderer exited $rc for ${p.id}")
        }
        total += took
        count += 1
      }
      return total to count
    }

    fun measureOnePass(scenario: String, run: Int, isCold: Boolean) {
      val cacheFlags =
        if (isCold) arrayOf("--no-build-cache", "--no-configuration-cache") else emptyArray()

      // Phase 1: config (dry-run, no actions executed).
      val dryFlags = arrayOf("--dry-run") + cacheFlags
      val configRes = gradle("$benchPath:composePreviewRender", *dryFlags)
      rows +=
        Row("config", scenario, run, configRes.wallMs, "wall of composePreviewRender --dry-run")

      // Phase 2: compileKotlin in isolation. (kotlin.jvm plugin: no
      // `compileDebugKotlin` variant — single `compileKotlin` task.)
      val compileRes = gradle("$benchPath:compileKotlin", *cacheFlags)
      val compileRan = didTaskRun(compileRes.output, "$benchPath:compileKotlin")
      rows +=
        Row(
          "compile",
          scenario,
          run,
          compileRes.wallMs,
          if (compileRan) "wall of compileKotlin task (incl. config)"
          else "compileKotlin UP-TO-DATE; wall is config + up-to-date checks",
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

      // Phase 4 + 5: composePreviewRender wall, then per-preview probe walls.
      val renderRes = gradle("$benchPath:composePreviewRender", *cacheFlags)
      val renderRan = didTaskRun(renderRes.output, "$benchPath:composePreviewRender")

      val (probeTotalMs, renderCount) = if (renderRan) probeRenders() else 0L to 0
      val forkInitMs = (renderRes.wallMs - probeTotalMs).coerceAtLeast(0)
      rows +=
        Row(
          "forkAndInit",
          scenario,
          run,
          forkInitMs,
          if (renderRan)
            "composePreviewRender wall - sum(per-preview javaexec) = Gradle orchestration between forks"
          else
            "composePreviewRender UP-TO-DATE; whole wall is Gradle overhead (no fork; no render)",
        )
      rows +=
        Row(
          "render",
          scenario,
          run,
          probeTotalMs,
          if (renderRan)
            "sum of $renderCount direct DesktopRendererMain javaexec walls (incl. per-process JVM+Skiko init)"
          else "composePreviewRender UP-TO-DATE; no render work (0 by definition)",
        )
    }

    fun measureScenario(scenario: String, run: Int) {
      when (scenario) {
        "cold" -> {
          cold()
          measureOnePass(scenario, run, isCold = true)
        }
        "warm-no-edit" -> {
          measureOnePass(scenario, run, isCold = false)
        }
        "warm-after-1-line-edit" -> {
          withPreviewEdit { measureOnePass(scenario, run, isCold = false) }
        }
        else -> error("unknown scenario: $scenario")
      }
    }

    val scenarioNames = listOf("cold", "warm-no-edit", "warm-after-1-line-edit")

    fun primeWarm() {
      gradle("$benchPath:composePreviewRender")
    }

    for (name in scenarioNames) {
      if (name != "cold") primeWarm()
      for (run in 1..runs) {
        measureScenario(name, run)
      }
    }

    appendCsv(csv, rows, target = "desktop")
    logger.lifecycle("bench: wrote ${rows.size} desktop rows to {}", csv)
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

  /**
   * Append rows to the CSV shared with :samples:android-daemon-bench, first migrating a legacy file
   * (no `target` column) by prepending `android,` to each row. Idempotent.
   */
  private fun appendCsv(csv: java.io.File, rows: List<Row>, target: String) {
    val newHeader = "target,phase,scenario,run,milliseconds,notes"
    val existing = if (csv.exists()) csv.readText() else ""
    val sb = StringBuilder()

    if (existing.isBlank()) {
      // First run for either target. Write fresh header.
      sb.appendLine("# baseline-latency.csv — captured by android-daemon-bench (P0.1) and")
      sb.appendLine("# desktop-daemon-bench (P0.6) :benchPreviewLatency tasks. See")
      sb.appendLine(
        "# compose-preview-daemon's docs/daemon/baseline-latency.md for methodology + reference machine."
      )
      sb.appendLine(newHeader)
    } else {
      // Two layouts to recognise:
      //   legacy (P0.1): `phase,scenario,run,milliseconds,notes`
      //   current      : `target,phase,scenario,run,milliseconds,notes`
      val lines = existing.lineSequence().toList()
      val headerIdx = lines.indexOfFirst { !it.startsWith("#") && it.isNotBlank() }
      check(headerIdx >= 0) { "baseline-latency.csv has no header row" }
      val header = lines[headerIdx]
      if (header.trim() == newHeader) {
        sb.append(existing)
        if (!existing.endsWith("\n")) sb.appendLine()
      } else if (header.trim() == "phase,scenario,run,milliseconds,notes") {
        // Migrate: prepend `android,` to every data row.
        for ((i, line) in lines.withIndex()) {
          when {
            line.startsWith("#") -> sb.appendLine(line)
            i == headerIdx -> sb.appendLine(newHeader)
            line.isBlank() -> {}
            else -> sb.appendLine("android,$line")
          }
        }
      } else {
        error("baseline-latency.csv has an unexpected header: '$header'")
      }
    }

    for (r in rows) {
      sb.appendLine(
        "$target,${r.phase},${r.scenario},${r.run},${r.ms},${r.notes.replace(",", ";")}"
      )
    }
    csv.writeText(sb.toString())
  }

  // Hand-parsed previews.json, keeping the bench script free of a kotlinx.serialization dep.
  private data class ProbePreview(
    val id: String,
    val className: String,
    val functionName: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val showBackground: Boolean,
    val backgroundColor: Long,
    val renderOutputRel: String,
    val wrapWidth: Boolean,
    val wrapHeight: Boolean,
  )

  private fun parsePreviewsJson(text: String): List<ProbePreview> {
    val json = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
    @Suppress("UNCHECKED_CAST") val previews = (json["previews"] as List<Map<String, Any?>>)
    return previews.map { p ->
      @Suppress("UNCHECKED_CAST") val params = (p["params"] as Map<String, Any?>)
      @Suppress("UNCHECKED_CAST")
      val captures = (p["captures"] as? List<Map<String, Any?>>).orEmpty()
      val widthDp = (params["widthDp"] as? Number)?.toInt() ?: 0
      val heightDp = (params["heightDp"] as? Number)?.toInt() ?: 0
      val density = (params["density"] as? Number)?.toFloat() ?: 2.625f
      // Mirrors DeviceDimensions.resolveForRender's wrap-content sandbox (400×800dp) for previews
      // with no size hints.
      val effWdp = if (widthDp > 0) widthDp else 400
      val effHdp = if (heightDp > 0) heightDp else 800
      val widthPx = (effWdp * density).toInt().coerceAtLeast(1)
      val heightPx = (effHdp * density).toInt().coerceAtLeast(1)
      val renderOutput =
        (captures.firstOrNull()?.get("renderOutput") as? String)?.removePrefix("renders/")?.takeIf {
          it.isNotEmpty()
        } ?: "${p["id"]}.png"
      // showSystemUi / device pin both axes to absolute size; absent →
      // wrap-content on whichever axis was unset (matches resolveForRender).
      val device = params["device"] as? String
      val showSystemUi = (params["showSystemUi"] as? Boolean) ?: false
      val wrapWidth = device == null && !showSystemUi && widthDp <= 0
      val wrapHeight = device == null && !showSystemUi && heightDp <= 0
      ProbePreview(
        id = p["id"] as String,
        className = p["className"] as String,
        functionName = p["functionName"] as String,
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        showBackground = (params["showBackground"] as? Boolean) ?: false,
        backgroundColor = (params["backgroundColor"] as? Number)?.toLong() ?: 0L,
        renderOutputRel = renderOutput,
        wrapWidth = wrapWidth,
        wrapHeight = wrapHeight,
      )
    }
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

// The desktop `composePreviewRenderer` configuration plus this module's classes and runtime
// classpath, resolved at config time.
val rendererCp = configurations.named("composePreviewRenderer")
val mainClasses = files(layout.buildDirectory.dir("classes/kotlin/main"))
val runtimeCp = configurations.named("runtimeClasspath")

tasks.register<BenchPreviewLatencyTask>("benchPreviewLatency") {
  group = "verification"
  description =
    "Times the existing desktop composePreviewRender path under cold / warm-no-edit / " +
      "warm-after-1-line-edit scenarios; appends desktop rows to build/daemon-bench/baseline-latency.csv."
  rendererClasspath.from(mainClasses, runtimeCp, rendererCp)
  // Renderer probe needs compiled classes + previews.json on disk.
  dependsOn("composePreviewRender")
  notCompatibleWithConfigurationCache(
    "BenchPreviewLatencyTask shells out to a nested ./gradlew invocation"
  )
  outputs.upToDateWhen { false }
}

// Stage-1 + stage-2 compile-leg bench (#1586). `benchPreviewLatency` measures stage 0 (a per-save
// `./gradlew`); this times the two faster save loops and prints a promote/demote verdict:
//
//   * stage 1 (`composePreview.daemon.continuousCompile`): a resident `gradle --continuous`, timing
//     edit → `BUILD SUCCESSFUL in N`, the leg `ContinuousCompileWorker.waitForNextBuild()` awaits.
//   * stage 2 (`composePreview.daemon.compileInProcess`): `javaexec`s `:daemon:core`'s
//     `BtaBenchMain` with the daemon's `btaCompile` block, driving the real
//     `BtaCompileSession.compileIncremental()` plus classloader rotation.
//
// The render leg is unchanged, so the verdict reuses the stage-0 warm-edit render median from the
// CSV — run `benchPreviewLatency` first.

abstract class BenchCompileStagesTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.settingsDirectory)

  @get:Input
  val benchModulePath: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention(":samples:desktop-daemon-bench")

  @get:Input
  val target: org.gradle.api.provider.Property<String> =
    project.objects.property(String::class.java).convention("desktop")

  @get:Internal
  val previewSourceFile: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(
        project.layout.projectDirectory.file(
          "src/main/kotlin/com/example/desktopdaemonbench/BenchPreviews.kt"
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
      .convention(project.layout.settingsDirectory.file("build/daemon-bench/baseline-latency.csv"))

  @get:Internal
  val daemonLaunchJson: org.gradle.api.file.RegularFileProperty =
    project.objects
      .fileProperty()
      .convention(project.layout.buildDirectory.file("compose-previews/daemon-launch.json"))

  // `:daemon:core`'s runtime classpath (`BtaBenchMain`, `BtaCompileSession`, build-tools-api); see
  // the `daemonBench` configuration below.
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

    // Prime warm state + the launch descriptor before measuring.
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
        (bta["ineligibilityReason"] as? String)
          ?.takeIf { it.isNotEmpty() }
          ?.let { cmd += "-Dcomposeai.daemon.bta.ineligibilityReason=$it" }
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

  // --- Stage-graduation verdict ------------------------------------------------------------

  private fun writeVerdict(
    csv: java.io.File,
    rows: List<StageRow>,
    notes: List<String>,
    target: String,
    stage2UsedMb: Long?,
  ) {
    fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]
    fun medianFor(phase: String, scenario: String) =
      median(
        rows.filter { it.phase == phase && it.scenario == scenario && it.ms >= 0 }.map { it.ms }
      )

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
        "Promote thresholds: save\u2192pixel total < 1 s on desktop / < 2 s on Android for the " +
        "standard scenarios, memory delta vs stage 1 under +250 MB per warm module, and a clean " +
        "fallback for KSP modules. Demote when the warm-path advantage over stage 1 collapses " +
        "below 200 ms."
    )
    sb.appendLine()
    sb.appendLine("## Measured medians")
    sb.appendLine()
    sb.appendLine("| Leg | Stage 1 | Stage 2 |")
    sb.appendLine("| --- | --- | --- |")
    sb.appendLine("| compile (warm, 1-line edit) | ${fmt(s1)} | ${fmt(s2compile)} |")
    sb.appendLine("| classloader-swap | — | ${fmt(s2swap)} |")
    sb.appendLine(
      "| render (warm, from stage-0 baseline) | ${fmt(renderBaseline)} | ${fmt(renderBaseline)} |"
    )
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
        else ->
          "INCONCLUSIVE — missing measurements (did `benchPreviewLatency` run first for the render baseline?)."
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
          if (c.size >= 5 && c[0] == target && c[1] == "render" && c[2] == "warm-after-1-line-edit")
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

  // Gradle prints "BUILD SUCCESSFUL in 420ms" / "in 2s" / "in 1m 3s". Sum the tokens to ms.
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

  /** As [BenchPreviewLatencyTask.appendCsv], so the two tasks interleave cleanly. */
  private fun appendCsv(csv: java.io.File, rows: List<StageRow>, target: String) {
    val newHeader = "target,phase,scenario,run,milliseconds,notes"
    val existing = if (csv.exists()) csv.readText() else ""
    val sb = StringBuilder()
    if (existing.isBlank()) {
      sb.appendLine(
        "# baseline-latency.csv — captured by the daemon-bench :benchPreviewLatency and"
      )
      sb.appendLine(
        "# :benchCompileStages tasks. See compose-preview-daemon's docs/daemon/baseline-latency.md for methodology."
      )
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
      sb.appendLine(
        "$target,${r.phase},${r.scenario},${r.run},${r.ms},${r.notes.replace(",", ";")}"
      )
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

dependencies {
  // A standalone resolvable configuration doesn't inherit the daemon BOM, and the catalog entry
  // carries no version.
  add("daemonBench", platform(libs.composeai.daemon.bom))
  add("daemonBench", libs.composeai.daemon.core)
}

tasks.register<BenchCompileStagesTask>("benchCompileStages") {
  group = "verification"
  description =
    "Drives the stage-1 (gradle --continuous) and stage-2 (in-process BTA) compile legs, appends " +
      "their rows to build/daemon-bench/baseline-latency.csv, and writes a stage-2 graduation verdict."
  daemonCoreClasspath.from(configurations.named("daemonBench"))
  dependsOn("composePreviewDaemonStart")
  notCompatibleWithConfigurationCache(
    "BenchCompileStagesTask shells out to nested ./gradlew + a daemon JVM"
  )
  outputs.upToDateWhen { false }
}

// CI smoke: ci.yml's `build-samples-full` (non-PR) runs `composePreviewRender` directly to keep
// this module from bit-rotting; nothing in CI invokes `check`. This hook is a local convenience
// only — don't wire it onto a PR-path task.
tasks.named("check") { dependsOn("composePreviewRender") }
