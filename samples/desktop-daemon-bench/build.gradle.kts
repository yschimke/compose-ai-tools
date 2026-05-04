// Desktop latency baseline harness for the preview daemon work — see docs/daemon/DESIGN.md § 13.
//
// Mirrors :samples:android-daemon-bench (P0.1) but renders through the
// Compose-Desktop path (`renderer-desktop`) instead of the Robolectric
// path. Five trivial @Preview functions, no animations / scrolls /
// @PreviewParameter — same shapes as the Android bench so the per-render
// row in baseline-latency.csv compares like-for-like across targets.
//
// `benchPreviewLatency` shells out to `./gradlew` repeatedly under
// different scenarios (cold / warm-no-edit / warm-after-1-line-edit) and
// appends desktop rows to docs/daemon/baseline-latency.csv (extending the
// schema with a leading `target` column the first time it sees the file
// in the legacy P0.1 layout). See README.md in this module for the
// scenario definitions and the desktop divergence in `render` accounting.
@file:Suppress("UnstableApiUsage", "DEPRECATION")

import java.io.File
import java.time.Duration
import java.time.Instant

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.components.uiToolingPreview)
}

// --- Bench task ---------------------------------------------------------

// One row per (target, phase, scenario, run). Captured to docs/daemon/baseline-latency.csv.
// Phases mirror P0.1's Android table; the desktop equivalents are:
//   config       — `:bench:renderPreviews --dry-run` wall (same as Android).
//   compile      — `compileKotlin` wall (kotlin.jvm; no `compileDebugKotlin`).
//   discovery    — `discoverPreviews` wall (renderer-agnostic).
//   forkAndInit  — derived: renderPreviews wall - sum(per-preview javaexec walls).
//                  Desktop forks ONE JVM PER PREVIEW (RenderPreviewsTask.renderWithCompose
//                  iterates `execOperations.javaexec` per preview) — so this captures
//                  Gradle's orchestration overhead BETWEEN those forks, not the forks
//                  themselves. The per-fork cost lives inside `render` on desktop.
//   render       — sum of per-preview javaexec walls, captured by the bench probing
//                  the renderer directly (same args RenderPreviewsTask passes), one
//                  process per preview. Includes JVM startup + Skiko/Compose-Desktop
//                  init + actual draw. This is the desktop counterpart to Android's
//                  "sum of JUnit testcase time= attrs" — except every row pays its
//                  own JVM cost because there's no shared sandbox.
//
// The desktop divergence from P0.1's accounting: Android shares a Robolectric sandbox
// across previews inside one Test JVM, so `render` is pure draw time and `forkAndInit`
// is the one-time JVM+sandbox bootstrap. Desktop has no shared sandbox — each preview
// process bootstraps Skiko + Compose-Desktop runtime independently — so the daemon's
// addressable surface on desktop is literally `render` itself, not just `forkAndInit`.

abstract class BenchPreviewLatencyTask : DefaultTask() {

  @get:Internal
  val rootProjectDir: org.gradle.api.file.DirectoryProperty =
    project.objects.directoryProperty().convention(project.rootProject.layout.projectDirectory)

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
      .convention(
        project.rootProject.layout.projectDirectory.file("docs/daemon/baseline-latency.csv")
      )

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

  // Snapshot of the renderer classpath at config time. Bench probes invoke
  // `java -cp <this> ee.schimke.composeai.renderer.DesktopRendererMainKt`
  // directly so per-preview wall times are observable. We resolve it eagerly
  // here (rather than re-resolving inside the task action) so the bench
  // doesn't need its own resolvable configuration at execution time.
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

    // Same string-literal swap rationale as P0.1: kotlinc strips comments,
    // so a comment-only edit leaves bytecode unchanged and downstream
    // `.class`-hashing tasks (renderPreviews, discoverPreviews) stay
    // UP-TO-DATE. A varying string literal is the smallest input mutation
    // that propagates all the way through.
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

    // Per-preview probe: read previews.json, spawn DesktopRendererMainKt
    // once per preview (same args shape as RenderPreviewsTask.renderWithCompose
    // builds), wall-time each call. Returns sum of probe walls + count.
    fun probeRenders(): Pair<Long, Int> {
      val previewsJson = previewsJsonFile.get().asFile
      check(previewsJson.exists()) {
        "previews.json missing at $previewsJson — discovery hasn't run"
      }
      // Minimal hand-parse: we only need className, functionName, and the
      // params we forward to the renderer. Avoid a serialization dep here
      // because BenchPreviewLatencyTask runs in the build script's
      // classloader, not the plugin's.
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
      val configRes = gradle("$benchPath:renderPreviews", *dryFlags)
      rows += Row("config", scenario, run, configRes.wallMs, "wall of renderPreviews --dry-run")

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

      // Phase 4 + 5: renderPreviews wall, then per-preview probe walls.
      val renderRes = gradle("$benchPath:renderPreviews", *cacheFlags)
      val renderRan = didTaskRun(renderRes.output, "$benchPath:renderPreviews")

      val (probeTotalMs, renderCount) = if (renderRan) probeRenders() else 0L to 0
      val forkInitMs = (renderRes.wallMs - probeTotalMs).coerceAtLeast(0)
      rows +=
        Row(
          "forkAndInit",
          scenario,
          run,
          forkInitMs,
          if (renderRan)
            "renderPreviews wall - sum(per-preview javaexec) = Gradle orchestration between forks"
          else "renderPreviews UP-TO-DATE; whole wall is Gradle overhead (no fork; no render)",
        )
      rows +=
        Row(
          "render",
          scenario,
          run,
          probeTotalMs,
          if (renderRan)
            "sum of $renderCount direct DesktopRendererMain javaexec walls (incl. per-process JVM+Skiko init)"
          else "renderPreviews UP-TO-DATE; no render work (0 by definition)",
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
      gradle("$benchPath:renderPreviews")
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
   * Append rows to the shared CSV. If the file is in the legacy P0.1 layout (header
   * `phase,scenario,run,milliseconds,notes` — no `target` column), migrate every existing row by
   * prepending `android,`. The migration is idempotent and run-once: subsequent appends see the new
   * header and skip straight to the append branch.
   *
   * The file is shared with :samples:android-daemon-bench:benchPreviewLatency, which writes android
   * rows. Either bench can be run independently or back-to-back; the order doesn't matter.
   */
  private fun appendCsv(csv: java.io.File, rows: List<Row>, target: String) {
    val newHeader = "target,phase,scenario,run,milliseconds,notes"
    val existing = if (csv.exists()) csv.readText() else ""
    val sb = StringBuilder()

    if (existing.isBlank()) {
      // First run for either target. Write fresh header.
      sb.appendLine("# baseline-latency.csv — captured by android-daemon-bench (P0.1) and")
      sb.appendLine("# desktop-daemon-bench (P0.6) :benchPreviewLatency tasks. See")
      sb.appendLine("# docs/daemon/baseline-latency.md for methodology + reference machine.")
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

  // Tiny hand-parser: previews.json is a known shape (PreviewManifest +
  // PreviewInfo from the plugin). We deliberately avoid a kotlinx.serialization
  // dep on the bench script classpath — keeping this task self-contained
  // makes it easier to copy into other branches without extra wiring.
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
      // Mirror DeviceDimensions.resolveForRender's wrap-content branch for
      // previews with no device / showSystemUi / widthDp / heightDp:
      // sandbox = 400dp × 800dp at default density. The renderer wraps to
      // intrinsic size on the unset axes; the bench just needs a sandbox
      // big enough to host the trivial widgets.
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

// Renderer classpath: same configuration the desktop renderPreviews task uses
// (`composePreviewRenderer` includes :renderer-desktop) plus this module's
// classes + runtime classpath. Resolved at config time.
val rendererCp = configurations.named("composePreviewRenderer")
val mainClasses = files(layout.buildDirectory.dir("classes/kotlin/main"))
val runtimeCp = configurations.named("runtimeClasspath")

tasks.register<BenchPreviewLatencyTask>("benchPreviewLatency") {
  group = "verification"
  description =
    "Times the existing desktop renderPreviews path under cold / warm-no-edit / " +
      "warm-after-1-line-edit scenarios; appends desktop rows to docs/daemon/baseline-latency.csv."
  rendererClasspath.from(mainClasses, runtimeCp, rendererCp)
  // Renderer probe needs compiled classes + previews.json on disk.
  dependsOn("renderPreviews")
  notCompatibleWithConfigurationCache(
    "BenchPreviewLatencyTask shells out to a nested ./gradlew invocation"
  )
  outputs.upToDateWhen { false }
}
